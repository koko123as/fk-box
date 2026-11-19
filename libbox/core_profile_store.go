package libbox

// The profiles store: the rows of the host's former Room database
// (profiles.db, version 3: tables "profiles" and "remote_servers"), owned by
// Go and kept in one JSON file written atomically (temporary file, fsync,
// rename, directory fsync). Profile content files stay where the rows point.
//
// The file is created once, by a two-step import from the host's Room data:
// UIProfileStoreStageImport writes and reads back "<path>.import", the host
// compares what came back with its rows, and UIProfileStoreCommitImport
// renames exactly those verified bytes into place. Until that rename the store
// does not exist and the host keeps reading Room, so an interrupted import is
// simply retried; after it the file is never re-imported. Room is never
// written by any of this.
//
// Semantics follow the Room DAO the host used: ids are never reused
// (AUTOINCREMENT, carried over from sqlite_sequence), an insert with id 0 takes
// the next id and one with an id keeps it (failing if the id exists), update
// and delete count the rows they matched, lists are ordered by userOrder (ties
// by id), nextOrder is MAX(userOrder)+1 or 0.
//
// Transactions mirror Room's withTransaction: UIProfileStoreBegin answers a
// token; calls that pass it see and change a private copy, written once on
// UIProfileStoreCommit or dropped on UIProfileStoreRollback. While one is
// open, reads without the token see the last committed state and writes
// without it (and a second Begin) wait for it to end.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

const (
	uiProfileStoreFormat  = "fk-box-profile-store"
	uiProfileStoreVersion = 1

	uiProfileTable = "profiles"
	uiServerTable  = "remote_servers"
)

// uiStoredTyped is TypedProfile (Parcel version 3) field by field.
type uiStoredTyped struct {
	Path               string `json:"path"`
	Type               int32  `json:"type"`
	RemoteURL          string `json:"remoteURL"`
	LastUpdated        int64  `json:"lastUpdated"`
	AutoUpdate         bool   `json:"autoUpdate"`
	AutoUpdateInterval int32  `json:"autoUpdateInterval"`
	Core               string `json:"core"`
	Upload             int64  `json:"upload"`
	Download           int64  `json:"download"`
	Total              int64  `json:"total"`
	Expire             int64  `json:"expire"`
	WebPageURL         string `json:"webPageUrl"`
	IntervalUserSet    bool   `json:"intervalUserSet"`
}

type uiStoredProfile struct {
	ID        int64          `json:"id"`
	UserOrder int64          `json:"userOrder"`
	Name      string         `json:"name"`
	Icon      *string        `json:"icon"`
	Typed     *uiStoredTyped `json:"typed"`
}

type uiStoredServer struct {
	ID        int64  `json:"id"`
	UserOrder int64  `json:"userOrder"`
	Name      string `json:"name"`
	URL       string `json:"url"`
	Secret    string `json:"secret"`
}

type uiProfileStoreState struct {
	Format       string            `json:"format"`
	Version      int               `json:"version"`
	ImportedFrom string            `json:"importedFrom"`
	ImportedAt   int64             `json:"importedAt"`
	ProfileSeq   int64             `json:"profileSeq"`
	ServerSeq    int64             `json:"serverSeq"`
	Profiles     []uiStoredProfile `json:"profiles"`
	Servers      []uiStoredServer  `json:"servers"`
}

// uiProfileImport is the host's Room data: every row of both tables and the
// sqlite_sequence values (0 when the table never had a row).
type uiProfileImport struct {
	ProfileSeq int64             `json:"profileSeq"`
	ServerSeq  int64             `json:"serverSeq"`
	Profiles   []uiStoredProfile `json:"profiles"`
	Servers    []uiStoredServer  `json:"servers"`
}

type uiProfileStore struct {
	path    string
	state   *uiProfileStoreState
	tx      int64
	txState *uiProfileStoreState
	lastTx  int64
}

var (
	uiProfileStoreAccess sync.Mutex
	uiProfileStoreIdle   = sync.NewCond(&uiProfileStoreAccess)
	uiProfileStoreOpen   *uiProfileStore
)

func uiStrictDecode(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if decoder.More() {
		return errors.New("trailing data")
	}
	if _, err := decoder.Token(); err != io.EOF {
		return errors.New("trailing data")
	}
	return nil
}

func (p *uiStoredProfile) validate() error {
	if p.ID <= 0 {
		return fmt.Errorf("invalid profile id %d", p.ID)
	}
	if p.Typed == nil {
		return fmt.Errorf("profile %d has no typed fields", p.ID)
	}
	return nil
}

func (s *uiStoredServer) validate() error {
	if s.ID <= 0 {
		return fmt.Errorf("invalid server id %d", s.ID)
	}
	return nil
}

// validate checks the invariants a loaded or imported state must hold.
func (s *uiProfileStoreState) validate() error {
	if s.Format != uiProfileStoreFormat {
		return fmt.Errorf("not a profile store (format %q)", s.Format)
	}
	if s.Version > uiProfileStoreVersion {
		return fmt.Errorf("profile store version %d is newer than this app (%d)", s.Version, uiProfileStoreVersion)
	}
	if s.Version != uiProfileStoreVersion {
		return fmt.Errorf("unsupported profile store version %d", s.Version)
	}
	seen := make(map[int64]bool, len(s.Profiles))
	for i := range s.Profiles {
		profile := &s.Profiles[i]
		if err := profile.validate(); err != nil {
			return err
		}
		if seen[profile.ID] {
			return fmt.Errorf("duplicate profile id %d", profile.ID)
		}
		seen[profile.ID] = true
		if profile.ID > s.ProfileSeq {
			return fmt.Errorf("profile id %d is above the id sequence %d", profile.ID, s.ProfileSeq)
		}
	}
	seen = make(map[int64]bool, len(s.Servers))
	for i := range s.Servers {
		server := &s.Servers[i]
		if err := server.validate(); err != nil {
			return err
		}
		if seen[server.ID] {
			return fmt.Errorf("duplicate server id %d", server.ID)
		}
		seen[server.ID] = true
		if server.ID > s.ServerSeq {
			return fmt.Errorf("server id %d is above the id sequence %d", server.ID, s.ServerSeq)
		}
	}
	return nil
}

func (s *uiProfileStoreState) encode() ([]byte, error) {
	if s.Profiles == nil {
		s.Profiles = []uiStoredProfile{}
	}
	if s.Servers == nil {
		s.Servers = []uiStoredServer{}
	}
	return json.MarshalIndent(s, "", " ")
}

func uiDecodeProfileStore(data []byte) (*uiProfileStoreState, error) {
	var state uiProfileStoreState
	if err := uiStrictDecode(data, &state); err != nil {
		return nil, fmt.Errorf("profile store is damaged: %w", err)
	}
	if err := state.validate(); err != nil {
		return nil, fmt.Errorf("profile store is damaged: %w", err)
	}
	return &state, nil
}

func (s *uiProfileStoreState) clone() *uiProfileStoreState {
	copied := *s
	copied.Profiles = make([]uiStoredProfile, len(s.Profiles))
	for i, profile := range s.Profiles {
		copied.Profiles[i] = profile.clone()
	}
	copied.Servers = append([]uiStoredServer{}, s.Servers...)
	return &copied
}

func (p uiStoredProfile) clone() uiStoredProfile {
	if p.Icon != nil {
		icon := *p.Icon
		p.Icon = &icon
	}
	if p.Typed != nil {
		typed := *p.Typed
		p.Typed = &typed
	}
	return p
}

// uiWriteFileAtomic replaces path with data: temporary file, fsync, rename,
// then a best-effort directory fsync (the rename has already taken effect).
func uiWriteFileAtomic(path string, data []byte) error {
	temporary := path + ".tmp"
	if err := uiWriteFileSynced(temporary, data); err != nil {
		os.Remove(temporary)
		return err
	}
	if err := os.Rename(temporary, path); err != nil {
		os.Remove(temporary)
		return err
	}
	uiSyncDir(filepath.Dir(path))
	return nil
}

func uiWriteFileSynced(path string, data []byte) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err = file.Write(data); err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	return err
}

func uiSyncDir(dir string) {
	if handle, err := os.Open(dir); err == nil {
		_ = handle.Sync()
		_ = handle.Close()
	}
}

// UIProfileStoreOpen loads the store at path and answers true, or answers
// false when it has not been created yet (the host then imports). A store
// that exists but cannot be read is an error, never a reason to re-import.
func UIProfileStoreOpen(path string) (bool, error) {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	if uiProfileStoreOpen != nil {
		if uiProfileStoreOpen.path != path {
			return false, fmt.Errorf("another profile store is open: %s", uiProfileStoreOpen.path)
		}
		return true, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("unable to read the profile store: %w", err)
	}
	state, err := uiDecodeProfileStore(data)
	if err != nil {
		return false, err
	}
	uiProfileStoreOpen = &uiProfileStore{path: path, state: state}
	return true, nil
}

func uiProfileStoreImportPath(path string) string { return path + ".import" }

// UIProfileStoreStageImport writes the host's Room data to "<path>.import"
// (synced) and answers what reading that file back gives: {"sha256",
// "profiles", "servers"}, rows in list order. Nothing is in effect until
// UIProfileStoreCommitImport. It refuses once the store exists.
func UIProfileStoreStageImport(path string, dataset string) (string, error) {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	if uiProfileStoreOpen != nil {
		return "", errors.New("profiles were already imported")
	}
	if _, err := os.Stat(path); err == nil || !errors.Is(err, os.ErrNotExist) {
		return "", errors.New("profiles were already imported")
	}
	var source uiProfileImport
	if err := uiStrictDecode([]byte(dataset), &source); err != nil {
		return "", fmt.Errorf("invalid profile import: %w", err)
	}
	state := &uiProfileStoreState{
		Format:       uiProfileStoreFormat,
		Version:      uiProfileStoreVersion,
		ImportedFrom: "room:profiles.db:3",
		ImportedAt:   time.Now().UnixMilli(),
		ProfileSeq:   max(source.ProfileSeq, 0),
		ServerSeq:    max(source.ServerSeq, 0),
		Profiles:     source.Profiles,
		Servers:      source.Servers,
	}
	// Room's AUTOINCREMENT never hands out an id at or below sqlite_sequence
	// or an existing row's id; keep both bounds.
	for _, profile := range state.Profiles {
		state.ProfileSeq = max(state.ProfileSeq, profile.ID)
	}
	for _, server := range state.Servers {
		state.ServerSeq = max(state.ServerSeq, server.ID)
	}
	if err := state.validate(); err != nil {
		return "", fmt.Errorf("invalid profile import: %w", err)
	}
	encoded, err := state.encode()
	if err != nil {
		return "", err
	}
	staged := uiProfileStoreImportPath(path)
	if err := uiWriteFileSynced(staged, encoded); err != nil {
		return "", fmt.Errorf("unable to stage the profile import: %w", err)
	}
	written, err := os.ReadFile(staged)
	if err != nil {
		return "", fmt.Errorf("unable to read back the profile import: %w", err)
	}
	readBack, err := uiDecodeProfileStore(written)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(written)
	return uiMarshalString(map[string]any{
		"sha256":   hex.EncodeToString(digest[:]),
		"profiles": uiSortedProfiles(readBack.Profiles),
		"servers":  uiSortedServers(readBack.Servers),
	})
}

// UIProfileStoreCommitImport puts the staged import in place, provided the
// staged file still has the digest the host verified, and opens the store.
func UIProfileStoreCommitImport(path string, digest string) error {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	if uiProfileStoreOpen != nil {
		return errors.New("profiles were already imported")
	}
	if _, err := os.Stat(path); err == nil || !errors.Is(err, os.ErrNotExist) {
		return errors.New("profiles were already imported")
	}
	staged := uiProfileStoreImportPath(path)
	written, err := os.ReadFile(staged)
	if err != nil {
		return fmt.Errorf("unable to read the staged profile import: %w", err)
	}
	sum := sha256.Sum256(written)
	if hex.EncodeToString(sum[:]) != digest {
		return errors.New("the staged profile import changed after it was verified")
	}
	state, err := uiDecodeProfileStore(written)
	if err != nil {
		return err
	}
	if err := os.Rename(staged, path); err != nil {
		return fmt.Errorf("unable to complete the profile import: %w", err)
	}
	uiSyncDir(filepath.Dir(path))
	uiProfileStoreOpen = &uiProfileStore{path: path, state: state}
	return nil
}

// uiProfileStoreClose forgets the open store (tests).
func uiProfileStoreClose() {
	uiProfileStoreAccess.Lock()
	uiProfileStoreOpen = nil
	uiProfileStoreIdle.Broadcast()
	uiProfileStoreAccess.Unlock()
}

func uiMarshalString(value any) (string, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func uiSortedProfiles(rows []uiStoredProfile) []uiStoredProfile {
	sorted := append([]uiStoredProfile{}, rows...)
	sort.SliceStable(sorted, func(i, j int) bool {
		if sorted[i].UserOrder != sorted[j].UserOrder {
			return sorted[i].UserOrder < sorted[j].UserOrder
		}
		return sorted[i].ID < sorted[j].ID
	})
	return sorted
}

func uiSortedServers(rows []uiStoredServer) []uiStoredServer {
	sorted := append([]uiStoredServer{}, rows...)
	sort.SliceStable(sorted, func(i, j int) bool {
		if sorted[i].UserOrder != sorted[j].UserOrder {
			return sorted[i].UserOrder < sorted[j].UserOrder
		}
		return sorted[i].ID < sorted[j].ID
	})
	return sorted
}

func uiProfileStoreRequire() (*uiProfileStore, error) {
	if uiProfileStoreOpen == nil {
		return nil, errors.New("profile store is not open")
	}
	return uiProfileStoreOpen, nil
}

// uiProfileStoreRead runs read on the state tx sees (committed when tx is 0).
func uiProfileStoreRead(tx int64, read func(*uiProfileStoreState) any) (any, error) {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	store, err := uiProfileStoreRequire()
	if err != nil {
		return nil, err
	}
	if tx == 0 {
		return read(store.state), nil
	}
	if store.tx != tx {
		return nil, errors.New("profile transaction is not active")
	}
	return read(store.txState), nil
}

// uiProfileStoreWrite runs change on a copy of the state and keeps it: inside
// tx in memory until commit, otherwise written to disk first (after waiting
// for an open transaction to end). A failed change or write leaves the state
// as it was.
func uiProfileStoreWrite(tx int64, change func(*uiProfileStoreState) (int64, error)) (int64, error) {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	store, err := uiProfileStoreRequire()
	if err != nil {
		return 0, err
	}
	if tx != 0 {
		if store.tx != tx {
			return 0, errors.New("profile transaction is not active")
		}
		next := store.txState.clone()
		result, err := change(next)
		if err != nil {
			return 0, err
		}
		store.txState = next
		return result, nil
	}
	for store.tx != 0 {
		uiProfileStoreIdle.Wait()
		if store, err = uiProfileStoreRequire(); err != nil {
			return 0, err
		}
	}
	next := store.state.clone()
	result, err := change(next)
	if err != nil {
		return 0, err
	}
	if err := store.persist(next); err != nil {
		return 0, err
	}
	return result, nil
}

func (s *uiProfileStore) persist(next *uiProfileStoreState) error {
	if err := next.validate(); err != nil {
		return err
	}
	encoded, err := next.encode()
	if err != nil {
		return err
	}
	if err := uiWriteFileAtomic(s.path, encoded); err != nil {
		return fmt.Errorf("unable to save profiles: %w", err)
	}
	s.state = next
	return nil
}

// UIProfileStoreBegin opens a transaction, waiting for one already open.
func UIProfileStoreBegin() (int64, error) {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	store, err := uiProfileStoreRequire()
	if err != nil {
		return 0, err
	}
	for store.tx != 0 {
		uiProfileStoreIdle.Wait()
		if store, err = uiProfileStoreRequire(); err != nil {
			return 0, err
		}
	}
	store.lastTx++
	store.tx = store.lastTx
	store.txState = store.state.clone()
	return store.tx, nil
}

func uiProfileStoreEnd(tx int64, commit bool) error {
	uiProfileStoreAccess.Lock()
	defer uiProfileStoreAccess.Unlock()
	store, err := uiProfileStoreRequire()
	if err != nil {
		return err
	}
	if store.tx != tx || tx == 0 {
		return errors.New("profile transaction is not active")
	}
	next := store.txState
	store.tx = 0
	store.txState = nil
	uiProfileStoreIdle.Broadcast()
	if !commit {
		return nil
	}
	return store.persist(next)
}

// UIProfileStoreCommit writes the transaction's changes and ends it. When the
// write fails the changes are dropped and the transaction still ends.
func UIProfileStoreCommit(tx int64) error { return uiProfileStoreEnd(tx, true) }

// UIProfileStoreRollback drops the transaction's changes and ends it.
func UIProfileStoreRollback(tx int64) error { return uiProfileStoreEnd(tx, false) }

func uiCheckTable(table string) error {
	if table != uiProfileTable && table != uiServerTable {
		return fmt.Errorf("unknown profile store table: %s", table)
	}
	return nil
}

// UIProfileStoreList answers the table's rows (JSON array) by userOrder.
func UIProfileStoreList(tx int64, table string) (string, error) {
	if err := uiCheckTable(table); err != nil {
		return "", err
	}
	rows, err := uiProfileStoreRead(tx, func(state *uiProfileStoreState) any {
		if table == uiProfileTable {
			return uiSortedProfiles(state.Profiles)
		}
		return uiSortedServers(state.Servers)
	})
	if err != nil {
		return "", err
	}
	return uiMarshalString(rows)
}

// UIProfileStoreGet answers the row with id, or "null".
func UIProfileStoreGet(tx int64, table string, id int64) (string, error) {
	if err := uiCheckTable(table); err != nil {
		return "", err
	}
	row, err := uiProfileStoreRead(tx, func(state *uiProfileStoreState) any {
		if table == uiProfileTable {
			for _, profile := range state.Profiles {
				if profile.ID == id {
					return profile
				}
			}
		} else {
			for _, server := range state.Servers {
				if server.ID == id {
					return server
				}
			}
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	return uiMarshalString(row)
}

// UIProfileStoreNextOrder answers MAX(userOrder)+1, or 0 for an empty table.
func UIProfileStoreNextOrder(tx int64, table string) (int64, error) {
	if err := uiCheckTable(table); err != nil {
		return 0, err
	}
	next, err := uiProfileStoreRead(tx, func(state *uiProfileStoreState) any {
		var orders []int64
		if table == uiProfileTable {
			for _, profile := range state.Profiles {
				orders = append(orders, profile.UserOrder)
			}
		} else {
			for _, server := range state.Servers {
				orders = append(orders, server.UserOrder)
			}
		}
		if len(orders) == 0 {
			return int64(0)
		}
		highest := orders[0]
		for _, order := range orders[1:] {
			highest = max(highest, order)
		}
		return highest + 1
	})
	if err != nil {
		return 0, err
	}
	return next.(int64), nil
}

func uiDecodeRow(table string, data []byte) (profile *uiStoredProfile, server *uiStoredServer, err error) {
	if table == uiProfileTable {
		profile = new(uiStoredProfile)
		err = uiStrictDecode(data, profile)
		if err == nil && profile.Typed == nil {
			err = errors.New("profile has no typed fields")
		}
	} else {
		server = new(uiStoredServer)
		err = uiStrictDecode(data, server)
	}
	if err != nil {
		err = fmt.Errorf("invalid %s row: %w", table, err)
	}
	return
}

// UIProfileStoreInsert adds row and answers its id: the next id when the
// row's id is 0, else its own id, which must not exist yet.
func UIProfileStoreInsert(tx int64, table string, row string) (int64, error) {
	if err := uiCheckTable(table); err != nil {
		return 0, err
	}
	profile, server, err := uiDecodeRow(table, []byte(row))
	if err != nil {
		return 0, err
	}
	return uiProfileStoreWrite(tx, func(state *uiProfileStoreState) (int64, error) {
		if profile != nil {
			if profile.ID < 0 {
				return 0, fmt.Errorf("invalid profile id %d", profile.ID)
			}
			if profile.ID == 0 {
				profile.ID = state.ProfileSeq + 1
			}
			for _, existing := range state.Profiles {
				if existing.ID == profile.ID {
					return 0, errors.New("UNIQUE constraint failed: profiles.id")
				}
			}
			state.ProfileSeq = max(state.ProfileSeq, profile.ID)
			state.Profiles = append(state.Profiles, *profile)
			return profile.ID, nil
		}
		if server.ID < 0 {
			return 0, fmt.Errorf("invalid server id %d", server.ID)
		}
		if server.ID == 0 {
			server.ID = state.ServerSeq + 1
		}
		for _, existing := range state.Servers {
			if existing.ID == server.ID {
				return 0, errors.New("UNIQUE constraint failed: remote_servers.id")
			}
		}
		state.ServerSeq = max(state.ServerSeq, server.ID)
		state.Servers = append(state.Servers, *server)
		return server.ID, nil
	})
}

// UIProfileStoreUpdate replaces the rows (a JSON array) whose ids exist and
// answers how many it replaced.
func UIProfileStoreUpdate(tx int64, table string, rows string) (int64, error) {
	if err := uiCheckTable(table); err != nil {
		return 0, err
	}
	var raw []json.RawMessage
	if err := json.Unmarshal([]byte(rows), &raw); err != nil {
		return 0, fmt.Errorf("invalid %s rows: %w", table, err)
	}
	profiles := make([]*uiStoredProfile, 0, len(raw))
	servers := make([]*uiStoredServer, 0, len(raw))
	for _, item := range raw {
		profile, server, err := uiDecodeRow(table, item)
		if err != nil {
			return 0, err
		}
		if profile != nil {
			profiles = append(profiles, profile)
		} else {
			servers = append(servers, server)
		}
	}
	return uiProfileStoreWrite(tx, func(state *uiProfileStoreState) (int64, error) {
		var count int64
		for _, profile := range profiles {
			for i := range state.Profiles {
				if state.Profiles[i].ID == profile.ID {
					state.Profiles[i] = profile.clone()
					count++
					break
				}
			}
		}
		for _, server := range servers {
			for i := range state.Servers {
				if state.Servers[i].ID == server.ID {
					state.Servers[i] = *server
					count++
					break
				}
			}
		}
		return count, nil
	})
}

// UIProfileStoreDelete removes the rows with the ids (a JSON array) and
// answers how many it removed.
func UIProfileStoreDelete(tx int64, table string, ids string) (int64, error) {
	if err := uiCheckTable(table); err != nil {
		return 0, err
	}
	var remove []int64
	if err := json.Unmarshal([]byte(ids), &remove); err != nil {
		return 0, fmt.Errorf("invalid %s ids: %w", table, err)
	}
	removing := make(map[int64]bool, len(remove))
	for _, id := range remove {
		removing[id] = true
	}
	return uiProfileStoreWrite(tx, func(state *uiProfileStoreState) (int64, error) {
		var count int64
		if table == uiProfileTable {
			kept := state.Profiles[:0:0]
			for _, profile := range state.Profiles {
				if removing[profile.ID] {
					count++
				} else {
					kept = append(kept, profile)
				}
			}
			state.Profiles = kept
		} else {
			kept := state.Servers[:0:0]
			for _, server := range state.Servers {
				if removing[server.ID] {
					count++
				} else {
					kept = append(kept, server)
				}
			}
			state.Servers = kept
		}
		return count, nil
	})
}

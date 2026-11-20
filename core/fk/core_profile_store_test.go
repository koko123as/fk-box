package fk

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// A dataset shaped like what the host reads from Room profiles.db v3: ids
// with gaps (deleted rows), sqlite_sequence above the highest id, ties in
// userOrder, a null and an empty icon, local and remote profiles with every
// TypedProfile field, and remote servers.
const uiTestRoomDataset = `{
 "profileSeq": 9,
 "serverSeq": 2,
 "profiles": [
  {"id": 2, "userOrder": 1, "name": "Remote", "icon": null, "typed": {"path": "/data/user/0/io.fkbox.app/files/configs/2.json", "type": 1, "remoteURL": "https://example.com/sub", "lastUpdated": 1763600000000, "autoUpdate": true, "autoUpdateInterval": 180, "core": "xray", "upload": 10, "download": 20, "total": 300, "expire": 1790000000, "webPageUrl": "https://example.com", "intervalUserSet": true}},
  {"id": 5, "userOrder": 0, "name": "Local", "icon": "", "typed": {"path": "/data/user/0/io.fkbox.app/files/configs/5.json", "type": 0, "remoteURL": "", "lastUpdated": 0, "autoUpdate": false, "autoUpdateInterval": 60, "core": "auto", "upload": 0, "download": 0, "total": 0, "expire": 0, "webPageUrl": "", "intervalUserSet": false}},
  {"id": 7, "userOrder": 1, "name": "Tie", "icon": "star", "typed": {"path": "/x/7.json", "type": 0, "remoteURL": "", "lastUpdated": 5, "autoUpdate": false, "autoUpdateInterval": 60, "core": "mihomo", "upload": 0, "download": 0, "total": 0, "expire": 0, "webPageUrl": "", "intervalUserSet": false}}
 ],
 "servers": [
  {"id": 2, "userOrder": 0, "name": "Box", "url": "192.168.1.2:9090", "secret": "s"}
 ]
}`

func uiTestStorePath(t *testing.T) string {
	t.Helper()
	uiProfileStoreClose()
	t.Cleanup(uiProfileStoreClose)
	return filepath.Join(t.TempDir(), "profiles-store.json")
}

func uiTestImport(t *testing.T, path string, dataset string) map[string]json.RawMessage {
	t.Helper()
	if exists, err := UIProfileStoreOpen(path); err != nil || exists {
		t.Fatalf("open before import = %v, %v", exists, err)
	}
	staged, err := UIProfileStoreStageImport(path, dataset)
	if err != nil {
		t.Fatal(err)
	}
	var readBack map[string]json.RawMessage
	if err := json.Unmarshal([]byte(staged), &readBack); err != nil {
		t.Fatal(err)
	}
	var digest string
	_ = json.Unmarshal(readBack["sha256"], &digest)
	if err := UIProfileStoreCommitImport(path, digest); err != nil {
		t.Fatal(err)
	}
	return readBack
}

func uiTestProfiles(t *testing.T, tx int64) []uiStoredProfile {
	t.Helper()
	listed, err := UIProfileStoreList(tx, uiProfileTable)
	if err != nil {
		t.Fatal(err)
	}
	var rows []uiStoredProfile
	if err := json.Unmarshal([]byte(listed), &rows); err != nil {
		t.Fatal(err)
	}
	return rows
}

func uiTestIDs(rows []uiStoredProfile) []int64 {
	ids := make([]int64, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.ID)
	}
	return ids
}

func TestUIProfileStoreImportFromRoomKeepsEveryField(t *testing.T) {
	path := uiTestStorePath(t)
	readBack := uiTestImport(t, path, uiTestRoomDataset)

	var source uiProfileImport
	if err := json.Unmarshal([]byte(uiTestRoomDataset), &source); err != nil {
		t.Fatal(err)
	}
	var staged []uiStoredProfile
	if err := json.Unmarshal(readBack["profiles"], &staged); err != nil {
		t.Fatal(err)
	}
	listed := uiTestProfiles(t, 0)
	if !reflect.DeepEqual(staged, listed) {
		t.Fatalf("staged read-back %+v differs from the opened store %+v", staged, listed)
	}
	// List order is userOrder, then id for ties: 5 (0), 2 (1), 7 (1).
	if got := uiTestIDs(listed); !reflect.DeepEqual(got, []int64{5, 2, 7}) {
		t.Fatalf("order = %v", got)
	}
	byID := map[int64]uiStoredProfile{}
	for _, row := range listed {
		byID[row.ID] = row
	}
	for _, want := range source.Profiles {
		if got := byID[want.ID]; !reflect.DeepEqual(got, want) {
			t.Fatalf("profile %d = %+v, want %+v", want.ID, got, want)
		}
	}
	if byID[2].Icon != nil || byID[5].Icon == nil || *byID[5].Icon != "" {
		t.Fatal("null and empty icons must stay distinct")
	}
	servers, err := UIProfileStoreList(0, uiServerTable)
	if err != nil || !strings.Contains(servers, `"secret":"s"`) {
		t.Fatalf("servers = %s, %v", servers, err)
	}
	if _, err := os.Stat(path + ".import"); !os.IsNotExist(err) {
		t.Fatal("the staged file must become the store")
	}

	// Reopening reads the same rows from disk.
	uiProfileStoreClose()
	if exists, err := UIProfileStoreOpen(path); err != nil || !exists {
		t.Fatalf("reopen = %v, %v", exists, err)
	}
	if !reflect.DeepEqual(uiTestProfiles(t, 0), listed) {
		t.Fatal("reopened store differs")
	}

	// Ids continue after sqlite_sequence (9), not after the highest row (7).
	id, err := UIProfileStoreInsert(0, uiProfileTable, `{"id":0,"userOrder":3,"name":"New","icon":null,"typed":{"path":"/n","type":0,"remoteURL":"","lastUpdated":1,"autoUpdate":false,"autoUpdateInterval":60,"core":"auto","upload":0,"download":0,"total":0,"expire":0,"webPageUrl":"","intervalUserSet":false}}`)
	if err != nil || id != 10 {
		t.Fatalf("insert = %d, %v", id, err)
	}
	serverID, err := UIProfileStoreInsert(0, uiServerTable, `{"id":0,"userOrder":1,"name":"","url":"h","secret":""}`)
	if err != nil || serverID != 3 {
		t.Fatalf("server insert = %d, %v", serverID, err)
	}
}

func TestUIProfileStoreImportIsOnceOnly(t *testing.T) {
	path := uiTestStorePath(t)
	uiTestImport(t, path, uiTestRoomDataset)
	if _, err := UIProfileStoreStageImport(path, `{"profiles":[],"servers":[]}`); err == nil {
		t.Fatal("a second import must be refused while the store is open")
	}
	uiProfileStoreClose()
	if _, err := UIProfileStoreStageImport(path, `{"profiles":[],"servers":[]}`); err == nil {
		t.Fatal("a second import must be refused once the store file exists")
	}
	if err := UIProfileStoreCommitImport(path, "x"); err == nil {
		t.Fatal("commit must be refused once the store file exists")
	}
	if exists, err := UIProfileStoreOpen(path); err != nil || !exists || len(uiTestProfiles(t, 0)) != 3 {
		t.Fatalf("store after refused imports: %v, %v", exists, err)
	}
}

func TestUIProfileStoreInterruptedImportRetries(t *testing.T) {
	path := uiTestStorePath(t)
	// A crash after staging and before commit leaves only "<path>.import"
	// (here half written): the store does not exist and the import reruns.
	if err := os.WriteFile(path+".import", []byte(`{"format":"fk-box-pro`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path+".tmp", []byte(`garbage`), 0o600); err != nil {
		t.Fatal(err)
	}
	if exists, err := UIProfileStoreOpen(path); err != nil || exists {
		t.Fatalf("open with a leftover staged import = %v, %v", exists, err)
	}
	if err := UIProfileStoreCommitImport(path, "0"); err == nil {
		t.Fatal("a damaged staged import must not be committed")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("a failed commit must not create the store")
	}
	// Staged but never committed (crash): staging again starts over.
	if _, err := UIProfileStoreStageImport(path, `{"profileSeq":1,"serverSeq":0,"profiles":[],"servers":[]}`); err != nil {
		t.Fatal(err)
	}
	readBack := uiTestImport(t, path, uiTestRoomDataset)
	var profiles []uiStoredProfile
	_ = json.Unmarshal(readBack["profiles"], &profiles)
	if len(profiles) != 3 || len(uiTestProfiles(t, 0)) != 3 {
		t.Fatalf("retried import has %d rows", len(profiles))
	}
}

func TestUIProfileStoreCommitChecksTheVerifiedBytes(t *testing.T) {
	path := uiTestStorePath(t)
	staged, err := UIProfileStoreStageImport(path, uiTestRoomDataset)
	if err != nil {
		t.Fatal(err)
	}
	var readBack struct {
		SHA256 string `json:"sha256"`
	}
	_ = json.Unmarshal([]byte(staged), &readBack)
	data, _ := os.ReadFile(path + ".import")
	if err := os.WriteFile(path+".import", []byte(strings.Replace(string(data), "Remote", "Remotf", 1)), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := UIProfileStoreCommitImport(path, readBack.SHA256); err == nil {
		t.Fatal("changed staged bytes must not be committed")
	}
	if exists, _ := UIProfileStoreOpen(path); exists {
		t.Fatal("store must not exist")
	}
}

func TestUIProfileStoreRejectsInvalidImports(t *testing.T) {
	path := uiTestStorePath(t)
	for _, dataset := range []string{
		`{"profiles":[{"id":1,"userOrder":0,"name":"a","icon":null,"typed":{}},{"id":1,"userOrder":0,"name":"b","icon":null,"typed":{}}],"servers":[]}`,
		`{"profiles":[{"id":0,"userOrder":0,"name":"a","icon":null,"typed":{}}],"servers":[]}`,
		`{"profiles":[{"id":1,"userOrder":0,"name":"a","icon":null}],"servers":[]}`,
		`{"profiles":[],"servers":[],"extra":1}`,
		`{"profiles":[{"id":1,"userOrder":0,"name":"a","icon":null,"typed":{"unknown":1}}],"servers":[]}`,
	} {
		if _, err := UIProfileStoreStageImport(path, dataset); err == nil {
			t.Fatalf("accepted %s", dataset)
		}
	}
}

func TestUIProfileStoreDamagedOrNewerStoreIsNotReimported(t *testing.T) {
	path := uiTestStorePath(t)
	if err := os.WriteFile(path, []byte(`{"format":"fk-box-profile-store","version":1,`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := UIProfileStoreOpen(path); err == nil || !strings.Contains(err.Error(), "damaged") {
		t.Fatalf("damaged store: %v", err)
	}
	if _, err := UIProfileStoreStageImport(path, uiTestRoomDataset); err == nil {
		t.Fatal("a damaged store must never be replaced by an import")
	}
	newer := `{"format":"fk-box-profile-store","version":2,"importedFrom":"","importedAt":0,"profileSeq":0,"serverSeq":0,"profiles":[],"servers":[]}`
	if err := os.WriteFile(path, []byte(newer), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := UIProfileStoreOpen(path); err == nil || !strings.Contains(err.Error(), "newer") {
		t.Fatalf("newer store: %v", err)
	}
	if data, _ := os.ReadFile(path); string(data) != newer {
		t.Fatal("a store this app cannot read must stay untouched")
	}
}

func TestUIProfileStoreCRUDAndOrdering(t *testing.T) {
	path := uiTestStorePath(t)
	uiTestImport(t, path, `{"profileSeq":0,"serverSeq":0,"profiles":[],"servers":[]}`)
	if next, err := UIProfileStoreNextOrder(0, uiProfileTable); err != nil || next != 0 {
		t.Fatalf("next order of an empty table = %d, %v", next, err)
	}
	if row, err := UIProfileStoreGet(0, uiProfileTable, 1); err != nil || row != "null" {
		t.Fatalf("get missing = %s, %v", row, err)
	}
	row := func(id, order int64, name string) string {
		return `{"id":` + jsonInt(id) + `,"userOrder":` + jsonInt(order) + `,"name":"` + name + `","icon":null,"typed":{"path":"/p/` + name + `","type":0,"remoteURL":"","lastUpdated":0,"autoUpdate":false,"autoUpdateInterval":60,"core":"auto","upload":0,"download":0,"total":0,"expire":0,"webPageUrl":"","intervalUserSet":false}}`
	}
	for i, name := range []string{"a", "b", "c"} {
		id, err := UIProfileStoreInsert(0, uiProfileTable, row(0, int64(2-i), name))
		if err != nil || id != int64(i+1) {
			t.Fatalf("insert %s = %d, %v", name, id, err)
		}
	}
	if got := uiTestIDs(uiTestProfiles(t, 0)); !reflect.DeepEqual(got, []int64{3, 2, 1}) {
		t.Fatalf("order = %v", got)
	}
	if next, _ := UIProfileStoreNextOrder(0, uiProfileTable); next != 3 {
		t.Fatalf("next order = %d", next)
	}
	if _, err := UIProfileStoreInsert(0, uiProfileTable, row(2, 0, "dup")); err == nil || !strings.Contains(err.Error(), "UNIQUE") {
		t.Fatalf("duplicate insert: %v", err)
	}
	// Reorder as BoxProfiles.reorder does: one update of every row.
	count, err := UIProfileStoreUpdate(0, uiProfileTable, "["+row(1, 0, "a")+","+row(2, 1, "b2")+","+row(3, 2, "c")+","+row(42, 0, "gone")+"]")
	if err != nil || count != 3 {
		t.Fatalf("update = %d, %v", count, err)
	}
	listed := uiTestProfiles(t, 0)
	if got := uiTestIDs(listed); !reflect.DeepEqual(got, []int64{1, 2, 3}) || listed[1].Name != "b2" {
		t.Fatalf("after reorder = %v %+v", got, listed)
	}
	if count, err := UIProfileStoreDelete(0, uiProfileTable, "[3,99]"); err != nil || count != 1 {
		t.Fatalf("delete = %d, %v", count, err)
	}
	// A deleted id is not handed out again; an explicit one is kept.
	if id, _ := UIProfileStoreInsert(0, uiProfileTable, row(0, 5, "d")); id != 4 {
		t.Fatalf("id after delete = %d", id)
	}
	if id, err := UIProfileStoreInsert(0, uiProfileTable, row(3, 6, "c-again")); err != nil || id != 3 {
		t.Fatalf("explicit id insert = %d, %v", id, err)
	}
	if _, err := UIProfileStoreInsert(0, uiProfileTable, `{"id":0,"userOrder":0,"name":"x","icon":null}`); err == nil {
		t.Fatal("a profile without typed fields must be rejected")
	}
	if _, err := UIProfileStoreList(0, "settings"); err == nil {
		t.Fatal("unknown table must be rejected")
	}
	// Every write is on disk.
	uiProfileStoreClose()
	if exists, err := UIProfileStoreOpen(path); err != nil || !exists {
		t.Fatal(err)
	}
	if got := uiTestIDs(uiTestProfiles(t, 0)); !reflect.DeepEqual(got, []int64{1, 2, 4, 3}) {
		t.Fatalf("reopened order = %v", got)
	}
}

func jsonInt(value int64) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}

func TestUIProfileStoreTransactions(t *testing.T) {
	path := uiTestStorePath(t)
	uiTestImport(t, path, uiTestRoomDataset)
	before, _ := os.ReadFile(path)

	tx, err := UIProfileStoreBegin()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := UIProfileStoreDelete(tx, uiProfileTable, "[2,5,7]"); err != nil {
		t.Fatal(err)
	}
	if len(uiTestProfiles(t, tx)) != 0 || len(uiTestProfiles(t, 0)) != 3 {
		t.Fatal("a transaction's changes are visible only inside it")
	}
	if after, _ := os.ReadFile(path); string(after) != string(before) {
		t.Fatal("nothing is written before commit")
	}
	if err := UIProfileStoreRollback(tx); err != nil {
		t.Fatal(err)
	}
	if len(uiTestProfiles(t, 0)) != 3 {
		t.Fatal("rollback must keep the rows")
	}
	if _, err := UIProfileStoreList(tx, uiProfileTable); err == nil {
		t.Fatal("an ended transaction must be refused")
	}

	tx, _ = UIProfileStoreBegin()
	if _, err := UIProfileStoreDelete(tx, uiProfileTable, "[2]"); err != nil {
		t.Fatal(err)
	}
	if _, err := UIProfileStoreInsert(tx, uiProfileTable, `{"id":2,"userOrder":9,"name":"Back","icon":null,"typed":{"path":"","type":0,"remoteURL":"","lastUpdated":0,"autoUpdate":false,"autoUpdateInterval":60,"core":"auto","upload":0,"download":0,"total":0,"expire":0,"webPageUrl":"","intervalUserSet":false}}`); err != nil {
		t.Fatal(err)
	}
	// A write from outside waits for the transaction to end.
	outside := make(chan int64, 1)
	go func() {
		id, err := UIProfileStoreInsert(0, uiServerTable, `{"id":0,"userOrder":5,"name":"later","url":"u","secret":""}`)
		if err != nil {
			id = -1
		}
		outside <- id
	}()
	select {
	case <-outside:
		t.Fatal("a write outside the transaction must wait")
	case <-time.After(100 * time.Millisecond):
	}
	if err := UIProfileStoreCommit(tx); err != nil {
		t.Fatal(err)
	}
	if id := <-outside; id != 3 {
		t.Fatalf("waiting write = %d", id)
	}
	uiProfileStoreClose()
	if _, err := UIProfileStoreOpen(path); err != nil {
		t.Fatal(err)
	}
	rows := uiTestProfiles(t, 0)
	if got := uiTestIDs(rows); !reflect.DeepEqual(got, []int64{5, 7, 2}) || rows[2].Name != "Back" {
		t.Fatalf("committed = %v", got)
	}
}

func TestUIProfileStoreFailedWriteKeepsState(t *testing.T) {
	path := uiTestStorePath(t)
	uiTestImport(t, path, uiTestRoomDataset)
	// Make the rename target a non-empty directory so the save fails.
	if err := os.Mkdir(path+".tmp", 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path+".tmp", "x"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := UIProfileStoreDelete(0, uiProfileTable, "[2]"); err == nil {
		t.Fatal("expected the save to fail")
	}
	if len(uiTestProfiles(t, 0)) != 3 {
		t.Fatal("a failed save must leave the rows")
	}
	tx, _ := UIProfileStoreBegin()
	_, _ = UIProfileStoreDelete(tx, uiProfileTable, "[2]")
	if err := UIProfileStoreCommit(tx); err == nil {
		t.Fatal("expected the commit to fail")
	}
	if len(uiTestProfiles(t, 0)) != 3 {
		t.Fatal("a failed commit must leave the rows")
	}
	if _, err := UIProfileStoreBegin(); err != nil {
		t.Fatal("a failed commit must end the transaction")
	}
}

func TestUIProfileStoreConcurrentAccess(t *testing.T) {
	path := uiTestStorePath(t)
	uiTestImport(t, path, `{"profileSeq":0,"serverSeq":0,"profiles":[],"servers":[]}`)
	const writers, each = 8, 10
	var group sync.WaitGroup
	errs := make(chan error, writers*each*3)
	for w := 0; w < writers; w++ {
		group.Add(1)
		go func(w int) {
			defer group.Done()
			for i := 0; i < each; i++ {
				if w%2 == 0 {
					tx, err := UIProfileStoreBegin()
					if err != nil {
						errs <- err
						return
					}
					next, _ := UIProfileStoreNextOrder(tx, uiProfileTable)
					_, err = UIProfileStoreInsert(tx, uiProfileTable, `{"id":0,"userOrder":`+jsonInt(next)+`,"name":"t","icon":null,"typed":{"path":"","type":0,"remoteURL":"","lastUpdated":0,"autoUpdate":false,"autoUpdateInterval":60,"core":"auto","upload":0,"download":0,"total":0,"expire":0,"webPageUrl":"","intervalUserSet":false}}`)
					if err != nil {
						errs <- err
					}
					if err := UIProfileStoreCommit(tx); err != nil {
						errs <- err
					}
				} else {
					if _, err := UIProfileStoreInsert(0, uiProfileTable, `{"id":0,"userOrder":0,"name":"n","icon":null,"typed":{"path":"","type":0,"remoteURL":"","lastUpdated":0,"autoUpdate":false,"autoUpdateInterval":60,"core":"auto","upload":0,"download":0,"total":0,"expire":0,"webPageUrl":"","intervalUserSet":false}}`); err != nil {
						errs <- err
					}
				}
				if _, err := UIProfileStoreList(0, uiProfileTable); err != nil {
					errs <- err
				}
			}
		}(w)
	}
	group.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	rows := uiTestProfiles(t, 0)
	seen := map[int64]bool{}
	for _, row := range rows {
		if seen[row.ID] {
			t.Fatalf("duplicate id %d", row.ID)
		}
		seen[row.ID] = true
	}
	if len(rows) != writers*each {
		t.Fatalf("rows = %d", len(rows))
	}
	uiProfileStoreClose()
	if _, err := UIProfileStoreOpen(path); err != nil || len(uiTestProfiles(t, 0)) != writers*each {
		t.Fatalf("reopened: %v", err)
	}
}

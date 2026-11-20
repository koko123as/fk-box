package httpdebug

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Message struct {
	Method            string   `json:"method,omitempty"`
	URL               string   `json:"url,omitempty"`
	Protocol          string   `json:"protocol"`
	Status            int      `json:"status,omitempty"`
	Headers           []Header `json:"headers"`
	HeadersTruncated  bool     `json:"headersTruncated"`
	Trailers          []Header `json:"trailers,omitempty"`
	TrailersTruncated bool     `json:"trailersTruncated,omitempty"`
	BodyBytes         int64    `json:"bodyBytes"`
	CapturedBytes     int64    `json:"capturedBytes"`
	BodyTruncated     bool     `json:"bodyTruncated"`
	BodyComplete      bool     `json:"bodyComplete"`
}

type Exchange struct {
	ID          string   `json:"id"`
	StartedAt   int64    `json:"startedAt"`
	DurationMS  int64    `json:"durationMs"`
	Complete    bool     `json:"complete"`
	Error       string   `json:"error,omitempty"`
	Note        string   `json:"note,omitempty"`
	OriginalURL string   `json:"originalUrl,omitempty"`
	DomainOnly  bool     `json:"domainOnly,omitempty"`
	Metadata    Metadata `json:"metadata"`
	Request     Message  `json:"request"`
	Response    Message  `json:"response"`
	BodyLimit   int64    `json:"bodyLimit"`
	storedBytes int64
	jsonBytes   int64
	io          *recordIO
}

// recordIO serializes the file operations of one record. Writes run after the
// store lock is released, so a slow disk never blocks other connections;
// removed is set under the store lock and checked under mu, so a write that
// starts after removal does not recreate a deleted file.
type recordIO struct {
	mu        sync.Mutex
	removed   atomic.Bool
	persisted uint64 // latest metadata snapshot handed out (store lock)
	written   uint64 // latest metadata snapshot on disk (mu)
}

// storeIO is file work collected under the store lock and run after it.
type storeIO []func()

func (tasks storeIO) run() {
	for _, task := range tasks {
		task()
	}
}

type Detail struct {
	Exchange
	RequestBody     []byte  `json:"requestBody"`
	ResponseBody    []byte  `json:"responseBody"`
	RequestPreview  Preview `json:"requestPreview"`
	ResponsePreview Preview `json:"responsePreview"`
}

type Summary struct {
	ID         string   `json:"id"`
	StartedAt  int64    `json:"startedAt"`
	DurationMS int64    `json:"durationMs"`
	Complete   bool     `json:"complete"`
	Method     string   `json:"method"`
	URL        string   `json:"url"`
	Protocol   string   `json:"protocol"`
	Status     int      `json:"status"`
	Packages   []string `json:"packages"`
	Bytes      int64    `json:"bytes"`
	Error      string   `json:"error,omitempty"`
	DomainOnly bool     `json:"domainOnly,omitempty"`
}

type Page struct {
	Items       []Summary `json:"items"`
	Total       int       `json:"total"`
	StoredBytes int64     `json:"storedBytes"`
	Error       string    `json:"error,omitempty"`
}

// Store retains a bounded prefix of each body on disk, never a full streaming
// response in memory. All bookkeeping shares one lock; file reads and writes
// run after it is released (recordIO), and no lock is held across upstream
// network operations or the complete duration of an HTTP transaction.
// Domain-only records (observed TLS/QUIC hostnames) have their own quota, so
// they never evict recorded HTTP exchanges.
type Store struct {
	mu         sync.Mutex
	directory  string
	records    map[string]*Exchange
	order      []string
	totalBytes int64
	lastError  string
}

func OpenStore(directory string) (*Store, error) {
	if directory == "" {
		return nil, errors.New("missing capture storage directory")
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, err
	}
	if err := removeRetiredWireFiles(directory); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		return nil, err
	}
	s := &Store{directory: directory, records: make(map[string]*Exchange)}
	for _, entry := range entries {
		name := entry.Name()
		id := strings.TrimSuffix(name, ".json")
		if entry.IsDir() || !strings.HasSuffix(name, ".json") || !validID(id) {
			continue
		}
		file, err := os.Open(filepath.Join(directory, name))
		if err != nil {
			continue
		}
		var record Exchange
		err = json.NewDecoder(io.LimitReader(file, 2*1024*1024)).Decode(&record)
		file.Close()
		if err != nil || record.ID != id || record.BodyLimit < 1 || record.BodyLimit > MaxBodyLimit {
			_ = os.Remove(filepath.Join(directory, name))
			continue
		}
		if !record.Complete {
			record.Complete = true
			record.Error = "Capture interrupted before the transaction finished"
		}
		record.Request.CapturedBytes, record.Response.CapturedBytes = 0, 0
		for _, suffix := range []string{".json", ".request", ".response"} {
			if info, err := os.Stat(filepath.Join(directory, id+suffix)); err == nil {
				record.storedBytes += info.Size()
				if suffix == ".json" {
					record.jsonBytes = info.Size()
				} else {
					message := &record.Request
					if suffix == ".response" {
						message = &record.Response
					}
					message.CapturedBytes = min(info.Size(), record.BodyLimit)
					message.BodyBytes = max(message.BodyBytes, info.Size())
				}
			}
		}
		record.Request.BodyTruncated = record.Request.BodyTruncated || record.Request.BodyBytes > record.Request.CapturedBytes
		record.Response.BodyTruncated = record.Response.BodyTruncated || record.Response.BodyBytes > record.Response.CapturedBytes
		record.io = &recordIO{}
		s.records[id] = &record
		s.order = append(s.order, id)
		s.totalBytes += record.storedBytes
	}
	slices.SortFunc(s.order, func(a, b string) int {
		if s.records[a].StartedAt < s.records[b].StartedAt {
			return -1
		}
		if s.records[a].StartedAt > s.records[b].StartedAt {
			return 1
		}
		return strings.Compare(a, b)
	})
	// Only remove files owned by this store, including orphaned body/temp files.
	for _, entry := range entries {
		name := entry.Name()
		for _, suffix := range []string{".request", ".response", ".json.tmp"} {
			id := strings.TrimSuffix(name, suffix)
			if strings.HasSuffix(name, suffix) && validID(id) && (suffix == ".json.tmp" || s.records[id] == nil) {
				_ = os.Remove(filepath.Join(directory, name))
			}
		}
	}
	s.trimLocked().run()
	return s, nil
}

func validID(id string) bool {
	if len(id) != 32 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}

func (s *Store) begin(metadata Metadata, request *http.Request, bodyLimit int64) string {
	random := make([]byte, 16)
	if _, err := rand.Read(random); err != nil {
		return ""
	}
	id := hex.EncodeToString(random)
	headers, truncated := captureHeaders(request.Header, request.Host)
	record := &Exchange{
		ID: id, StartedAt: time.Now().UnixMilli(), Metadata: metadata, BodyLimit: bodyLimit,
		Request: Message{Method: request.Method, URL: request.URL.String(), Protocol: request.Proto,
			Headers: headers, HeadersTruncated: truncated, BodyComplete: request.Body == nil || request.Body == http.NoBody},
		Response: Message{Headers: []Header{}}, io: &recordIO{},
	}
	s.mu.Lock()
	s.records[id] = record
	s.order = append(s.order, id)
	tasks := append(storeIO{s.persistLocked(record)}, s.trimLocked()...)
	s.mu.Unlock()
	tasks.run()
	return id
}

// observeDomain records a finished domain-only entry (an observed TLS/QUIC
// hostname or a TLS failure) with a single metadata write.
func (s *Store) observeDomain(metadata Metadata, request *http.Request, bodyLimit int64, note string, err error) {
	random := make([]byte, 16)
	if _, randomErr := rand.Read(random); randomErr != nil {
		return
	}
	id := hex.EncodeToString(random)
	headers, truncated := captureHeaders(request.Header, request.Host)
	if note != "" {
		note = "\n" + note
	}
	note = "Domain only: observed TLS/QUIC hostname; HTTPS plaintext was not observed" + note
	record := &Exchange{
		ID: id, StartedAt: time.Now().UnixMilli(), Complete: true, Note: note, DomainOnly: true,
		Metadata: metadata, BodyLimit: bodyLimit,
		Request: Message{Method: request.Method, URL: request.URL.String(), Protocol: request.Proto,
			Headers: headers, HeadersTruncated: truncated, BodyComplete: true},
		Response: Message{Headers: []Header{}}, io: &recordIO{},
	}
	if err != nil {
		record.Error = err.Error()
	}
	s.mu.Lock()
	s.records[id] = record
	s.order = append(s.order, id)
	tasks := append(storeIO{s.persistLocked(record)}, s.trimLocked()...)
	s.mu.Unlock()
	tasks.run()
}

func (s *Store) response(id string, response *http.Response) {
	var tasks storeIO
	defer func() { tasks.run() }()
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil {
		if response.TLS != nil && record.Metadata.TLS != nil {
			metadata := *record.Metadata.TLS
			metadata.UpstreamVersion = tlsVersion(response.TLS.Version)
			metadata.UpstreamServerName = response.TLS.ServerName
			if len(response.TLS.PeerCertificates) > 0 {
				fingerprint := sha256.Sum256(response.TLS.PeerCertificates[0].Raw)
				metadata.UpstreamCertificate = hex.EncodeToString(fingerprint[:])
			}
			record.Metadata.TLS = &metadata
		}
		headers, truncated := captureHeaders(response.Header, "")
		record.Response = Message{
			Status: response.StatusCode, Protocol: response.Proto, Headers: headers, HeadersTruncated: truncated,
			BodyComplete: response.Body == nil || response.Body == http.NoBody || response.ContentLength == 0,
		}
		if response.StatusCode == http.StatusSwitchingProtocols {
			record.Note += "\nProtocol upgraded; only the HTTP handshake is recorded"
			record.Response.BodyComplete = true
		}
		tasks = append(storeIO{s.persistLocked(record)}, s.trimLocked()...)
	}
}

func (s *Store) rewrittenRequest(id string, request *http.Request, originalURL string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil {
		if originalURL != request.URL.String() {
			record.OriginalURL = originalURL
		}
		record.Request.URL = request.URL.String()
		record.Request.Method = request.Method
		record.Request.Headers, record.Request.HeadersTruncated = captureHeaders(request.Header, request.Host)
		record.Request.BodyComplete = request.Body == nil || request.Body == http.NoBody
	}
}

func (s *Store) addNote(id, note string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil && len(record.Note) < 4096 {
		if len(note) > 4096-len(record.Note) {
			note = note[:4096-len(record.Note)]
		}
		if record.Note != "" {
			record.Note += "\n"
		}
		record.Note += note
	}
}

// Domain extraction uses all retained matching records, not only the currently
// visible page. Historical URL hosts remain readable and are deduped.
func (s *Store) Domains(query string) []string {
	query = strings.ToLower(strings.TrimSpace(query))
	s.mu.Lock()
	defer s.mu.Unlock()
	hosts := make(map[string]bool)
	for _, id := range s.order {
		r := s.records[id]
		if r == nil || !r.matchesQuery(query) {
			continue
		}
		values := []string{r.Metadata.Host}
		for _, raw := range []string{r.Request.URL, r.OriginalURL} {
			if value, err := url.Parse(raw); err == nil {
				values = append(values, value.Hostname())
			}
		}
		for _, value := range values {
			value = normalizeHost(value)
			if validHost(value) {
				hosts[value] = true
			}
		}
	}
	result := make([]string, 0, len(hosts))
	for host := range hosts {
		result = append(result, host)
	}
	slices.Sort(result)
	return result
}

func (s *Store) appendBody(id string, response bool, p []byte) {
	s.mu.Lock()
	record := s.records[id]
	if record == nil {
		s.mu.Unlock()
		return
	}
	message, suffix := &record.Request, ".request"
	if response {
		message, suffix = &record.Response, ".response"
	}
	message.BodyBytes += int64(len(p))
	remaining := record.BodyLimit - message.CapturedBytes
	var write []byte
	if remaining > 0 && len(p) > 0 {
		write = p[:min(int64(len(p)), remaining)]
		// Reserve the bytes now so concurrent appends and trimming see them;
		// a failed write gives back what was not written.
		message.CapturedBytes += int64(len(write))
		record.storedBytes += int64(len(write))
		s.totalBytes += int64(len(write))
	}
	message.BodyTruncated = message.BodyBytes > message.CapturedBytes
	tasks := s.trimLocked()
	path := filepath.Join(s.directory, id+suffix)
	s.mu.Unlock()
	tasks.run()
	if len(write) == 0 {
		return
	}
	written, err := record.appendFile(path, write)
	if err == nil && written == len(write) {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.records[id] == record {
		missing := int64(len(write) - written)
		message.CapturedBytes -= missing
		record.storedBytes -= missing
		s.totalBytes -= missing
		message.BodyTruncated = message.BodyBytes > message.CapturedBytes
	}
	if err == nil {
		err = io.ErrShortWrite
	}
	s.lastError = "Cannot save capture body: " + err.Error()
}

// appendFile appends to one of the record's body files unless the record has
// been removed; removal deletes the files under the same lock.
func (r *Exchange) appendFile(path string, data []byte) (int, error) {
	r.io.mu.Lock()
	defer r.io.mu.Unlock()
	if r.io.removed.Load() {
		return len(data), nil
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return 0, err
	}
	n, err := file.Write(data)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	return n, err
}

func (s *Store) bodyComplete(id string, response bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil {
		if response {
			record.Response.BodyComplete = true
		} else {
			record.Request.BodyComplete = true
		}
	}
}

func (s *Store) trailers(id string, response bool, trailers http.Header) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil {
		message := &record.Request
		if response {
			message = &record.Response
		}
		message.Trailers, message.TrailersTruncated = captureHeaders(trailers, "")
	}
}

func (s *Store) setError(id string, err error) {
	if err == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[id]; record != nil && record.Error == "" {
		record.Error = err.Error()
	}
}

func (s *Store) finish(id string) {
	var tasks storeIO
	s.mu.Lock()
	if record := s.records[id]; record != nil {
		record.Complete = true
		record.DurationMS = max(0, time.Now().UnixMilli()-record.StartedAt)
		tasks = append(storeIO{s.persistLocked(record)}, s.trimLocked()...)
	}
	s.mu.Unlock()
	tasks.run()
}

// persistLocked snapshots the record's metadata under the store lock and
// returns the write to run after it. Snapshots are numbered so an older one
// that is written late never replaces a newer one.
func (s *Store) persistLocked(record *Exchange) func() {
	data, err := json.Marshal(record)
	if err != nil {
		s.lastError = "Cannot save capture metadata: " + err.Error()
		return func() {}
	}
	record.io.persisted++
	sequence := record.io.persisted
	path := filepath.Join(s.directory, record.ID+".json")
	return func() { s.writeMetadata(record, path, data, sequence) }
}

func (s *Store) writeMetadata(record *Exchange, path string, data []byte, sequence uint64) {
	record.io.mu.Lock()
	if record.io.removed.Load() || sequence <= record.io.written {
		record.io.mu.Unlock()
		return
	}
	err := os.WriteFile(path+".tmp", data, 0o600)
	if err == nil {
		err = os.Rename(path+".tmp", path)
	}
	if err != nil {
		_ = os.Remove(path + ".tmp")
	} else {
		record.io.written = sequence
	}
	record.io.mu.Unlock()
	s.mu.Lock()
	if err != nil {
		s.lastError = "Cannot save capture metadata: " + err.Error()
		s.mu.Unlock()
		return
	}
	var tasks storeIO
	if s.records[record.ID] == record {
		delta := int64(len(data)) - record.jsonBytes
		record.jsonBytes = int64(len(data))
		record.storedBytes += delta
		s.totalBytes += delta
		tasks = s.trimLocked()
	}
	s.mu.Unlock()
	tasks.run()
}

// removeLocked drops the record from the index and returns the deletion of
// its files.
func (s *Store) removeLocked(id string) func() {
	record := s.records[id]
	if record == nil {
		return func() {}
	}
	record.io.removed.Store(true)
	s.totalBytes -= record.storedBytes
	delete(s.records, id)
	return func() {
		record.io.mu.Lock()
		defer record.io.mu.Unlock()
		for _, suffix := range []string{".json", ".request", ".response", ".json.tmp"} {
			if err := os.Remove(filepath.Join(s.directory, id+suffix)); err != nil && !errors.Is(err, os.ErrNotExist) {
				s.mu.Lock()
				s.lastError = "Cannot remove capture: " + err.Error()
				s.mu.Unlock()
			}
		}
	}
}

// trimLocked evicts the oldest records over the storage limit, the oldest
// HTTP exchanges over MaxEntries and the oldest domain-only records over
// MaxDomainEntries. It returns the file deletions to run after the lock.
func (s *Store) trimLocked() storeIO {
	exchanges, domains := 0, 0
	for _, id := range s.order {
		if s.records[id].DomainOnly {
			domains++
		} else {
			exchanges++
		}
	}
	var tasks storeIO
	for len(s.order) > 0 {
		victim := -1
		switch {
		case s.totalBytes > MaxStorageBytes:
			victim = 0
		case exchanges > MaxEntries:
			victim = slices.IndexFunc(s.order, func(id string) bool { return !s.records[id].DomainOnly })
		case domains > MaxDomainEntries:
			victim = slices.IndexFunc(s.order, func(id string) bool { return s.records[id].DomainOnly })
		}
		if victim < 0 {
			break
		}
		id := s.order[victim]
		if s.records[id].DomainOnly {
			domains--
		} else {
			exchanges--
		}
		tasks = append(tasks, s.removeLocked(id))
		s.order = slices.Delete(s.order, victim, victim+1)
	}
	return tasks
}

func (s *Store) Clear() {
	s.mu.Lock()
	s.lastError = ""
	var tasks storeIO
	for _, id := range s.order {
		tasks = append(tasks, s.removeLocked(id))
	}
	s.order = nil
	s.mu.Unlock()
	tasks.run()
}

func (r *Exchange) matchesQuery(query string) bool {
	return query == "" || strings.Contains(strings.ToLower(r.Request.URL+" "+r.Request.Method+" "+
		strings.Join(r.Metadata.Packages, " ")+" "+strconv.Itoa(r.Response.Status)), query)
}

func (s *Store) Page(limit int, query string) Page {
	limit = min(max(limit, 1), MaxEntries)
	query = strings.ToLower(strings.TrimSpace(query))
	s.mu.Lock()
	defer s.mu.Unlock()
	page := Page{Items: make([]Summary, 0), StoredBytes: s.totalBytes, Error: s.lastError}
	for _, id := range slices.Backward(s.order) {
		r := s.records[id]
		if !r.matchesQuery(query) {
			continue
		}
		page.Total++
		if len(page.Items) >= limit {
			continue
		}
		duration := r.DurationMS
		if !r.Complete {
			duration = max(0, time.Now().UnixMilli()-r.StartedAt)
		}
		page.Items = append(page.Items, Summary{ID: id, StartedAt: r.StartedAt, DurationMS: duration, Complete: r.Complete,
			Method: r.Request.Method, URL: r.Request.URL, Protocol: r.Request.Protocol, Status: r.Response.Status,
			Packages: r.Metadata.Packages, Bytes: r.Request.BodyBytes + r.Response.BodyBytes, Error: r.Error, DomainOnly: r.DomainOnly})
	}
	return page
}

func (s *Store) Detail(id string) (*Detail, error) {
	s.mu.Lock()
	record := s.records[id]
	if record == nil {
		s.mu.Unlock()
		return nil, os.ErrNotExist
	}
	// The body files are read after the lock is released.
	detail := &Detail{Exchange: *record}
	s.mu.Unlock()
	if !detail.Complete {
		detail.DurationMS = max(0, time.Now().UnixMilli()-detail.StartedAt)
	}
	for i, suffix := range []string{".request", ".response"} {
		message := &detail.Request
		if i == 1 {
			message = &detail.Response
		}
		file, err := os.Open(filepath.Join(s.directory, id+suffix))
		if errors.Is(err, os.ErrNotExist) {
			message.BodyTruncated = message.BodyTruncated || message.CapturedBytes > 0 || message.BodyBytes > 0
			message.CapturedBytes = 0
			continue
		}
		if err != nil {
			return nil, err
		}
		data, err := io.ReadAll(io.LimitReader(file, detail.BodyLimit))
		file.Close()
		if err != nil {
			return nil, err
		}
		message.BodyTruncated = message.BodyTruncated || int64(len(data)) < message.CapturedBytes || int64(len(data)) < message.BodyBytes
		message.CapturedBytes = int64(len(data))
		if i == 0 {
			detail.RequestBody = data
		} else {
			detail.ResponseBody = data
		}
	}
	detail.RequestPreview = preview(detail.Request, detail.RequestBody)
	detail.ResponsePreview = preview(detail.Response, detail.ResponseBody)
	return detail, nil
}

package fk

// WebDAV backup transfers (was the request sequence of the app's
// BoxWebDAV.kt). The app keeps the account (its password sealed with an
// Android Keystore key), checks it and resolves the directory and file
// addresses with java.net.URI; these calls only send the requests and judge
// the answers, through UIWebDAVRequest (ui_http.go).

import (
	"errors"
	"fmt"
	"net/http"
)

// uiWebDAVSend is one request; a status outside 2xx fails as
// "WebDAV <method> failed: HTTP <status>", except a 405 when acceptExisting
// (MKCOL on a directory that already exists).
func uiWebDAVSend(method, link, username, password string, content []byte, acceptExisting bool) (*UIHTTPResponse, error) {
	response, err := UIWebDAVRequest(method, link, username, password, content)
	if err != nil {
		return nil, err
	}
	if !(response.Status >= 200 && response.Status <= 299 || acceptExisting && response.Status == 405) {
		return nil, fmt.Errorf("WebDAV %s failed: HTTP %d", method, response.Status)
	}
	return response, nil
}

// UIWebDAVCheck sends OPTIONS to the account's directory.
func UIWebDAVCheck(directoryURL, username, password string) error {
	_, err := uiWebDAVSend(http.MethodOptions, directoryURL, username, password, nil, false)
	return err
}

// UIWebDAVUpload creates the backup directory (an existing one answers 405)
// and puts the backup file in it.
func UIWebDAVUpload(directoryURL, fileURL, username, password string, content []byte) error {
	if len(content) > uiBackupLimit {
		return errors.New("Backup exceeds 64 MiB")
	}
	if _, err := uiWebDAVSend("MKCOL", directoryURL, username, password, nil, true); err != nil {
		return err
	}
	_, err := uiWebDAVSend(http.MethodPut, fileURL, username, password, content, false)
	return err
}

// UIWebDAVDownload reads the backup file; only a 200 is a download.
func UIWebDAVDownload(fileURL, username, password string) ([]byte, error) {
	response, err := uiWebDAVSend(http.MethodGet, fileURL, username, password, nil, false)
	if err != nil {
		return nil, err
	}
	if response.Status != 200 {
		return nil, fmt.Errorf("WebDAV download failed: HTTP %d", response.Status)
	}
	return response.Content, nil
}

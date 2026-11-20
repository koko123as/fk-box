package fkhost

import "sync"

// The working directory kernels keep their files below (mihomo's home,
// geo data, routing assets). The desktop core sets it at start; on Android
// it stays empty and libbox's Setup path (the app's files directory) is used.
var (
	workingAccess    sync.RWMutex
	workingDirectory string
)

// SetWorkingDirectory sets the working directory.
func SetWorkingDirectory(directory string) {
	workingAccess.Lock()
	workingDirectory = directory
	workingAccess.Unlock()
}

// WorkingDirectory returns the working directory, "" when none was set.
func WorkingDirectory() string {
	workingAccess.RLock()
	defer workingAccess.RUnlock()
	return workingDirectory
}

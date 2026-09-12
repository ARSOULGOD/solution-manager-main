package persist

import (
	"os"
	"path/filepath"
)

// WriteFileAtomic writes data to a temporary file, syncs it, and atomically renames it to path.
// It also applies the given mode. If mode is 0, it defaults to 0600.
func WriteFileAtomic(path string, data []byte, mode os.FileMode) error {
	if mode == 0 {
		mode = 0600
	}

	dir := filepath.Dir(path)
	// Create a temporary file in the same directory to ensure they are on the same filesystem
	// for atomic rename.
	tmpFile, err := os.CreateTemp(dir, filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmpFile.Name()

	// Ensure the temporary file is cleaned up if we fail before rename
	defer func() {
		_ = tmpFile.Close()
		_ = os.Remove(tmpPath)
	}()

	if _, err := tmpFile.Write(data); err != nil {
		return err
	}

	if err := tmpFile.Sync(); err != nil {
		return err
	}
	
	if err := tmpFile.Chmod(mode); err != nil {
		return err
	}

	if err := tmpFile.Close(); err != nil {
		return err
	}

	if err := os.Rename(tmpPath, path); err != nil {
		return err
	}

	return nil
}

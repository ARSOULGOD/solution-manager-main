//go:build !unix

package commands

import "os"

var currentUID = func() int { return 0 }

func ownedByAgent(os.FileInfo) bool { return true }

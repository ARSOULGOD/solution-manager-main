//go:build unix

package commands

import (
	"os"
	"syscall"
)

// currentUID is the agent's own uid; a variable so a test can play a foreign owner.
var currentUID = os.Getuid

func ownedByAgent(fi os.FileInfo) bool {
	st, ok := fi.Sys().(*syscall.Stat_t)
	return !ok || int(st.Uid) == currentUID()
}

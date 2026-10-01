package main

import (
	"fmt"
	"strconv"
	"strings"
)

// parseBytes accepts a positive byte count with an optional KiB, MiB or GiB
// suffix.
func parseBytes(s string) (int64, error) {
	mult := int64(1)
	for _, u := range []struct {
		suffix string
		n      int64
	}{{"KiB", 1 << 10}, {"MiB", 1 << 20}, {"GiB", 1 << 30}} {
		if v, ok := strings.CutSuffix(s, u.suffix); ok {
			s, mult = v, u.n
			break
		}
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("bad byte size %q (want e.g. 64MiB)", s)
	}
	return n * mult, nil
}

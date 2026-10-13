package main

import "testing"

func TestParseBytes(t *testing.T) {
	for in, want := range map[string]int64{"4096": 4096, "64KiB": 64 << 10, "64MiB": 64 << 20, "2GiB": 2 << 30} {
		if got, err := parseBytes(in); err != nil || got != want {
			t.Errorf("%q → %d %v, want %d", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "0", "-1", "64MB", "MiB", "8589934592GiB", "9223372036854775807KiB"} {
		if _, err := parseBytes(bad); err == nil {
			t.Errorf("%q must be refused", bad)
		}
	}
}

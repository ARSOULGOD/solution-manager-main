package telemetry

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
)

const defaultMaxBody = 1024 * 1024

// Run scrapes all targets, validates samples, persists results, and returns
// an error if any target failed scrape or validation.
func Run(ctx context.Context, dataDir string, targets []Target, client *http.Client, maxBody int64) error {
	if maxBody == 0 {
		maxBody = defaultMaxBody
	}

	store := Store{}
	var failed bool
	var errs []error

	for _, t := range targets {
		sample := Scrape(ctx, client, t, maxBody)
		if err := Validate(&sample, maxBody); err != nil {
			failed = true
			fmt.Fprintf(os.Stderr, "collect: target %s: %s\n", t.ID, err)
			if saveErr := store.SaveFailure(dataDir, sample); saveErr != nil {
				errs = append(errs, saveErr)
			}
			continue
		}
		if err := store.SaveSuccess(dataDir, sample); err != nil {
			failed = true
			errs = append(errs, err)
		}
	}

	if len(errs) > 0 {
		return errors.Join(errs...)
	}
	if failed {
		return fmt.Errorf("one or more targets failed")
	}
	return nil
}

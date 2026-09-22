package telemetry

import (
	"context"
	"io"
	"net/http"
	"time"
)

// Scrape performs an HTTP GET against the target and returns a sample.
// ScrapedAt is set to UTC RFC3339. Transport errors are recorded in Error;
// on a completed HTTP response Error is cleared before validation in Run.
// Body reads are capped at maxBody (default 1 MiB when maxBody is 0).
func Scrape(ctx context.Context, client *http.Client, t Target, maxBody int64) Sample {
	if maxBody == 0 {
		maxBody = defaultMaxBody
	}
	sample := Sample{
		ServiceID: t.ID,
		URL:       t.URL,
		ScrapedAt: time.Now().UTC().Format(time.RFC3339),
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, t.URL, nil)
	if err != nil {
		sample.Error = err.Error()
		return sample
	}

	resp, err := client.Do(req)
	if err != nil {
		sample.Error = err.Error()
		return sample
	}
	defer resp.Body.Close()

	sample.HTTPStatus = resp.StatusCode
	sample.ContentType = resp.Header.Get("Content-Type")

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		sample.Error = err.Error()
		return sample
	}
	if int64(len(body)) > maxBody {
		sample.Error = "body exceeds maximum size"
		return sample
	}
	sample.Body = string(body)
	sample.Error = ""

	return sample
}

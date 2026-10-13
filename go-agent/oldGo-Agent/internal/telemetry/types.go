package telemetry

// Target is a configured HTTP scrape endpoint.
type Target struct {
	ID  string
	URL string
}

// Sample is a single scrape result persisted as JSON.
type Sample struct {
	ServiceID   string `json:"service_id"`
	URL         string `json:"url"`
	ScrapedAt   string `json:"scraped_at"`
	HTTPStatus  int    `json:"http_status"`
	ContentType string `json:"content_type"`
	Body        string `json:"body"`
	Error       string `json:"error"`
	OK          bool   `json:"ok"`
}

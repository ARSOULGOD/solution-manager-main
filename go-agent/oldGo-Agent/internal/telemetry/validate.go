package telemetry

import (
	"fmt"
)

// Validate checks sample fields and sets OK/Error. Returns an error when invalid.
func Validate(sample *Sample, maxBody int64) error {
	if sample == nil {
		return fmt.Errorf("sample is nil")
	}

	if sample.Error != "" {
		sample.OK = false
		return fmt.Errorf("%s", sample.Error)
	}

	if sample.HTTPStatus < 200 || sample.HTTPStatus >= 300 {
		sample.OK = false
		sample.Error = fmt.Sprintf("HTTP status %d", sample.HTTPStatus)
		return fmt.Errorf("%s", sample.Error)
	}

	if sample.Body == "" {
		sample.OK = false
		sample.Error = "empty body"
		return fmt.Errorf("%s", sample.Error)
	}

	if int64(len(sample.Body)) > maxBody {
		sample.OK = false
		sample.Error = "body exceeds maximum size"
		return fmt.Errorf("%s", sample.Error)
	}

	sample.OK = true
	sample.Error = ""
	return nil
}

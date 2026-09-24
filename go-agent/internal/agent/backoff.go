package agent

import "time"

const (
	backoffBase = time.Second
	backoffCap  = 5 * time.Minute
)

// backoffFor is min(cap, base·2^attempt); the reducer applies env.Jitter.
func backoffFor(attempt int) time.Duration {
	if attempt < 0 {
		attempt = 0
	}
	if attempt > 20 {
		attempt = 20
	}
	d := backoffBase << uint(attempt)
	if d > backoffCap || d <= 0 {
		return backoffCap
	}
	return d
}

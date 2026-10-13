# Plan 011 P7.2 — lease expiry and recovery (real Go agent, 150 s lease)

Generated 2026-10-07T10:18:40.012682Z by the integration test; do not edit by hand.

| Scenario | Verdict |
|---|---|
| L1 Lease expiry with a healthy DP (renewals refused) | PASS — fail-closed while expired, recovered by the DP's renewal prompt, held command ran once |
| L2 Lease expiry across a DP outage | PASS — recovered from an outage longer than the lease without operator action; work resumed and ran once |

## L1 — Lease expiry with a healthy DP (renewals refused)

**Expected:** The lease lapses: the agent goes LEASE_EXPIRED and runs nothing, the DP offers nothing and reports nothing false; when renewals work again the DP prompts, the agent renews with its last nonce, and the held command runs exactly once

**Observed:**
- agent reached LEASE_EXPIRED 149 s after renewals began to be refused
- command held QUEUED for 6 s; docker starts=0; agent phase LEASE_EXPIRED
- recovered; lease nonce changed; docker starts=1

**Verdict:** PASS — fail-closed while expired, recovered by the DP's renewal prompt, held command ran once

## L2 — Lease expiry across a DP outage

**Expected:** The DP is away longer than the lease lives: the agent fails closed, and when the DP returns it is told LEASE_RENEW_REQUIRED, renews with its last nonce and resumes — it is never halted with ISSUE_NOT_ALLOWED

**Observed:**
- DP down 147 s; agent phase LEASE_EXPIRED; health: {"session_phase":"CLOSED","authority_phase":"LEASE_EXPIRED","expires_at":"2026-10-07T10:18:28.519Z","clock_offset_ms":-2,"at":"2026-10-07T10:18:28.634216Z"} 
- agent ACTIVE again, epoch 1 -> 2, lease nonce rotated

**Verdict:** PASS — recovered from an outage longer than the lease without operator action; work resumed and ran once

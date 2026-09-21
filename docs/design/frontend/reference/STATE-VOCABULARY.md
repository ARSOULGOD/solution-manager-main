# State vocabulary — transcribed from the approved mockup

**Transcribed verbatim 2026-08-19** from the files named per section. This document exists because the vocabulary was once reconstructed **from a derived document** — an earlier spec written from the same mockup — instead of from the mockup source, and was wrong in every dimension: membership, tones and labels. The lesson is `L16` ("your own derived document is not a source"), not merely "don't work from memory": a plausible secondary document *was* consulted, which is exactly why the error felt like diligence. **Transcribe from the source files, or from this document, which cites them line by line.**

Governing header comment (`sm-v2/app/data.jsx:8`):

> `// ---- STATE VOCABULARIES (colour is never the only signal: each carries icon + text) ----`

## Structural facts that must survive any rebuild

1. **Do not trust a count written in prose here — run the census.**

   ```bash
   python3 docs/design/frontend/reference/census-vocabularies.py
   ```

   The membership of this document was wrong **three rounds running**, each time because the search matched the shape I remembered: round 2 looked in only three files; round 3 used a `tone:` grep, which is structurally blind to `designer.jsx:316` where the keys are abbreviated `t`/`i`/`l`. A count is a claim; the census is a measurement (`L17`).

   **Today's census prints: 9 tone/label tables, 37 object-literal entries, 6 files; 6 ordered ladders; 2 tone-free label+icon maps; 1 label-only map.** All 9 tables and both tone-free maps are now transcribed below, plus `ERR_TEXT` and `STATE_LABEL`. Run the census rather than trusting this sentence.

   Two structural exceptions the census cannot count as entries, both deliberate: `NOTFOUND.restart_expected` is an **alias** (`restart_expected: HEALTH.restart_expected`, not an object literal — so 37 counted + 1 alias = 38 real entries), and `STATE_LABEL` stores labels only, with tone applied at the call site.

   **Still outstanding:** the six ordered ladders are transcribed only where they are state ladders (`CONFIG_STATES`, `RESTART_STRATEGIES`, and `governance.jsx`'s `LADDER` below). `INSTANCE_TABS`, `ADMIN_TABS` and `RANGE_PRESETS` are navigation and range, not state — classified, deliberately not transcribed as vocabulary. Login copy (`login.jsx:52-66`) remains untranscribed; see [`ERRATA.md`](ERRATA.md) **E6** before using it.

   > A previous version of this very paragraph said "two ordered ladders". The census says six. I wrote that number from recall **immediately after building the tool that measures it, without running it** — the third instance of the `L17` failure, inside the paragraph that teaches `L17`. If you are about to write a count here, run the census in the same breath.

   Outstanding, not yet transcribed:

   | Not yet transcribed | Why it matters |
   |---|---|
   | `designer.jsx:316` — the version pill map | A ninth tone table, keyed by the `UI-D12` codes with **different labels** ("Version drift", not "Drift"). Has **no `VERSION_UNKNOWN` arm and falls back to `OK`** — a load-bearing quirk |
   | `governance.jsx:15-21` — `LADDER` | A **second** apply ladder, same five keys as `CONFIG_STATES` but with different approved copy, e.g. *"We cannot see this… an honest gap, not a pass."* |
   | `charts.jsx:167` — `STATE_LABEL` | Chart-series states (`gap`, `pending`, `type-suspect`, `best-effort`), tones assigned at the call site (`:192`) |
   | `designer_data.jsx:193` — `RESTART_STRATEGIES` | Five strategies with **semantic order**, consequence levels, and the approved "confirm even when authorized" copy (rendered at `designer_panels.jsx:213-214`). No contract field carries this vocabulary |
   | `data.jsx:55-60` — `DRIVER` and `LOCUS` | label+icon(+tip) maps whose **tips are load-bearing security copy** — e.g. *"Holds credentials; needs inbound reach"*. ⚠️ The census is **structurally blind** to these: no `tone:` key, so they appear in neither the table sweep nor the label-map sweep |
   | `explore.jsx:30-36` — `ERR_TEXT` | Five `422` codes with approved operator copy |
   | `login.jsx:52-66` | Status and arrival copy, including a promise no decision supports (see the reference errata) |
   | `instance.jsx:38`, `admin.jsx:142`, `charts.jsx:40` | `INSTANCE_TABS`, `ADMIN_TABS`, `RANGE_PRESETS` — ordered, but navigation/range rather than state. Classify deliberately; do not leave them merely uncounted |

   Anyone building the Designer, Governance, Monitoring or Admin screens must read those sources directly until they are transcribed here.

2. **Tone is not severity.** "Is this blocking?" and "what colour is it?" are separate axes. `VERSION.drift` is `tone: "warn"` *and* `edit: false`. Never infer one from the other.
3. **The same conceptual state has different labels in different contexts.** `inactive_replica` is *"Inactive replica"* in `HEALTH` but *"Standby · idle"* in `GOV_HEALTH`; `not_applicable` is *"Not applicable"* vs *"No health declared"*. A single shared label per concept would be a regression.
4. **Seven tones exist**, not five: `ok`, `warn`, `err`, `info`, `neutral`, `idle`, `purple`. `idle` is a distinct paint from `neutral` (`theme.css:156-157` — same background, different text colour). `purple` has **two** callers, not one: `ENV_TONE.PROD` and the IdP-group chip at `admin_iam.jsx:11` (`<Chip tone="purple" sm>IdP group</Chip>`).
5. **Different tables carry different auxiliary fields.** `HEALTH` (5), `NOTFOUND` (4) and `GOV_HEALTH` (5) entries carry a `dot`; `VERSION`, `REPLICA_HEALTH`, `CONFIG`, `STATUS_META` and `CAT_STATE` do not. `NOTFOUND` adds `scope`. `VERSION` uses `note` instead of `tip` and adds `edit` / `blocked`. `CAT_STATE` has neither `tip` nor `icon`. One `StateDescriptor` type covering all eight must accommodate that, or the tables stay separately typed.

---

## 1. `HEALTH` — `sm-v2/app/data.jsx:10-20`

> `// Health — five values, not two.`

| Key | Label | Tone | Icon |
|---|---|---|---|
| `healthy` | Healthy | `ok` | `checkCircle` |
| `not_responding` | **Not responding** | `err` | `xCircle` |
| `restart_expected` | Restart expected | **`info`** | `restart` |
| `inactive_replica` | Inactive replica | **`idle`** | `pauseCircle` |
| `not_applicable` | Not applicable | `idle` | `ban` |

Tips, verbatim:
- `not_responding` — "The instance stopped answering health checks. Investigate."
- `restart_expected` — "The platform restarted this instance on purpose — this is not an outage."
- `inactive_replica` — "A standby: up and deliberately processing nothing. The instance is fine — its KPIs read zero because it takes no traffic."
- `not_applicable` — "This solution exposes no health endpoint — a normal condition. It is never treated as an outage."

`healthy` has **no tip**. Any type requiring a non-empty tip on every entry contradicts the source.

## 2. `NOTFOUND` — `sm-v2/app/data.jsx:22-32`

> `// "Declared but not found" — four conditions, plus the bootstrap case. Never one "missing" badge.`

`restart_expected` is an **alias**: `restart_expected: HEALTH.restart_expected`. Entries carry a `scope`.

| Key | Label | Tone | Icon | Scope |
|---|---|---|---|---|
| `restart_expected` | *(alias of `HEALTH.restart_expected`)* | `info` | `restart` | — |
| `unreachable` | Host unreachable | `warn` | `wifiOff` | Host / target alert |
| `not_exposed` | Health not exposed | `warn` | `alert` | Configuration finding |
| `missing` | Missing | `err` | `xCircle` | Instance alert |
| `bootstrap` | Awaiting first restart | `info` | `hourglass` | Bootstrap |

Tips, verbatim:
- `unreachable` — "Transport-level failure — a host or network problem, not an instance problem."
- `not_exposed` — "Reachable, but the health endpoint is absent or unexposed. A configuration finding — fixable."
- `missing` — "Reachable and exposed, yet the instance is genuinely absent beyond N intervals."
- `bootstrap` — "Newly declared — unobservable until after its first restart. Sequence: declare → apply → restart → observable."

⚠️ **Collapsing these four into one "missing" badge is what the source comment forbids.** They are four different operator situations with four different remedies.

## 3. `VERSION` — `sm-v2/app/data.jsx:34-39`

> `// Version — four values; 'unverified' is the permanent-normal state on a plain VM (editable).`

Uses `note`, not `tip`. Carries `edit`, and `unknown` alone carries `blocked: true`.

| Key | Label | Tone | Icon | `edit` | Note |
|---|---|---|---|---|---|
| `ok` | OK | `ok` | `check` | `true` | Observed matches declared. |
| `drift` | **Drift** | **`warn`** | `alert` | `false` | Deployed and declared disagree. Config is read-only until reconciled. |
| `unknown` | Unknown | `err` | `ban` | `false` (`blocked: true`) | No package covers the running version. Config editing is blocked; monitoring continues. |
| `unverified` | Unverified | **`idle`** | `info` | `true` | Nothing corroborates the declared version — normal for an MI on a plain VM. Editing is allowed. |

⚠️ `drift` is **`warn`, not `err`** — while still non-editable. This is the clearest instance of rule 2 above.
⚠️ `unverified` is `idle` and **editable**, consistent with `UI-D12` (*"`VERSION_UNVERIFIED` must not block — it is Cache-on-a-VM's permanent state"*).

## 4. `CONFIG` — `sm-v2/app/data.jsx:41-54`

> `// Config — five states, never collapsed. Cache is permanently effective-unverified.`

⚠️ **The comment says five; there are six members.** Off-by-one in the source — carry all six.

| Key | Label | Tone | Icon |
|---|---|---|---|
| `in_sync` | **Applied** | `ok` | `checkCircle` |
| `staged` | **Staged · restart req'd** | `warn` | `hourglass` |
| `rendered` | **Rendered · not persisted** | `info` | `doc` |
| `diverged` | **Config diverged** | `err` | `alert` |
| `drift` | Drift detected | `warn` | `edit` |
| `na` | **`—`** (a bare em dash) | `idle` | `ban` |

Tips, verbatim:
- `in_sync` — "Restart observed after the config was written (restart-observed). Not proof the values are effective — Cache exposes no protected config endpoint, so effective values stay unverified."
- `staged` — "Config written and persisted, but the process has not restarted. Written is not working — restart to apply."
- `rendered` — "SM produced the file but has not confirmed it landed on the target yet."
- `diverged` — "Replicas are split across config versions after a halted rollout. Explicit remediation required."
- `drift` — "The on-target file differs from SM's rendered config — a hand-edit. Alert-only; reconcile manually (never auto-reverted)."
- `na` — "observe-only target — Solution Manager writes no configuration here."

## 5. `GOV_HEALTH` — `sm-v2/app/instance.jsx:45-51`

Governance context. **Two labels differ from `HEALTH` for the same concept.**

| Key | Label | Tone | Icon |
|---|---|---|---|
| `GREEN` | Healthy | `ok` | `checkCircle` |
| `RED` | Not responding | `err` | `xCircle` |
| `RESTART_EXPECTED` | Restart expected | `info` | `restart` |
| `INACTIVE_REPLICA` | **Standby · idle** | `idle` | `pauseCircle` |
| `NOT_APPLICABLE` | **No health declared** | `idle` | `ban` |

## 6. `REPLICA_HEALTH` — `sm-v2/app/designer_data.jsx:161-166`

Per-replica rollout context. Four values — no `NOT_APPLICABLE`.

| Key | Label | Tone | Icon |
|---|---|---|---|
| `GREEN` | Healthy | `ok` | `checkCircle` |
| `RED` | Not responding | `err` | `xCircle` |
| `RESTART_EXPECTED` | Restart expected | `info` | `restart` |
| `INACTIVE_REPLICA` | Inactive replica | `idle` | `pauseCircle` |

Tips, verbatim:
- `RESTART_EXPECTED` — "Platform-initiated restart — not an outage."
- `INACTIVE_REPLICA` — "Standby: up and deliberately processing nothing. A health gate would pass it though it takes no traffic — why rolling-health-gated is unavailable for this MI."

## 7. `CONFIG_STATES` — `sm-v2/app/designer_data.jsx:154-160`

**An ordered array, not a tone table.** Five steps, each `{ k, label, tip }` — no tone, no icon. It renders as the apply progress ladder (`designer_panels.jsx:8-12` does `CONFIG_STATES.findIndex(s => s.k === reached)`), so **order is semantic**.

| # | `k` | Label |
|---|---|---|
| 1 | `desired` | Desired |
| 2 | `rendered` | Rendered |
| 3 | `persisted` | Persisted |
| 4 | `restart-observed` | Restart-observed |
| 5 | `effective-verified` | Effective-verified |

Tips, verbatim:
- `desired` — "The operator's intended config — the draft."
- `rendered` — "SM produced the config file."
- `persisted` — "The file landed on the target."
- `restart-observed` — "The process restarted and came back with the file — NOT proof it is using it."
- `effective-verified` — "The running process is confirmed using the config. Requires a protected endpoint the flagship MI does not expose — so this stays unreached (APPLY_INDETERMINATE)."

This matches `SM-D22`'s five-state model. Note `APPLY_INDETERMINATE` appears **only inside this tip and in prose copy** — it has no label, tone or icon anywhere in the mockup, so it is an *outcome concept*, not a vocabulary entry.

## 8. `STATUS_META` — `sm-v2/app/admin_data.jsx:22-26`

Permission-catalog status, rendered on the Admin RBAC screen (`admin.jsx:31`). No `icon`, no `dot`.

| Key | Label | Tone |
|---|---|---|
| `grantable` | grantable | `ok` |
| `proposed` | **proposed · not grantable** | `warn` |
| `absent` | **candidate · absent** | `idle` |

Tips, verbatim:
- `grantable` — "Exists in the ratified catalog and can be granted today."
- `proposed` — "Proposed (◇) but the catalog hasn't been amended, so it can't be granted yet."
- `absent` — "Not in the catalog at all — a placeholder name so the screen has something to lay out."

⚠️ This vocabulary encodes the **permission-catalog shortfall** — that most verbs the console needs are proposed or absent rather than grantable (`REQ-006`, `DECISIONS.md` open item 23). The three states are the screen's honest rendering of that gap; do not simplify them to "enabled/disabled".

## 9. `CAT_STATE` — `sm-v2/app/explore.jsx:9-13`

Metric-catalog state, rendered in Explore (`explore.jsx:101`). **No `tip` and no `icon`** — label plus tone only.

| Key | Label | Tone |
|---|---|---|
| `plottable` | plottable | `ok` |
| `needs-typing` | needs typing | `warn` |
| `type-suspect` | type-suspect | `warn` |

⚠️ Two distinct states share `tone: "warn"`, so **tone alone cannot identify the state** — the label is load-bearing. `needs-typing` and `type-suspect` are different conditions: the first is an undeclared metric awaiting classification (`UI-D5` quarantine), the second is a declared metric whose observed behaviour contradicts its declared `metric_type` (`SM-D30`'s `TYPE_SUSPECT` check). Note the keys are hyphenated, unlike every other table's snake_case or SCREAMING_CASE.

## 10. `VersionPill` map — `sm-v2/app/designer.jsx:316`

The **ninth** tone table, and the one two review rounds missed: its keys are abbreviated `t` / `i` / `l`, so a `tone:` grep cannot see it. Rendered in the Designer header (`designer.jsx:215`).

| Key | Label | Tone | Icon |
|---|---|---|---|
| `OK` | **Version OK** | `ok` | `check` |
| `VERSION_DRIFT` | **Version drift** | `warn` | `alert` |
| `VERSION_UNVERIFIED` | **Version unverified** | `idle` | `info` |

⚠️ **Different labels from `VERSION` for the same concepts** — "Version drift" here, "Drift" there. Both are approved copy in their own context. `L16` records *"'Version drift' for a chip labelled 'Drift'"* as an original transcription error; in fact **both strings are real**, in two different tables.

⚠️ **There is no `VERSION_UNKNOWN` arm, and the fallback is `OK`** — `map[v.state] || map.OK`. So a `VERSION_UNKNOWN` instance renders as *"Version OK"*. That is almost certainly wrong: `VERSION.unknown` is `err` and `blocked: true` (config editing blocked). **Do not transcribe the fallback.** Routed as errata.

## 11. `RESTART_STRATEGIES` — `sm-v2/app/designer_data.jsx:193`

An **ordered** list of five, with `consequence` levels and behavioural flags. Rendered at `designer_panels.jsx:213`. **No contract field carries any of this** — see open item 30.

| # | Key | Label | Consequence | Flags |
|---|---|---|---|---|
| 1 | `manual` | Manual | `recommended` | `def: true` (the default) |
| 2 | `rolling-health-gated` | Rolling · health-gated | `gated` | `needsHealthMapping`, `needsNonUniform` |
| 3 | `rolling-no-validation` | Rolling · no validation | `high` | `needsNonUniform`, **`confirmEvenWhenAuthorized`** |
| 4 | `restart-all` | Restart all | `outage` | **`confirmEvenWhenAuthorized`** |
| 5 | `scheduled` | Scheduled | `unavailable` | `outOfRelease` |

Descriptions, verbatim:
- `manual` — "Stage config to every replica, then wait. Nothing restarts until you say so; the instance keeps serving on the current config."
- `rolling-health-gated` — "Restart one replica at a time, advancing only when the last reports healthy."
- `rolling-no-validation` — "Restart replicas one at a time without waiting for a health signal. Faster, but a bad config rolls across every replica."
- `restart-all` — "Restart every replica at once. Full outage for the instance until they return."
- `scheduled` — "Restart within a maintenance window."

⚠️ **`confirmEvenWhenAuthorized` is a real and unusual requirement**: two strategies demand a confirmation *even from an operator who holds the verb*. That is not `FE-D9`'s omit-vs-disable rule — it is a third case (permitted, but consequential enough to require deliberate assent) and no decision covers it.

## 12. `LOCUS` — `sm-v2/app/data.jsx:56-60`

**No tone. The census was structurally blind to this table** until fixed. Its tips are **load-bearing security copy** — they state the credential posture that `SM-D7` and `architecture.md` §13's capability limits turn on.

| Key | Label | Icon | Tip (verbatim) |
|---|---|---|---|
| `dp-remote` | dp-remote | `globe` | "The data plane reaches into the runtime (SSH / Docker API / K8s API). **Holds credentials; needs inbound reach.**" |
| `host-local` | host-local | `server` | "An agent on the host acts locally. **Outbound-only, no stored host credentials.**" |
| `observe-only` | observe-only | `eye` | "SM never writes to this target — telemetry only." |

⚠️ This is the operator-facing statement of the **outbound-only guarantee holding only for `host-local`** — the capability limit `CLAUDE.md` names as needing explicit acceptance at ratification. Paraphrasing it would misrepresent the security posture.

## 13. `DRIVER` — `sm-v2/app/data.jsx:55`

No tone, no tips.

| Key | Label | Icon |
|---|---|---|
| `docker` | Docker | `package` |
| `k8s` | **Kubernetes** | `network` |
| `systemd` | **systemd · VM** | `hardDrive` |

## 14. `ERR_TEXT` — `sm-v2/app/explore.jsx:30-36`

Five `422` codes with approved operator copy. Values are **functions**, one taking the error metadata.

| Code | Copy |
|---|---|
| `SERIES_CAP_EXCEEDED` | ``Projected ${m.projected} series exceeds the cap of ${m.cap} (seriesBy: ${m.seriesBy}). Narrow the selection, or aggregate into fewer lines.`` |
| `AGGREGATION_NOT_APPLICABLE` | "Aggregation across instances needs at least two instances to combine." |
| `GROUP_BY_REQUIRES_AGGREGATION` | "Group-by only applies once you aggregate across instances." |
| `STAT_REQUIRES_AGGREGATION` | "A single-stat or gauge shows one number, so it requires aggregation to a single value." |
| `METRIC_UNTYPED` | "This metric has no declared type and cannot be queried until it is typed." |

⚠️ **`AGGREGATION_NOT_APPLICABLE`'s copy encodes the wrong semantics.** `DM-D8` defines the code as *incoherent aggregation for the metric type* (e.g. summing a gauge), not "fewer than two instances". The **copy** must change with the semantics; see [`ERRATA.md`](ERRATA.md) **E3**. The other four are correct as written.

## 15. `STATE_LABEL` — `sm-v2/app/charts.jsx:167`

Chart-series states. **No tones in the table** — tone is decided at the call site (`:192`): `type-suspect` → `warn`, everything else → `idle`.

| Key | Chip label | Tone (from `:192`) |
|---|---|---|
| `ok` | **`null`** — no chip is rendered | — |
| `gap` | gap | `idle` |
| `inactive-replica` | inactive replica | `idle` |
| `pending-config-change` | **pending** | `idle` |
| `type-suspect` | type-suspect | **`warn`** |
| `unverified-durability` | **best-effort** | `idle` |

⚠️ Two label divergences worth preserving: `pending-config-change` renders as just **"pending"**, and `unverified-durability` renders as **"best-effort"** — not as its key. `ok` renders **nothing**, which is the "absence is not zero" discipline applied to chips: a healthy series carries no badge.

## 16. `LADDER` — `sm-v2/app/governance.jsx:15-21`

The **second** apply ladder. Same five keys as `CONFIG_STATES` (§7), but **different approved copy** and an extra `st` field carrying reached-state. Rendered at `governance.jsx:137`.

| # | Key | Label | `st` | Note (verbatim) |
|---|---|---|---|---|
| 1 | `desired` | Desired | `done` | "Your intended configuration (the saved draft)." |
| 2 | `rendered` | Rendered | `done` | "SM produced the config file." |
| 3 | `persisted` | Persisted | `done` | "The file landed on the target." |
| 4 | `restart-observed` | Restart-observed | **`cur`** | "The process restarted and came back with the file. **This is as far as the platform can see for this MI.**" |
| 5 | `effective-verified` | Effective-verified | **`unknown`** | "**We cannot see this.** The MI exposes no protected config endpoint, so the running process is permanently effective-unverified — **an honest gap, not a pass.**" |

⚠️ **This copy is the operator-facing statement of `SM-D22`'s central limit** and it is better than `CONFIG_STATES`'s: *"an honest gap, not a pass"* is exactly the guarantee `architecture.md` requires — that the UI never presents `restart-observed` as proof of convergence. **Do not merge the two ladders into one.** They render in different screens with different framing, and this one carries the honesty language.

The `st` vocabulary (`done` / `cur` / `unknown`) is a third small closed set with no contract home.

---

## `ENV_TONE` — `sm-v2/app/data.jsx:125`

```js
const ENV_TONE = { DEV: "neutral", TEST: "info", PROD: "purple" };
```

## Tones and their paint — `sm-v2/theme.css:152-158`

| Tone | Background | Foreground |
|---|---|---|
| `ok` | `--success-w10` | `--success-w100` |
| `warn` | `--warning-w10` | `--warning-w100` |
| `err` | `--error-w10` | `--error-w100` |
| `info` | `--info-w10` | `--info-w100` |
| `neutral` | `--secondary-w10` | `--secondary-text-wMain` |
| `idle` | `--secondary-w10` | `--secondary-w80` |
| `purple` | `#f2ecfb` **hardcoded** | `#6b3fa0` **hardcoded** |

⚠️ **`purple` is the one tone with no token behind it.** Every other tone resolves to a `_ds` custom property. `purple` is two literal hexes that appear neither in the verified upstream palette nor in the design-system file — they exist only in `theme.css` (verified 2026-08-19 by direct search of both files).

**All three sites, enumerated.** An earlier version of this document claimed they appeared "nowhere but `theme.css:158`", which was false:

| Site | Rule | Uses |
|---|---|---|
| `theme.css:158` | `.chip-purple` | `background:#f2ecfb; color:#6b3fa0` |
| `theme.css:552` | `.layer-op` | `background:#f2ecfb; color:#6b3fa0` |
| `theme.css:597` | `.prin-ic.group` | `background:#6b3fa0` |

Consumers: the PROD environment chip (`ENV_TONE.PROD`), the IdP-group chip (`admin_iam.jsx:11`), the Designer's layer-origin badge, and the group-principal icon in RBAC. **The deviation below therefore is not scoped to one chip** — it repaints elements in the Designer and Admin screens too.

**A token-based option exists.** The verified palette carries a purple pair in the shape every other tone uses: `--accent-purple: #7841A8` and `--accent-purple-10: #F2ECF6`, both upstream. The mockup's `#f2ecfb` is one hex digit from `#F2ECF6`, which reads like a hand-typed approximation of it.

⚠️ **It is not a straight improvement, and the earlier claim that it was is withdrawn.** Computed (WCAG relative luminance and contrast ratio):

| Pair | Foreground luminance | Contrast |
|---|---|---|
| mockup `#6b3fa0` on `#f2ecfb` | 0.0922 | **6.39 : 1** |
| verified `#7841A8` on `#F2ECF6` | 0.1060 | **5.80 : 1** |

`#7841A8` is **lighter** than `#6b3fa0`, not darker, and the swap **lowers** contrast from 6.39:1 to 5.80:1. Both clear WCAG AA for normal text (4.5:1), so this is a real but non-blocking regression. A previous version of this document asserted the opposite — "a darker, higher-contrast foreground" — from recall rather than computation. That is precisely the `L17` failure mode, committed inside the document written to prevent it.

**Two viable resolutions, and the choice is the designer's** (question 3 against open item 19b): adopt `accent-purple` / `accent-purple-10` and accept the contrast reduction, **or** promote the mockup's two values to explicit named tokens to keep the exact approved paint. Either satisfies `FE-D5`. The scope is all four consumers above, not the PROD chip alone. Do not apply either silently.

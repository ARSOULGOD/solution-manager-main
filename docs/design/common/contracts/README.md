# Cross-repo contracts

The interfaces between components. **These are the expensive things to get wrong** — a contract change is a
coordinated release across up to four repos, so design them before the repos diverge.

**How the frontend and backend meet:** [`console-integration.md`](console-integration.md) — the console integration reference (REFERENCE, reviewed 2026-09-15; **materially revised 2026-09-16** — §0, §4.3, §5.2 row 7, §11, §14.1 and §16): canonical REST and event artifacts and which of them exist, the authentication/session contract, **the endpoint → permission → resource-scope map**, how to join the mesh, the hybrid EDA rules (REST authoritative, events are notices, backfill by `stateVersion`), query invalidation, chart tails, live/degraded behaviour, error and indeterminate-outcome shapes, fixture-vs-live mode with the no-fallback prohibition, the `dp-remote` vs Agent Gateway responsibility matrix, and a **measured** implemented/planned/blocked status.

**What changed on the wire since** (login `credential`, the catalog at 43, the two ungated restart strategies, scheduling, undeclare): [`frontend-note-plan-012-ops.md`](frontend-note-plan-012-ops.md) — a note to the frontend team, not a decision.

**How the backend consumes these without drifting:** [`backend-consumption.md`](backend-consumption.md)
(`BE-D10`/`BE-D11`, PROPOSED) — the authority map, the mirror-and-lock model, module consumption rules,
check ownership between `contracts/run-checks.sh --strict` and `mvn verify`, and versioning policy.

## The contracts

| Contract | Between | Design status | Machine-readable form |
|---|---|---|---|
| **Agent ↔ plane protocol** | go-agent ↔ backend | ⚠️ **authority model decided (`SM-D28`), wire protocol UNSPECIFIED.** Session homes at the DP (`SM-D24`); CP owns CA/enrollment/revocation epoch; DP holds a short renewable lease. No `.proto`, no state machine. | `.proto` (gRPC/HTTP2 over mTLS) — **not written** |
| **`dp-remote` apply protocol** | backend ↔ MI hosts | ⚠️ **not specified** — required v1 scope, open item 3 | n/a (SSH / Docker API / K8s API) |
| **Solution Manager API reference / Console REST surface** | frontend ↔ backend | ⚠️ **OpenAPI not written**, but the reader-friendly, screen-by-screen [`console-rest-surface.md`](console-rest-surface.md) documents all 66 API operations plus `/config.json`: every parameter, request/response payload, field purpose, status, error, consuming screen and cross-endpoint dependency. It is derived from current frontend call sites and carries `CONTRACT: TBD` verdicts through rather than resolving them. A server can implement those exact payloads without changing the current frontend; the document does not ratify unresolved product behavior. | OpenAPI, split `/api/cp/*`, `/api/dp/*` and `/api/console/v1/*` — **not written** |
| **Solution Package** | authoring ↔ backend ↔ frontend | The component/field/action/metric document is canonical in `contracts/solution-package.schema.json`; the browser now compiles that same schema for preflight. ⚠️ The outer signed bundle, plural config attachments, digest→version mapping and durability fields added by `SM-D21`/`SM-D26`/`SM-D27` remain only partially specified. | JSON Schema |
| **Telemetry envelope + metric contract** | all producers ↔ backend ingest | ⚠️ **partially specified** — envelope and metric types defined; **durability fields (`SM-D27`) not yet in the schema**, and the storage DDL must be executable before it is ratifiable (`SM-D26`) | JSON Schema |
| **Config schema per solution** | per-solution **source adapter** | ⚠️ **`D3`'s premise is false** — Cache has no annotation processor and no generated metadata, and adding one violates `HC1`. See `SM-D20`. | JSON Schema, from a curated contract (Cache) or `FM_CONFIGURATION_TEMPLATE` (File Mesh) |

**Word edition:** [`solution-manager-api-reference.docx`](solution-manager-api-reference.docx) presents the same Solution Manager API reference as a modern visual handbook with workflow navigation, color-coded methods, native tables, request/response panels, and field-by-field explanations.

## Worked example

[`examples/db-designer/`](examples/db-designer/) explains one hypothetical solution end to end. Its linked
`package.json` is a canonical, machine-validated authoring example; its outer `descriptor.yaml` and signed-bundle
layout remain illustrative proposals. The walkthrough also states exactly what the console's "Load a package"
button validates today and what remains for a future backend.

## Governance — in-tree, single-writer

The project is a **monorepo**, so contracts live in-tree and are consumed by module/path dependency. That removes
the publishing pipeline and the cross-repo PR choreography — but **not** the need for a single writer: four modules
consume these, and a casual edit breaks three of them.

- **Contract changes are deliberate and reviewed.** ⚠️ **Gates to build, not current enforcement — none of the
  following exists yet:** `CODEOWNERS` on the contracts tree; a backward-compatibility CI job; a generated-code diff
  check. Until they are in place, "single-writer" is review discipline only, and should be described as such.
- **Every contract PR must run a backward-compatibility check** (to build) — `buf breaking` (protobuf), `oasdiff`
  (OpenAPI), a schema-compat check (JSON Schema). This is the highest-value automation here, because **agents upgrade on the
  customer's schedule, not ours**: a break caught at PR time costs five minutes, while one caught in the field means
  an agent in an environment we cannot reach has stopped reconciling.
- **Every contract carries a version field from the first commit**, and a breaking change requires an explicit
  compatibility window. A monorepo makes the *build* consistent; it does nothing for a deployed agent in the field.
- **Generated code is not committed** — or is regenerated in CI with a diff check.

### Before the repo split

Two things must be settled **before** the monorepo is deconstructed, and neither is needed now:

1. **Publishing** — versioned artifacts consumable by a Gradle build, an npm build and a Go build, with pre-release
   artifacts published from a PR branch so a developer is never blocked on multi-party review.
2. **Read-only consumption** — once modules are separate repos, contracts must arrive as versioned dependencies
   (inherently read-only) rather than in-tree source, with CI failing on any local modification.

Deferring these is only safe while there is one tree. **Do not split before they exist.**

## Rules

- **The CP↔DP split is a contract too.** Control-plane and data-plane modules must not import each other
  (`I7`); their interface is coarse-grained on purpose, because in-process calls become network calls after the
  SaaS split.
- **Version every contract from the first commit.** A protocol with no version field cannot be evolved safely once
  an agent is deployed in a customer environment you cannot reach.
- **Contract changes need a compatibility window**, since agents upgrade on the customer's schedule, not yours.

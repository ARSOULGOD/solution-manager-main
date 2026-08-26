# Subsystem A — brief

**Tracking:** issue `#5` · **Status:** ⚠️ **SUPERSEDED — design written** · **Date:** 2026-08-11

> ⚠️ **ERRATA. This brief is retained as the record of what was known before the design, not as guidance.**
> [`design.md`](design.md) + [`decisions.md`](decisions.md) are authoritative; where they disagree with this
> document, they win. Four things below are now known to be **wrong**, each marked inline:
>
> | § | Stale claim | Corrected in |
> |---|---|---|
> | §4 `SM-D14` row | `VERSION_UNVERIFIED` blocks editing | `UI-D12` — it must **not** block; it is Cache-on-a-VM's permanent state |
> | §5 Registry | The custom-UI escape hatch is an open question | `UI-D21` — proposed to be **narrowed**, and it conflicts with `LOCKED` `D1` |
> | §6 | `/api/cache/stats` "only works on the Caffeine provider" | `UI-D7` — it has **no** guard; it 500s, and `/size` returns a plausible wrong `0` |
> | §7 | `FM_CONFIGURATION_TEMPLATE_COPY` is a near-identical shadow table | It holds **0 rows**. Also: 1,989 template rows and 230 `showWhen`, not the figures quoted here |

This is the entry point for the Subsystem A design. It carries everything decided so far and everything still open,
so the design session starts from here rather than reassembling context.

---

## 1. Why this is the priority

Measured across `architecture.md`:

| Section | Lines |
|---|---|
| §8 Storage | **229** |
| §6 Config ownership | 119 |
| §5 Domain model | 113 |
| **§14 Config-driven UI** | smaller than every section above |
| **`ui enrichment` in the Solution Package** | **1 line** |
| **How a solution declares a graph** | **nothing — zero occurrences** |

`REQ-001` ("onboarding a solution needs no new console code") and dynamic graphs are the product's **differentiator**.
Storage — an enabler — received 229 lines and five rounds of adversarial review. The differentiator received a line
and a half. That inversion is what this design corrects.

## 2. Scope

**Full config-driven UI across all three categories** — Designer, Governance, Monitoring — plus the custom-UI escape
hatch (File Mesh's flow-DAG editor).

**Cross-cutting by nature.** It spans the Solution Package (declaration), the backend (schema registry, version
filtering, graph query) and the frontend (renderer). Written inside the frontend design, the backend half gets
under-specified — and vice versa. It therefore **precedes** the backend (`#6`) and frontend (`#9`) component designs
because it constrains both.

## 3. The three pieces to define

| Piece | Answers | State today |
|---|---|---|
| **Contract** | What does a solution's description contain? Fields, types, sections, order, control kind, secrets, which graphs from which metrics | 1 line |
| **Registry** | Where is it stored, how are versions handled? Cache 2.1 has different fields than 2.4 | open |
| **Renderer** | How does a description become screens and charts? | §14, short |

Concretely, Subsystem A is what makes this enough to produce Cache's whole Designer screen and KPI graphs with **no
Cache-specific React**:

```yaml
fields:
  - key: cache-engine.mode
    ui: { type: select, options: [READ, READ_WRITE, ADMIN], section: General, order: 1 }
  - key: cache-engine.solace.password
    ui: { type: secret, section: Broker, order: 3 }        # masked everywhere, incl. diffs
graphs:
  - metric: cache.hit_rate
    mark: line
    section: Overview
    thresholds: { warn: 0.7, crit: 0.5 }
```

---

## 4. Decided — do not re-open

| Decision | Position |
|---|---|
| **`SM-D20`** | Schema comes from **per-solution source adapters**, not build-generated metadata. Cache has no annotation processor and no generated metadata, and adding one violates `HC1`. Cache uses a **curated schema verified by a bidirectional key-set contract test**; File Mesh uses `FM_CONFIGURATION_TEMPLATE`. |
| **`SM-D30`** | Metric catalog is a **package baseline + user extensions**, two namespaced layers. Package upgrade replaces the baseline wholesale, never touches extensions. Name collision → package wins the name, user's retained under a suffix and flagged, **never merged**. |
| **`SM-D30` guards** | `metric_type` and `unit` **required at declaration** · empirical **`TYPE_SUSPECT`** check against observed behaviour · **hard per-instance cardinality cap** that rejects and surfaces rather than degrading. |
| **`SM-D31`** | Dashboards are **per-instance and shared**. Package defaults are a **separate upgradeable layer**; rendered view is the union; an operator may **hide** a default but not delete it. New RBAC verbs: `dashboard.view` / `.edit` / `.reset`. |
| **`SM-D31`** | v1 mark set: **line, gauge, single-stat**. Deferred: heatmap, stacked area, multi-axis. |
| **`SM-D14`** | ⚠️ **SUPERSEDED by `UI-D12`.** Render config against the **observed** version's schema, never the declared one. `VERSION_DRIFT` / `VERSION_UNKNOWN` / `VERSION_UNVERIFIED` block editing with a stated reason. *(The version is not observable from the MI at all, and `VERSION_UNVERIFIED` must not block.)* |
| **Excel→JSON** | An **authoring front-end on top of the contract** — the importer *emits a valid package* and validates against the same JSON Schema as any other source. It is **not** a parallel schema source. The spreadsheet is never authoritative on round-trip. |
| **Authoring rights** | Publishing a *solution* is **Solace super-admin only** (`B/D4`). Customers create *instances*, if entitled. |
| **Entitlement** | Binary per solution + version range. Count-based licensing **declined**. |

## 5. Open — to settle in the design session

**A2 — the discovery model.** If an operator adds a metric to the collector configuration, does SM **discover** it or
must it also be declared in SM? `SM-D13` is declared-only for *instances*; it has never been extended to *metrics*.
`SM-D30`'s layering works either way, but the UX differs sharply.

*Proposal on the table, deliberately not yet decided:* the **`series` table already is the catalog** — every metric
that has ever arrived has a row, so the dropdown is one query filtered on `last_seen`. The **collector configuration
is the declaration** (one place, carrying `type` and `unit` beside the scrape path), so nothing is declared twice.
Metrics that arrive **undeclared** are *discovered but not plottable until typed* — they appear in a "needs typing"
list, which makes the gap a visible to-do rather than a silent hazard. **Never infer the type.**

**C — the fields half**
- **C1** Field catalog source per MI, and how a curated Cache schema is authored and kept honest
- **C2** Validation contract: type, required, enum options, range, regex, default, secret-ness
- **C3** Applicability predicates — `when: provider == caffeine`, so fields and metrics that do not apply are not offered
- **C4** Version-aware rendering mechanics against the observed version

**B — remaining graph questions**
- Metric catalog UX: filtering, labels as group-by/filter dimensions, stale-metric handling
- Type-aware plotting: a **counter** renders as a rate/delta, never a raw monotonic climb
- Resolution routing stays automatic (≤7d raw · 7–90d hourly · >90d daily · forced-Raw override); the user picks the
  metric and mark, never the tier

**Registry**
- Storage shape, version filtering, and behaviour when the observed version has no registered package
- ⚠️ *(Addressed by `UI-D21`, which proposes narrowing this and **conflicts with `LOCKED` `D1`** — flagged, not settled.)*
  Custom-UI escape hatch: how a solution supplies its own module (File Mesh's flow-DAG editor) inside the same shell

## 6. Constraints that bound the design

- **`HC1`** — no MI development. Anything needed from an MI must be satisfiable by something SM controls, or degrade
  with the loss stated.
- **`SM-D22`** — effective config is often unobservable. Five states: `desired → rendered → persisted →
  restart-observed → effective-verified`. **Cache stops at `restart-observed`** (exposes `health,info,metrics` only).
  The UI must never present `restart-observed` as proof of convergence.
- **`SM-D26`** — `metric_type` drives `delta` and reset-detection. A counter mislabelled as a gauge yields a
  **plausible-looking wrong graph**, and nothing errors. This is why typing is mandatory rather than helpful.
- **Applicability is real, not hypothetical** — ⚠️ **the mechanism stated here is WRONG; see `UI-D7`.**
  `/api/cache/stats` has **no** `@ConditionalOnProperty`: `stats()` defaults to `null`, the controller
  dereferences it, and it returns **HTTP 500**. `/api/cache/size` defaults to `0` and returns a **plausible
  wrong zero**. `provider` is a field SM itself writes. An SM-written config change must not silently break a
  declared metric.
- **`I6`** — secrets never reach config storage, history, diffs, exports or logs. The field-level diff view is
  load-bearing UX and must mask.

## 7. Prior art — study before inventing

`FM_CONFIGURATION_TEMPLATE` (`file-mesh-ui/database/file-mesh-schema.sql:3531`) is a **production implementation of
this platform's #1 objective**:

```
COMPONENT_TYPE · CONFIGURATION_FIELD · CONFIGURATION_DESCRIPTION
UI_TYPE · UI_TYPE_META · UI_DATA_SOURCE          <- a proven renderer contract
SECTION · SECTION_ORDER · SUB_SECTION · SECTION_FIELD_ORDER
DEFAULT_VALUE · CONFIG_FILE_RELATIVE_PATH
MIN_VERSION · MAX_VERSION                        <- DEAD COLUMNS, see below
```

⚠️ **`MIN_VERSION`/`MAX_VERSION` are not used.** The production query `fetchTemplate` has **no version predicate**
(`file-mesh-services/src/database/queries/index.ts`) and `IConfigurationTemplate` **omits both fields**. The columns
exist; nothing reads them. Treat this table as a **legacy adapter input and a source of shape ideas — not the
reference model**. Design the registry properly: normalized, semver-aware, version-filtered.

⚠️ There is also a table `FM_CONFIGURATION_TEMPLATE_COPY` — **it holds 0 rows**, so it is an empty shell, not a
near-identical shadow. Verified by [`evidence/extract-fm-vocabulary.py`](evidence/extract-fm-vocabulary.py).

## 8. Deliverable

Per the lesson executable DDL taught — three rounds of prose produced defects in a primary key that **one migration
run** caught:

1. A real **JSON Schema** for the enrichment + graph-declaration blocks
2. A real **Cache instance** that validates against it
3. An assertion that the declared **screen and graphs are derivable** from it

Design prose alone is not the deliverable. Anything load-bearing that can be executed, should be.

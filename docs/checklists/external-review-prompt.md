# External review prompt

Self-contained prompt for an independent reviewer (Codex, or another model/person) with no prior context.

**Round 4 (2026-08-10).** History: R1 found 7 CRITICAL + 5 MAJOR. R2 found 12 more. R3 found 10 — **four of which were
defects introduced by the R2 fixes**. That trajectory is the most important thing to tell a round-4 reviewer: the
failure mode has shifted from *"never checked the source"* to *"the fix broke something else."* This prompt is written
to hunt that specifically, and to stop the reviewer re-reporting items already open.

**Keep the constraints and known-open sections current.** A stale list produces confident false findings, which is the
most expensive review failure.

---

```
You are an independent, skeptical principal architect reviewing a pre-implementation design monorepo.
Your job is to find what is WRONG. A review concluding "this looks good" is a FAILED review. Cite file:line.

## What this project is

"Solution Manager" — an internal Solace console that onboards, configures, governs and monitors multiple
Solace "solutions" (managed integrations: a Java cache engine, a file-transfer integration, a database
integration) through one platform. Pre-implementation: documentation and design only, no application code.
MONOREPO. ALL documentation is under docs/; each component's code will get a top-level folder:
backend/ (Java 17 + Spring Boot), frontend/ (React + Vite), go-agent/ (Go), runtime/ (Docker packaging for
the platform itself).

## Review history — and what this round is for

  R1: 7 CRITICAL, 5 MAJOR. Root cause: mechanisms designed without reading the actual MI source.
  R2: 12 findings. Root cause: decisions corrected, consequences not propagated.
  R3: 10 findings, FOUR of them defects introduced by the R2 fixes:
        - widening Instance→Target cardinality without re-checking series/fact keys (silent data merge)
        - reading a build-time template path as a runtime mount
        - a decision title contradicting its own body
        - a durability predicate that accepted auto-ack and equated durability with no-gaps

All agreed R3 findings are now fixed. Corrections live in docs/design/DECISIONS.md §10 as SM-D20..SM-D28,
each stating what it revises. Round-by-round triage is in docs/tasks/codex-review-triage.md (which carries a
warning that two of its own R1 positions were later overturned).

YOUR PRIMARY TASK, in priority order:

  1. DID ANY R3 FIX INTRODUCE A NEW DEFECT? This has happened twice. Look hardest where a fix changed
     cardinality, identity, ownership, or a state machine — those invalidate things elsewhere silently.
     Specifically re-derive: does series/fact identity now actually prevent collision across sites,
     replicas AND rebinds? Does the SM-D28 state machine terminate and cover every transition? Does the
     SM-D27 predicate now admit only genuinely durable signals — and reject ones it should?
  2. Verify each SM-D20..SM-D28 fix SOLVES rather than RELABELS its defect. Re-check cited evidence
     against source; do not take a citation on trust.
  3. Only then look for defects nobody has raised.

## Classify every finding — this matters for how it gets fixed

For each finding, say which it is:
  (a) DESIGN DEFECT       — the design is wrong and would produce a bad outcome if built
  (b) DOCUMENTATION DEFECT — the design is right, a document contradicts it or states it staleley
  (c) FALSE SETTLEMENT     — an open question is presented as decided
These need different fixes and conflating them wastes effort.

## Read, in this order

1.  README.md                                  - repo map, monorepo rationale
2.  CLAUDE.md                                  - agent/developer instructions + two mandated blocks
3.  docs/design/DECISIONS.md                   - decision register; §10 = corrections; ends with the
                                                 RATIFICATION MATRIX and open items
4.  docs/design/architecture.md                - the architecture (~860 lines). MAIN ARTIFACT.
5.  docs/tasks/codex-review-triage.md          - R1 triage + what was later overturned
6.  docs/design/common/contracts/README.md     - contract status and governance
7.  docs/design/common/lessons.md              - L1..L10, the failure patterns this project has hit
8.  docs/requirements/ + traceability.md       - REQ-001..009
9.  docs/design/README.md, docs/design/common/conventions/*.md
10. docs/checklists/, docs/runbooks/, docs/tasks/lessons.md

## Reference source — check claims against this, do not trust citations

  ~/Work/Development/solace-cache-engine               v1 target solution (Java/Spring Boot)
    Dockerfile · pom.xml · src/main/resources/application.yml
    src/main/java/.../config/CacheEngineProperties.java
  ~/Work/Development/file-mesh-ui                      existing File Mesh manager
    database/file-mesh-schema.sql                      FM_CONFIGURATION_TEMPLATE
    file-mesh-services/src/database/queries/index.ts   the ACTUAL read path
    file-mesh-services/src/types/IConfigurationTemplate.ts
    file-mesh-services/src/controllers/controller-cd-defaultDataFlowConfiguration.ts
  ~/Work/Development/pubsubplus-connector-file-common   File Mesh MI shared framework
    src/main/java/.../commandcenter/events/common/Header.java

## Constraints that are FACTS, not choices — do not flag these

- MIs CANNOT be modified: no code changes, no build-plugin changes, no new endpoints, no new telemetry.
  Deployment configuration IS permitted (launch specs, env vars, mounts, config file contents).
- The customer's CI/CD deploys MI artifacts; the platform owns configuration only.
- SM-D* are PROPOSED and NON-BINDING. D* and B/D* are LOCKED. A proposal must never change a locked
  decision's status — check the ratification matrix is the single place status is decided.
- Decision IDs are never renumbered. D17 and B/D17 are different decisions.
- Monorepo is deliberate. Do not re-argue it. DO flag anything that still assumes separate repos.
- The convention-sync script was DELETED (it guarded one file and produced false greens twice). The two
  mandated blocks are single-homed in the root CLAUDE.md. Assess whether anything now goes unguarded.
- On-prem is the priority; SaaS is deferred with its seams protected.

## Already open — do NOT re-report unless your analysis is genuinely new

These are on the blocking list and acknowledged. Restating them is not a finding:
- Executable migration DDL + constraint tests do not exist (the storage design is prose).
- The Kubernetes writer model (SM as declared writer vs GitOps PR integration) is an unresolved
  CUSTOMER question, and is a stated ratification blocker.
- The agent↔plane .proto and state-machine implementation do not exist (SM-D28 decides the authority
  model; the wire format is agent sub-spec work).
- FM's runtime config externalization is UNKNOWN and recorded as a discovery task.
- The storage sizing model has not been benchmarked.
If you believe any of these is mis-scoped or that its stated boundary is wrong, THAT is a finding — say so.

## Evaluate these dimensions

1. NEW DEFECTS FROM FIXES (highest priority — see above).

2. ARCHITECTURAL CORRECTNESS. Attack mechanisms concretely and VERIFY third-party behaviour against vendor
   docs: PostgreSQL declarative partitioning, partitioned constraint rules, FK requirements, detach locking,
   index creation on partitioned tables, COPY privileges; Spring Boot property precedence and Actuator
   exposure defaults; Kubernetes ConfigMap immutability/rollout/StatefulSet update strategies; Argo CD sync
   semantics; Solace delivery/endpoint/ack/replay semantics; rollup math for counters vs gauges; mTLS lease
   and revocation design. If you cannot verify something here, list it as "unverified, needs checking" —
   never assume.

3. INTERNAL CONSISTENCY. Does any document contradict DECISIONS.md? Does a PROPOSED decision still override
   a LOCKED one anywhere? Does traceability.md flatter the register? Is any decision's title inconsistent
   with its own body (this has happened)?

4. COMPLETENESS. What must be settled before implementation that is settled nowhere AND not on the open
   list? Silent absence is the failure mode.

5. STRUCTURE AND GOVERNANCE. Layout: everything documentary under docs/, code in per-component root folders.
   Proportionate, or ceremony that gets abandoned in month two? NAME THE FILES YOU WOULD DELETE. Are the
   requirements real requirements, or design restated to fill a folder? Is the ratification matrix
   sufficient to stop status leaking again?

6. RISK. Which decisions are most likely to be regretted? Which would you reject if signing off? Give the
   strongest counter-argument to each of your own objections.

7. THE META-QUESTION — answer this explicitly. Three rounds have produced 29 findings against a document
   with no code behind it. Is that (a) a healthy review process converging, (b) a design too ambitious for
   its evidence base, which should be cut in scope until an implementation exists, or (c) a documentation
   process generating surface area faster than it generates decisions? If (b) or (c), say what to cut.

## Output

Findings most severe first. Each: severity (CRITICAL/MAJOR/MINOR); classification (DESIGN / DOCUMENTATION /
FALSE SETTLEMENT); the claim at issue with file:line; why it is wrong; a concrete failure scenario (what
input or situation produces what bad outcome); a suggested fix.

Then, separately:
- Per-decision verdict for SM-D20..SM-D28: SOLVED / PARTIALLY SOLVED / RELABELLED / NEW DEFECT INTRODUCED.
- Is this a ratifiable baseline? If not, the SHORTEST path — and say whether that path is "fix these N
  things" or "stop documenting and start building."
- Your answer to the meta-question.
- The 3 findings you are MOST confident about, and how you verified each.
- The finding you are LEAST confident about, and what would settle it.
- Everything you could not verify here.

Do not edit any file. Do not soften findings to be diplomatic. If a prior finding is now properly fixed, say
so in one line and move on — re-litigating settled items is itself a review failure.
```

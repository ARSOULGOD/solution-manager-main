# solution-manager-yamlToPackage: Stage 2 (UI building) and Stage 3 (MI over SSH)

> **Status: APPROVED 2026-11-08** ("go ahead"). The four decisions below were accepted at their recommended
> defaults; D4 was overridden by the go-ahead itself: executed in the same session, stopping at each stage gate.

**Goal:** extend the committed skill (`.claude/skills/solution-manager-yamlToPackage/`, commit `7faf7f6`) from one stage
(YAML → `package.json`) to a gated three-stage pipeline:

| stage | does | ends with |
|---|---|---|
| 1. Package creation | YAML → package (built, iteration-1 benchmark 100% vs 53% baseline) | PASS/FAIL report, **STOP-AND-CONFIRM** |
| 2. UI building | generated contract test, the package loaded into the real console and checked in a headless browser, YAML round-trip | PASS/FAIL report + screenshots, **STOP-AND-CONFIRM** |
| 3. Solution via SSH | render config, dry run, deploy, health, functional smoke test, offer teardown | PASS/FAIL report, **STOP-AND-CONFIRM** |

**This is a dev/test tool, not product code.** Nothing here changes `frontend/src`, `backend/` or `contracts/`.

## Global constraints

- **No UI code changes.** The console must render any valid package with no per-solution code. A rendering defect is
  *reported* (with screenshot), never worked around in `frontend/`.
- **I6.** Secret values never appear in the package, the rendered YAML written to disk, logs or reports. The rendered
  config carries `${SM_SECRET_<NAME>}` placeholders. The MI resolves them from its environment at start, and the
  environment is supplied at deploy time from the operator's shell, never from a file the skill writes.
- **SSH safety.** Dry run is the default. No network action before explicit approval. Key or agent auth only. Never ask
  for or store a password. `StrictHostKeyChecking=yes` against a known_hosts the operator supplies. One dedicated remote
  directory. Back up before overwrite. No `rm` outside the install dir. If SSH is refused, no workarounds: stay in dry
  run and hand over `deploy_ssh.sh`.
- **I1.** Deploy only artifacts and config; never build or patch the MI.
- **I8.** Nothing new ships inside the skill beyond Python stdlib + PyYAML (MIT). Playwright (Apache-2.0) is already a
  frontend devDependency. A Solace broker image, if used, is test infrastructure only and is named as such.
- **Architecture flag (not resolved here):** the one confirmed position is *SM is a config/governance plane, not a
  deployment plane*, and the product already applies config over Docker-on-SSH (`OR-111`). Stage 3 is a test harness
  that deploys an MI *for testing*. It is not an SM feature, and `references/ssh-deploy.md` must say so in its first lines.

## Decisions (accepted 2026-11-08 at their defaults)

| # | question | recommended default **[assumed]** | alternatives |
|---|---|---|---|
| D1 | Stage 3 test target | local `sm-standin-sshd` (port 2222, the repo's demo key) + a stand-in MI container (serves `/actuator/health`, reads the rendered `application.yml`, writes its log) + **mocked** Solace. The report says "mocked" wherever it applies | real File Mesh artifact (user-supplied) + `solace/solace-pubsub-standard`; or dry run only |
| D2 | defaults vs `design.md` §7.3 (*"never from the sample application.yml"*, `UI-D*`, PROPOSED) | keep YAML defaults (FM-D7, the user's Stage 2 "defaults seeded from the YAML"); every report states the conflict; add an open item to `DECISIONS.md` so it is ruled on | follow §7.3 (round-trip compares structure/types only); per-profile choice |
| D3 | Node for the frontend (needs ≥ 22.12; host has 18.19) | run vitest, `vite` dev and Playwright in the local `node:22` image with the repo bind-mounted | user installs Node 22 |
| D4 | execution mode | fresh session against this plan | this session, stopping at each gate |

## Review focus

- **Empty `configPath` rendering.** `project.ts:384` builds `renderedPath` as `` `${configPath}.${field.key}` ``. With
  `configPath: ""` that reads `.file.source.x` unless handled elsewhere (the fixture
  `contracts/view-models/fixtures/viewmodel/fm-rendered-path-join-empty-configpath.json` exists). Read it before
  writing the round-trip: a leading-dot path is a real finding, not a normalisation to hide.
- **Two renderers drifting.** The round-trip must use the frontend's own `yamlConfiguration.ts` output (emitted by
  the generated test), not only `render_config.py`. Where both exist, they are compared and any disagreement fails.
- **Shared component, two roles.** FM-D11: sink values differ from the shared defaults. The Stage 2 round-trip for
  the sink role is *expected* to differ on those 12 keys. They must be listed as known differences from the profile,
  not hidden by a blanket ignore.
- **A secret reaching the browser.** The Playwright check must assert the input is masked *and* that no secret value
  from the YAML appears anywhere in the page HTML or the screenshots' source data.

---

## Task 0: iteration-1 fixes (from the benchmark)

- [x] **Chaining role runs.** SKILL.md: when a previous role's run produced an output package in this session (or the
  user says "same package" after one), feed that output as `--package` and say which base was used. Both sink runs in
  iteration 1 tripped on this.
- [x] **§7.3 conflict (D2).** `yaml_to_package.py` adds a standing note when `defaults.role_components: yaml`, citing
  `design.md` §7.3.
- [ ] Draft the `DECISIONS.md` open item and show it to the user before adding it (it is a register change). *Drafted at the Stage 2 gate.*
- [x] Re-run the two File Mesh examples; diff unchanged apart from the note.

## Task 1: Stage gates and per-stage summary in SKILL.md

- [x] Restructure SKILL.md into three stages, each ending with a PASS/FAIL block and an explicit STOP. Move the stage
  details to `references/ui-testing.md` and `references/ssh-deploy.md`, so SKILL.md stays under 300 lines.
- [x] Description: add triggers "build/test the UI for this package" (done) and "deploy the MI over SSH" (with Stage 3).
- [x] Final output: a per-stage summary table (stage · verdict · evidence path · open questions).

## Task 2: `scripts/gen_contract_test.py` (Stage 2.2)

- [x] Read `frontend/contracts/packages/file-mesh/package.test.ts` and generate
  `<solution>/package.generated.test.ts`. It must never overwrite a hand-written `package.test.ts`. Cover:
  - validity via `parseAndValidateSolutionPackage` in dev mode;
  - component/role/cardinality shape and unique keys;
  - every secret is `type: secret` and projects to a `secret` control;
  - role-only fields stay in their role component, using the profile's `role_exclusive` and component map;
  - enum options, `when` show/hide and `required`;
  - `project()` at **every** declared `packageVersion`, with the field set matching the `version[]` filter.
- [x] Known defects are pinned with `it.fails` and reported, as the reference test does; never worked around.
- [x] The test also writes the frontend renderer's YAML (`yamlConfiguration.ts`) for the defaults to a JSON artifact
  that Task 4 consumes.
- [x] Placement: the file has to sit beside the package for vitest's `contracts` project to pick it up. Writing it
  into `frontend/contracts/packages/<solution>/` is a repo change, so it is a gate question. Otherwise run it from a
  temporary copy and delete it afterwards.

## Task 3: `scripts/run_frontend.sh` (D3)

- [x] Subcommands `test <file>`, `dev`, `e2e <spec>`. Each runs `docker run --rm -v <repo>:/repo -w /repo/frontend node:22 …`,
  with Playwright Chromium installed once into a named volume. Prints the exact command before running it.
- [x] Verify `vitest run contracts/packages/file-mesh/package.test.ts` passes in the container, as a baseline before
  any generated test is trusted.

## Task 4: round-trip (Stage 2.4) and `scripts/render_config.py`

- [x] `render_config.py --package --role --version [--values draft.json]`: builds the config tree with the same rules as
  `yamlConfiguration.ts` (`renderedPath`, virtual excluded, `setAtPath`). Secrets become `${SM_SECRET_…}`; output
  goes to YAML.
- [x] `roundtrip`: compare the rendered defaults with the input YAML, ignoring comments and key order. The allowed
  differences come from `report.json` only: coerced, excluded, withheld secrets, list joins, and the profile's listed
  shared differences. Anything else fails with the key path.
- [x] Compare against Task 2's frontend-rendered artifact; any disagreement fails.

## Task 5: headless UI check (Stage 2.3)

- [x] Learn the dev load flow from `src/app/dev/parsePackage.ts`, `devPackageFlow.test.ts` and the `/solutions` dialog,
  and the `fixture` Playwright project (MSW, dev server).
- [x] Generate `ui-check.spec.ts` (temporary, outside `frontend/e2e/` unless the user agrees). It loads the package
  through the dialog and asserts:
  - the catalog card and inventory;
  - the role component plus the shared ones;
  - section order matching `ui.order`;
  - labels and helperText;
  - masked secrets, with no secret value present in the DOM;
  - the version selector;
  - `when` show/hide;
  - defaults equal to the YAML values.

  It captures one screenshot per role.
- [x] Report PASS/FAIL per check with the screenshot paths. Then **STOP**.

## Task 6: `scripts/deploy_ssh.sh` + `references/ssh-deploy.md` (Stage 3)

- [x] Inputs (asked, never guessed): host, user, port, auth (key or agent), known_hosts, delivery (jar, docker or
  tarball, and its location), install dir, start mode (systemd, nohup or docker), broker details.
- [x] `plan` (default): print every `ssh`/`scp` command, file, remote path and start command. Secrets show as
  `${SM_SECRET_…}`. Exit without touching the network.
- [x] `apply` (needs `--yes-i-approved`):
  - create the dedicated dir;
  - back up any existing config to `config.bak.<ts>`;
  - copy the config and the artifact;
  - start the MI;
  - record the PID or container id in `<dir>/.sm-skill-state`.
- [x] `verify`:
  - poll `/actuator/health` until `UP` within a timeout (port and exposure from the rendered YAML; if `health` is
    not exposed, report that rather than fail open);
  - grep the file in `logging.file.name` for ERROR/Exception;
  - confirm the Solace connection from health details or the log, marked **mocked** under D1.
- [x] `teardown`: offered, never automatic. Stop the MI, restore the backup, and remove only what `.sm-skill-state`
  lists.
- [x] On SSH refusal, print the exact commands and the script; do not retry with other options.

## Task 7: smoke test (Stage 3.5)

- [x] `smoke_test:` section in `references/profiles/file-mesh.yaml`: what to drop, where (source watched path), the
  expected Solace destination, the sink output dir, and a checksum compare. **Mark it unconfirmed.** It needs the
  user to say which path File Mesh actually watches (the source YAML points `input-0.destination` at
  `file_paths.cfg`, a list file, not a directory).
- [x] For other solutions: `profile_proposer.py` drafts a `smoke_test:` stub and asks; the engine never runs an
  unconfirmed smoke test.

## Task 8: tests

- [x] (a) Generated file-mesh contract test runs green in `node:22`, or reports its known defects. Show the screenshots
  (source, sink) and the round-trip result.
- [x] (b) Stage 3 dry run against a placeholder host (`mi.example.invalid`), proving no network call is made (run under
  `unshare -n` or with ssh shimmed to fail). Real run against the local `sm-standin-sshd` + stand-in MI + mock broker
  (D1), with the report saying exactly which parts were stand-ins.
- [x] New eval cases (`ui-stage-file-mesh`, `ssh-dry-run-placeholder`); iteration 2 with baselines; viewer.

## Results

### Stage 2 (Tasks 0-5), 2026-11-08: BLOCKED at one step, everything else PASS

Evidence: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-stage2/`.

| step | verdict | evidence |
|---|---|---|
| 2.1 baseline `file-mesh/package.test.ts` | **FAIL, pre-existing** (4 failed, 9 passed, 1 expected fail) | test assumes `configPath: file.source`; the package now uses `""` |
| 2.2 generated contract test | PASS (24 passed + 1 known defect pinned) | `contract-test/vitest-output.txt`; planted password default caught |
| 2.3 browser check | 17 PASS · 2 N/A · 2 BLOCKED | `ui/ui-check.json`, 9 screenshots; planted secret leak caught |
| 2.4 round-trip source @v3 | PASS (0 unexplained, 0 console drift) | `roundtrip-source.md` |
| 2.4 round-trip sink @v3 | **FAIL** (2 unexplained) | `roundtrip-sink.md`: shared `logging` renders 2 source-only `logging.level.com.solace.connector.*` keys for sink |

Findings, reported and not fixed:
- leading-dot `renderedPath` for an empty `configPath` (frontend `project.ts:384` vs the canonical engine);
- the instances grid crashes for a loaded package (pre-plan-008 dev fixture rows; the repo's own `devPackageFlow`/`devPackageScreens` tests fail on the same path), which blocks the version selector;
- the baked dev instances cover only role + `spring-cloud-stream` at v1.

The sink round-trip FAIL is a real package finding (FM-D11 class). The engine now raises it as an open question.

Deviation: D4 overridden; executed in this session at the user's "go ahead".

### Stage 3 (Tasks 6-8), 2026-11-08: PASS against the local stand-in target (mocked broker)

Evidence: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-stage3/`. Target (D1): repo `sm-standin-sshd`
on 127.0.0.1:2223 with a fresh key, **stand-in MI** (`scripts/testing/standin-mi`), **mocked Solace** (a shared
directory). No real File Mesh artifact or broker was used.

| step | verdict | evidence |
|---|---|---|
| 3.1 render source / sink @v3 | PASS: 113 / 105 keys, 6 secret placeholders each | `application-<role>.yml`, Designer drafts `draft-<role>.json` |
| 3.2 plan, placeholder host `mi.example.invalid` | PASS: tripwire `ssh`/`scp`/`curl` never invoked | `plan-placeholder.txt` |
| 3.2 refusals | PASS: system/shallow/relative install dir, no known_hosts, apply without approval, literal secret in config | |
| 3.3 apply source + sink | PASS: two containers started; 12/12 secrets resolved from stdin; no secret value in any file | `plan-<role>.txt` |
| 3.4 verify | PASS: HEALTH UP on 8092/8093, 0 ERROR lines, Solace UP **(MOCKED)** | `verify-<role>.txt` |
| 3.5 smoke test | PASS **with an unconfirmed spec**: refused without `--accept-unconfirmed`; with it, 4 KiB file source → mock broker → sink, SHA-256 matches | `smoke.md` |
| 3.6 teardown | PASS: created dirs removed; in a pre-existing dir the original config was restored byte-identical and the operator's file kept | |

Fixed during testing:
- `plan` exited 1 after success;
- `die` printed its exit code in the message;
- teardown left the `app/` and `work/` subfolders it had created in a pre-existing dir.

Open: the File Mesh `smoke_test` assumption (what `file_paths.cfg` means); the iteration-2 benchmark with Stage 2/3 eval cases.

### Benchmark iteration 2, 2026-11-08 (Task 8, last item)

Five eval cases, with skill vs without (the three original cases reuse their iteration-1 baselines).

| case | with skill | without |
|---|---|---|
| file-mesh-source-regenerate | 7/7 | 5/7 |
| file-mesh-sink-flags-consumer | 5/5 | 3/5 |
| no-profile-stops-and-asks | 7/7 | 2/7 |
| ui-stage-file-mesh (new) | 6/6 | 4/6 |
| ssh-dry-run-placeholder (new) | 5/5 | 5/5 |

Overall: 100% vs 65%, about 141 s vs 521 s and 87k vs 131k tokens per run. The UI baseline's numbers are inflated by an API
rate-limit interruption.

Fixed from these runs (`420fb75`): a provenance question on a no-op run, and the optional `cache` component rendered into a
role's config.

Candidates for iteration 3, which the UI baseline did and the skill does not:
- an edit + save check through the Designer;
- a full build + vitest regression against the original package.

The baseline also found a second console crash: a dropped package's generated instance fails on `placements[0]`.

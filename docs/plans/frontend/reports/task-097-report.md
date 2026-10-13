# FE-097 · Palette and contrast validation in CI — Report

## What I implemented

Design §18 records contrast as something that **"cannot be signed off until open item 19 tokens
exist"**. The tokens exist (`FE-D30` checked in the visual reference; task 002/003 transcribed
them), so this task turns that sign-off into a check that passes or fails — `npm run verify:palette`,
a required CI job where **a failure is a build failure, not a warning** (task spec:
`docs/plans/frontend/implementation.md` §FE-097; refs design §8.1, §18, `dynamic-metrics` §18,
conflict C2; deps 002, 003).

Writing the check found **six measured WCAG 2.2 AA failures in the palette that shipped through
phases 0–5**. Per the developer's decision, the task fixes them rather than waiving them, so the
gate is green on merge and catches regressions from day one. The repair is recorded as **`FE-D39`**
(`I3` — new ID, nothing edited).

| File | Summary |
|---|---|
| `frontend/src/shared/theme/contrast.ts` | **New.** Pure, dependency-free colour maths: WCAG 2.2 relative luminance and contrast ratio, alpha compositing, Machado et al. (2009) dichromacy simulation at severity 1.0, CIEDE2000, and `worstContrast` over a surface set. Used by the *check*, never by the paint. |
| `frontend/src/shared/theme/contrast.test.ts` | **New.** 17 tests of the *instrument*, against independently known answers — black-on-white is exactly 21:1, `#767676` on white is the canonical 4.5:1 AA boundary grey, `#595959` the 7:1 AAA one, greys survive CVD simulation unchanged, red/green collapse under protan/deutan but not tritan. |
| `frontend/src/shared/theme/palette.contrast.test.ts` | **New.** The gate: 152 assertions across both themes. |
| `frontend/src/shared/theme/palette.ts` | Categorical chart palette and tone scale become **per-mode and contrast-derived**; new `chart.cvdSafeCount` and `palette.focusRing`. |
| `frontend/src/shared/theme/createSmTheme.ts` | Focus-visible ring becomes **two concentric bands**. |
| `frontend/src/shared/theme/createSmTheme.test.ts` | Focus-ring test updated to the two-band shape (both modes). |
| `frontend/scripts/validate-palette.mjs` | **New.** `npm run verify:palette` — runs the gate *and* the instrument's own tests. |
| `frontend/package.json` | `"verify:palette": "node scripts/validate-palette.mjs"`. |
| `frontend/src/test/setup.ts` | `configure({ asyncUtilTimeout: 5_000 })` — a flake fix this task surfaced; see below. |
| `.github/workflows/frontend.yml` | `verify:palette` added to the `gate` matrix with a rationale block. |
| `docs/design/frontend/decisions.md` | `FE-D39` recorded, indexed, changelogged. |

## What the check found

Every number below is measured, not estimated:

| Defect | Measured | Required |
|---|---|---|
| `accent.sky` as a light-theme series colour | **1.58:1** | 3:1 (SC 1.4.11) |
| `accent.orange`, light theme | **1.83:1** | 3:1 |
| `accent.indigo` as a dark-theme series colour | **1.87:1** | 3:1 |
| `accent.purple`, dark theme | **2.13:1** | 3:1 |
| Focus ring — brand green `#00C895` on white | **2.17:1** | 3:1 |
| `tone.warn` chip label on its own fill | **2.12:1** | 4.5:1 (SC 1.4.3) |
| `tone.idle` / `neutral` / `info` / `ok` / `err` chip labels | **2.58–3.30:1** | 4.5:1 |
| `indigo`/`purple` under deuteranopia | **ΔE2000 2.26** | separable |

None was a near miss. Three root causes:

1. **One fixed ten-colour list cannot serve two themes.** The light surfaces are near-white
   (`#F7F8F9` / `#FFFFFF`), the dark ones near-navy (`#03223B` / `#0a2c47`). That is arithmetic, not
   taste — so `chart.categorical` is now a function of `palette.mode`.
2. **`tone.main` has two jobs.** `Chip` paints it as label text on a tinted fill; `StatStrip`,
   `LogViewer` and `SectionNav` paint it as bare text on the page. One value cannot clear 4.5:1 for
   both in both modes. The old table also shipped the *light* tints into dark mode, so a dark-theme
   chip was a near-white pill on a navy page — legible only by accident.
3. **No single token clears 3:1 on both a white and a navy page.** Brand green is 2.17:1 on white;
   primary marine is 1.93:1 on dark paper. A one-colour focus ring cannot be conformant in both.

## How each was fixed

**Contrast-derived values, hue and saturation held.** Each failing colour has its *lightness* moved
until it clears the bar against the surfaces of its own mode. No new hue is introduced and every
value traces to a token in `tokens.ts`, so **open item 19 and `FE-D5`'s block on brand paint are
untouched** — what changed is legibility, which is not the architect group's question. Each literal
names its source token and resulting ratio inline.

**The values are baked, not clamped at runtime.** Deriving them inside `createPalette` would be more
elegant and would make the gate **vacuous** — it would assert a property the code had just forced to
be true. Baked values can regress; that is what makes the gate worth running.

**A bounded colour-blindness claim.** Ten hues from a fourteen-token accent set *cannot* all be
CVD-safe: `indigo`/`purple` and `purple`/`magenta` sit at ΔE2000 ≈ 2.3 under deuteranopia in any
ordering. Rather than assert a guarantee the palette cannot keep, the list is ordered maximin and
publishes `chart.cvdSafeCount = 6` — the first six hold **ΔE2000 ≥ 8.78** across protan, deutan and
tritan in *both* themes. Past six, the legend and data table carry it. The gate fails if the number
is raised without hues to back it, and equally if it is lowered to make a bad palette pass.

**A two-band focus ring.** Green 0–2px out, navy 2–4px out. Green stays nearest the control so the
ring still reads as brand (reference §B.2); navy carries the light theme at **15.24:1**, green
carries the dark theme at **7.48:1**, and the bands separate from each other at 7.48:1 in both. A
transparent `outline` sits underneath as the forced-colors-mode fallback, since that mode drops
box-shadows. Both bands are box-shadows because an `outline` at `outlineOffset: 2px` would paint
*over* the middle of the shadow rather than nesting inside it.

## What is asserted, and on what authority

| Assertion | Threshold | Source |
|---|---|---|
| Chart series colours, every surface, both themes | ≥ 3:1 | WCAG 2.2 SC 1.4.11 |
| `text.primary`, `text.secondary`, chart axis/legend/tooltip labels, nav text | ≥ 4.5:1 | SC 1.4.3 (AA) |
| Tone chip label on fill; tone as bare page text; chip border | ≥ 4.5:1 / ≥ 3:1 | SC 1.4.3 / 1.4.11 |
| Focus ring — at least one band vs page, and band-vs-band | ≥ 3:1 | SC 1.4.11 |
| Leading categorical hues under three dichromacies | ΔE2000 ≥ 8 | **project threshold** |
| Every vocabulary entry has a label and an icon | — | SC 1.4.1 |

The ΔE threshold is explicitly **not** presented as a standard — WCAG says nothing about categorical
hue separation. It is calibrated against what the DS accent set delivers (≈8.8 across the first six,
collapsing to ≈2.3 by the tenth) and against the exact simulation pipeline in `contrast.ts`; the two
move together or not at all.

**`text.disabled` is deliberately exempt.** SC 1.4.3 excludes "text … that is part of an inactive
user interface component", and forcing it to 4.5:1 would make disabled text indistinguishable from
enabled. It is asserted to be *dimmer* than secondary text instead, so the exemption cannot cover an
accidental regression the other way.

## Verification

| Check | Result |
|---|---|
| `npm run verify:palette` | **169 passed** (152 gate + 17 instrument) |
| `npm run lint` | clean |
| `npm run typecheck` | clean |
| `npm test` | **2145 passed**, exit 0 (after the `waitFor` fix below) |
| `npm run test:a11y -- --all` | 351 passed, 101 files |
| `npm run build` | passes; `assert-echarts-chunk` OK |
| `npm run verify:bundle` | 134.5 KiB initial vs 160 KiB ceiling (was 134.2 — +0.3 KiB of palette tables) |
| `npm run check:forbidden-deps` · `lint:fixtures:assert` | clean |
| `npm run verify:reflow` | **red — pre-existing, not this task**; see below |

**The gate was proven to fail, not just to pass.** Three deliberate regressions were injected and
each produced a red build with a legible message:

| Probe | Result |
|---|---|
| Revert one light-theme series colour to its raw token (`#FCA829`, 1.95:1) | `FAIL … series 1 (#FCA829) clears 3:1 on every surface` |
| Raise `cvdSafeCount` from 6 to 8 | `FAIL … series 5 (#CB1AA5) and 6 (#3A4880) are ΔE2000 7.09 apart under protan` |
| Make the two focus bands the same colour | `FAIL … separates its two bands from each other` (both themes) |

Each probe was reverted and the gate re-run green.

## One flake fixed, one pre-existing failure found

**`waitFor`'s timeout was never raised for the saturated pool.** Adding 169 tests made the suite
heavier, and `InstancesPage > removes each selected instance` began failing intermittently — a
dialog asserted `toBeNull()` while its MUI exit transition was still running. `vitest.config.ts`
already documents this class of problem and raises `testTimeout` to 20 s for it, but Testing
Library's `waitFor` keeps its **own** 1 s timeout, which that reasoning never reached. Fixed once in
`src/test/setup.ts` rather than at the one call site, since every `waitFor` in the suite was exposed.
Confirmed: the test passes in isolation on both trees, and the full suite is green with the fix
(2145/2145, exit 0).

**`npm run verify:reflow` was red on `dev/frontend`, and already was before this task.** Two cases
failed — `targets-stub` at 320 px and 375 px. **Verified pre-existing**: the same two cases failed
identically with this task's changes stashed, so it was not caused by FE-097 (colour changes cannot
move layout). Both causes came from FE-095 and are **now fixed** in a follow-up commit (`3189fb8`):

- the four-column target table scrolls sideways at the reflow floor legitimately, but never declared
  `data-reflow-exception="data-table"` — so `FE-110`'s gate read the scroller as an accident and the
  `InfoTip` spans inside it as focusables clipped with no way to reach them;
- the two model-field columns carried a hard `minWidth: 300`, but the content box at 320 px is
  296 px, so each overflowed by 4 px and pushed `main` to `scrollWidth` 328. Now
  `min(300px, 100%)` — the intent is "two columns while there is room", and a bare floor turns that
  into an overflow rather than a wrap.

Diagnosed by measuring at 320 px with a standalone Playwright pass, not by guessing.
`verify:reflow` is now **137 passed, exit 0**.

## Deviations from the plan

The task brief names two files to create; this delivers **four**. `contrast.ts` and
`contrast.test.ts` were split out because a gate whose instrument is unverified proves nothing — a
`contrastRatio` that always returned 21 would let FE-097 pass forever. `scripts/validate-palette.mjs`
runs both, so the CI job cannot check the palette without also checking the maths.

The brief did not anticipate that the palette would *fail* the check. Fixing it (rather than
waiving) was the developer's call, taken before implementation; it expands the task from "add a
check" to "add a check and make it pass", and is why `FE-D39` exists.

## External facts verified (`I2`)

- **Machado, Oliveira & Fernandes (2009) CVD matrices**, severity 1.0 — all 27 coefficients
  transcribed from and re-checked against the authors' own page on 2026-09-02:
  <https://www.inf.ufrgs.br/~oliveira/pubs_files/CVD_Simulation/CVD_Simulation.html>.
  ⚠️ That page publishes the matrices but **does not state the colour space they apply in**. The
  implementation multiplies in linear-light sRGB (what common implementations do) and says so in a
  comment rather than claiming the source specifies it. This is noted because the ΔE threshold is
  calibrated against that choice.
- WCAG 2.2 relative-luminance and contrast-ratio definitions, and the SC 1.4.3 inactive-component
  exemption, are cited to <https://www.w3.org/TR/WCAG22/>.

## Follow-ups (not done here, deliberately)

- **`chart.cvdSafeCount` is published but nothing consumes it yet.** A chart with more than six
  series is not currently warned about, and `ChartRenderer` does not vary series symbol shape as a
  second channel. That is chart work, not palette work — worth raising against FE-103 or a new task.
- **`docs/design/frontend/design.md` §8.1 and §18 still describe a single categorical list and
  state contrast as unsigned-off.** Both are now stale. FE-104 is the plan's documentation-closure
  task and is the right place to sync them; noting it here so it is not lost.

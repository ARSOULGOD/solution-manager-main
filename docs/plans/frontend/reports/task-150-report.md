# FE-150 · A hidden field produces no validations — Report

Second task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §8).
Closes the *frontend half* of open item 36's `project()` divergence **for this one rule**.

---

## 1 · What was wrong

The two `project()` implementations disagreed on whether a field the operator cannot see should be validated:

| | Behaviour |
|---|---|
| `contracts/view-models/src/project.ts` (canonical) | `validations: visible ? … : []` |
| `frontend/contracts/view-model/project.ts` (what the app runs) | validated **regardless of `visible`** |

Latent until now, because no shipped package paired `required` with a `when:` branch. A connector-type
discriminator does that by construction: `sftp_settings.ip` is `required` and hidden whenever the connector is not
SFTP. Under the frontend engine, an operator on **NWFile** would have a submit-blocking `REQUIRED` on a control
they cannot see — a form that cannot be saved and cannot say why.

The failure was reproduced before it was fixed: the new fixture's diff showed `sftp_settings.ip` with
`visible: false` **and** `validations: [{ code: "REQUIRED", … }]`, nothing else different.

## 2 · What changed

| Change | File |
|---|---|
| `validations` is `[]` for a non-visible field | `frontend/contracts/view-model/project.ts` (one expression) |
| Fixture: hidden required ⇒ no validation | `frontend/contracts/fixtures/view-model/hidden-required-yields-no-validation.json` |
| Fixture: same field, branch active ⇒ `REQUIRED` fires | `…/hidden-required-blocks-once-branch-activates.json` |

**Both fixtures were hand-authored from the invariant, not generated from the engine's output.** Generating
`expected` from the code under test would have enshrined the buggy behaviour and the test could never have
failed. Each also carries a *visible* required-and-empty field (`general.adapter_id`) that must still report
`REQUIRED` — a guard against over-fixing, i.e. against "fixing" this by suppressing validation everywhere.

## 3 · What deliberately did not change

- **The canonical engine, `contracts/view-models/`, and `contracts/view-models/src/cases.ts`.** No case was added
  there: the canonical engine already behaves correctly, and a new case would regenerate committed fixtures and
  change what the Java implementation must match (`run-checks.sh` step 8) — a single-writer contract change that
  is not this task's to make.
- **The rest of the divergence.** The two engines still differ elsewhere (flat vs nested `label`; `ViewModel`
  shape; selection shape). Only this one rule converged. Open item 36 stays open.
- **Visible-field validation.** Untouched; the flip-side fixture and the pre-existing `required-blocks-when-empty`
  / `required-clears-when-filled` fixtures pin it.

## 4 · Evidence

| Check | Result |
|---|---|
| Fixture suite before the fix | 23 pass, **1 fail** — the target, for the stated reason |
| Fixture suite after | **24 / 24** |
| Canonical engine, probed by execution on the identical scenario | NWFILE: `visible=false validations=[]` · SFTP: `visible=true validations=["REQUIRED"]` — **identical to the frontend engine** |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |
| `validate-package.py` over the nine packages | 9 / 9 PASS (unchanged) |
| Full frontend suite | 340 / 340 files, 2416 / 2416 tests |

The canonical-engine probe was a throwaway script run against `contracts/view-models/dist/` — not committed. It
exists to turn spec §8's "the canonical engine does X" from a reading of the source into an observation. Note
`dist/` is a build artifact and could lag `src/`; the source, read directly, says the same.

## 5 · Deviations from the plan

One. The plan said to generate `expected` "by running the suite once and copying the actual output — then review
it by hand". That would have made the first run the *passing* baseline of the bug. It was replaced with
hand-authored expectations, which is what let the test fail for the right reason. Plan Task 150 Step 1 is updated.

## 6 · Full-suite result

**340 / 340** files, **2416 / 2416** tests (the 2414 baseline from `FE-149` plus the two new fixtures), exit 0.

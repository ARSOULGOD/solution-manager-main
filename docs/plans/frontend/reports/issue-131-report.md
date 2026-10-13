# Issue #131 · Vitest suite: intermittent timeouts under full parallel load — Report

## What was wrong

Two independent defects, and the flake needed both fixed. The issue's own text warned that
*"repeatedly raising individual numbers doesn't stay ahead of the suite's growth"* — that turned out
to be exactly right, and neither fix here is a raised number.

### Defect 1 — the worker pool was oversubscribed

Vitest defaults `maxWorkers` to the CPU count. A jsdom worker running an axe scan is not one core's
worth of work: it is that, plus V8's GC threads and jsdom's parsing. Ten workers on ten cores
therefore **thrash rather than saturate**, and every test's wall-clock time inflates roughly 4×.

Measured on the same tree, both runs `--reporter=verbose` (2026-09-03, 10 logical cores):

| Test | idle | 10 workers | 5 workers |
|---|---:|---:|---:|
| InstancesPage · grid, light | 3.93 s | 14.96 s | **7.74 s** |
| InstancesPage · grid, dark | 5.89 s | 18.45 s | **15.13 s** |
| InstancesPage · card, light | 3.57 s | 15.29 s | **8.23 s** |
| InstancesPage · card, dark | 3.51 s | 12.97 s | **6.76 s** |
| Gallery · assembled, light | 6.94 s | 28.59 s | **20.58 s** |
| Gallery · assembled, dark | 11.47 s | 48.64 s | **25.41 s** |
| **whole suite, wall clock** | — | **299.19 s** | **299.26 s** |

**Per-test latency roughly halves and the suite takes exactly as long.** That is the finding the fix
rests on: the total work is unchanged, so the pool was purely fighting itself. Capping it is not a
throughput trade — it is free.

Set as `maxWorkers: '50%'` rather than a literal, so a 4-core CI runner gets 2 workers rather than
the 5 that happen to suit this machine. The ratio is what matters, and CI runners are the weaker
case the issue was most worried about.

### Defect 2 — a stale downward timeout override

`InstancesPage.a11y.test.tsx` carried `15000` as a per-test timeout on its four most expensive
cases. It was added on **2026-08-26** (`884b249`), when Vitest's default was 5000 ms — so at the time
it was a *raise*. **FE-098 on 2026-09-01** (`43ab5df`) set `testTimeout: 20_000` on the `a11y`
project for exactly this reason, which silently turned the override into a **lowering**.

Confirmed from history, not inferred:

```
$ git merge-base --is-ancestor 884b249 43ab5df && echo "override predates the project default"
override predates the project default
```

It was **the only downward override in the suite**, and it sat on the four slowest tests. Against
its 15 s budget the measured loaded times were 14.96 s, 18.45 s, 15.29 s and 12.97 s — one passed
with **40 ms** to spare and two did not pass at all. Removed; those tests now inherit the project's
20 s.

## The hypothesis I got wrong, and the control that caught it

My first hypothesis was that `color-contrast` dominated the axe cost — plausible, because it needs
`getComputedStyle` per node and jsdom has no layout engine, so it should be both expensive and
useless there. The first measurement agreed emphatically:

```
FULL RULE SET      2089ms
NO color-contrast   379ms   (82% faster)
color-contrast results in jsdom: violations=0 incomplete=0
```

82% cheaper, and the rule returns nothing. I nearly shipped that with a confident rationale.

It was an artifact of measuring the *second* axe call in the file. Reversing the order and adding a
control:

```
[reversed] no-contrast FIRST   2060ms
[reversed] full        SECOND   389ms
[control]  full FIRST          1854ms
[control]  full SECOND          339ms
```

The first `axe()` call in a worker costs ~2 s and every later one ~350 ms, **regardless of rules**.
That is axe-core's one-time initialisation, not `color-contrast`. The rule costs nothing, and
disabling it would have removed real coverage for no gain. Recorded here because the wrong version
of this fix is a very attractive-looking commit.

## Verification

Three consecutive full `npm test` runs on the fixed tree — the only evidence that matters for a
flake, since any single green run was always achievable:

```
run 1: exit=0  Tests 2172 passed (2172)   Duration 202.01s
run 2: exit=0  Tests 2172 passed (2172)   Duration 200.92s
run 3: exit=0  Tests 2172 passed (2172)   Duration 211.08s
```

**Zero failures, three times.** For contrast, every full run earlier in this session — on this tree
and on `main` with the work stashed — failed between 2 and 7 tests, and never the same set twice.

Wall clock is unchanged: ~200–211 s here against a ~215 s baseline before any of this. The 299 s
figures in the table above are both `--reporter=verbose` runs, which is why they are higher; they
are comparable to each other, which is what that comparison needed.

Per-test costs after the fix (a11y project, the two heaviest files):

| Test | before (10 workers) | after |
|---|---:|---:|
| InstancesPage · grid, light | 14.96 s | **3.12 s** |
| InstancesPage · grid, dark | 18.45 s | **4.13 s** |
| InstancesPage · card, light | 15.29 s | **2.78 s** |
| InstancesPage · card, dark | 12.97 s | **2.77 s** |
| Gallery · assembled, light | 28.59 s | **5.48 s** |
| Gallery · assembled, dark | 48.64 s | **8.26 s** |

Against a 20 s budget, the worst case now has ~5× margin rather than being 3.45 s over.

## What this does not fix

- **The per-test budgets are still absolute numbers.** They now have real margin (grid dark: ~15 s
  against 20 s at the old pool size, ~8 s at the new one), but a suite that keeps growing will
  eventually need this looked at again. The `maxWorkers` cap buys headroom by removing contention,
  which is a different and better lever than raising the number — it does not make the number
  self-adjusting.
- **CI is not measured here.** GitHub Actions runners are slower and have fewer cores; `'50%'` keeps
  the ratio but not the absolute times. The first CI run after this lands is the real test, and if
  it still flakes the next lever is `poolOptions` per-project rather than another timeout bump.
- **`FE-103`'s render budgets already learned this lesson independently** — see `FE-D44`, where an
  absolute jsdom millisecond ceiling was replaced by a ratio for the same underlying reason: a
  wall-clock number in a contended jsdom worker measures the machine, not the code.

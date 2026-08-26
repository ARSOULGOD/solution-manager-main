# Checklist — definition of done

Never mark a task complete without proving it works. Evidence before assertions.

## Proven, not assumed

- [ ] Tests written and **passing** — output seen, not inferred
- [ ] Lint and build clean
- [ ] Behaviour demonstrated: for a fix, the failure reproduces before and not after
- [ ] Logs or output checked for new warnings and errors
- [ ] "Would a staff engineer approve this?" — and if a fix felt hacky, the elegant version was at least considered

## Correctness gates

- [ ] All SQL uses **bind parameters** — no string interpolation (`I5`)
- [ ] Tenant scope applied through the **central data-access module**, never from client input (`I5`)
- [ ] No CP↔DP import crossing, no cross-schema join or FK (`I7`)
- [ ] No secret value in config storage, history, diffs, exports, or logs (`I6`)
- [ ] Timestamps stored and compared in **UTC**; ordering never relies on wall-clock (`B/D15`)
- [ ] Anything touching an MI honours `HC1` — configuration only, no MI development

## Bookkeeping

- [ ] Working checklist updated; review section filled in
- [ ] Lessons captured after any user correction — **repo-local vs cross-cutting** chosen deliberately (`C3`)
- [ ] Design or decision updated if implementation revealed the design was wrong. **Do not leave code and design
      disagreeing** — and do not edit a decision in place; supersede it (`I3`)

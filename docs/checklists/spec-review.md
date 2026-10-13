# Checklist — spec / design review

Run **both** passes. Self-review catches contradictions; only an independent adversarial pass catches wrong facts
(lesson `L2`).

## Pass 1 — self-review

- [ ] No `TBD`, `TODO`, or placeholder text
- [ ] No section contradicts another; the architecture matches the feature descriptions
- [ ] Every requirement has one unambiguous reading — if two are possible, pick one and say so
- [ ] Scope is one implementable unit, not several
- [ ] Every decision has an **ID with the right prefix**, a status, and a rationale (`L4`)
- [ ] Decisions that supersede something say so **in both entries**
- [ ] Capability **limits** are stated, not implied — what this design cannot do
- [ ] Anything undesigned is explicitly **routed** (to a sub-spec, or deferred) — never silently absent

## Pass 2 — independent adversarial review

- [ ] Reviewer briefed to **find what is wrong**, and told a "looks good" verdict is a failed review
- [ ] Reviewer instructed to **verify external facts with WebFetch**, not from memory (`L1`)
- [ ] Reviewer has the predecessor documents, so supersession claims can be checked line by line
- [ ] Findings triaged with an explicit disposition each: fix now / route to sub-spec / accept as a limit

## Project-specific traps

- [ ] **`HC1`** — does anything here require MI development? Build-plugin changes and new endpoints count
- [ ] Every MI dependency is either **SM-controlled** or has a **defined degradation**
- [ ] Framework defaults, licence terms and database semantics **verified**, with the source named (`L1`)
- [ ] For layered config: **where does our write land in the precedence order** (`L3`)
- [ ] Claims of "unchanged" against a predecessor decision **checked against the actual text**
- [ ] `PROPOSED` vs `LOCKED` status is correct, and conflicts are **flagged rather than resolved**
- [ ] Requirements traced in `../requirements/traceability.md`
- [ ] Secrets: nothing lands in config storage, history, diffs, exports or logs (`I6`)

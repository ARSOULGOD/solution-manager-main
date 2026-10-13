# Checklist — implementation plan review

Before execution starts. The plan lives at `docs/plans/<component>/`; the template and rules are in
[`../design/common/conventions/plan-template.md`](../design/common/conventions/plan-template.md).

- [ ] The component's **design exists** and this plan implements it — no architecture invented in the plan
- [ ] Requirement IDs and decision IDs listed, and none of them is a `PROPOSED` decision that could still change
- [ ] **Prerequisites** section names every open item that must close first
- [ ] Scope says what is **out**, not just what is in
- [ ] Every task has a **verification** — how we prove it works, not "implement X"
- [ ] Tasks are independently completable; no task silently depends on a later one
- [ ] Contract changes identified, and the **central contract PR is open or merged** — a plan must not assume an
      unpublished contract (`../design/common/contracts/README.md`)
- [ ] Migrations identified, and their behaviour on a **large partitioned table** considered
- [ ] Execution route chosen: **new session** if >5 tasks, >3 files, or >1 component
- [ ] Rollback thought through for anything touching production data or customer hosts

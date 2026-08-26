# Checklist — standing up a module

For `backend/`, `frontend/`, `go-agent/`, `runtime/`. **Monorepo** — these are modules in
one tree, not separate repos.

## Before creating it

- [ ] The component's **design is written** at `docs/design/<component>/design.md`
- [ ] Contracts are consumed **in-tree by module/path dependency** — no publishing pipeline needed while this is a
      monorepo. *(Publishing + read-only consumption must be settled before any future repo split — not now.)*
- [ ] Owner named

## Repo setup

- [ ] Module-scoped `CLAUDE.md` **only if** the module needs notes beyond the root one (build commands, local
      gotchas). The two mandated blocks live in the **root** `CLAUDE.md` — do not duplicate them per module.
- [ ] CI runs the docs checks: **markdown links resolve** and **decision IDs are complete** (the convention blocks are single-homed in the root `CLAUDE.md`; there is no sync script to run)
- [ ] Path-scoped CI so this module's tests run independently of the others
- [ ] `docs/design/`, `docs/plans/` and `docs/tasks/` created
- [ ] **`docs/tasks/todo.md`** and **`docs/tasks/lessons.md`** both present and non-empty — `lessons.md` seeded
      with the pointer to `docs/design/common/lessons.md` so the repo-local vs cross-cutting choice is
      obvious from day one
- [ ] `design/<component>/design.md` exists and `plans/<component>/` holds the approved plan
- [ ] Language-appropriate ignores added to the root `.gitignore`
- [ ] Build, test and lint commands documented in `CLAUDE.md` and working from a clean clone

## Boundary enforcement

- [ ] **Backend:** ArchUnit (or module) rules enforcing the CP↔DP import ban and `platform-core` isolation. **A
      monorepo is what makes this enforceable at build time** — the main reason to stay in one tree. Do not let it
      degrade into review discipline.
- [ ] Contracts referenced by module dependency, never copy-pasted between modules
- [ ] CI **fails if any contract or generated stub is modified locally** — contracts are read-only here
- [ ] Generated code either not committed, or regenerated in CI with a diff check

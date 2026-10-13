// VIOLATION — the logger must ship nothing (task 102, design §20, `REQ-003`).
// This directory mirrors `src/shared/observability/**` so the scoped rule
// applies here, the same way `lint-fixtures/shared/` mirrors `src/shared/`.
// The realistic mistake is a `fetch` added to logger.ts "just to get errors
// off the box" — which is exactly what an air-gapped install cannot have.
export function ship(line: string) {
  void fetch('/telemetry', { method: 'POST', body: line });
}

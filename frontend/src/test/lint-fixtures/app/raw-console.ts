// VIOLATION — a raw console call outside `shared/observability` (task 102).
// `console.error(err)` hands the browser a live reference to the whole error
// — `cause` chain, response body and all — which is the leak `logger`'s
// primitives-only field type exists to prevent.
export function report(error: unknown) {
  console.error('something went wrong', error);
}

/**
 * The `D1` 🔒 custom-UI escape hatch, expressed as an SM-built named-screen
 * registry rather than package-supplied code (task 063, `UI-D21` —
 * **flagged conflict with `LOCKED` `D1`, not resolved**; `implementation.md`
 * row C8: "Ship the generic renderer + composer; create the named-screen
 * registry and leave it empty. No package-supplied code path in v1.").
 *
 * `D1` is `LOCKED` and explicitly includes a per-solution custom-UI escape
 * hatch; `UI-D21` proposes narrowing that to this registry instead, but the
 * architect group has not ratified the narrowing (`I4`). Until that
 * happens this stays empty and inert by construction: `resolveNamedScreen`
 * always returns `undefined`, and nothing in this module ever imports,
 * fetches or executes code a package supplies — there is no loader here to
 * point at one. The composer (`Composer/`) covers every v1 multi-component
 * case (File Mesh: source + sink) without needing an entry.
 */
export type NamedScreenComponent = never;

export const NAMED_SCREEN_REGISTRY: Readonly<Record<string, NamedScreenComponent>> = Object.freeze(
  {},
);

export function resolveNamedScreen(key: string): NamedScreenComponent | undefined {
  return NAMED_SCREEN_REGISTRY[key];
}

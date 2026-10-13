/**
 * The auth seam (task 030, `FE-D8`, design §15). Every feature depends on
 * this interface, never on `localSessionAdapter` or `devMockAdapter`
 * directly — swapping v1 local session for OIDC/Keycloak later is an
 * implementation change behind this interface, not a feature rewrite.
 */

export type IdentitySource = 'local' | 'idp';

/**
 * `organizationNames` is DISPLAY ONLY (`I5`, `D20`, `FE-D53`). The names the
 * server returned for the caller's visible top-level organizations, for the
 * app-bar chip — never authority the client derives anything from or sends
 * back as a request parameter. The real `Identity` wire response carries no
 * flat `organizationId` (console.yaml's `Identity` schema has only
 * `organizations[]`, the full visible hierarchy) — see the 2026-10-08
 * real-end-to-end finding this type was rebuilt from
 * (`docs/plans/integration/reports/real-e2e-2026-10-08.md`).
 */
export interface Identity {
  username: string;
  source: IdentitySource;
  /**
   * When true, the backend fails every other authenticated call closed
   * (428 `MUST_ROTATE`) until the credential is rotated. `RequireAuth`
   * renders a blocking `RotateCredentialDialog` in place of the app shell
   * whenever this is true.
   */
  mustRotate: boolean;
  /**
   * Derived, not server-sent: true when any granted permission is held at
   * an unscoped (`{}`) grant — the signal that distinguishes Administrator
   * (bootstrap/emergency only, OR-12: unscoped wherever it reaches) from
   * every scope-bound role. The real wire response has no `superAdmin`
   * field and no role-name field to read this from directly.
   */
  superAdmin: boolean;
  organizationNames: readonly string[];
  /** Permission ids the server granted this identity (feeds task 032's `PermissionApi`). */
  permissions: readonly string[];
}

export interface Credentials {
  username: string;
  password: string;
}

export type SessionExpiredListener = () => void;
/** Returned by `onSessionExpired` — call it to unsubscribe. */
export type Unsubscribe = () => void;

export interface AuthAdapter {
  getIdentity(): Promise<Identity>;
  login(credentials: Credentials): Promise<Identity>;
  logout(): Promise<void>;
  /** Forced credential rotation (`identity.mustRotate` / 428 `MUST_ROTATE`). */
  rotateCredential(current: string, next: string): Promise<void>;
  /** Fires when the server no longer recognises the session (a 401 on any request). */
  onSessionExpired(callback: SessionExpiredListener): Unsubscribe;
  /**
   * Present only for an adapter that holds an in-memory fallback token
   * (design §15 — used only when the gateway cannot set the session
   * cookie). The v1 cookie-session path returns `undefined`.
   */
  getAccessToken?(): string | undefined;
}

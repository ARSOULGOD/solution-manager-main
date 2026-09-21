/**
 * The auth seam (task 030, `FE-D8`, design §15). Every feature depends on
 * this interface, never on `localSessionAdapter` or `devMockAdapter`
 * directly — swapping v1 local session for OIDC/Keycloak later is an
 * implementation change behind this interface, not a feature rewrite.
 */

/**
 * `organization` is DISPLAY ONLY (`I5`, `D20`, `FE-D53`). It is a string the server
 * returned to describe the caller's organization for the app-bar chip — never an
 * id the client sends back as authority. There is deliberately no
 * `organizationId` field here to make "send it as a parameter" impossible to do
 * by accident.
 */
export interface Identity {
  name: string;
  email: string;
  role: string;
  organization: string;
  superAdmin: boolean;
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
  /** Fires when the server no longer recognises the session (a 401 on any request). */
  onSessionExpired(callback: SessionExpiredListener): Unsubscribe;
  /**
   * Present only for an adapter that holds an in-memory fallback token
   * (design §15 — used only when the gateway cannot set the session
   * cookie). The v1 cookie-session path returns `undefined`.
   */
  getAccessToken?(): string | undefined;
}

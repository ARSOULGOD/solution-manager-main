/**
 * v1's real `AuthAdapter` (task 030, `FE-D8`, `FE-D14`, design §15).
 * Session lives in an httpOnly `SameSite=strict` cookie the GATEWAY sets —
 * this adapter never reads, writes or stores it. `credentials:
 * 'same-origin'` (task 026's `request()`) is what makes the cookie travel.
 *
 * // CONTRACT: TBD — the login endpoint, its request/response shape, and
 * // the CSRF mechanism (§16 Q3) are unspecified (FE-030 Ready: FIXTURE).
 * // The routes below are this adapter's OWN naming, confined here so a
 * // real contract replaces only this file.
 */
import { onUnauthorized, type PlaneClient } from '@shared/api';

import type {
  AuthAdapter,
  Credentials,
  Identity,
  SessionExpiredListener,
  Unsubscribe,
} from './AuthAdapter';

// CONTRACT: TBD — an in-memory fallback token, used ONLY if a login
// response ever carries one (design §15: "fallback: in-memory access token
// only if the API cannot set cookies"). Never written to localStorage
// (`FE-D14`) and never survives a page reload — that is the point of
// keeping it in a closure rather than any persisted store.
interface LoginResponse extends Identity {
  accessToken?: string;
}

export function createLocalSessionAdapter(cpClient: PlaneClient): AuthAdapter {
  let inMemoryAccessToken: string | undefined;

  return {
    async getIdentity(): Promise<Identity> {
      return cpClient.get<Identity>('/auth/identity');
    },

    async login(credentials: Credentials): Promise<Identity> {
      const response = await cpClient.post<LoginResponse>('/auth/login', credentials);
      inMemoryAccessToken = response.accessToken;
      return response;
    },

    async logout(): Promise<void> {
      inMemoryAccessToken = undefined;
      await cpClient.post('/auth/logout');
    },

    onSessionExpired(callback: SessionExpiredListener): Unsubscribe {
      return onUnauthorized(callback);
    },

    getAccessToken(): string | undefined {
      return inMemoryAccessToken;
    },
  };
}

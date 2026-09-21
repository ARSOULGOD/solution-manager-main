// VIOLATION — a secret-shaped log field (task 102, `I6`, `FE-D13`).
// `logger`'s field type is primitives-only, so `{ password }` COMPILES; it is
// the field NAME that makes it a leak, and only lint can see that.
import { logger } from '@shared/observability';

export function signIn(username: string, password: string) {
  logger.error('login.failed', { username, password });
}

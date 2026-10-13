/**
 * Public barrel for `shared/observability` (task 102, design §20).
 *
 * One export that application code uses — `logger`. The rest is exported for
 * this module's own tests and for the lint rule's error message to point at.
 * There is deliberately no second entry point: `eslint.config.js` restricts
 * raw `console` everywhere else, so this barrel is the whole surface.
 */
export {
  formatLogLine,
  logger,
  redact,
  REDACTED,
  type LogFields,
  type Logger,
  type LogLevel,
  type LogValue,
} from './logger';

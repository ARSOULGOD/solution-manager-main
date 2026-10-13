import { defineConfig } from 'vitest/config';

// solclientjs writes several INFO lines per connection to stdout; with ~150 connections in the
// suite they bury the leg records. Drop them (they are library chatter, not evidence); the
// per-leg records are the `LEG {...}` lines here and evidence/legs.jsonl.
export default defineConfig({
  test: {
    include: ['tests/**/*.test.mjs'],
    onConsoleLog(log) {
      if (/solclientjs|websocket browserSupportBinaryCheck|ExperimentalWarning|localstorage-file/i.test(log)) return false;
      return undefined;
    },
  },
});

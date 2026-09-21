/**
 * Build-time identity, read from `import.meta.env` (design §20, task 036's
 * support menu). Populated by CI via `VITE_APP_VERSION`/`VITE_APP_COMMIT`
 * at build time; a local `npm run dev`/`vite build` without them falls back
 * to labels that are visibly not a real release, never a blank field.
 */

export interface BuildInfo {
  version: string;
  commit: string;
}

export function getBuildInfo(): BuildInfo {
  return {
    version: import.meta.env.VITE_APP_VERSION ?? 'dev',
    commit: import.meta.env.VITE_APP_COMMIT ?? 'unknown',
  };
}

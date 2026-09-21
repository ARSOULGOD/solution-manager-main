/**
 * Deploy-time configuration, fetched before first render (design §13, §21).
 * `config.json` carries a public API prefix, the idle-lock timeout and a
 * release channel label — never a plane hostname (`FE-D19`) and never a
 * secret (`FE-D14`).
 */

export interface RuntimeConfig {
  apiBasePath: string;
  idleTimeoutMinutes: number;
  channel: string;
}

const DEFAULT_IDLE_TIMEOUT_MINUTES = 30;

export class RuntimeConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RuntimeConfigError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

// A parse failure never falls back to a silent default — every field but
// `idleTimeoutMinutes` (defaulted to 30 per design §15) must be present and
// well-typed, or boot fails visibly. Unknown extra fields are ignored.
export function parseRuntimeConfig(input: unknown): RuntimeConfig {
  if (!isRecord(input)) {
    throw new RuntimeConfigError('Runtime configuration is not a JSON object.');
  }

  const { apiBasePath, idleTimeoutMinutes, channel } = input;

  if (typeof apiBasePath !== 'string' || apiBasePath.length === 0) {
    throw new RuntimeConfigError('Runtime configuration is missing a string "apiBasePath".');
  }
  if (typeof channel !== 'string' || channel.length === 0) {
    throw new RuntimeConfigError('Runtime configuration is missing a string "channel".');
  }
  if (idleTimeoutMinutes !== undefined && typeof idleTimeoutMinutes !== 'number') {
    throw new RuntimeConfigError('Runtime configuration "idleTimeoutMinutes" must be a number.');
  }

  return {
    apiBasePath,
    idleTimeoutMinutes: idleTimeoutMinutes ?? DEFAULT_IDLE_TIMEOUT_MINUTES,
    channel,
  };
}

export async function loadConfig(url = '/config.json'): Promise<RuntimeConfig> {
  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) {
    throw new RuntimeConfigError(
      `Runtime configuration request failed: ${response.status} ${response.statusText}`,
    );
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new RuntimeConfigError('Runtime configuration is not valid JSON.');
  }

  return parseRuntimeConfig(body);
}

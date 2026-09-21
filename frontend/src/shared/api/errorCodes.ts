/**
 * The error-code registry (task 028, `dynamic-metrics` §5.8, UI §A.10.4).
 * `ApiError`/`ErrorCode` themselves live in `types.ts` (task 026 — see that
 * file's header for why); this module is the part that is genuinely new to
 * task 028: a runtime-usable registry marking each known code `SPECIFIED`
 * or `TBD`, plus the typed copy the UI renders per code.
 *
 * **Rule this file exists to enforce:** the UI switches on `code`, never on
 * `message` — every string a component shows for a known code comes from
 * `describeErrorCode()` here, not from the server's free-text `message`.
 */
import type { ErrorCode, SpecifiedErrorCode, TbdErrorCode } from './types';

export type ErrorCodeStatus = 'SPECIFIED' | 'TBD';

export interface ErrorCodeEntry {
  status: ErrorCodeStatus;
  /** Copy shown to the operator. `{n}`-style tokens are filled by the caller. */
  message: string;
}

// SPECIFIED — dynamic-metrics §5.8 / UI §A.10.4's typed-error-copy table.
const SPECIFIED_ENTRIES: Record<SpecifiedErrorCode, ErrorCodeEntry> = {
  SERIES_CAP_EXCEEDED: {
    status: 'SPECIFIED',
    message:
      'Projected series exceed the cap. Narrow the selection, or aggregate into fewer lines.',
  },
  AGGREGATION_NOT_APPLICABLE: {
    status: 'SPECIFIED',
    message: 'Aggregation across instances needs at least two instances to combine.',
  },
  GROUP_BY_REQUIRES_AGGREGATION: {
    status: 'SPECIFIED',
    message: 'Group-by only applies once you aggregate across instances.',
  },
  STAT_REQUIRES_AGGREGATION: {
    status: 'SPECIFIED',
    message:
      'A single-stat or gauge shows one number, so it requires aggregation to a single value.',
  },
  METRIC_UNTYPED: {
    status: 'SPECIFIED',
    message: 'This metric has no declared type and cannot be queried until it is typed.',
  },
};

// TBD — named in design §13/§16 but with NO body shape anywhere (§16 Q9).
// The message below is a honest placeholder, not an invented contract.
const TBD_ENTRIES: Record<TbdErrorCode, ErrorCodeEntry> = {
  CONFIG_DIVERGED: {
    status: 'TBD',
    message: 'Replicas are split across config versions. Remediation is required.',
  },
  APPLY_INDETERMINATE: {
    status: 'TBD',
    message:
      'The apply outcome could not be confirmed. Resolve from observed state before applying again.',
  },
  VERSION_DRIFT: {
    status: 'TBD',
    message: 'Deployed and declared versions disagree. Configuration is read-only until reconciled.',
  },
  VERSION_UNKNOWN: {
    status: 'TBD',
    message: 'No published package covers the running version. Configuration editing is blocked.',
  },
  CONFIG_CONFLICT: {
    status: 'TBD',
    message: 'Someone else saved a newer configuration. Refetch and reconcile before retrying.',
  },
};

export const ERROR_CODE_REGISTRY: Record<ErrorCode, ErrorCodeEntry> = {
  ...SPECIFIED_ENTRIES,
  ...TBD_ENTRIES,
};

function isKnownErrorCode(code: string): code is ErrorCode {
  return code in ERROR_CODE_REGISTRY;
}

/**
 * Renders the copy for a code. An UNKNOWN code is never swallowed — it
 * surfaces the server's own message (required in that case) rather than a
 * blank or a generic fallback, so a genuinely new server-side code is still
 * visible to the operator instead of disappearing.
 */
export function describeErrorCode(code: string | undefined, serverMessage: string | undefined): string {
  if (code !== undefined && isKnownErrorCode(code)) {
    return ERROR_CODE_REGISTRY[code].message;
  }
  return serverMessage ?? 'An unexpected error occurred.';
}

export function errorCodeStatus(code: string): ErrorCodeStatus | 'UNKNOWN' {
  return isKnownErrorCode(code) ? ERROR_CODE_REGISTRY[code].status : 'UNKNOWN';
}

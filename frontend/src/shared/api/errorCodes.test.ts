import { describe, expect, it } from 'vitest';

import {
  describeErrorCode,
  ERROR_CODE_REGISTRY,
  errorCodeStatus,
  type ErrorCodeEntry,
} from './errorCodes';
import type { ErrorCode } from './types';

const SPECIFIED_CODES: ErrorCode[] = [
  'SERIES_CAP_EXCEEDED',
  'AGGREGATION_NOT_APPLICABLE',
  'GROUP_BY_REQUIRES_AGGREGATION',
  'STAT_REQUIRES_AGGREGATION',
  'METRIC_UNTYPED',
];

const TBD_CODES: ErrorCode[] = [
  'CONFIG_DIVERGED',
  'APPLY_INDETERMINATE',
  'VERSION_DRIFT',
  'VERSION_UNKNOWN',
  'CONFIG_CONFLICT',
];

describe('ERROR_CODE_REGISTRY', () => {
  it.each(SPECIFIED_CODES)('marks %s as SPECIFIED with non-empty copy', (code) => {
    const entry: ErrorCodeEntry = ERROR_CODE_REGISTRY[code];
    expect(entry.status).toBe('SPECIFIED');
    expect(entry.message.length).toBeGreaterThan(0);
  });

  it.each(TBD_CODES)('marks %s as TBD, still with usable copy', (code) => {
    const entry: ErrorCodeEntry = ERROR_CODE_REGISTRY[code];
    expect(entry.status).toBe('TBD');
    expect(entry.message.length).toBeGreaterThan(0);
  });
});

describe('describeErrorCode', () => {
  it('returns the registry copy for a known code, ignoring the server message', () => {
    expect(describeErrorCode('METRIC_UNTYPED', 'some other server text')).toBe(
      ERROR_CODE_REGISTRY.METRIC_UNTYPED.message,
    );
  });

  it('surfaces the server message for an unknown code rather than swallowing it', () => {
    expect(describeErrorCode('SOME_NEW_CODE_NOT_IN_THE_REGISTRY', 'brand new server text')).toBe(
      'brand new server text',
    );
  });

  it('falls back to a generic message when neither a known code nor a server message exists', () => {
    expect(describeErrorCode(undefined, undefined)).toBe('An unexpected error occurred.');
  });
});

describe('errorCodeStatus', () => {
  it('reports UNKNOWN for a code outside the registry', () => {
    expect(errorCodeStatus('NOT_A_REAL_CODE')).toBe('UNKNOWN');
  });

  it('reports SPECIFIED / TBD correctly for known codes', () => {
    expect(errorCodeStatus('SERIES_CAP_EXCEEDED')).toBe('SPECIFIED');
    expect(errorCodeStatus('CONFIG_DIVERGED')).toBe('TBD');
  });
});

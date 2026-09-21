import { describe, expect, it } from 'vitest';

import {
  displayTimeZone,
  formatAxisTimestamp,
  formatDateTime,
  formatTime,
  timeZoneLabel,
} from './time';

const SAMPLE_UTC = '2026-08-20T14:30:00Z';

describe('formatDateTime', () => {
  it('converts a UTC instant to the requested display zone', () => {
    expect(
      formatDateTime(SAMPLE_UTC, {
        timeZone: 'America/New_York',
        dateStyle: 'short',
        timeStyle: 'short',
      }),
    ).toBe('8/20/26, 10:30 AM');
  });

  it('falls back to the resolved local time zone when none is given', () => {
    expect(displayTimeZone.length).toBeGreaterThan(0);
    expect(formatDateTime(SAMPLE_UTC).length).toBeGreaterThan(0);
  });
});

describe('formatTime', () => {
  it('converts a UTC instant to a time-only string in the requested zone', () => {
    expect(formatTime(SAMPLE_UTC, { timeZone: 'America/New_York' })).toBe('10:30:00 AM');
  });
});

describe('timeZoneLabel', () => {
  it('returns the short abbreviation for an explicit zone', () => {
    expect(timeZoneLabel('America/New_York', new Date(SAMPLE_UTC))).toBe('EDT');
    expect(timeZoneLabel('UTC', new Date(SAMPLE_UTC))).toBe('UTC');
  });
});

describe('formatAxisTimestamp', () => {
  it('always carries the zone label alongside the time', () => {
    expect(formatAxisTimestamp(SAMPLE_UTC, 'America/New_York')).toBe('10:30:00 AM EDT');
  });
});

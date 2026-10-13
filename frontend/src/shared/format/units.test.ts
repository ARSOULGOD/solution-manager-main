import { describe, expect, it } from 'vitest';

import { formatAxisValue, formatUnitValue } from './units';

describe('formatUnitValue', () => {
  it('formats a ratio as a percentage', () => {
    expect(formatUnitValue(0.855, 'ratio')).toBe('85.5%');
  });

  it('formats an unrecognised unit as a grouped number plus the declared label, unchanged', () => {
    expect(formatUnitValue(500, 'ms')).toBe('500 ms');
    expect(formatUnitValue(42, 'count')).toBe('42 count');
    expect(formatUnitValue(1234, 'entries')).toBe('1,234 entries');
  });

  it('formats a null unit as a plain grouped number', () => {
    expect(formatUnitValue(1234, null)).toBe('1,234');
  });
});

describe('formatAxisValue', () => {
  it('renders a ratio as a plain decimal, not a percent', () => {
    // The one deliberate divergence from `formatUnitValue` (which gives
    // `85.5%`) — tick labels follow the reference, which renders ratios as
    // decimals. Confirmed choice, recorded in task 128's report.
    expect(formatAxisValue(0.7, 'ratio')).toBe('0.7');
    expect(formatAxisValue(0.855, 'ratio')).toBe('0.86');
  });

  it('compacts large magnitudes the way the reference does', () => {
    expect(formatAxisValue(47_900, 'count')).toBe('47.9k');
    expect(formatAxisValue(2_500_000, 'entries')).toBe('2.5M');
    expect(formatAxisValue(3_000_000_000, 'events')).toBe('3B');
  });

  it('leaves small magnitudes alone', () => {
    expect(formatAxisValue(42, 'count')).toBe('42');
    expect(formatAxisValue(999, null)).toBe('999');
  });

  it('compacts negative magnitudes by absolute size', () => {
    expect(formatAxisValue(-47_900, 'count')).toBe('-47.9k');
  });

  it('never repeats the unit on a tick, unlike formatUnitValue', () => {
    expect(formatUnitValue(1234, 'entries')).toBe('1,234 entries');
    expect(formatAxisValue(1234, 'entries')).toBe('1.2k');
  });
});

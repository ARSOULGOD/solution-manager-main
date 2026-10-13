/**
 * Tests for the *instrument*, not the palette (that is
 * `palette.contrast.test.ts`). A gate is only worth what the maths behind
 * it is worth: a `contrastRatio` that silently returned 21 would make
 * FE-097 pass forever while proving nothing. Every case below has an answer
 * that can be checked independently of this implementation.
 */
import { describe, expect, it } from 'vitest';

import {
  blend,
  contrastRatio,
  deltaE2000,
  parseHex,
  relativeLuminance,
  simulateCvd,
  toHex,
  worstContrast,
} from './contrast';

describe('parseHex / toHex', () => {
  it('reads long and short form, with and without the hash', () => {
    expect(parseHex('#3C69E1')).toEqual([0x3c, 0x69, 0xe1]);
    expect(parseHex('3C69E1')).toEqual([0x3c, 0x69, 0xe1]);
    expect(parseHex('#abc')).toEqual([0xaa, 0xbb, 0xcc]);
  });

  it('rejects anything that is not a hex colour', () => {
    expect(() => parseHex('rgb(1,2,3)')).toThrow(/Not a hex colour/);
    expect(() => parseHex('#12345')).toThrow(/Not a hex colour/);
  });

  it('round-trips', () => {
    expect(toHex(parseHex('#0A2C47'))).toBe('#0A2C47');
  });

  it('clamps out-of-gamut channels rather than wrapping', () => {
    expect(toHex([-20, 300, 128])).toBe('#00FF80');
  });
});

describe('relativeLuminance', () => {
  // WCAG 2.2 fixes both endpoints exactly.
  it('is 0 for black and 1 for white', () => {
    expect(relativeLuminance('#000000')).toBe(0);
    expect(relativeLuminance('#FFFFFF')).toBe(1);
  });
});

describe('contrastRatio', () => {
  it('is exactly 21 for black on white — the defined maximum', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 10);
  });

  it('is 1 for a colour against itself', () => {
    expect(contrastRatio('#015B82', '#015B82')).toBeCloseTo(1, 10);
  });

  it('is order-independent', () => {
    expect(contrastRatio('#015B82', '#FFFFFF')).toBeCloseTo(
      contrastRatio('#FFFFFF', '#015B82'),
      10,
    );
  });

  // #767676 and #595959 on white are the canonical AA (4.5:1) and AAA
  // (7:1) boundary greys — quoted in countless contrast tools, so an
  // implementation that disagrees with them is wrong, not novel.
  it('lands on the canonical AA and AAA boundary greys', () => {
    expect(contrastRatio('#767676', '#FFFFFF')).toBeCloseTo(4.54, 2);
    expect(contrastRatio('#595959', '#FFFFFF')).toBeCloseTo(7.0, 2);
  });
});

describe('blend', () => {
  it('returns the endpoints at alpha 1 and 0', () => {
    expect(blend('#FF0000', '#0000FF', 1)).toBe('#FF0000');
    expect(blend('#FF0000', '#0000FF', 0)).toBe('#0000FF');
  });

  it('meets in the middle at alpha 0.5', () => {
    expect(blend('#000000', '#FFFFFF', 0.5)).toBe('#808080');
  });
});

describe('simulateCvd', () => {
  it('leaves greys alone — CVD is a hue confusion, not a luminance shift', () => {
    for (const type of ['protan', 'deutan', 'tritan'] as const) {
      const [r, g, b] = parseHex(simulateCvd('#808080', type));
      expect(Math.abs(r - g)).toBeLessThan(4);
      expect(Math.abs(g - b)).toBeLessThan(4);
    }
  });

  it('collapses red and green toward each other for protan and deutan', () => {
    for (const type of ['protan', 'deutan'] as const) {
      const before = deltaE2000('#FF0000', '#00FF00');
      const after = deltaE2000(simulateCvd('#FF0000', type), simulateCvd('#00FF00', type));
      expect(after).toBeLessThan(before / 2);
    }
  });

  it('leaves the red/green pair largely intact for tritan, which confuses blue/yellow', () => {
    const before = deltaE2000('#FF0000', '#00FF00');
    const after = deltaE2000(simulateCvd('#FF0000', 'tritan'), simulateCvd('#00FF00', 'tritan'));
    expect(after).toBeGreaterThan(before / 2);
  });
});

describe('deltaE2000', () => {
  it('is 0 for identical colours and symmetric', () => {
    expect(deltaE2000('#3C69E1', '#3C69E1')).toBe(0);
    expect(deltaE2000('#3C69E1', '#009193')).toBeCloseTo(deltaE2000('#009193', '#3C69E1'), 10);
  });

  it('separates opponent primaries far more than two neighbouring blues', () => {
    expect(deltaE2000('#FF0000', '#00FF00')).toBeGreaterThan(50);
    expect(deltaE2000('#3C69E1', '#426EE2')).toBeLessThan(3);
  });
});

describe('worstContrast', () => {
  it('reports the worse of the two surfaces, not the average', () => {
    // Against white this green is poor; against navy it is strong. The
    // worst case is the only one that decides legibility.
    const surfaces = ['#FFFFFF', '#03223B'];
    expect(worstContrast('#00C895', surfaces)).toBeCloseTo(contrastRatio('#00C895', '#FFFFFF'), 10);
  });
});

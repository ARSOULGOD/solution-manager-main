import { describe, expect, it } from 'vitest';

import './tokens.css';
import { tokens } from './tokens';

/**
 * Helper to get a CSS custom property value from the DOM.
 * Mirrors the implementation in tokens.ts getTokenValue().
 */
function getDOMValue(propertyName: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(
    propertyName
  );
  return value ? value.trim() : '';
}

describe('Design Tokens — DOM Resolution', () => {
  it('CSS custom properties are applied to the DOM', () => {
    // Sanity check: verify tokens.css was actually loaded and processed
    const brandValue = getDOMValue('--brand-wMain');
    expect(brandValue).toBe('#00C895');
  });

  describe('Brand tokens resolve from DOM', () => {
    it('resolves brand.wMain from CSS', () => {
      expect(tokens.brand.wMain).toBe(getDOMValue('--brand-wMain'));
    });

    it('resolves brand.w100 from CSS', () => {
      expect(tokens.brand.w100).toBe(getDOMValue('--brand-w100'));
    });
  });

  describe('Primary tokens resolve from DOM', () => {
    it('resolves primary.wMain from CSS', () => {
      expect(tokens.primary.wMain).toBe(getDOMValue('--primary-wMain'));
    });

    it('resolves primary.w100 from CSS', () => {
      expect(tokens.primary.w100).toBe(getDOMValue('--primary-w100'));
    });

    it('resolves primary text variants from CSS', () => {
      expect(tokens.primary.text.wMain).toBe(
        getDOMValue('--primary-text-wMain')
      );
    });
  });

  describe('Semantic tokens resolve from DOM', () => {
    it('resolves info.wMain from CSS', () => {
      expect(tokens.info.wMain).toBe(getDOMValue('--info-wMain'));
    });

    it('resolves error.wMain from CSS', () => {
      expect(tokens.error.wMain).toBe(getDOMValue('--error-wMain'));
    });

    it('resolves success.wMain from CSS', () => {
      expect(tokens.success.wMain).toBe(getDOMValue('--success-wMain'));
    });
  });

  describe('Typography tokens resolve from DOM', () => {
    it('resolves font sizes from CSS', () => {
      expect(tokens.fontSize.xs).toBe(getDOMValue('--fs-xs'));
      expect(tokens.fontSize.base).toBe(getDOMValue('--fs-base'));
      expect(tokens.fontSize['2xl']).toBe(getDOMValue('--fs-2xl'));
    });

    it('resolves font weights from CSS', () => {
      expect(tokens.fontWeight.regular.toString()).toBe(
        getDOMValue('--fw-regular').trim()
      );
      expect(tokens.fontWeight.semibold.toString()).toBe(
        getDOMValue('--fw-semibold').trim()
      );
    });
  });

  describe('Spacing tokens resolve from DOM', () => {
    it('resolves spacing values from CSS', () => {
      expect(tokens.space[1]).toBe(getDOMValue('--space-1'));
      expect(tokens.space[4]).toBe(getDOMValue('--space-4'));
      expect(tokens.space[20]).toBe(getDOMValue('--space-20'));
    });
  });

  describe('Radii tokens resolve from DOM', () => {
    it('resolves radius values from CSS', () => {
      expect(tokens.radius.xs).toBe(getDOMValue('--radius-xs'));
      expect(tokens.radius.lg).toBe(getDOMValue('--radius-lg'));
      expect(tokens.radius.default).toBe(getDOMValue('--radius'));
    });
  });

  describe('Shadow tokens resolve from DOM', () => {
    it('resolves shadow values from CSS', () => {
      expect(tokens.shadow.xs).toBe(getDOMValue('--shadow-xs'));
      expect(tokens.shadow.card).toBe(getDOMValue('--shadow-card'));
      expect(tokens.shadow.modal).toBe(getDOMValue('--shadow-modal'));
    });
  });

  describe('Console layer tokens resolve from DOM', () => {
    it('resolves console.primary from CSS', () => {
      expect(tokens.console.primary).toBe(getDOMValue('--primary'));
    });

    it('resolves console.line from CSS', () => {
      expect(tokens.console.line).toBe(getDOMValue('--line'));
    });

    it('resolves elevation shadows from CSS', () => {
      expect(tokens.console.e1).toBe(getDOMValue('--e1'));
      expect(tokens.console.e4).toBe(getDOMValue('--e4'));
      expect(tokens.console.e8).toBe(getDOMValue('--e8'));
    });
  });

  describe('Semantic mapping tokens resolve from DOM', () => {
    it('resolves semantic.fg1 from CSS', () => {
      expect(tokens.semantic.fg1).toBe(getDOMValue('--fg1'));
    });

    it('resolves semantic.bg1 from CSS', () => {
      expect(tokens.semantic.bg1).toBe(getDOMValue('--bg1'));
    });

    it('resolves semantic.borderDefault from CSS', () => {
      expect(tokens.semantic.borderDefault).toBe(
        getDOMValue('--border-default')
      );
    });
  });

  describe('No token resolves to undefined or empty', () => {
    it('all exported token values are non-empty strings or numbers', () => {
      const checkValue = (val: unknown, path: string) => {
        if (typeof val === 'string') {
          expect(val.length > 0).toBe(true);
        } else if (typeof val === 'number') {
          expect(val).toBeDefined();
        } else if (typeof val === 'object' && val !== null) {
          Object.entries(val).forEach(([key, value]) => {
            checkValue(value, `${path}.${key}`);
          });
        }
      };

      checkValue(tokens, 'tokens');
    });
  });

  describe('Dark theme tokens are hardcoded constants (not mode-scoped)', () => {
    it('darkTheme.line is the expected dark-mode value', () => {
      // This is a hardcoded constant, not resolved from CSS
      // because [data-theme="dark"] overrides are not applied at module load
      expect(tokens.darkTheme.line).toBe('#2b4358');
    });

    it('darkTheme.lineSoft is the expected dark-mode value', () => {
      expect(tokens.darkTheme.lineSoft).toBe('#213546');
    });

    it('all dark theme tokens are non-empty', () => {
      Object.values(tokens.darkTheme).forEach((val) => {
        expect(typeof val).toBe('string');
        expect(val.length > 0).toBe(true);
      });
    });
  });
});

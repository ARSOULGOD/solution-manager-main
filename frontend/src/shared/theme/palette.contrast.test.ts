/**
 * FE-097 · the palette gate.
 *
 * Design §18 says contrast "cannot be signed off until open item 19 tokens
 * exist". This file is the other half of that sentence: the tokens now
 * exist, so the sign-off becomes a check that passes or fails rather than a
 * judgement someone makes by eye. Every assertion runs against **both**
 * themes, and a failure is a build failure — `npm run verify:palette` is a
 * required CI gate, not a warning.
 *
 * What is asserted, and why each threshold is the one it is:
 *
 *   · Chart series colours ≥ 3:1 against every surface they can be drawn
 *     on. SC 1.4.11 (non-text contrast) — a series line is a graphical
 *     object conveying information.
 *   · Text and label colours ≥ 4.5:1. SC 1.4.3 at AA for body-sized text.
 *   · Focus indicator ≥ 3:1. SC 1.4.11 again, applied to the ring.
 *   · Categorical hues separable under simulated dichromacy, for as many
 *     leading entries as the palette claims (`chart.cvdSafeCount`).
 *   · Every state vocabulary entry carries a label and an icon, so no
 *     state is signalled by colour alone (SC 1.4.1).
 *
 * The maths lives in `contrast.ts` and is itself tested in
 * `contrast.test.ts` — a gate whose instrument is unverified is theatre.
 */
import { describe, expect, it } from 'vitest';

import { allVocabEntries, healthVocab } from '../vocab';

import { contrastRatio, deltaE2000, simulateCvd, type CvdType } from './contrast';
import { createSmTheme, type SmThemeMode } from './createSmTheme';
import { echartsTheme } from './echartsTheme';

const MODES: SmThemeMode[] = ['light', 'dark'];
const CVD_TYPES: CvdType[] = ['protan', 'deutan', 'tritan'];

/** SC 1.4.11 — graphical objects and UI component boundaries. */
const NON_TEXT_MIN = 3;
/** SC 1.4.3 at AA — body-sized text. */
const TEXT_MIN = 4.5;

/**
 * Minimum CIEDE2000 separation between two series colours as a dichromat
 * sees them.
 *
 * This is a project threshold, not a figure from a standard — WCAG says
 * nothing about how far apart two categorical hues must be. It was set by
 * looking at what the DS accent set can actually deliver: the best possible
 * ordering of these ten hues holds ≈8.8 across the first six, and collapses
 * to ≈2.3 by the tenth. 8 sits just under what the palette achieves, so the
 * gate fails on a real regression (a hue swapped, the order shuffled)
 * without failing on floating-point noise. It is calibrated against the
 * exact CVD pipeline in `contrast.ts` — moving one means re-deriving the
 * other.
 */
const CVD_MIN_DELTA_E = 8;

/** Every surface a chart, chip or label can sit on, per mode. */
function surfacesFor(mode: SmThemeMode): string[] {
  const { palette } = createSmTheme(mode);
  return [palette.background.default, palette.background.paper];
}

describe.each(MODES)('%s theme · chart series colours (SC 1.4.11)', (mode) => {
  const theme = createSmTheme(mode);
  const surfaces = surfacesFor(mode);

  it.each(theme.palette.chart.categorical.map((c, i) => [i, c] as const))(
    'series %i (%s) clears 3:1 on every surface',
    (_index, colour) => {
      for (const surface of surfaces) {
        expect(contrastRatio(colour, surface)).toBeGreaterThanOrEqual(NON_TEXT_MIN);
      }
    },
  );

  it('has ten distinct series colours', () => {
    expect(theme.palette.chart.categorical).toHaveLength(10);
    expect(new Set(theme.palette.chart.categorical).size).toBe(10);
  });

  // The ECharts theme is a pure map (DM-D13), so this catches a mapping
  // that stops forwarding the validated list — the chart is what a user
  // actually sees, not `palette.chart`.
  it('hands the validated list to ECharts unchanged', () => {
    expect(echartsTheme(theme).color).toEqual(theme.palette.chart.categorical);
  });
});

describe.each(MODES)('%s theme · colour-blind separability (SC 1.4.1)', (mode) => {
  const theme = createSmTheme(mode);
  const { categorical, cvdSafeCount } = theme.palette.chart;
  const safe = categorical.slice(0, cvdSafeCount);

  it.each(CVD_TYPES)('the leading series colours stay separable for %s viewers', (type) => {
    safe.forEach((a, i) => {
      safe.slice(i + 1).forEach((b, offset) => {
        const j = i + 1 + offset;
        const distance = deltaE2000(simulateCvd(a, type), simulateCvd(b, type));
        expect(
          distance,
          `series ${i} (${a}) and ${j} (${b}) are ΔE2000 ${distance.toFixed(2)} apart under ${type}`,
        ).toBeGreaterThanOrEqual(CVD_MIN_DELTA_E);
      });
    });
  });

  // The honest half of the claim. If someone raises cvdSafeCount without
  // finding hues that support it, the loop above fails; if someone lowers
  // it to make a bad palette pass, this fails. Six is what the DS accent
  // set can actually deliver.
  it('claims a safe count the palette can back, and no more', () => {
    expect(cvdSafeCount).toBe(6);
    expect(cvdSafeCount).toBeLessThanOrEqual(categorical.length);
  });
});

describe.each(MODES)('%s theme · text and labels (SC 1.4.3 AA)', (mode) => {
  const theme = createSmTheme(mode);
  const { palette } = theme;
  const surfaces = surfacesFor(mode);

  const textColours: [name: string, colour: string, on: string[]][] = [
    ['text.primary', palette.text.primary, surfaces],
    ['text.secondary', palette.text.secondary, surfaces],
    // Chart chrome. axisLabel/legend/tooltip text are the labels a reader
    // has to decode to interpret the series at all.
    ['chart axis label', echartsTheme(theme).valueAxis.axisLabel.color, surfaces],
    ['chart legend label', echartsTheme(theme).legend.textStyle.color, surfaces],
    [
      'chart tooltip text',
      echartsTheme(theme).tooltip.textStyle.color,
      [echartsTheme(theme).tooltip.backgroundColor],
    ],
    // The rail is navy in both modes, so it is checked in both.
    ['nav.text', palette.nav.text, [palette.nav.bg]],
    ['nav.muted', palette.nav.muted, [palette.nav.bg]],
  ];

  it.each(textColours)('%s clears 4.5:1', (_name, colour, on) => {
    for (const surface of on) {
      expect(contrastRatio(colour, surface)).toBeGreaterThanOrEqual(TEXT_MIN);
    }
  });

  /**
   * `text.disabled` is deliberately **not** in the list above. WCAG 2.2
   * SC 1.4.3 exempts "text or images of text that are part of an inactive
   * user interface component". Holding disabled text to 4.5:1 would make it
   * indistinguishable from enabled text, which is a worse outcome for
   * everyone — the exemption exists for a reason. It is asserted to be
   * *dimmer* than the enabled text instead, so the exemption cannot be used
   * as cover for an accidental regression in the other direction.
   */
  it('keeps disabled text dimmer than secondary text, exempt from 4.5:1', () => {
    const surface = palette.background.paper;
    expect(contrastRatio(palette.text.disabled, surface)).toBeLessThan(
      contrastRatio(palette.text.secondary, surface),
    );
  });
});

describe.each(MODES)('%s theme · tone chips (SC 1.4.3 AA, SC 1.4.11)', (mode) => {
  const { palette } = createSmTheme(mode);
  const surfaces = surfacesFor(mode);
  const tones = Object.entries(palette.tone);

  // `Chip` paints `main` as the label text on a `bg` fill, so this is the
  // pair a reader actually decodes.
  it.each(tones)('%s label text clears 4.5:1 on its own fill', (_name, tone) => {
    expect(contrastRatio(tone.main, tone.bg)).toBeGreaterThanOrEqual(TEXT_MIN);
  });

  // `main` is also read as bare text straight on the page — StatStrip's
  // value, LogViewer's level, SectionNav's counts. Same 4.5:1 duty, a
  // different backdrop, and the reason the tone table has to be per-mode.
  it.each(tones)('%s clears 4.5:1 as bare text on the page', (_name, tone) => {
    for (const surface of surfaces) {
      expect(contrastRatio(tone.main, surface)).toBeGreaterThanOrEqual(TEXT_MIN);
    }
  });

  // The chip's 1px border is `main` too. It clears 3:1 by construction if
  // the text rules above hold, but assert it: the border is what separates
  // the chip from the page when the fill is near the page luminance.
  it.each(tones)('%s border separates the chip from the page', (_name, tone) => {
    for (const surface of surfaces) {
      expect(contrastRatio(tone.main, surface)).toBeGreaterThanOrEqual(NON_TEXT_MIN);
    }
  });
});

describe.each(MODES)('%s theme · focus indicator (SC 1.4.11)', (mode) => {
  const { palette } = createSmTheme(mode);
  const { inner, outer } = palette.focusRing;
  const surfaces = surfacesFor(mode);

  // A two-band ring passes when the band the surface does *not* swallow
  // still clears 3:1 — that is the whole point of using two.
  it.each(surfaces)('at least one band clears 3:1 against %s', (surface) => {
    const best = Math.max(contrastRatio(inner, surface), contrastRatio(outer, surface));
    expect(best).toBeGreaterThanOrEqual(NON_TEXT_MIN);
  });

  it('separates its two bands from each other', () => {
    expect(contrastRatio(inner, outer)).toBeGreaterThanOrEqual(NON_TEXT_MIN);
  });

  it('keeps the ring visible on the navy nav rail', () => {
    const best = Math.max(
      contrastRatio(inner, palette.nav.bg),
      contrastRatio(outer, palette.nav.bg),
    );
    expect(best).toBeGreaterThanOrEqual(NON_TEXT_MIN);
  });
});

// An outlined field's own focused border (`.Mui-focused .notchedOutline`)
// never wears the ring above — it has this colour instead, alone, so
// (unlike the ring) there is no second band to fall back on if this one
// fails. Sampled from the Solace Broker Manager's own Try Me page; see
// `palette.ts`'s own doc comment on `fieldFocusBorder` for the full
// derivation.
describe.each(MODES)("%s theme · outlined field's own focus border (SC 1.4.11)", (mode) => {
  const { palette } = createSmTheme(mode);
  const surfaces = surfacesFor(mode);

  it.each(surfaces)('clears 3:1 against %s, alone', (surface) => {
    expect(contrastRatio(palette.fieldFocusBorder, surface)).toBeGreaterThanOrEqual(NON_TEXT_MIN);
  });
});

describe.each(MODES)('%s theme · status colours are never the only channel (SC 1.4.1)', (mode) => {
  const { palette } = createSmTheme(mode);

  // Cross-check against the vocabulary module, per the task brief: a state
  // is rendered through a vocabulary entry, and every entry must carry a
  // written label and an icon alongside its tone.
  it.each(allVocabEntries.map((e) => [e.key, e] as const))(
    '%s carries a label and an icon, not just a tone',
    (_key, entry) => {
      expect(entry.label.trim().length).toBeGreaterThan(0);
      expect(entry.icon).toBeTruthy();
      expect(palette.tone[entry.tone]).toBeDefined();
    },
  );

  // The health vocabulary is the one users read as an alarm. If two health
  // states shared a tone *and* the icons were ever dropped, colour would be
  // doing work alone — so assert the tones are distinct as well. Read off
  // `healthVocab` rather than filtering `allVocabEntries` by key: other
  // vocabularies reuse some of the same keys, and a key filter silently
  // pulls their entries in too.
  it('gives every health state a distinct tone', () => {
    const tones = Object.values(healthVocab).map((entry) => entry.tone);
    expect(new Set(tones).size).toBe(tones.length);
  });
});

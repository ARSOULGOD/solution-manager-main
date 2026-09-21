/**
 * Colour maths for the FE-097 palette gate. Pure, dependency-free, and
 * deliberately its own module rather than inlined in the test: the gate is
 * only worth what its instrument is worth, so these functions carry their
 * own unit tests (`contrast.test.ts`) against values with known answers.
 *
 * Nothing here reads the theme, and the theme does not read this at build
 * time — see the "why the palette is baked, not clamped" note in
 * `palette.ts`. This module is used by the *check*, not by the paint.
 */

export type Rgb = readonly [r: number, g: number, b: number];

/** `#rgb` / `#rrggbb`, with or without the hash. Throws on anything else. */
export function parseHex(hex: string): Rgb {
  const body = hex.trim().replace(/^#/, '');
  const full =
    body.length === 3
      ? body
          .split('')
          .map((c) => c + c)
          .join('')
      : body;
  if (!/^[0-9a-fA-F]{6}$/.test(full)) {
    throw new Error(`Not a hex colour: ${hex}`);
  }
  return [
    parseInt(full.slice(0, 2), 16),
    parseInt(full.slice(2, 4), 16),
    parseInt(full.slice(4, 6), 16),
  ] as const;
}

export function toHex([r, g, b]: Rgb): string {
  const channel = (v: number) =>
    Math.round(Math.min(255, Math.max(0, v)))
      .toString(16)
      .padStart(2, '0');
  return `#${channel(r)}${channel(g)}${channel(b)}`.toUpperCase();
}

/** sRGB 8-bit channel → linear-light [0,1]. WCAG 2.2 relative-luminance definition. */
function toLinear(channel8Bit: number): number {
  const c = channel8Bit / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function fromLinear(linear: number): number {
  const c = Math.min(1, Math.max(0, linear));
  return (c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055) * 255;
}

/**
 * WCAG 2.2 relative luminance.
 * https://www.w3.org/TR/WCAG22/#dfn-relative-luminance
 */
export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHex(hex).map(toLinear) as unknown as Rgb;
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * WCAG 2.2 contrast ratio, 1..21. Order-independent.
 * https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio
 */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

/** Composite an opaque `fg` over an opaque `bg` at `alpha` (0..1). */
export function blend(fg: string, bg: string, alpha: number): string {
  const f = parseHex(fg);
  const b = parseHex(bg);
  return toHex([
    f[0] * alpha + b[0] * (1 - alpha),
    f[1] * alpha + b[1] * (1 - alpha),
    f[2] * alpha + b[2] * (1 - alpha),
  ]);
}

// ── Colour-vision deficiency ──────────────────────────────────────────────
//
// Machado, Oliveira & Fernandes (2009), "A Physiologically-based Model for
// Simulation of Color Vision Deficiency", severity 1.0 (= dichromacy).
// Coefficients transcribed from the authors' own page and verified against
// it on 2026-09-02 (`I2`):
// https://www.inf.ufrgs.br/~oliveira/pubs_files/CVD_Simulation/CVD_Simulation.html
//
// ⚠️ That page publishes the matrices but does **not** state the colour
// space they apply in. This implementation multiplies in **linear-light**
// sRGB and re-encodes afterwards, which is what the common implementations
// do. The distinction matters only for the absolute ΔE numbers, not for
// which pairs collide, and CVD_MIN_DELTA_E in `palette.contrast.test.ts`
// is calibrated against *this* pipeline — so treat the threshold and this
// choice as one unit. Do not change one without re-deriving the other.

export type CvdType = 'protan' | 'deutan' | 'tritan';

const CVD_MATRIX: Record<CvdType, readonly [Rgb, Rgb, Rgb]> = {
  protan: [
    [0.152286, 1.052583, -0.204868],
    [0.114503, 0.786281, 0.099216],
    [-0.003882, -0.048116, 1.051998],
  ],
  deutan: [
    [0.367322, 0.860646, -0.227968],
    [0.280085, 0.672501, 0.047413],
    [-0.01182, 0.04294, 0.968881],
  ],
  tritan: [
    [1.255528, -0.076749, -0.178779],
    [-0.078411, 0.930809, 0.147602],
    [0.004733, 0.691367, 0.3039],
  ],
};

/** How `hex` appears to a dichromat of the given type. */
export function simulateCvd(hex: string, type: CvdType): string {
  const [r, g, b] = parseHex(hex).map(toLinear) as unknown as Rgb;
  const m = CVD_MATRIX[type];
  return toHex([
    fromLinear(m[0][0] * r + m[0][1] * g + m[0][2] * b),
    fromLinear(m[1][0] * r + m[1][1] * g + m[1][2] * b),
    fromLinear(m[2][0] * r + m[2][1] * g + m[2][2] * b),
  ]);
}

// ── Perceptual distance ───────────────────────────────────────────────────

/** CIE L*a*b* under a D65 white point. */
function toLab(hex: string): readonly [number, number, number] {
  const [r, g, b] = parseHex(hex).map(toLinear) as unknown as Rgb;
  const x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
  const y = r * 0.2126 + g * 0.7152 + b * 0.0722;
  const z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  const [fx, fy, fz] = [f(x), f(y), f(z)];
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

/**
 * CIEDE2000 colour difference. Used over the simpler CIE76 because CIE76
 * badly overstates distance in the saturated blues this palette is full of
 * — the very region where `indigo`/`purple` need an honest answer.
 */
export function deltaE2000(hexA: string, hexB: string): number {
  const [l1, a1, b1] = toLab(hexA);
  const [l2, a2, b2] = toLab(hexB);
  const rad = Math.PI / 180;
  const deg = 180 / Math.PI;
  const pow7 = (n: number) => Math.pow(n, 7);

  const c1 = Math.hypot(a1, b1);
  const c2 = Math.hypot(a2, b2);
  const cBar = (c1 + c2) / 2;
  const g = 0.5 * (1 - Math.sqrt(pow7(cBar) / (pow7(cBar) + pow7(25))));

  const ap1 = (1 + g) * a1;
  const ap2 = (1 + g) * a2;
  const cp1 = Math.hypot(ap1, b1);
  const cp2 = Math.hypot(ap2, b2);

  const hue = (b: number, ap: number) => {
    if (b === 0 && ap === 0) return 0;
    const h = Math.atan2(b, ap) * deg;
    return h < 0 ? h + 360 : h;
  };
  const hp1 = hue(b1, ap1);
  const hp2 = hue(b2, ap2);

  const dL = l2 - l1;
  const dC = cp2 - cp1;
  let dh = 0;
  if (cp1 * cp2 !== 0) {
    dh = hp2 - hp1;
    if (dh > 180) dh -= 360;
    else if (dh < -180) dh += 360;
  }
  const dH = 2 * Math.sqrt(cp1 * cp2) * Math.sin((dh / 2) * rad);

  const lBar = (l1 + l2) / 2;
  const cpBar = (cp1 + cp2) / 2;
  let hBar: number;
  if (cp1 * cp2 === 0) {
    hBar = hp1 + hp2;
  } else {
    hBar = hp1 + hp2;
    if (Math.abs(hp1 - hp2) > 180) hBar += hBar < 360 ? 360 : -360;
    hBar /= 2;
  }

  const t =
    1 -
    0.17 * Math.cos((hBar - 30) * rad) +
    0.24 * Math.cos(2 * hBar * rad) +
    0.32 * Math.cos((3 * hBar + 6) * rad) -
    0.2 * Math.cos((4 * hBar - 63) * rad);

  const dTheta = 30 * Math.exp(-Math.pow((hBar - 275) / 25, 2));
  const rC = 2 * Math.sqrt(pow7(cpBar) / (pow7(cpBar) + pow7(25)));
  const sL = 1 + (0.015 * Math.pow(lBar - 50, 2)) / Math.sqrt(20 + Math.pow(lBar - 50, 2));
  const sC = 1 + 0.045 * cpBar;
  const sH = 1 + 0.015 * cpBar * t;
  const rT = -Math.sin(2 * dTheta * rad) * rC;

  return Math.sqrt(
    Math.pow(dL / sL, 2) + Math.pow(dC / sC, 2) + Math.pow(dH / sH, 2) + rT * (dC / sC) * (dH / sH),
  );
}

/**
 * The smallest contrast `hex` achieves against any of `surfaces` — the only
 * number worth asserting on, since a colour is legible on a surface pair
 * only if it clears the bar on the *worse* of them.
 */
export function worstContrast(hex: string, surfaces: readonly string[]): number {
  return Math.min(...surfaces.map((surface) => contrastRatio(hex, surface)));
}

# Design Tokens — Provenance

## Source Files

All design tokens are extracted verbatim from the following checked-in reference files:

### colors_and_type.css (Solace Agent Mesh Design System)

**Path:** `docs/design/frontend/reference/_ds/solace-agent-mesh-design-system-659eb3ca-3eea-42ce-afd4-72a265693d75/colors_and_type.css`

**SHA-256:** `eef234c1a96bf183d51d722190c63de5d7e83b0093a44d966cce53f2cbba2793`

**Contents extracted:**
- Brand colors (wMain, w100, w60, w30, w10)
- Primary colors (w100, w90, wMain, w60, w40, w20, w10) + text variants
- Secondary colors (w100, w80, w70, wMain, w40, w20, w10) + text variants
- Background colors (w100, wMain, w20, w10)
- Semantic colors: info, error, warning, success (each with wMain, w100, w30, w10)
- Chart accents (10 colors + 10 light variants)
- State layer overlays
- Dark surface overrides
- Typography: 3 font families (Rubik, Inter, JetBrains Mono)
- Font sizes, line heights, weights
- Spacing scale (4px base, 11 steps)
- Border radii (6 steps)
- Shadows (5 depths)
- Motion: easing curves and durations
- **Primitive semantic mappings** (--fg1, --fg2, --fg-muted, --fg-brand, --bg1, --bg2, --bg-dark, --border-default, --border-subtle)

### theme.css (Solution Manager Console Layer)

**Path:** `docs/design/frontend/reference/theme.css`

**SHA-256:** `e34622eb1cf60514a0f5ac9e250c9873b9d0102b70970bd5fc072342e03ddd1b`

**Contents extracted:**
- Console-layer tokens: primary shortcuts (--primary, --primary-d, --primary-dd, --primary-050/100/160)
- MUI accent aliases (--accent, --navy)
- Line tokens (--line, --line-soft)
- Elevation shadows (--e1, --e2, --e4, --e8)
- Layout dimensions (--appbar-h, --drawer-w)
- Motion overrides (--dur, --dur-s, --ease)
- **Dark theme overrides** in `[data-theme="dark"]` selector:
  - Line colors (#2b4358, #213546)
  - Surface backgrounds (#03223B, #0a2c47, #0e344f)
  - Text colors (#c9d4dd, #eaf1f6, #8ea9bd)
  - Border and grid colors (#2b4358, #1c344a)

These files are **authoritative**. Any token change must first be updated in the reference CSS files, then regenerated in this module.

## Font Substitution

The reference DS file (`colors_and_type.css`) imports Google Fonts via:
```css
@import url("https://fonts.googleapis.com/css2?family=Rubik:wght@300;400;500;600;700&family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap");
```

**This project self-hosts fonts instead** to comply with **REQ-003: air-gapped deployment support**.

Fonts are hosted at `/public/fonts/` with `@font-face` rules. The mapping is:

| Font         | Original (Google Fonts) | Self-Hosted |
| --- | --- | --- |
| Rubik 300, 400, 500, 600, 700 | CDN | `/fonts/Rubik-Light.woff2`, `/fonts/Rubik-Regular.woff2`, etc. |
| Inter 400, 500, 600, 700 | CDN | `/fonts/Inter-Regular.woff2`, `/fonts/Inter-Medium.woff2`, etc. |
| JetBrains Mono 400, 500, 600 | CDN | `/fonts/JetBrainsMono-Regular.woff2`, etc. |

**Font binaries are a follow-up task** (out of scope for FE-002). The CSS rules are complete; actual `.woff2`/`.woff` files must be sourced separately.

## Ratification Status

**These tokens are PENDING architect acceptance of Design Decision 19** (§16 Q1 of `docs/design/architecture.md`).

> "SM pending explicit acceptance: the Solace Agent Mesh DS tokens (FE-D5 / FE-D30) are a close fit, but SM's color, spacing and type choices must be explicitly ratified, **and no PR treating them as brand-final before that acceptance is a defect.**"

— Design §8.1

**Do not treat this token set as design-final.** If the architect group requests changes, all modifications flow through this module — that is the whole point of centralizing tokens here.

## Verification

To verify this token set matches the source files:

```bash
# Recompute source file hashes
sha256sum docs/design/frontend/reference/_ds/solace-agent-mesh-design-system-659eb3ca-3eea-42ce-afd4-72a265693d75/colors_and_type.css
sha256sum docs/design/frontend/reference/theme.css
```

If the hashes match those recorded above (eef234c1a96..., e34622eb1cf...), the token set is in sync with source.

## Token Completeness

All tokens from the source files are re-exported via `tokens.ts` in a typed interface. Categories present:

- ✓ **Solace Agent Mesh DS**: Brand, primary, secondary, background, semantic (info/error/warning/success), accents (10 colors + 10 light variants), state layers, dark surface, typography (fonts, 10 sizes, 10 heights, 4 weights), spacing (11 steps), radii (6 steps), shadows (5 depths), motion (3 easings, 3 durations), primitive semantic mappings (9 shortcuts)
- ✓ **Console Layer**: 19 MUI/component-level tokens (primary shortcuts, aliases, lines, elevations, layout, motion)
- ✓ **Dark Theme**: 10 override tokens for dark mode surfaces and text

## How to Use

Do **not** reference `tokens.css` directly in components. Instead:

```typescript
import { tokens } from '@shared/theme';

export const MyComponent = () => {
  return (
    <div style={{ color: tokens.primary.wMain, padding: tokens.space[4] }}>
      Hello
    </div>
  );
};
```

This ensures:

1. **Type safety:** IDEs auto-complete token names and catch typos.
2. **Traceability:** A search for `tokens.primary` finds all uses and their origin.
3. **Audit:** Any token value change is visible as a diff to this module.
4. **Single source of truth:** All values derive from tokens.css; no duplicate literals in components.

## Testing

The token set is validated by a snapshot test (`tokens.test.ts`) that:
- Asserts all token categories are present
- Verifies no token value is undefined
- Confirms `tokens.ts` resolves values from the actual CSS custom properties (via jsdom environment)

The test imports `tokens.css` at runtime, ensuring the DOM custom properties are present and resolved correctly.

## Component-Level Theme (FE-003)

Material-UI theme factory and component overrides are a separate task (FE-003). That task will consume these tokens and layer on MUI-specific tokens (e.g., `palette.primary.main`, `palette.mode`) for the component library.

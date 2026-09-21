# Solace Design System

A design system for **Solace** — distilled from the *Solace Brand Book, Version 2.9 (© 2025 Solace, LLC.)*.

## Source materials
- `uploads/Solace Brand Book - FINAL-compressed (1).pdf` (76 pp.) — the single source of truth for this system. All colors, type, voice, and visual-language rules below are drawn from it.
- No codebase or Figma file was provided. UI-kit screens are faithful reconstructions of the *product surfaces shown in the brand book* (blog cards, webinar promos, datasheets, architecture diagrams) plus standard patterns for an event-driven integration platform. They are **recreations for prototyping**, not copies of production code. If you have access to the live product code or Figma, share it and these can be tightened.

## What is Solace?
Solace builds an **event-driven integration and streaming platform**. Per the brand book:

> **Mission** — *"To help enterprises become real-time and agent-ready by delivering a powerful, flexible, and intelligent event-driven integration and streaming platform that connects systems, applications, data, and AI — securely, seamlessly, and at scale."*
>
> **Vision** — *"To become the digital nervous system for the real-time, event-driven, agentic world."*

The flagship product is the **Solace Platform** (event broker / "PubSub+", Event Mesh, Event Portal). Customers are enterprise architects, developers, and integration teams in banking, capital markets, logistics/IoT, retail, and aviation. Positioning: *"the only event-driven integration and streaming platform built from the ground up for a real-time, agentic world."*

### Products / surfaces represented
1. **Marketing website** (solace.com) — hero banners, blog posts, webinar promos, resource cards.
2. **Solace Platform console** (PubSub+ Cloud) — broker/mesh management UI (developer-facing → **dark mode**).
3. **Collateral** — datasheets, whitepapers, PowerPoint decks, social ads (LinkedIn carousels, Google display).

---

## CONTENT FUNDAMENTALS
**Brand persona:** *"We are your friendly enterprise architect."* Deep technical mastery delivered with warmth — translating complex architecture into clear language "for everyone from top to bottom of the org chart."

**Six brand attributes** (use as a tone checklist): *passionate · approachable · reliable · we solve problems · we provide insights · we build bridges.*

**Voice & tone**
- **Person:** First-person plural **"we"**, addressing the reader as **"you / your."** Warm, peer-to-peer, never talking down. ("Liberate *your* data and connect *your* enterprise.")
- **Clarity over cleverness:** *"No jargon, no ego, no cutesy marketing — just understanding and support."* Explain, don't dazzle. Battle-tested, matter-of-fact confidence.
- **Casing:** Headlines in **Title Case** (the display serif is never set in all-caps). **Eyebrows / kickers** are **ALL CAPS** in Space Mono, letter-spaced (e.g. `BLOG POST`, `FROM DATA MESS TO DATA MESH`, `PROFESSIONAL SERVICES PACKS`). Body is sentence case.
- **Sentence style:** Active voice, concrete outcomes, real numbers when proving scale (*"118 billion messages a day … 1.3 million messages a second, 24 hours a day"*). Benefit-led headlines, often a promise + mechanism ("The Lightweight Way to Liberate your Data and Connect your Enterprise").
- **Emoji:** **Not used** in product/marketing voice. Don't add them.
- **Punctuation signature:** the wordmark and many headlines end with a **period** — "solace." — a small full-stop that signals calm finality. Mirrors the brand name's meaning (*solace* = comfort/steadiness).

**Vibe:** Calm competence. Real-time energy expressed through motion *imagery* (flowing data shapes), not loud copy. Friendly but enterprise-credible.

---

## VISUAL FOUNDATIONS

**Color** — Primary palette anchors everything: **Classic Green `#00C895`** (the signature), **Bright Green `#ABFF88`** (energy), **Deep Blue `#093B5F`** (primary text / structure), **Dark Blue `#03213B`** (deepest ground, dark mode). Secondary/accent set (Spring Green, Sky Blue, Sunrise Yellow, Orange `#FCA829`, Deep Green/teal `#009193`) adds vibrancy. Cool grays (`#F4F4F4 / #EAEAEA / #D6D6D6`) for surfaces & dividers.
- **Three modes:** **Light** (white bg, deep-blue text), **Bright** (green bg, deep-blue text), **Dark** (dark-blue bg, green text/accents). Pick exactly one color per role (text / background / secondary fill / accent outline) per composition. **Developer tools & code are always dark mode.**
- **Hard color rules** (from the book): never combine two+ secondary colors — each secondary pairs with a primary green. No all-pastel combos (pair pastels with primary palette). Never set **copy** in a secondary color. Avoid monotone art unless it's a green palette.
- **Gradients:** lime `#ABFF88→#00C895`, teal `#00C895→#009193`, ocean `#009193→#093B5F`. Used in brand shapes & hero washes, not behind body text.

**Typography** — Three families:
- **Display serif** — brand uses **ITC Souvenir** (logotype heritage) / **New Spirit** for H1–H2. Warm, rounded, high-character; **Light weight at large sizes, Title Case, never all-caps — "let it breathe."** → *Substituted here with **Newsreader** (Google Fonts) — see Caveats.*
- **Figtree** (exact, Google Fonts) — the workhorse sans. Everything that isn't a primary headline: H3–H5 (Bold), body (Regular/Bold), and all UI (buttons, labels, inputs). Pairs with the serif.
- **Space Mono** (exact, Google Fonts) — tertiary. Eyebrows, code snippets, compact UI metadata. **≤16px, ALL CAPS, tracked.** Never headlines.

**Brand shapes & patterns** — Core motif: **clusters of overlapping perfect circles** ("bubbles") plus a dominant **hero circle** — evoking **data and its movement**. **Solid-fill circles = data; outline circles = the Solace platform/solution.** They're scaled, cropped, and overlapped to tie a layout together, layered over a faint **isometric triangular mesh grid**, and used to frame line-icon illustrations. Circles are always **true circles — never wonky/organic blobs.** Real shape-driven illustrations ship in `assets/illustrations/`.

**Illustration** — Flat / isometric line illustrations built from a green or navy **hero circle** holding a 1px line icon, surrounded by **bubble clusters** (solid + outline circles) over a mesh grid. **Line work always Deep Blue/white, 1px.** **Shadows always Deep Blue**, bold/intentional, flat (not 3D). Two tiers: **Hero** (big, specific technical story) and **Thumbnail** (small, general). Real examples ship in `assets/illustrations/` — Migration, Reliability, Scalability, Security, Usability, Performance — each in light and dark variants.

**Photography** — *Purposeful. Consistent. Human.* **High-contrast black & white**; color introduced only via brand backgrounds or **duotone** overlays. Real workspaces, candid moments, diverse people, genuine emotion. No cliché "hacker" or glowing-code shots. Often masked inside a brand shape or full-bleed behind a shape overlay.

**Backgrounds** — White (light), Classic Green (bright), Dark Blue (dark). Brand shapes and mesh patterns layer on top. Hero washes use the brand gradients. No noisy textures behind text.

**Motion** — Calm and confident; quick fades and eases, **no bounces**. Default `200ms`, `cubic-bezier(0.4,0,0.2,1)`. Data-movement is the conceptual through-line, so subtle directional/flow animation fits; decorative looping does not.

**Interaction states** — Hover: darken the accent (green → `#00B385`) or lift elevation; links shift toward teal/bright-green. Press: deeper color + slight scale-down (`0.98`). Focus: 3px green ring (`--ring`).

**Borders, radii, elevation** — Soft, friendly corner radii (`--radius-sm 6px → xl 24px`; pills for tags/buttons). Hairline borders in cool gray (`#EAEAEA`). **Shadows are Deep-Blue-tinted**, not neutral gray — subtle for UI, or a hard flat offset (`8px 8px 0 Deep Blue`) for the illustration look. Cards: white surface, hairline border, soft deep-blue shadow, medium radius.

**Transparency & blur** — Reserved for imagery overlays / glass headers over photos; not used behind dense text. Duotone/overlay opacity for photo treatments.

---

## ICONOGRAPHY
The brand uses **simple line icons "with a splash of vivid green"** — a consistent thin-stroke (outline) style with a single Classic-Green accent to communicate complex ideas quickly. There is **no proprietary icon font** in the brand book, and icon source files were not provided.

- **Implementation:** we use **[Lucide](https://lucide.dev)** (consistent 2px outline icons) via CDN as the closest free match to Solace's line-icon style. **This is a flagged substitution** — if Solace has an official icon set, drop it into `assets/icons/` and update components. Treatment: stroke in `--text-strong` / `currentColor`, with the brand "splash" applied by coloring a key icon (or one stroke) in `--green-classic`.
- **Architecture-diagram icons** are their own system (Level-1 / Level-2 broker, mesh, API, protocol glyphs in Deep Blue + green). Not reproduced here; use the brand book's diagram library for real diagrams.
- **Emoji:** never used. **Unicode glyphs** are not used as UI icons. Use Lucide (or the official set) for all iconography.
- **Logo / "S" icon:** the wordmark "solace." and the circular/square "S" app-icon. See `assets/` and the `Logo` component.

---

## INDEX (manifest)
**Root**
- `styles.css` — global entry; `@import` manifest only. Link this one file.
- `readme.md` — this guide.
- `SKILL.md` — Agent-Skill front-matter for use in Claude Code.

**`tokens/`** — `colors.css` · `typography.css` · `spacing.css` · `effects.css` · `modes.css`

**`assets/`** — `illustrations/` (real Solace concept graphics, light+dark PNGs: Migration, Reliability, Scalability, Security, Usability, Performance) · logo wordmark/"S" via the `Logo` component. *(The architecture-diagram SVGs were dropped — they render solid black as `<img>` because their fills live in stripped embedded styles; re-export as flattened SVG or PNG to use them.)*

**`guidelines/`** — foundation specimen cards (Type, Colors, Spacing, Brand) rendered in the Design System tab.

**`components/`** — reusable React primitives (Button, IconButton, Input, Textarea, Select, Checkbox, Switch, Badge, Tag, Card, Avatar, Tabs, Eyebrow, Logo, CodeBlock, Stat, Alert). Each: `<Name>.jsx` + `<Name>.d.ts` + `<Name>.prompt.md`, with a per-group `*.card.html`.

**`ui_kits/`** — full-screen recreations:
- `website/` — solace.com marketing surfaces (hero, blog, resource cards).
- `platform/` — Solace Platform console (dark mode, broker/mesh management).

> See **Caveats** at the end of a turn for substitutions (display serif, icon set, brand-shape vectors, photography).

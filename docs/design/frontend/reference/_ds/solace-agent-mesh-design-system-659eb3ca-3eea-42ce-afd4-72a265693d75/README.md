# Solace Agent Mesh Design System

A design system derived from the [Solace Agent Mesh](https://github.com/SolaceLabs/solace-agent-mesh) open-source framework — an event-driven multi-agent AI platform built by Solace.

---

## Product context

**Solace Agent Mesh (SAM)** is an open-source framework for building AI applications where multiple specialized AI agents collaborate to solve complex problems. Agents communicate asynchronously over the Solace Event Broker using the Agent-to-Agent (A2A) protocol, and the framework wraps Google's Agent Development Kit (ADK) for LLM logic and tool execution.

The repo ships **two user-facing product surfaces**:

1. **Web UI (`client/webui/frontend`)** — the primary chat / agent mesh application. React + Vite + Tailwind v4 + Radix primitives (shadcn-style). Dense, information-rich, supports **light + dark themes** via CSS custom properties generated from palette objects. The app baseline font size is 14px and the typeface is **Rubik**.
2. **Config Portal (`config_portal/frontend`)** — a small init wizard launched by `sam init --gui` for first-time project setup. Remix + Tailwind with a simpler palette (`solace-blue`, `solace-green`, `solace-dark-green`) and **Inter** as the font. Step-based flow.

### Sources referenced

- GitHub repo: `SolaceLabs/solace-agent-mesh`
- Palette: `client/webui/frontend/src/lib/providers/themes/palettes/{solace,solaceDark,themePalette}.ts`
- Global CSS: `client/webui/frontend/src/lib/index.css`
- shadcn-style primitives: `client/webui/frontend/src/lib/components/ui/{button,card,badge,input,…}.tsx`
- Shell/nav: `client/webui/frontend/src/AppLayout.tsx`, `components/navigation/`, `components/header/Header.tsx`
- Logos: `client/webui/frontend/src/assets/solace-logo-{full,s}.svg`
- Provider icons: `client/webui/frontend/src/lib/components/models/assets/*.svg`
- Config portal tokens: `config_portal/frontend/tailwind.config.ts`
- Website / brand copy: https://solace.com

---

## CONTENT FUNDAMENTALS

### Voice
Clear, technical, confident — written for developers and platform engineers. The brand tone blends **enterprise-platform seriousness** with **open-source friendliness**: short declarative sentences about infrastructure, occasional second-person ("you can…"), and pragmatic imperatives in docs ("Create a directory", "Run the project"). Marketing copy leans toward the future and scale ("event-driven", "true scalability", "production deployment").

### Casing
- **Product name:** `Solace Agent Mesh` (title case, always three words). Short form: `SAM`.
- **Feature names:** Title Case (`Agent Mesh`, `Orchestrator`, `Dynamic Embeds`, `Config Portal`).
- **UI labels:** Sentence case (`Recent chats`, `Add agent`, `Create project`).
- **Buttons:** Sentence-case verbs (`Save`, `Delete`, `New chat`, `Add agent`).
- **Code / CLI:** lowercase, hyphenated (`sam init`, `sam run`, `solace-agent-mesh`).

### Pronouns
"You" addresses the developer/user. First person ("we"/"I") is avoided in the product and docs. Agents are referred to in the third person ("the Orchestrator agent", "peer agents").

### Emoji
- **Docs + README:** Emoji are used as section markers (🔑 Key Features, 🚀 Quick Start, ⚙️ System Requirements, 💻 Setup Steps). This is an **open-source-README convention**, not a UI convention.
- **Product UI:** Emoji are **not** used. The chat/web UI relies on Lucide icons.
- When in doubt in generated product assets: **do not use emoji**.

### Examples
- Hero line (repo): *"Open-source framework for building event driven multi-agent AI systems"*
- CTA pattern: *"Star ⭐️ this repo to stay updated"*
- Welcome empty state: *"Welcome to Solace Agent Mesh!"*
- Setup imperative: *"Create a directory for a new project"*
- Section header: *"Key Features"*, *"Quick Start (5 minutes)"*, *"Next Steps"*

### Vibe
Infrastructure for AI. Thoughtful, pragmatic, non-hype. The design system should feel like an **engineering tool**, not a consumer app — closer in spirit to Grafana, Datadog, or the AWS console than to Notion or Linear.

---

## VISUAL FOUNDATIONS

### Color
- **Brand color is green** (`#00C895` — "Solace green"), used sparingly: logo mark, primary accent highlights, focus rings, brand moments. It is **not** a primary button color.
- **Primary action color is deep teal/navy blue** (`#015B82`). All primary buttons, links, and active nav items use this.
- **Navy-black** (`#03223B`) is the signature dark surface — used for the nav sidebar on the light theme, for dark-theme backgrounds, and in the logo lockup.
- **Neutrals** are cool grays (secondary scale `#8790A0` down to `#F3F4F6`).
- **Semantic** colors are standard: info blue `#0591D3`, error red `#E94C4E`, warning orange `#FF8E2B`, success green `#009A80` (distinct from brand green — success is more muted).
- **Accents** (for chart series, tags, chips) include blue, indigo, teal, purple, magenta, coral, orange, sky, slate, red. Used across `accent-n0…n9` tokens.

### Type
- **Primary face: Rubik** (webfont, loaded from Google Fonts). Geometric, slightly rounded, works at dense UI sizes.
- **Secondary face: Inter** (used only in the config portal).
- **Mono: JetBrains Mono** as substitute (the repo doesn't pin a mono face; we've picked a close match — flag for review).
- Baseline body size is **14px**. Headings step up to 30–48px. Buttons are **semibold**.
- Letter-spacing is tight on large display sizes (-0.01 to -0.02em), default otherwise.

### Spacing
4px base grid (`space-1` = 4px through `space-20` = 80px). Page content sits inside a 32px gutter (`px-8`) and the header is a fixed 80px tall.

### Backgrounds
- **Mostly flat.** White (`#FFFFFF`) on the light theme, navy (`#03223B`) on dark. The sidebar is always navy regardless of theme (`darkSurface` palette).
- No full-bleed hero illustrations in the product; marketing site uses photography and a key architecture diagram.
- No repeating patterns, no grain, no gradients in product chrome. The only notable gradient is a **fade mask** on scrollable regions (`fade-both-mask`) that blends 40px edges to bg.
- Imagery (when used) is **cool** in tone — navy, teal, with green accents. Never warm.

### Animation
- Subtle and functional. Transitions on color/box-shadow (`transition-[color,box-shadow]`).
- Durations: fast 120ms, base 200ms, slow 320ms. Easing is standard `cubic-bezier(0.4, 0, 0.2, 1)`.
- Named animations in the codebase:
  - `pulse-generating` (1.5s opacity 1→0.4→1) — title generation state
  - `shimmer` — loading nodes
  - `halo-pulse` — processing nodes in flow chart (scale 1→1.03 + box-shadow)
  - `progressSlideIn` — timeline item entry (translateY(-8px) + opacity)
- No bounces, no spring physics, no elaborate choreography. Fades and small slides only.

### Hover states
- **Primary buttons:** background darkens one step (`primary-wMain` → `primary-w90` → `primary-w100` on active).
- **Ghost / secondary buttons:** background gains a light tint (`primary-w10`), text darkens.
- **Cards (clickable):** shadow deepens from `shadow-card` → `shadow-hover`.
- **Nav items:** left-border brand accent + background tint (`stateLayer-w10`).

### Press states
Buttons go to their `w100` color (darkest in the scale). No scale transform.

### Focus states
**1px solid `--brand-wMain` outline** (the signature green). This is the only place brand green consistently appears in interactive chrome: `*:focus-visible { outline: 1px solid var(--brand-wMain); }`.

### Borders
- Default border color: `secondary-w40` (#CFD3D9), 1px.
- Subtle: `secondary-w20` (#E7E9EC).
- On dark surfaces: `secondary-w70` (#536574).

### Shadows
Tight, low-opacity, tinted with navy (not black):
- `shadow-card`: `0 1px 4px rgba(3,34,59,0.10)` — resting cards
- `shadow-hover`: `0 4px 12px rgba(3,34,59,0.10)` — hovered cards
- `shadow-popover`, `shadow-modal` for overlays.

### Corner radii
- `radius: 0.5rem` (8px) is the base token. Buttons use `rounded-sm` (4px). Cards use `rounded-lg` (8px). Inputs are sharper — `rounded-xs` (2px). Badges are `rounded-lg`. Fully round (`9999px`) is reserved for avatars, scroll thumbs, status dots.

### Transparency & blur
Minimal. State-layer overlays use 10% or 20% opacity of the navy (`#03223B1a`, `#03223B33`). Modal backdrop is `rgba(0,0,0,0.5)`. No backdrop-filter blur.

### Card treatment
`.card-surface` pattern:
- 1px border `secondary-w20`
- `shadow-card` at rest
- White (`background-w10`) fill
- `rounded-lg` (8px)
- `p-6` internal padding (24px)
- On hover (if interactive): shadow deepens to `shadow-hover`, selected state gains `border-brand-wMain`.

### Layout rules
- App shell: vertical nav on the left (collapsible), main content area fills remaining width, fixed 80px header per page.
- Content max-width is not rigidly capped — the product is dense and uses available width (chat expands, tables fill).
- Dialogs center with modal backdrop.

### Iconography vibe
See ICONOGRAPHY section below. In short: **Lucide** line icons at stroke-width 2, 16–20px, in current text color.

---

## ICONOGRAPHY

The Web UI imports **`lucide-react`** for all UI icons — line icons, stroke-width 2, rounded caps. Sizes are typically `size-4` (16px) inside buttons and `size-5` (20px) for standalone. Color inherits from current text color (`currentColor`).

- **Chevrons/carets**: `ChevronRight`, `ChevronDown` for nav, breadcrumbs, disclosure.
- **Actions**: `Plus`, `Trash`, `Edit`, `Copy`, `Share`, `Download`, `Search`.
- **Navigation**: `MessageSquare` (chat), `FolderOpen` (projects), `Network` (agent mesh), `Sparkles` (prompts), `FileText` (artifacts), `Calendar` (scheduled tasks), `Settings`.
- **Status**: `Check`, `X`, `Info`, `AlertTriangle`, `Loader2`.

**Provider icons** live at `assets/icons/providers/` — proprietary SVGs for `openai`, `claude`, `bedrock`, `azure_openai`, `google_ai_studio`, `vertexai`, `ollama`. Use these verbatim anywhere an LLM provider is named.

**Logos**
- `assets/solace-logo-s.svg` — S-mark (32×32), used as compact/favicon.
- `assets/solace-logo-full.svg` — full "Solace" wordmark (99×25), used in the nav header.
- Both are rendered in solid brand green `#00C895`.

**Emoji:** Not used in UI. Used only in the README/docs as section markers.

**Unicode chars:** Not used as icons. The ellipsis `…`, bullet `•`, and arrow `→` are fine in copy but never as icon substitutes.

**No raster icons.** SVG only.

---

## Index of files

- `README.md` — this file
- `SKILL.md` — skill manifest for agentic use (Claude Code / Agent Skills compatible)
- `colors_and_type.css` — CSS custom properties for all design tokens + semantic typography classes
- `assets/` — logos and brand imagery
  - `solace-logo-full.svg`, `solace-logo-s.svg`
  - `icons/providers/*.svg` — LLM provider icons
- `preview/` — small HTML cards rendered as design-system specimens (Design System tab)
- `ui_kits/webui/` — high-fidelity React recreation of the main chat Web UI (sidebar, agent mesh grid, chat, agent detail drawer)

---

## Caveats

- **Mono font not pinned in repo.** We substitute **JetBrains Mono** as the closest match; swap for the team's preferred face if different.
- **Full marketing-site photography not mirrored here** — only product surfaces (chat app, config portal) are covered. The agent mesh architecture diagram lives at `docs/static/img/Solace_AI_Framework_With_Broker.png` in the source repo.
- Dark-theme specimens are included but the UI kit's interactive demo defaults to the light theme (matching the product's default).

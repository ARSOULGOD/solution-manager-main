# Palette and type provenance

Verification record for the design tokens carried in this folder. Written to satisfy `I2` — *"Do not state framework defaults, licence terms, or database semantics from memory … WebFetch of specific URLs works — use it."*

**Verified 2026-08-19.** Method: `curl` of the raw upstream files, then a case-insensitive set comparison of every six-digit hex literal. Commands are reproducible below.

---

## 1. Colour — VERIFIED against Solace product source

`_ds/solace-agent-mesh-design-system-*/colors_and_type.css` declares in its header that it was *"Extracted from `client/webui/frontend/src/lib/providers/themes/palettes/`"*. Solace Agent Mesh is public, so the claim is checkable rather than trusted.

**Upstream:** `https://raw.githubusercontent.com/SolaceLabs/solace-agent-mesh/main/client/webui/frontend/src/lib/providers/themes/palettes/solace.ts`
(directory confirmed to exist and to contain `index.ts`, `solace.ts`, `solaceDark.ts`, `themePalette.ts`)

### Result

| Measure | Value |
|---|---|
| Unique hex literals in the local DS file | **58** |
| Unique hex literals upstream | 94 |
| Local values **absent** upstream | **0** |

**Every colour in the local design system appears in upstream product source.** That includes the two blocks most likely to be invented — the `--accent-*` chart/tag ramp (`#3C69E1`, `#3A4880`, `#009193`, `#7841A8`, `#CB1AA5`, `#F66651`, `#FCA829`, `#7CD3F6`, `#86939E`, `#DA162D`) and the `--darkSurface-*` navigation slice (`#03223B`, `#021B2F`, `#F5F5F5`, `#ACBAC2`, `#00C895`, `#536574`). Both are upstream. The local file is a faithful re-expression of the product palette as CSS custom properties, not a design-authored palette.

Spot values, for the record:

| Token | Local | Upstream | Match |
|---|---|---|---|
| `brand.wMain` | `#00C895` | `#00C895` | ✅ |
| `brand.w100` | `#00AD93` | `#00AD93` | ✅ |
| `primary.wMain` | `#015B82` | `#015B82` | ✅ |
| `primary.w100` | `#01374E` | `#01374E` | ✅ |
| `primary.text.wMain` | `#273749` | `#273749` | ✅ |
| `background.wMain` | `#03223B` | `#03223B` | ✅ |
| `background.w20` | `#F7F8F9` | `#F7F8F9` | ✅ |
| `secondary.wMain` | `#8790A0` | `#8790A0` | ✅ |
| `info.wMain` | `#0591D3` | `#0591D3` | ✅ |
| `error.wMain` | `#E94C4E` | `#E94C4E` | ✅ |
| `warning.wMain` | `#FF8E2B` | `#FF8E2B` | ✅ |
| `success.wMain` | `#009A80` | `#009A80` | ✅ |

⚠️ **Comparison must be case-insensitive.** Measured, not recalled — upstream has **95 case-sensitive** distinct hex literals against **94 case-insensitive**, so there is **exactly one** collision pair: `#00AD93` and `#00ad93` both appear. Separately, **four** upstream values are spelled lowercase *throughout* — `#0892ce`, `#33b8e0`, `#acbac2`, `#f5f5f5` — and a fifth, `#00ad93`, appears in **both** cases, since it is the collision pair above rather than a lowercase-only value. The local design-system file spells three of those five uppercase: `#00AD93`, `#ACBAC2`, `#F5F5F5`. Of those, `#00AD93` matches an upstream spelling, so a case-sensitive diff **falsely flags exactly two** — `#ACBAC2` and `#F5F5F5`. That is why the comparison below lower-cases both sides.

> An earlier version of this section claimed `#00C895`/`#00c895` plus *"eight other such pairs"*. Both claims were false: lowercase `#00c895` never occurs upstream, and there is one pair, not nine. That was a recalled count in the one document whose purpose is to hold measured ones — the `L17` failure mode. The figures above come from the command below; re-run it rather than trusting this paragraph.

### Reproducing it

Run from the repo root:

```bash
BASE=https://raw.githubusercontent.com/SolaceLabs/solace-agent-mesh/main/client/webui/frontend/src/lib
curl -sfL "$BASE/providers/themes/palettes/solace.ts" -o /tmp/solace.ts
DS=$(ls docs/design/frontend/reference/_ds/solace-agent-mesh-design-system-*/colors_and_type.css)

grep -ohE '#[0-9A-Fa-f]{6}' "$DS"          | tr 'A-F' 'a-f' | sort -u > /tmp/ds.hex
grep -ohE '#[0-9A-Fa-f]{6}' /tmp/solace.ts | tr 'A-F' 'a-f' | sort -u > /tmp/up.hex

# An empty input would make the diff below pass for the wrong reason. Refuse that.
[ -s /tmp/ds.hex ] && [ -s /tmp/up.hex ] || { echo "ABORT: an input is empty"; exit 1; }
echo "local=$(wc -l < /tmp/ds.hex)  upstream=$(wc -l < /tmp/up.hex)"   # expect local=58 upstream=94

comm -23 /tmp/ds.hex /tmp/up.hex   # MUST print nothing
```

**Pass condition:** the counts print `local=58 upstream=94`, **and** `comm` prints nothing. Any line printed is a local colour with no upstream basis and must be investigated before it enters the theme.

To reproduce the case-collision figures quoted above:

```bash
python3 - <<'EOF'
import re, collections
hx = re.findall(r'#[0-9A-Fa-f]{6}\b', open('/tmp/solace.ts').read())
ci = collections.defaultdict(set)
for h in hx: ci[h.lower()].add(h)
pairs = {k: v for k, v in ci.items() if len(v) > 1}
print('case-sensitive uniques:', len(set(hx)), '| case-insensitive:', len(ci))
print('collision pairs:', len(pairs), pairs)
print('lowercase-spelled:', sorted(h for h in set(hx) if h == h.lower() and any(c.isalpha() for c in h[1:])))
EOF
```

Expect `95 | 94`, one pair (`#00ad93`), and five lowercase-spelled values.

⚠️ **Both halves of that pass condition are load-bearing, and the count check is not decoration.** The first version of this command assigned the glob to `DS` and left the expansion unquoted at point of use. That works in `bash` but not in `zsh`, which does not glob unquoted parameter expansions — so `grep` silently read **no file**, `/tmp/ds.hex` came out empty, and `comm` printed nothing. The command reported a clean pass while comparing nothing at all. `[[L7]]`: a check that cannot fail is not a check. `ls` resolves the glob at assignment time, and the `-s` test makes the vacuous pass impossible.

---

## 2. Type family — VERIFIED, but narrower than the local file

**Upstream:** `https://raw.githubusercontent.com/SolaceLabs/solace-agent-mesh/main/client/webui/frontend/src/lib/index.css`

Line 123: `font-family: "Rubik", sans-serif;`

So **Rubik is the Solace Agent Mesh product font.** That much is verified.

⚠️ **The local file's stack is wider than upstream's.** `colors_and_type.css` declares:

```
--font-sans: "Rubik", "Inter", system-ui, -apple-system, "Segoe UI", sans-serif;
```

Upstream is only `"Rubik", sans-serif`. The `Inter` / `system-ui` / `-apple-system` / `Segoe UI` tail is a **design-authored fallback chain**, not an upstream value. It is a reasonable fallback chain; it is not a Solace fact. Same for `--font-mono: "JetBrains Mono", …`, which has no upstream basis in either file fetched.

---

## 3. NOT verified — everything else in the local token file

`solace.ts` is **palette-only**: 183 lines, zero occurrences of `fontFamily`, `fontSize`, `spacing`, `borderRadius`, `boxShadow`, `shadow` or `transition`. Therefore the following, all present in `colors_and_type.css`, are **not** established by this verification:

- the numeric type scale (`--fs-*`, `--lh-*`) and weight scale (`--fw-*`)
- the spacing scale (`--space-1` … `--space-20`)
- the radius scale (`--radius-*`)
- the shadow set (`--shadow-xs` … `--shadow-modal`)
- the motion set (`--ease-*`, `--dur-*`)
- the semantic alias layer (`--fg1`, `--bg2`, `--border-default`, …) and the `.text-*` classes

These are **design-authored structure**. They are internally coherent and safe to build on, but they must not be described as verified, and a reviewer may change them without contradicting any Solace source.

---

## 4. NOT verified — the brand book

`_ds/solace-brand-book-design-system-*/tokens/colors.css` declares itself *"Solace Brand Book v2.9, §Identity / Color"*. **That document has not been obtained.** The claim is therefore unverified, and the file disagrees with the verified product palette on almost everything except the signature green:

| Role | Brand book (unverified) | Agent Mesh (verified) |
|---|---|---|
| Solace green | `#00C895` | `#00C895` — **agree** |
| Deep navy | `#093B5F` | `#015B82` |
| Darkest navy | `#03213B` | `#03223B` |
| Success | `#00C895` | `#009A80` |
| Info | `#009193` | `#0591D3` |
| Warning | `#FCA829` | `#FF8E2B` |
| Danger | `#E5484D` | `#E94C4E` |
| Body text | `#28333B` | `#273749` |

The agreement on `#00C895` is meaningful — that is the value a brand book most authoritatively owns, and two independently-authored files landing on it is corroboration.

### What the console does about it

Per **`FE-D5`** (amended 2026-08-19), the **verified product palette governs UI semantics** and the brand book governs **identity** (logo, marketing gradients). Reasons, in order of weight:

1. The verified palette has provenance; the brand book file does not.
2. The approved mockup links **only** `colors_and_type.css` — `Solution Manager v2.html` has no `<link>` to the brand book. Adopting brand-book values would make the built console diverge from what was reviewed and approved.
3. A brand book is an identity palette. It carries gradients and the rule *"never combine two secondaries"*; it does not carry state-layer overlays, a dark-surface navigation slice, or a semantic ramp — all of which a console needs and the product palette has.

### ⚠️ Still open, and it is the designer's call, not this document's

Open item 19b in [`../../DECISIONS.md`](../../DECISIONS.md) assigns the visual reference to **the designer or Figma**. This record does not close it and must not be read as closing it. Three questions remain:

1. Confirm that the product palette is the right basis for a Solace console's UI, and that the brand book governs identity only.
2. Confirm the brand book file's own provenance — is `#093B5F` really the Brand Book v2.9 navy?
3. Resolve the untokenised purple. `#f2ecfb` / `#6b3fa0` have no basis in either palette and drive four things: the PROD environment chip, the IdP-group chip, the Designer's layer-origin badge and the RBAC group-principal icon. Either adopt the verified `accent-purple` pair (accepting a contrast drop from 6.39:1 to 5.80:1, both AA) or promote the mockup's values to named tokens. See [`STATE-VOCABULARY.md`](STATE-VOCABULARY.md).

What has changed is the *shape* of the question. It is no longer "please re-send a missing reference"; it is two narrow confirmations with specific hex values attached. Until they are answered the palette is **well-provenanced but not designer-confirmed**, and `FE-D5` says so.

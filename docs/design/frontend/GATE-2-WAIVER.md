# Waiver — `design.md` §24 gate 2 (theme tokens)

**Status: DRAFT — UNSIGNED.** No frontend theme work may proceed until this is signed.

| | |
|---|---|
| **Gate** | [`design.md`](design.md) §24, precondition (2) |
| **Gate text** | *"open item 19 tokens **or** an explicit 'walk with unverified interim theme' waiver"* |
| **Arm taken** | the **waiver** arm |
| **Blocks if unsigned** | `FE-D26` theme implementation, and every plan that depends on it |
| **Decision owner** | Parikshit Tyagi |
| **Signed** | *(unsigned)* |

---

## 1. Why a waiver rather than "gate met"

The palette is **verified**: all 58 unique hex values in the Solace Agent Mesh token set appear in `SolaceLabs/solace-agent-mesh` product source when compared **case-insensitively** — upstream spells five values lowercase and carries one internal case collision, so a case-sensitive diff false-flags two faithful entries. The font family `Rubik` is verified at that product's `lib/index.css:123`. Method and reproduction command: [`reference/PALETTE-PROVENANCE.md`](reference/PALETTE-PROVENANCE.md).

That is much stronger than the *"unverified interim theme"* this waiver arm was written to cover. It is still not the **tokens** arm, because open item 19b names the authority explicitly — it *"assigns the reference to **the designer or Figma**"* — and a product repository is neither.

The distinction being preserved is provenance versus authority. Provenance now exists. Authority does not. Cross-cutting lesson `L8` is the rule that forbids collapsing the two: *"status transitions are caused by **ratification**, never by the existence of a proposal."* A gate that a proposal can satisfy by asserting itself is not a gate. So the honest instrument is this waiver, and open item 19b stays open.

## 2. What is being waived

Proceeding with `FE-D5`'s theme — palette, font family, and the design-authored scales around them — **before the designer has confirmed**:

1. that the Solace Agent Mesh **product** palette is the right basis for a Solace console's UI, with the Solace Brand Book governing identity (logo, marketing gradients) only;
2. that the brand book file's own claim to be *"Solace Brand Book v2.9"* holds — specifically whether `#093B5F` is really its navy, given the verified product navy is `#015B82`;
3. how to resolve the mockup's **untokenised purple**, `#f2ecfb` / `#6b3fa0`, which has no basis in either palette. It is not one chip: it drives the **PROD environment chip**, the **IdP-group chip** (`admin_iam.jsx:11`), the Designer's **layer-origin badge** (`theme.css:552`) and the RBAC **group-principal icon** (`theme.css:597`). Two options — adopt the verified `accent-purple` pair (`#7841A8` / `#F2ECF6`), which **lowers** contrast from 6.39:1 to 5.80:1 (both clear AA), or promote the mockup's two values to explicit named tokens and keep the approved paint exactly.

## 3. What is **not** waived

- **Visual-regression baselines.** Open item 19b continues to block these. Do not capture approval-grade baselines from an unconfirmed palette; they would have to be recaptured.
- **Any claim that the palette is brand-final.** `design.md` §8.1 states outright that a theme PR describing it as designer-signed-off is a defect.
- **The non-colour scales.** Type sizes, spacing, radii, shadows and motion are design-authored, not verified — upstream `solace.ts` is palette-only. They must not be labelled verified anywhere.
- **Gates (3) and (4).** The Console OpenAPI still does not exist, and `SM-D*` is still unratified. This waiver covers gate 2 only.

## 4. Exposure if the designer disagrees

Bounded, and bounded **by design** rather than by luck.

`FE-D5` forbids hex, px and font sizes in feature code — every value resolves through the MUI theme. A palette reversal therefore edits two files (`shared/theme/tokens.ts`, `shared/theme/theme.ts`) and touches no feature code. Component tests assert against theme tokens, not literals, so they survive a swap.

| If the designer says… | Cost |
|---|---|
| "Product palette is right" | zero — the waiver simply converts to a signed token set and 19b closes |
| "Use brand-book values instead" | two theme files re-pointed; ~8 semantic values change. No feature-code churn |
| "Neither — here is Figma" | same two files; plus the type and spacing scales may change, which can affect layout density |
| "Keep the exact mockup purple" | two tokens added; the §2.3 deviation is reverted across all four consumers |

The genuinely expensive artifacts — visual-regression baselines and screenshot-based sign-off — are explicitly excluded in §3, so the reversal cost stays at "re-point two files."

## 5. Alternatives considered

**Wait for the designer.** Cleanest bookkeeping; blocks all theme and component work for an unknown period, on a question that is now narrow enough to answer in a short conversation. Rejected as disproportionate given §4's bounded exposure.

**Build on MUI defaults, theme later.** Avoids the question entirely, but produces screens nobody can review against the approved mockup — and reviewability is the point of having a mockup. It also defers the palette decision to the moment it is most expensive, once components exist.

**Declare the gate met on the verification.** Rejected — `L8`, and it is the specific defect an independent review caught in the first draft of this work.

## 6. Conditions attached to this waiver

If signed, the following hold:

1. **Open item 19b stays open** until the designer answers, with the three questions in §2 recorded against it.
2. **The three questions go to the designer** rather than waiting to be raised by the next reviewer.
3. **Every artifact built under this waiver labels the palette** as verified-but-unconfirmed. `tokens.ts` carries the provenance record path in its docstring.
4. **No visual-regression baseline** is captured as approval-grade.
5. **The purple deviation is recorded** in the implementing plan's review section — naming all four affected consumers and the contrast change — not applied silently.

---

## Signature

> I accept the exposure described in §4 and the conditions in §6, and authorise theme and
> component work to proceed on the verified-but-unconfirmed palette.

| | |
|---|---|
| **Name** | |
| **Date** | |
| **Notes / conditions added** | |

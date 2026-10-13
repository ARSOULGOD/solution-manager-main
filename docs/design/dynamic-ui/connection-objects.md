# Connection Objects — a reusable catalog that populates Designer fields

**Status: PROPOSED.** Adds `UI-D27`, `UI-D28` and `UI-D29`. Ratifies with the `UI-D*` family,
in the order `CLAUDE.md` sets out. **Requires an amendment to LOCKED `D18`** (§9.1) which this
document flags and does not resolve (`I3`, `I4`).

**Date:** 2026-09-23

**Approved with amendments** by the project owner on 2026-09-23. The eight open decisions in §9
carry that ruling; the three implementation-rule corrections are folded into §4, §5 and §6 and
are marked ⚑ where they reverse an earlier draft.

---

## 1. What this is

Operators configure the same broker, and the same remote file server, into instance after
instance by hand. This design gives SM a **Connection Objects catalog**: configure an endpoint
once, reference it from many solution instances, and let the Solution Package declare — generically —
which Designer fields that connection fills in.

Two connection types ship first: **Solace broker** and **remote server** (basic auth or private-key
auth). A third type is a data change, not a code change.

This closes [`design.md` §9 item 10](design.md), which has been an explicitly open gap since
`UI-D22`:

> ⚠️ **Catalog selection that populates fields** — File Mesh's `SELECT_BROKER` fills
> `solace/java/msg-vpn`, `client-username`, `client-password` and `host` from a chosen catalog
> entry; **42 rows** in `COMP_SINK_SOLACE` are broker attributes with no config path of their own.
> The contract can declare the selector (`optionsSource`) and the target fields, but **not the
> population**. Needs a decision: a `valueFrom: {entity, attribute}` on the target field, a
> `populates:` map on the selector, or treating it as an SM platform feature outside the package
> contract.

Of the three candidates the gap names, this design takes the **first**: `valueFrom` on the target
field. §3.3 says why.

### What it is not

- **Not a Target.** A Target is a host **SM** reaches into; a Connection Object is an endpoint the
  **MI** reaches out to. §2.4.
- **Not Test Connection.** Deferred by instruction, but the contract surface for it already exists
  and is used unchanged when backend support lands. §7.
- **Not a secret backend.** This design depends on one and does not build one. §9.4.
- **Not connection-specific Designer code.** `project()` learns exactly one new generic rule and
  contains no knowledge of what a broker is. §5.

---

## 2. The entity model

### 2.1 Connection Type Descriptor

A descriptor is **SM-owned data** — never package-supplied — that declares a connection type's
attributes **using the existing `$defs/field` vocabulary from `UI-D14`**.

That reuse is the load-bearing decision of this whole design. Because a descriptor is a field list,
the Connection Objects form is rendered by the **same dynamic form renderer as the Designer**, and
adding a connection type is a data change with no new UI code. Requirement 4 ("keep the mechanism
generic so future connection types need no connection-specific logic") then holds *structurally*
rather than by discipline.

Descriptors live at `contracts/connection-types/<type>.json`.

```json
{
  "connectionType": "solace-broker",
  "label": "Solace broker",
  "descriptorVersion": 1,
  "attributes": [
    { "key": "host", "type": "string", "required": true,
      "ui": { "label": "Host", "helperText": "tcps://host", "order": 1, "listColumn": true } },
    { "key": "port", "type": "int", "default": 55555,
      "ui": { "label": "Port", "order": 2 } },
    { "key": "vpn", "type": "string", "default": "default",
      "ui": { "label": "Message VPN", "order": 3, "listColumn": true } },
    { "key": "username", "type": "string",
      "ui": { "label": "Client username", "order": 4 } },
    { "key": "password", "type": "secret", "secret": true,
      "ui": { "label": "Password", "order": 5 } }
  ]
}
```

```json
{
  "connectionType": "remote-server",
  "label": "Remote server",
  "descriptorVersion": 1,
  "attributes": [
    { "key": "host", "type": "string", "required": true,
      "ui": { "label": "Host", "order": 1, "listColumn": true } },
    { "key": "port", "type": "int", "default": 22, "ui": { "label": "Port", "order": 2 } },
    { "key": "username", "type": "string", "required": true,
      "ui": { "label": "Username", "order": 3, "listColumn": true } },

    { "key": "authMethod", "type": "enum", "required": true, "discriminator": true,
      "options": ["basic", "private-key"], "default": "basic",
      "ui": { "label": "Authentication", "order": 4, "listColumn": true } },

    { "key": "password", "type": "secret", "secret": true,
      "when": { "field": "authMethod", "eq": "basic" },
      "ui": { "label": "Password", "order": 5 } },

    { "key": "privateKey", "type": "secret", "secret": true,
      "when": { "field": "authMethod", "eq": "private-key" },
      "ui": { "label": "Private key", "helperText": "PEM. Stored in the secret backend, never displayed.", "order": 5 } },
    { "key": "passphrase", "type": "secret", "secret": true,
      "when": { "field": "authMethod", "eq": "private-key" },
      "ui": { "label": "Key passphrase", "helperText": "Optional.", "order": 6 } }
  ]
}
```

`authMethod` is an ordinary `discriminator` with `when:` predicates, so `UI-D4` retention applies
for free: switching basic ↔ private-key and back does not lose what was typed.

`ui.listColumn` is the one addition to `$defs/ui`, and it is descriptor-only — it tells the
catalog list which attributes to show as columns, so the list is generated per type rather than
hardcoded.

### 2.2 The stored object

```json
{
  "id": "conn-7",
  "name": "prod-broker-01",
  "connectionType": "solace-broker",
  "descriptorVersion": 1,
  "scope":  { "org": "acme", "subOrg": "payments", "env": "PROD" },
  "description": "Payments production broker, Frankfurt.",
  "attributes": { "host": "tcps://broker.acme.internal", "port": 55443,
                  "vpn": "payments-prod", "username": "svc-cache" },
  "secretRefs":  { "password": "sm-secret://acme/payments/prod-broker-01/password" },
  "status": "active",
  "revision": 4,
  "updatedAt": "2026-09-23T10:14:00Z",
  "updatedBy": "ops@acme.example"
}
```

Four properties carry weight:

- **`attributes` and `secretRefs` are separate maps.** A secret-typed attribute is *structurally
  incapable* of landing in `attributes`, so it cannot reach config tables, version history, diffs,
  exports or the browser. `I6` becomes a shape rather than a rule to remember.
- **`scope` is server-derived, never client-supplied** (`I5`). It filters every dropdown, so a DEV
  instance cannot bind a PROD broker.
- **`revision`** is the change-detection handle. §6 depends on it entirely.
- **`status: active | inactive`** — deactivation is how a connection leaves the dropdowns without
  breaking instances that reference it. §9.1 rules that deactivation is governed by
  `connection.edit`, not a separate verb.

### 2.3 Scope

**One exact `org / sub-org / environment` triple per connection** (ruling, §9.6). A connection is
offered only to instances whose declared triple matches exactly. Inherited or shared scope is a
later addition and needs an explicit authorization model first; starting exact makes PROD isolation
and dropdown behaviour unambiguous, and widening a scope model later is compatible in a way
narrowing one is not.

The hierarchy vocabulary is the finalized **organization → sub-organization → environment**
(`D20`, `FE-D51`, `FE-D53`). The unreconciled `organization`/`tenant` split in `contracts/events/`
(`CLAUDE.md` item 38) is a separate problem and is not resolved here (§9.8).

### 2.4 Why this is not a Target

| | Target | Connection Object |
|---|---|---|
| Direction | SM reaches **in** | MI reaches **out** |
| Credential owner | SM's own (`control_locus`, `dp-remote`) | the MI's, written into its config file |
| Blast radius of a leak | SM's control over a host | one integration's endpoint access |
| Already in `D18` | `target.view/create/use` | nothing |

The same physical host may legitimately be both rows. Conflating them would put two credential
owners and two blast radii in one record, and would make `target.use` authorize something it was
never ratified to authorize.

---

## 3. Contract changes

Three additions to [`contracts/solution-package.schema.json`](../../../contracts/solution-package.schema.json).
That file is **single-writer** (`CLAUDE.md`): these land as one deliberate reviewed change,
together with the validator copies, the `project()` engine, the fixtures and the negative cases.

### 3.1 `optionsSource` gains `connectionType`

This is how a dropdown declares *which kind* of connection it wants — requirement 2.

```json
"optionsSource": {
  "type": "object",
  "required": ["kind", "entity"],
  "additionalProperties": false,
  "description": "UI-D22, amended by UI-D27 — a package names an SM-owned entity. It can never supply a URL.",
  "properties": {
    "kind":   { "enum": ["sm-catalog"] },
    "entity": { "enum": ["connection", "environment", "instance", "metric"] },
    "connectionType": { "enum": ["solace-broker", "remote-server"] }
  },
  "allOf": [{
    "description": "a connection selector must say which connection type it wants",
    "if":   { "properties": { "entity": { "const": "connection" } }, "required": ["entity"] },
    "then": { "required": ["connectionType"] }
  }]
}
```

**`entity: "broker"` is retired** (ruling, §9.3). It becomes
`entity: "connection", connectionType: "solace-broker"`. Retirement is safe because nothing
consumes `optionsSource` yet — there are zero references in `frontend/src` — so the cost is fixture
edits only, and no alias is warranted. This supersedes part of `UI-D22`; per `I3` it is recorded as
`UI-D27`, not as an edit to `UI-D22`.

### 3.2 New `$defs/valueFrom`

```json
"valueFrom": {
  "type": "object",
  "required": ["ref", "attribute"],
  "additionalProperties": false,
  "description": "UI-D28 — this field takes its value from the connection chosen in `ref`.",
  "properties": {
    "ref":       { "type": "string", "description": "field.key of a connection selector in the SAME component" },
    "attribute": { "type": "string", "description": "attribute key in that selector's connectionType descriptor" }
  }
}
```

Referenced from `$defs/field.properties.valueFrom`.

**`ref` is same-component only** (ruling, §9.7). Several File Mesh components may each select the
same Connection Object independently; cross-component field references would make package
composition materially harder to validate, for no capability gained.

### 3.3 Why `valueFrom` on the target and not `populates` on the selector

The gap named three candidates. `valueFrom` wins on four counts:

1. **The mapping lives next to the field it governs.** With a `populates:` map, a field can be
   silently overwritten by a selector declared in a different section of the same document, and
   reading a field tells you nothing about where its value comes from.
2. **It survives per-field versioning.** `UI-D25` lets a field carry its own `version` array; a
   field that exists only in package version 7 carries its own binding. A central `populates:` map
   would need its own parallel version filtering.
3. **Absence is the default.** A field with no `valueFrom` is simply never populated — no
   registration, no opt-out.
4. **Gate 13 is local.** Type-compatibility between attribute and field is checkable by looking at
   one field and one descriptor.

The third candidate — treating population as an SM platform feature outside the contract — is
rejected outright: it is precisely the connection-specific Designer logic requirement 4 forbids,
and it moves knowledge in the direction `UI-D14` exists to prevent.

### 3.4 A package using it

The whole of requirements 2 and 3 for the Cache solution:

```json
{
  "key": "solaceBroker",
  "type": "enum",
  "virtual": true,
  "required": true,
  "optionsSource": { "kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker" },
  "when": { "field": "solace.enabled", "eq": true },
  "ui": { "section": "Broker", "order": 6, "label": "Broker", "promoteToDeclare": true }
},
{ "key": "solace.host", "type": "string", "required": true,
  "valueFrom": { "ref": "solaceBroker", "attribute": "host" },
  "ui": { "section": "Broker", "order": 6, "label": "Host" } },
{ "key": "solace.vpn", "type": "string", "default": "default",
  "valueFrom": { "ref": "solaceBroker", "attribute": "vpn" },
  "ui": { "section": "Broker", "order": 6, "label": "Message VPN" } },
{ "key": "solace.port", "type": "int", "default": 55555,
  "valueFrom": { "ref": "solaceBroker", "attribute": "port" },
  "ui": { "section": "Broker", "order": 6, "label": "Port" } },
{ "key": "solace.username", "type": "string",
  "valueFrom": { "ref": "solaceBroker", "attribute": "username" },
  "ui": { "section": "Broker", "order": 6, "label": "Username" } },
{ "key": "solace.password", "type": "secret", "secret": true,
  "valueFrom": { "ref": "solaceBroker", "attribute": "password" },
  "ui": { "section": "Broker", "order": 6, "label": "Password" } }
```

The selector is `virtual: true` (`UI-D24`) — it drives the form and persists in the draft, but is
never written to the MI's config file. Correct, because the MI has no "which broker object"
property; it has `solace.host`.

`ui.promoteToDeclare` is the second addition to `$defs/ui`: it marks a field the Declare Instance
dialog surfaces early. One declaration, two render sites, no duplicate vocabulary.

### 3.5 Validation gates

Added to `contracts/run-checks.sh`:

| Gate | Rule |
|---|---|
| 15 | `valueFrom.ref` resolves to a field **in the same component** that declares `optionsSource` with `entity: "connection"` |
| 16 | `valueFrom.attribute` exists in that `connectionType`'s descriptor, and its type is assignable to the target field's type |
| 17 | a `secret: true` field binds **only** to a `secret` attribute, and a non-secret field **never** binds to a secret attribute (`I6`) |
| 18 | ⚑ two fields binding the same `(ref, attribute)` pair **warn**, never fail — see below |
| 19 | a `required` field carrying `valueFrom` must have its selector `required` too (a required field cannot depend on an optional choice) |

⚠️ **Numbered from 15, not 12.** `contracts/validation/validate-package.py` already issues gate ids through
`gate14` — `gate12` is the unreachable-readonly-discriminator rule, `gate13` the contradictory-secret rule and
`gate14` the undeclared-field-version rule. An earlier draft of this section numbered these 12–16 and would have
given three live ids a second, different meaning. Gate ids follow the same discipline as decision ids (`I3`): a
citation must never shift meaning, so new rules take the next free numbers.

⚑ **Gate 18 was a hard error in the first draft; that was too restrictive.** Two MI configuration
properties may legitimately need the same connection attribute — a host that must appear in both a
data-plane and a management-plane property is an ordinary case, not a copy-paste mistake. It warns,
because it *is* also what a copy-paste mistake looks like.

Gate 11 is **unchanged**. ⚑ The first draft proposed extending it to forbid `default` alongside
`valueFrom`; that was wrong, and §4 replaces it with an explicit precedence rule.

---

## 4. Value precedence ⚑

A field's effective value is resolved in **exactly this order**, highest first:

| | Source | Condition |
|---|---|---|
| 1 | **Override** | the operator explicitly detached this field (§6.2) |
| 2 | **Selected connection** | `valueFrom` resolves against a chosen connection |
| 3 | **Declared `default`** | no connection chosen **or** the chosen connection leaves that attribute unset — and the field is **optional** |
| 4 | **Unset** | same conditions, but the field is **required** — a validation error, never a silent default |

Row 3's second clause is not a corner case: `solace-broker.username` is optional in the descriptor, so a broker
configured without one must fall through to the field's own default rather than write an empty string into the MI's
config file. A selected connection therefore **participates in** precedence; it does not short-circuit it.

⚑ This reverses the first draft, which forbade `default` and `valueFrom` on the same field. A
default is a legitimate fallback before a selection exists — `solace.port`'s `55555` is useful the
moment the form opens. What must not happen is a *required* connection-backed field quietly
resolving to a default and shipping a config file nobody chose: row 4 makes that a blocked save
rather than a silent success.

Precedence is evaluated in `project()` and is the same in the Designer, the Declare dialog, the
save payload and the rendered config file. There is one implementation of it.

---

## 5. Data flow: selection to population

```
1. A field declares optionsSource{entity:"connection", connectionType:X}.
   The renderer requests the catalog:
       GET /api/connections?type=X&status=active
   Scope is applied SERVER-side from the instance's org/subOrg/env (I5) —
   never sent by the client, never trusted from it.

2. The operator picks conn-7. The draft stores ONLY the reference:
       draft["solaceBroker"] = "conn-7"

3. project() resolves every field whose valueFrom.ref === "solaceBroker",
   applying §4 precedence:
       solace.host     -> connections["conn-7"].attributes.host
       solace.vpn      -> ...vpn
       solace.port     -> ...port
       solace.password -> BOUND. Value never sent to the browser; the
                          ViewField reports "set", exactly as project()'s
                          existing obligation 7 already does for secrets.

4. ViewField gains  origin: "connection" | "override" | "literal" | "default".
   A connection-origin field renders read-only with a provenance chip:
       [ from prod-broker-01 ]  [Override]

5. The save payload stores the REFERENCE, not the resolved value:
       { "solaceBroker":   "conn-7",
         "solace.host":    { "$from": "conn-7#host" },
         "solace.port":    55556 }          <- an override, a literal
   plus the effective-configuration fingerprint of §6.

6. Render/apply resolves references server-side into the MI's config file.
   Secrets resolve from the secret backend at render time and at no other
   moment (I6).
```

**Step 3 is the whole of requirement 4.** `project()` learns exactly one rule — *a field with
`valueFrom` takes its value from the referenced selector's chosen catalog entry, subject to §4
precedence* — and holds no knowledge of what a Solace broker is. A future Kafka or JDBC connection
type needs a descriptor and nothing else: no `project()` change, no React change, no new gate.

### 5.1 Declare-time selection does not apply anything

Ruling, and a boundary worth stating plainly: **selecting a connection in the Declare Instance
dialog saves the selection and nothing more.** It writes no config, touches no MI and triggers no
apply. The Designer then shows the resolved values and any overrides, and the ordinary
`config.apply` action is what delivers them.

This preserves the existing Declare flow — [`declareForm.ts`](../../../frontend/src/features/inventory/instances/declareForm.ts)'s
identity-only posture is bent, not broken, because a connection *reference* is a declared intent,
not an observed fact — while still giving the operator the early selection requirement 2 asks for.

---

## 6. Change detection ⚑

### 6.1 The effective-configuration fingerprint

⚑ **This is the correction that matters most, and the first draft got it wrong.** It proposed
hashing the stored draft, which under §5 step 5 contains `{"$from": "conn-7#host"}`. That string is
*invariant* under exactly the change we need to detect: if `conn-7`'s host changes, the hash does
not move, and a drift banner built on it would claim to detect a change it structurally cannot see.

The frontend has no config hash today at all — `configGeneration` is an opaque string
([configRepository.ts:33](../../../frontend/src/features/designer/configRepository.ts#L33)) — so
there is nothing existing to inherit this defect from. It is introduced correctly or not at all.

**The rule.** Keep the stable reference in the draft. Compute the **effective-configuration
fingerprint** over the *resolved* values:

```
fingerprint = H(
    literal and overridden field values,
    resolved connection attribute values,
    for each referenced connection: (id, revision),
    EXCLUDING every secret plaintext — secrets contribute their
    secretRef and the connection revision, never a resolved value
)
```

The `(id, revision)` pair is what makes this reliable without reading secrets: a connection whose
password changed bumps its revision, so the fingerprint moves even though no secret value entered
the hash. This is consistent with `I6`'s existing rule that config hashes are computed over
references rather than resolved values — the reference set here simply includes the revision that
makes it meaningful.

The fingerprint is computed **server-side**, where both the resolved values and the secret backend
live. The client receives the fingerprint and the per-connection revisions, never the resolutions
of secrets.

### 6.2 A connection is edited later

Because the draft holds a reference, an edit is live for every non-overridden field. "Live" must
not mean "invisible", and must never mean "applied":

1. **Saving a connection shows an impact confirmation**, driven by the reference count:
   *"3 instances reference prod-broker-01. Their configuration-of-record will change; the change
   reaches each MI only when someone applies it."*
2. **Each affected instance's Designer shows a connection-drift banner** — a **new** banner, not
   `DivergedBanner`. `DivergedBanner` is the `CONFIG_DIVERGED` rollout banner whose three
   remediations (restage, restart, rollback) address replicas stranded on different generations;
   none of them applies here. The connection-drift banner has one remediation: **review and save**.
3. **Nothing reaches a running MI without an explicit `config.apply`.** An edit to a connection can
   never silently restart or reconfigure anything. This is the safety property that makes binding
   acceptable at all, and it falls out of the existing apply model unchanged.
4. Overridden fields are untouched, and the banner says how many.

### 6.3 An operator edits a populated field

A bound field renders read-only with an **[Override]** affordance. Clicking it:

1. Copies the current resolved value into the draft as a literal and unlocks the input.
2. Replaces the chip: `[from prod-broker-01]` → `[overridden · was prod-broker-01]`, with
   **[Revert to connection]**.
3. Excludes that one field from every future connection update. Siblings stay bound.

**Secrets are the exception.** A bound secret may be **re-bound or replaced, never revealed**.
Overriding `solace.password` means entering a new secret; there is no editing of the existing one,
because the browser never held it.

**Changing the selector** re-binds all non-overridden fields and warns if overrides exist:
*"2 fields are overridden and will keep their current values."*

**Deleting a connection** is blocked while any instance references it (ruling, §9.1). Deactivating
is the alternative: existing references keep resolving; the connection stops appearing in dropdowns.

---

## 7. Test Connection — deferred, accounted for

No new contract surface is required. `action.kind: "test-connection"` already exists under
`UI-D22`, and the visual reference already renders the button
(`reference/app/designer_data.jsx:70`, `TEST_BROKER_CONNECTION`).

```json
"actions": [{
  "key": "TEST_BROKER_CONNECTION",
  "kind": "test-connection",
  "inputs": ["solace.host", "solace.vpn", "solace.username", "solace.password"],
  "when": { "field": "solace.enabled", "eq": true },
  "ui": { "section": "Broker", "label": "Test connection" }
}]
```

Ship the declaration; render the button **disabled**, with a tooltip stating that server support is
pending. When the backend lands, the button enables with **no contract change, no package change
and no descriptor change**.

`UI-D22` already binds the eventual implementation: because `inputs` includes a `secretRef`, the
test **must** execute server-side and return a verdict, never the credential. A client-side test
would require shipping a resolved secret to the browser, so "add it later in the frontend" is not
an available shortcut. The same button appears in the Connection Objects drawer (§8.2), testing the
connection directly rather than a field set.

---

## 8. Screens

⚠️ **The visual reference contains no Connections screen.** `FE-D30` makes
[`reference/`](../frontend/reference/README.md) the source of truth for screens and copy, so the
layouts below are **proposals built by analogy** to the existing Targets and Admin list screens,
not transcriptions. They need a designer (§9.5).

### 8.1 Catalog list

Top-level nav item **Connections**, immediately after **Targets** — same altitude, same shape: a
flat org-scoped catalog, not nested under a solution.

```
┌─ Connections ──────────────────────────────────────────────────────┐
│  [ Solace brokers (4) │ Remote servers (7) ]     [+ New connection] │
│  Search ▸ ______   Org ▾   Sub-org ▾   Env ▾                        │
├─────────────────────────────────────────────────────────────────────┤
│  NAME             HOST                VPN         SCOPE   USED      │
│  prod-broker-01   broker.acme:55443   payments…   PROD      3   ▸   │
│  stage-broker-01  broker-st.acme      payments…   TEST      1   ▸   │
│  dev-broker       localhost           default     DEV       0   ▸   │
└─────────────────────────────────────────────────────────────────────┘
```

**Tabs are generated from the descriptors**, and **columns from `ui.listColumn`** — a third
connection type adds a tab and its columns with no code change.

**`USED` is load-bearing, not decoration.** It is the count of instances referencing the
connection; it gates deletion (§6.3) and sizes the impact confirmation (§6.2).

### 8.2 Detail / edit drawer

Rendered by the same dynamic form renderer as the Designer, from the descriptor.

```
┌─ prod-broker-01 ─────────────────────── Solace broker · rev 4 ─┐
│  Name         [prod-broker-01              ]                   │
│  Description  [Payments production broker… ]                   │
│  Scope        acme / payments / PROD          (read-only)       │
│  ──────────────────────────────────────────────────────────    │
│  Host         [tcps://broker.acme.internal ]                    │
│  Port         [55443]     Message VPN [payments-prod]           │
│  Client user  [svc-cache]                                       │
│  Password     [••••••••]  set · never displayed     [Replace]   │
│  ──────────────────────────────────────────────────────────    │
│  [Test connection]   ← disabled, "available when server         │
│                         support lands"                          │
│  Used by 3 instances ▸                                          │
│                               [Cancel]   [Save changes]         │
└─────────────────────────────────────────────────────────────────┘
```

For **remote-server**, `authMethod` swaps the credential block: Password for `basic`; Private key
(multiline, write-only) plus optional Passphrase for `private-key`.

Secrets show **set / unset status only** (ruling, §9.4). There is no read path.

### 8.3 Declare Instance dialog

Promoted fields render after the org/sub-org/env cascade and **before** Replicas. The ordering is
required, not cosmetic: the cascade supplies the scope that filters the dropdown.

```
Name          [cache-01                        ]
Package ver   [7 ▾]           Role   [sink ▾]
Organization  [acme ▾]   Sub-org [payments ▾]   Env [PROD ▾]
─── Connections ────────────────────────────────────────
Broker        [prod-broker-01 ▾]    ⓘ 5 fields will be set
              ↳ tcps://broker.acme.internal · payments-prod
Source server [sftp-inbound-prod ▾]
              + New connection…      ← opens the drawer inline
Replicas      …
```

The inline **+ New connection…** matters: without it, an operator who reaches this point and finds
no suitable connection must abandon a half-filled dialog.

Per §5.1 this dialog **saves a selection and applies nothing**.

---

## 9. Decisions

Eight were tabled; all eight are ruled below. Two (§9.1, §9.5) still need another group's signature
and are marked accordingly.

### 9.1 RBAC verbs ⚠️ requires a `D18` amendment

**Ruling:** add `connection.view`, `connection.create`, `connection.edit`, `connection.delete` and
`connection.use` **through a formal `D18` amendment**. Deactivation is governed by
`connection.edit`, not a separate verb. Deletion is permitted **only at zero references**.

⚠️ **This is flagged, not resolved** (`I3`, `I4`). `D18` is **LOCKED** at 35 operations and contains
no `connection.*` family, so under `D10`'s deny-by-default check the screen is unauthorizable until
the amendment is ratified. This is the same failure family as open item 23 (`DM-D18`'s six verbs)
and the three unratified `mesh.*` verbs. **The architect group owns it.**

Until then the console follows the precedent the reference already sets in
`reference/app/admin_data.jsx`, which models unratified permissions as
`status: grantable | proposed | absent` with tone chips and notes that *"Adding a verb is DATA, not
a layout change."* The five verbs ship as `proposed`.

### 9.2 Binding authorization is two-sided

**Ruling: yes.** Selecting a connection requires permission to configure the instance **and**
`connection.use` on the chosen connection — the same two-sided shape `D10` already ratifies for
`target.use` ⇄ `instance.bindTarget`.

**Rechecked on save and on apply, not only when the dropdown loads.** A permission can be revoked
between selection and apply, and an authorization decision cached at dropdown-render time is an
authorization decision made at the wrong moment.

### 9.3 `entity: "broker"` is retired

**Ruling:** standardize on `entity: "connection"` plus `connectionType`. No alias — nothing consumes
`optionsSource` yet, so no package needs one. Recorded as `UI-D27` superseding part of `UI-D22`
(`I3`: never edit a decision's meaning in place).

### 9.4 Secret storage is a prerequisite

**Ruling:** credentials live in the server-side secret backend; the UI sees **set/unset status
only**. Backend secret support is a **prerequisite for saving or applying a real connection** — not
a follow-up.

This intersects unresolved `DECISIONS.md` open item 22 (server-side redaction of embedded secrets).
The frontend proceeds against fixtures, as every other feature in this repo has; **shipping does
not.**

### 9.5 The Connections screen needs a design ⚠️ designer

**Ruling:** add a Connections screen design and visual baseline to the reference. **This is a design
task and not a reason to alter the data model.**

⚠️ Until it exists, `frontend/design.md` §24's gate-2 waiver remains unsigned and
visual-regression baselines stay blocked for this screen — the existing constraint of open item
19b, inherited unchanged. **The designer owns it.**

### 9.6 Scope is one exact triple

**Ruling:** exactly one `org / sub-org / environment` per connection. Inherited sharing is a later
addition and requires an explicit authorization model first. See §2.3.

### 9.7 `valueFrom.ref` is same-component

**Ruling:** within one component for v1. Multiple File Mesh components may select the same
Connection Object independently; cross-component references would make package composition harder
to validate for no capability gained. Gate 12 enforces it.

### 9.8 Hierarchy terminology

**Ruling:** new connection data uses the finalized **organization → sub-organization → environment**
hierarchy. The older `contracts/events/` terminology conflict (`CLAUDE.md` item 38) is resolved
separately and is **not** a dependency of this work.

---

## 10. New decisions this document adds

| ID | Statement | Status |
|---|---|---|
| **`UI-D27`** | **Connection Objects are an SM-owned catalog of descriptor-driven types.** A Connection Type Descriptor declares its attributes using `UI-D14`'s existing field vocabulary, so the catalog form and the Designer share one renderer and a new type is data. `optionsSource.entity` gains `connection` with a required `connectionType`; `broker` is retired. Supersedes `UI-D22`'s entity enum and its residual population gap. Scope is one exact org/sub-org/environment triple. | PROPOSED |
| **`UI-D28`** | **`valueFrom` binds a field to an attribute of a selected catalog entry.** Declared on the target field, same-component only. Precedence is override → selected connection → declared default → unset-and-invalid-if-required. `project()` gains exactly one generic rule and no connection-specific knowledge. Closes `design.md` §9 item 10 in favour of the first of its three candidates. | PROPOSED |
| **`UI-D29`** | **The effective-configuration fingerprint is computed over resolved values plus each referenced connection's `(id, revision)`, excluding secret plaintext.** The draft keeps stable references; the fingerprint does not, because a reference is invariant under the change it must detect. Server-side only. | PROPOSED |

A fourth change is required but is **not** this document's to make: the `D18` amendment adding the
five `connection.*` verbs (§9.1).

---

## 11. Build order

1. **Contract** — schema additions, descriptors, gates 12–16, fixtures and negative cases; one
   reviewed single-writer change. `./contracts/run-checks.sh` green.
2. **`project()`** — `valueFrom` resolution, §4 precedence, `ViewField.origin`.
3. **Catalog screen** — list, drawer, descriptor-driven form, fixture-backed.
4. **Declare dialog** — `promoteToDeclare` rendering, scope-filtered dropdowns, inline create.
5. **Designer** — provenance chips, override/revert, connection-drift banner.
6. **Fingerprint** — §6.1, server-side; the drift banner is not trustworthy before this exists and
   should not ship claiming to be.

Steps 1–5 are buildable against fixtures today. Step 6, real secrets (§9.4) and the `D18`
amendment (§9.1) are what stand between this and shipping.

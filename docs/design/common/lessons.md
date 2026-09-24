# Cross-cutting lessons

Patterns that affect more than one repo, or that are architectural/contract-level. **Every repo reads this file.**

Repo-specific lessons stay in that repo's own `docs/tasks/lessons.md` — including this repo's, at
[`../../tasks/lessons.md`](../../tasks/lessons.md). **When in doubt, put it here**: a lesson seen by
people who did not need it costs far less than one that stays invisible to someone who did.

**Review this at session start.** Each entry exists because something went wrong once.

---

## L1 — Verify framework defaults and licence terms; never recall them

**What happened:** three factual errors reached a design document that had already passed self-review — Spring Boot
actuator defaults, TimescaleDB's Apache-2/TSL split, and PostgreSQL `DETACH PARTITION` locking. Two of them broke
mechanisms the design depended on.

**Rule:** before writing any framework default, licence term or database semantic into a document, verify it with
**WebFetch** against vendor documentation. Web search is disabled here; WebFetch of a specific URL works.

**Why it recurs:** the failure mode is confident recall, not ignorance. It feels like knowing.

---

## L2 — Self-review does not catch wrong facts

**What happened:** a self-review pass found three genuine internal inconsistencies and zero factual errors. An
independent adversarial review then found six critical issues, three of them factual.

**Rule:** for anything that will be built against, run **both** — self-review for internal consistency, then an
independent reviewer instructed to *refute*, with a "looks good" conclusion treated as a failed review.

---

## L3 — Owning a config file is not owning the configuration

**What happened:** a design assumed writing an external Spring config file made SM the authority on config. Spring
resolves properties across an 11-source stack where config files rank 3rd; env vars, system properties and
command-line args all win.

**Rule:** when taking over any layered configuration system, establish **where your write lands in the precedence
order**, and verify the **effective** value rather than the value you wrote. Applies to backend (writes the file)
and go-agent (writes it locally) alike.

---

## L4 — Check the ID prefix before citing a decision

**What happened:** `B/D17` (editions) was recorded as `D17` (first vertical slice) during consolidation — with the
no-renumbering rule visible on the same screen.

**Rule:** decision families collide by number. Always cite with the prefix, and grep the register before asserting
what an ID means.

---

## L5 — Don't offer labelled options without showing the delta

**What happened:** options presented as three named "approaches" were rejected twice as unexplained, because they
were really three ways of organising the same decisions and the reader could not see what differed.

**Rule:** state what does **not** change across the options first, show the concrete per-option delta, anchor
against the status quo, and run the same real scenarios through each. Then ask.

---

## L6 — Read the actual artifact before designing a mechanism against it

**What happened:** three separate design mechanisms were built on assumptions about Cache and File Mesh that were
false, and none of it required special access to discover — just opening the files:

| Assumption | Reality | Cost |
|---|---|---|
| SM can set the config location via an env var | Cache's `Dockerfile:12` passes it on the **command line**, which outranks env vars | The config mechanism did not work on the v1 target solution |
| The config schema is generated from the solution's build | Cache has **no annotation processor and no generated metadata**; adding one violates `HC1` | A locked decision (`D3`) was unimplementable |
| File Mesh config metadata does not exist in a usable form | `FM_CONFIGURATION_TEMPLATE` is a **versioned, production config-driven-UI registry** | The platform's #1 objective was designed from scratch alongside a working implementation |

**Rule:** before specifying how the platform will interact with an existing artifact, **read that artifact** — its
Dockerfile/launch spec, its build file, and its actual schema. Two greps would have prevented all three. This is
`L1` (verify facts) applied to first-party code, where the temptation to assume is strongest because it feels
familiar.

---

## L7 — A guard that cannot fail is not a guard

**What happened:** `sync-conventions.sh --check` exited **0** when every target repo was missing, so CI would pass
against paths that did not exist. Separately, a mistyped mode fell through to the **write** path, and the drift check
compared the stamped hash rather than the block body — so a hand-edit was invisible.

**Rule:** for anything that gates a merge, enumerate the ways it can produce a **false green** and test each one
deliberately: absent target · unknown argument · duplicate content · content edited without touching metadata. A
guard tested only on the happy path is documentation, not enforcement.

---

## L8 — A proposal must never silently change a locked decision's status

**What happened:** the decision register let **PROPOSED** `SM-D2` mark **LOCKED** `B/D6` and `D16` as `RETIRED`,
while its own status legend said proposals are non-binding. Two developers could have built different systems and
both claimed compliance.

**Rule:** status transitions are caused by **ratification**, never by the existence of a proposal. A proposal records
*intent to supersede*; the label stays `LOCKED — proposed supersession by X` until a decision is actually taken.

---

## L9 — A fix that widens cardinality must be re-checked against every key it touches

**What happened:** allowing an Instance to bind to several Targets (multi-site) was the right abstraction, but series
identity was not updated to match. Since the suggested `source_key` values — pod name, host — are **not globally
unique**, two sites would have silently merged into one series and overwritten each other at identical scrape
timestamps. The fix for one defect created a worse one, in a different document.

**Rule:** when a change makes something *more* plural (one→many targets, one→many attachments, one→many writers), list
every uniqueness constraint, primary key and identity claim that assumed singularity, and check each. Widening
cardinality is exactly the change that invalidates keys silently — nothing fails at write time, the data just merges.

---

## L10 — Do not treat a build-time path as a runtime fact

**What happened:** `/projConfig/application.yml` was read as evidence that a deployed File Mesh MI mounts config at
that path. It is actually the **manager's own** generation path — it reads versioned template YAMLs from
`artifacts/connectorType/<type>/<version>/Config/` to seed its UI. Nothing about the running artifact was established.
Separately, `MIN_VERSION`/`MAX_VERSION` columns were cited as solving version-aware rendering, when the production
query has **no version predicate** and the backend interface **omits both fields**.

**Rule:** a column existing is not a feature working, and a path in a repo is not a mount in a container. Before citing
existing code as prior art, **find the read path** — the query, the interface, the consumer. Schema archaeology without
the call site produces confident, wrong conclusions.

---

## L11 — Search the repo before designing; the answer is often already here, verified

**What happened:** an independent review of the Subsystem A spec returned 5 CRITICAL findings. **Four** were cases where
the correct answer was already recorded in this repo, already verified, and simply not propagated:

| Designed as new | Already in the repo |
|---|---|
| Green/red health | `architecture.md:797` — "Restart windows render as *unready/gap*, **not 'down'**"; `DECISIONS.md:811` open item 15 + `architecture.md:835` — a Cache `active_standby` standby "reports `health: UP` while processing nothing" |
| "Never render a flat zero" as a new rule | `architecture.md:273` — `onUnavailable: degrade` already required **per mapping** |
| The Redis-provider hazard, rediscovered by reading source | `architecture.md:290-293` — "switching Cache to Redis … turns its KPI graphs into 500s and a flat zero, and **a flat zero is worse than an error**" |
| Dropping the custom-UI escape hatch as "dissolved" | `D1` is **`LOCKED`** and explicitly names it (`DECISIONS.md:59`) |

Reading the *reference codebases* was done well. Reading *this repo* was not — so the design re-derived what was settled
and contradicted what was locked.

**Rule:** before writing a design section, **grep this repo for its topic first** — `DECISIONS.md` (including the open
items, not just the ratified rows), `architecture.md`, and the invariants. Cite what you find, or state why you are
superseding it. A design that rediscovers a repo fact by reading source has skipped a step, and a design that
contradicts a `LOCKED` decision without flagging it has broken `I4`.

**Why it recurs:** the repo is large enough that recall substitutes for search, and reading source *feels* like the
rigorous option. It is — but it is the second step, not the first.

---

## L12 — Never report a grep count as a verified figure

**What happened:** a design doc's evidence table stated "1,494 template rows · 88 `showWhen` values · 9 `UI_TYPE_META`
keys" as **verified**. A quote-aware parser found **1,989 rows, 230 `showWhen`, and 22 keys** — plus a second
conditional mechanism (`dependency`, 41 rows) that the extraction had hidden entirely. The regex broke on apostrophes
and literal newlines inside `helperText` and silently skipped every row containing them. Nothing errored; the numbers
looked plausible; they were quoted into three documents and a commit message.

**Rule:** a count that will be written into a design is **derived by a checked-in script whose output is committed
alongside it**, and the script must **report its own parse failures** (`unparsed rows: 0`). Never quote a figure
produced by an ad-hoc `grep | sort | uniq -c` over structured data — SQL dumps, JSON, YAML. A lossy extraction is
indistinguishable from an accurate one at the point of use.

**Corollary:** structural conclusions can survive wrong counts — "all predicates are AND-of-equalities" held at 271
predicates as it did at the 88 originally sampled. Report the *check* ("0 grammar violations across all 271"), not the
sample, so the conclusion carries its own scope.

**See:** `docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py` for the shape this takes.

---

## L13 — A contract is not proven until an artifact is generated from real data

**What happened:** three review rounds and one adversarial review all reasoned *about* whether the Solution
Package contract could express File Mesh. Generating the fixture from the real 1,989 template rows falsified
three parts of it in an afternoon:

| Believed | Actual |
|---|---|
| Predicates reference config fields | **All 11** predicate-referenced File Mesh fields have **no config path**. Gate 4 would have rejected **every** File Mesh predicate — the applicability design was unusable against the only MI whose applicability data exists |
| `action.kind` needed `browse-remote-path` and `refresh-catalog` | Zero evidence for either in 1,989 rows. Of 16 distinct buttons, **one** is api-backed; ten are renderer chrome or out of scope |
| A discriminator is an `enum` | `solace.enabled` is a **boolean** discriminator, and so is File Mesh's `CONFIG_JAVA_RUNTIME` |

Two other worries evaporated on contact: the `FILETOFILE`/`FILEEVENTS` variant axis and the 57 field-name
collisions both turned out expressible with **no** vocabulary change.

**Rule:** for any contract, schema or protocol, the deliverable is **the schema plus a fixture generated from
production data plus one negative fixture per rejection rule**. Not a fixture hand-written to match the schema
— that only proves you can write agreeing JSON twice. Generate it with an adapter, and make the adapter emit a
**coverage report naming every row it could not express**, so the gap is measured rather than assumed.

**Why it recurs:** a contract reads as complete because every clause in it is true. Completeness is not
visible by reading — only by trying to encode something real and counting what is left over.

**See:** `contracts/run-checks.sh`. Precedent: `V001__telemetry_schema.sql` settled a primary key in one run
that three review rounds got wrong in prose ([[L9]]).

---

## L14 — A contract change justified only by values in one package is a curation fix

**What happened:** an independent review found that `project()` writes package `default`s into the MI's config
file, and that nothing had decided this. I traced it, found that five of the nine values written for a fresh
Cache instance came from the MI's shipped sample `application.yml` — the exact source `UI-D17` forbids, in a
rule I had written myself — and then proposed **splitting `default` into `default` + `recommended`** in the
schema, and asked the user to arbitrate whether `solace.enabled: true` should be written.

The user's reply: *"I really don't understand why are we looking at Cache solution specific things, when idea
should be to have configurable fields pushed and solution gets configured component wise."*

They were right. The engine rule is one sentence with no solution knowledge in it — *a declared value the
operator did not override is written* — and it is correct. Whether `solace.enabled: true` is an appropriate
declared value is a **package-authoring** question, owned by the package author under `UI-D19` and policed by
`UI-D17`'s provenance tiers and the contract tests. I had taken a curation bug in one hand-written fixture,
escalated it to a schema change, and asked the platform owner to adjudicate one MI's configuration.

**Rule:** before proposing a contract, schema or engine change, ask **what evidence would still support it if
this MI did not exist**. If the whole justification is values in one package, it is a curation fix — correct
the package, and record the generic rule that already covered it. Escalating it to the contract adds surface
area to the platform to compensate for a bad row in a data file.

**The tell:** I was proposing a **schema** change whose entire evidence base was **five values in one file**.
The ratio between the size of the proposed mechanism and the breadth of its evidence is the signal.

**Why it recurs:** this is the second time in one session. The first correction — *"we are building this for
multiple MIs with different scenarios"* — produced the component model (`UI-D1`) and closed vocabularies
(`UI-D14`). The reflex it was correcting came straight back on a narrower point, because Cache is the MI whose
source I have actually read, so its specifics are the ones that feel like evidence. Depth of knowledge about
one instance is exactly what makes over-generalising from it feel rigorous.

**See also:** [[L11]] (search the repo before designing) — same family of error, one level up.

## L15 — Never put code in a plan you have not executed

An implementation plan for the frontend carried ~1,800 lines including real TypeScript, TSX and bash. An
independent reviewer installed the exact dependency set, applied the plan's own `tsconfig.json`, and ran it.
Six things broke **on execution**, not on inspection:

- a component failed to compile — `TS2375`, because the plan's own `exactOptionalPropertyTypes: true` rejects an
  explicitly-passed `undefined`
- a test could never pass against the component in the same task: it omitted the prop that renders the element
  it then queried for
- one task imported a barrel file that a *later* task created, so it could never go green
- `"typecheck": "tsc -b --noEmit false"` wrote compiled `.js` beside every source file, which the test runner
  then collected a second time
- `toBePartiallyChecked` cannot pass against MUI's Checkbox, which sets only `data-indeterminate` — while the
  plan explicitly instructed "pass `indeterminate` straight to MUI"
- a shell guard used ERE lookahead, so `grep` exited 2, the `&& note` short-circuited, and the script printed
  **"all guards pass"** on every run

Every one is invisible to reading and obvious to running. Worse, the plan's Self-Review section asserted that
type consistency across tasks *had* been checked, and a helper used seven times was defined nowhere — a claimed
verification that never happened.

**Rule:** code in a plan is either **executed before the plan ships**, or it is not code — reduce it to a
precise prose specification plus the command that proves it. Scaffolding is mechanical: build it first, get it
green, and write the plan **from** the working tree. A plan that specifies a stack in prose you never ran is a
list of hypotheses formatted as instructions.

**The tell:** you are writing a version pin, a compiler flag, a matcher name, or a `grep` expression that you
have not run in this session. Each is a factual claim about a tool's behaviour — the same class of claim
[[L1]] already forbids recalling.

**Why it recurs:** plan-writing feels like design, where prose is the deliverable, so the strict-mode flag and
the jest-dom matcher get written with a designer's confidence rather than an engineer's. The mode switch is
silent, and TDD makes it worse: a plan whose tests are wrong produces an executor who builds the wrong thing
*and* reports green.

**See also:** [[L7]] (a guard that cannot fail) — the guard that printed "all guards pass" is the purest
instance. [[L13]] (a contract is not proven until an artifact is generated from real data) — same principle,
one level up.

## L16 — Your own derived document is not a source

A plan needed a state vocabulary. The source was `data.jsx` in the approved mockup. Instead the vocabulary was
reconstructed from `screen-prompts.md` — a document written earlier **in the same session, by me, from the same
mockup**. The result was wrong in every dimension:

- **membership** — 11 keys transcribed against **35** real entries, in **eight** tables plus an ordered ladder
  across **five** files, not one table. The source file's own comment warned against exactly the collapse
  performed: *"four conditions, plus the bootstrap case. Never one 'missing' badge."*
  *(And the first corrected transcription still missed two of the eight tables — the count above is the one an
  adversarial pass measured, not the one I arrived at unaided. Recovering from this error took two rounds.)*
- **labels** — `"Version drift"` for a chip labelled `"Drift"`; `"Down"` for a state actually named
  `"Not responding"`; and `CONFIG.na`'s label is a bare em dash
- **tones** — `VERSION_DRIFT` given `err` where the source says `warn`; `RESTART_EXPECTED` given `warn` where
  all three tables say `info`; two tones (`idle`, `purple`) missing from the type union altogether, which left
  the plan's own environment chip unable to render `PROD`
- **invention** — two states with no vocabulary entry anywhere in the source

The tones were then **pinned by assertions**, so a TDD executor would have made the product match the errors.

**Rule:** transcribe from the artifact, every time. A derived document — a spec you wrote, a summary, a
subagent's report — is a *lossy* copy, and its losses are invisible because it reads as authoritative and is
written in your own voice. When a vocabulary, schema or copy deck must survive a rebuild, extract it verbatim
into a dedicated file that cites `file:line`, and make that the thing everyone transcribes from.

**The tell:** you are writing a list of names, keys or labels and the source file is not open. If you cannot
point at the line, you are recalling.

**Why it recurs:** [[L11]] says search the repo first — and the search *succeeded*. The failure was one step
later: a plausible-looking derived document was accepted in place of the primary source, which feels like
diligence because a document was in fact consulted.

**See also:** [[L11]], [[L6]], [[L12]] (never report a grep count as verified) — same family: confidence
attaching to the convenient artifact rather than the authoritative one.

## L17 — Verify the verification record itself

A provenance document was written to satisfy [[L1]]: it recorded that a design system's palette had been
fetched and diffed against product source. The verification was real. The **record of it** was wrong in both
directions at once:

- it claimed *"12 of 12 values match"* above a **13-row** table, and the true figure was **58 of 58** — the
  verification was far stronger than claimed
- it labelled two whole token groups *"design-authored — NOT upstream product values"* when both **were**
  upstream, so the reproduction command it supplied would have printed nothing and contradicted its own prose
- it labelled typography, spacing, radii, shadows and motion **"verified"** when the fetched file was
  palette-only. The font family happened to be correct, but lived in a different file that was never
  fetched — a recalled fact wearing a verification badge
- its diff command was case-sensitive against an upstream that mixes `#00AD93` and `#00ad93` for the same
  value, so faithful transcriptions would have surfaced as inventions

**Rule:** a document whose purpose is accuracy gets **more** scrutiny than the work it describes, not less.
Run the reproduction command you are about to publish and check its output matches the prose beside it. State
scope in both directions — what was verified *and* what the fetched artifact could not possibly establish.
Record the count from the command, never from memory of the count.

**The tell:** prose and a command sitting next to each other, where the command was written to look right rather
than run. Also: a total in prose that disagrees with the number of rows beneath it.

**Why it recurs:** having done the verification produces the feeling of being verified, and that feeling then
covers the *write-up* — which is a separate act, performed later, from memory. Conservative errors feel safe
("design-authored" understates the claim), but in a provenance record an understatement is still a false
statement, and it sends the next reader to re-derive work already done.

**See also:** [[L2]] (self-review does not catch wrong facts) — this is L2 applied to the artifact that exists
to prevent L1. [[L12]], [[L8]] (a proposal must never silently change a locked decision's status) — the same
work also declared its own gate satisfied on the strength of a proposal it had just minted.

## L18 — The most dangerous place to write a recalled fact is inside the checker

**What happened:** Task 7 added a date-time format gate to `contracts/run-checks.sh` — a checker whose whole
purpose was to make an inert schema constraint (`format: date-time`) actually enforced, so a Java producer and a
TypeScript consumer could not drift. Its implementation leaned on Python's `datetime.fromisoformat`, and its
comment asserted from memory that `fromisoformat` rejects a bare date and malformed offsets. Verified on 3.14.4:
false. `'2026-08-27'`, `'20260827'`, `'2026-08-27T10:00'`, `'2026-08-27T10:00:00'` and
`'2026-08-27 10:00:00+00:00'` were all **accepted** — while `Instant.parse` on the Java side rejects every one.
The gate was blind to precisely the producer/consumer drift it existed to catch, and it *reported green*, which is
worse than not existing: it retired the suspicion that would have found the gap. Fourth instance of the
recalled-fact class in this repo ([[L1]], [[L10]], [[L16]] being the others).

**Rule:** a behavioural claim about a stdlib, vendor library or platform written **into a guard** gets verified by
execution before the guard is committed — and the guard's own claims get **mutation-tested in both directions**:
plant a value the comment says is rejected and watch it fail, plant a value the comment says is accepted and watch
it pass. Where the verified behaviour is weaker than the guard needs, pin the shape yourself (a regex, an explicit
enum) and keep the library call only for what it demonstrably does. One unverified fact per file is already one
too many, so prefer the stricter reading of an ambiguity over a second recalled fact about the other side.

**The tell:** a comment inside a checker that states what some *other* code rejects, sitting next to a call you did
not run against those inputs. Also: a guard that has never been red, whose author cannot name the input that would
turn it red.

**Why it recurs:** writing a guard *feels* like the diligent act — it is the thing you do instead of trusting
yourself — so the confidence it generates covers the recalled fact you smuggled in while building it. [[L7]] says a
guard that cannot fail is not a guard; this is the subtler version, where the guard can fail but not on the case it
was written for, and the recall is what decided which case that is.

**See also:** [[L1]] (never recall framework defaults) — this is L1 at its highest-leverage location. [[L7]] (a
guard that cannot fail is not a guard), [[L17]] (verify the verification record itself) — same family: the artifact
built to establish trust is the one that receives the least of it.

## L19 — A guarantee is settled only when its enforcing artifact is in the document

**What happened:** the EDA walking-skeleton design (2026-09) went through three external review rounds
that each caught the same defect *class* at a deeper level, after the named instance had been "fixed":
a hash rule stated in a SQL comment instead of a CHECK; then a CHECK that validated key *presence*
while the claim was evidence; then "immutable by database privilege" with no CREATE ROLE / GRANT
anywhere in the design; then "bounded retries" with no bound. Every round, the fix converted the
finding into more confident prose one artifact short of enforceable — the reviewer had to ask *"why
are we not fixing the issue?"*

**Rule:** when a design claims a guarantee, the section that claims it must contain the artifact that
enforces it — the constraint expression, the DCL, the index definition, the named test obligation —
or explicitly name the enforcing owner and its test ("engine-enforced, pinned by test X") where no
schema-level artifact can exist. Before writing "enforced", "immutable", "bounded", "refused" or
"guaranteed", ask: *what object refuses the violation, and is that object written down here?* If the
answer is "review will catch it" or "the engine just won't do that", the guarantee is not settled —
say so instead. Corollary for reviewers of your own text: grep your draft for those five words and
demand the artifact under each.

---

## L20 — A new, stricter predicate can silently unpin the old ones it was added beside

**What happened:** twice in the same backend repair wave (the EDA walking-skeleton's `resolution_is_evidence`
CHECK), landing a new, more specific predicate beside older ones caused several of the older predicates to
stop being pinned by anything, while every test kept passing. R3's `BE-D17` landing added a generation-equality
clause; the suite's existing array-shape legs all happened to carry a stagedGeneration value the new equality
clause *already* refused, so the array clauses became provably deletable — `V002` could lose them and the
reactor would stay green. `BE-D18`'s landing (R6) repeated the exact same shape at one level deeper: making
`replicas`/`restartsObserved` elements well-formed made every legacy element-incomplete leg (`[{"ref": "r1"}]`)
refused by the *new* well-formedness predicate regardless of what the *pre-existing* clauses said — including
`BE-D17`'s own generation-equality clause, the reason `BE-D17` exists at all. Both times, `mvn verify` was
green throughout; the unpinning was invisible to every gate that only asks "does the suite still pass," and
was found only by a reviewer who mutated the pre-existing clauses too, not just the new ones.

**Rule:** when a CHECK (or any layered validation) gains a stricter predicate, re-run the per-clause mutation
table over **every pre-existing clause in the same constraint**, not only the new ones. A new predicate that
independently refuses the same hostile input as an old clause leaves that old clause deletable with the suite
still green — the suite passing is not evidence the old clause still does anything. Concretely: for each
pre-existing clause, remove it alone (leaving the new predicates in place) and confirm at least one shipped leg
flips from REJECT to ACCEPT; if none does, either the clause is now logically dead (subsumed — say so and keep
it as defence-in-depth, per [[L19]]'s "name the enforcing owner" test) or a leg needs its fixture completed to
stop tripping the new predicate before it ever reaches the old one.

**The tell:** a validation-shape task described as "add N predicates to an existing CHECK" that reports the
new predicates' own mutation table but not the old ones'; a legacy test fixture that is *element-incomplete*,
*type-incomplete*, or otherwise deliberately partial, sitting unchanged beside a new predicate whose whole job
is to complete exactly that shape.

**Why it recurs:** the natural scope of "did my change work" is the change itself, and a per-clause mutation
table over only the new predicates answers that question completely and looks thorough. It does not ask "did
my change silently change what an *old* clause is for" — and a green suite actively hides the answer, because
the suite's job is to notice refusals, not to notice *which* clause supplied one. [[L7]]'s "a guard that cannot
fail is not a guard" is the same shape one layer up: here the guard can still fail, on the right leg, for the
wrong reason, and only a reviewer who removes the old clause in isolation ever finds out.

**See also:** [[L19]] (a guarantee is settled only when its enforcing artifact is in the document — the artifact
existing is not enough if a leg no longer reaches it), [[L7]] (a guard that cannot fail is not a guard), [[L18]]
(mutation-test a guard's own claims in both directions — this is that same discipline applied across an
amendment boundary rather than within one predicate).

---

## L21 — Count the processes before designing the replicas

**What happened:** the Agent Gateway design (2026-09-08) specified gateway replicas behind a load balancer,
a session-ownership row with a fencing epoch, failover tests and a replica-count formula inside a Data Plane that
ships as one process with one `dp-core`. Independent review found the HA section unbuildable in that packaging and
found command delivery relying on an after-commit hook that reaches only the JVM that committed.

**Rule:** before writing replica, failover, ownership or notification mechanisms, state the number of processes in
the deployment actually shipped. If it is one, state that limit and make multi-process machinery a tested
precondition for the later deployment. If it is more than one, no process-local hook, event or cache can be the sole
carrier of information another process needs; a durable poll or persisted row is the path of record.

---

## L22 — Measuring an input is not measuring the outcome you wrote down

**What happened:** plan 005 recorded, as a **measured fact**, that
`cache-engine.solace.enabled: false` would let the Solace Cache Engine run broker-free — "switches
off `JcsmpSolaceConsumer`/`JcsmpSolaceProducer`/`LeaderElection` (`@ConditionalOnProperty`,
measured)". The three annotations were read from the Cache's own source and are exactly as
described. The fact was still wrong: `CacheManagerService` is a plain `@Service` with a hard
constructor dependency on `JcsmpSolaceProducer`, so switching the bean off makes the application
context fail to refresh. The claim that was *measured* ("these three beans are conditional") and
the claim that was *written* ("therefore the demo needs no broker") were different claims, and only
the first was checked. **And the first correction repeated the error one layer down.** Explaining a
`503: Unknown Queue` at start-up, the fix asserted that "`JcsmpSolaceConsumer`'s `@PostConstruct`
calls its own `@Async` method by self-invocation". Two annotations, read from the class, and the
conclusion drawn without tracing the caller: that class's `@PostConstruct` is **empty**, and the
self-invoking method is reached from a *different bean* whose own `@ConditionalOnProperty` the
configuration in question had switched off — so the queue was never bound and the provisioning
service added to "fix" it was unnecessary. An independent audit caught it, and a three-second
probe against a broker with an empty queue list settled it.

This is the failure [[I2]] is about, arriving through the front door: the fact *was* verified
against the source, so it did not feel like recall. Reading an annotation, a flag or a config key
is reading an **input**. Writing "therefore X starts / connects / succeeds" is a claim about an
**outcome**, and an outcome is only measured by producing it.

**Rule:** state the measurement at the granularity of the claim, and trace the call, not the
annotation. If the sentence you are writing is
"therefore it works without Y", the measurement is *running it without Y and reading the exit code
or the log*, not reading the switch that was supposed to make that true. Before writing "measured"
beside a conclusion, name the observation: what command ran, what it printed. When the observation
is expensive, write the weaker sentence you actually measured ("these three beans are conditional
on that key") and mark the stronger one as an expectation — an expectation that turns out wrong
costs a paragraph; a "measured fact" that turns out wrong costs the reader's trust in every other
one in the document. Related: [[L16]] (your own derived document is not a source), [[L19]] (a
guarantee is settled only when its enforcing artifact is present).

## L23 — A value copy of reducer state is not a snapshot while its maps are shared

**What happened:** the go-agent's reducers take and return a composite `State` by value, which
reads like immutability. But `commands.Journal`, `commands.Pulling` and the `reports` state hold
maps, and `Journal.Put`, `reports.observe`/`ack` and the `Pulling` writes mutate them in place.
Every `State` copy therefore aliases the same live maps — a reducer's write is visible through the
*old* value before the caller has even adopted the new one. The loop is single-goroutine, so the
reducers themselves were safe; the admin surface was not. `/healthz` and `/metrics` read
`Snapshot()` from another goroutine and raced the reducers' writes, which `-race` reproduced (plan
005, task 22). The fix was a lock around `Reduce` and a deep clone in `Snapshot()`.

**Rule:** in Go, copying a struct copies its map and slice *headers*, not their contents, so "the
reducer is pure" can be true of its logic and false of its storage. Anything that reads reducer
state off the loop goroutine goes through the deep-copying accessor (`Snapshot()` /
`cloneState`), and **every map- or slice-typed field added to reducer state is added to that clone
in the same change** — the clone is the one list that has to stay complete, and nothing fails when
it falls behind until a race detector happens to catch it. Appending to a slice has the same
hazard in a quieter form: `append` may write into a backing array an earlier copy still
references, so take `slices.Clip` before appending in a reducer (the dispatch queue does).
Related: [[L19]] (a guarantee is settled only when its enforcing artifact is present).

## L24 — A test that cannot fail on the regression it names is not coverage

**What happened:** plan 005's final review, and the fixes after it, found three go-agent tests
that read as assertions of a property and could not fail on the defect they named. The redaction
test asserted that a secret's ASCII text was absent from logged output — but Go renders a `[]byte`
struct field as decimal byte codes, so the text was absent with redaction removed too, while the
entire secret was being logged byte by byte. `TestResumeTable` declared `wantAck` and `wantRecvAck`
columns and never asserted either, and checked deletes only where one was expected, so a resume
that re-acked an acknowledged record or deleted a started step still passed; the orphaned-record
bug (review finding C1) sat in the neighbouring row for the same reason. The "executed at most
once" check bounded a count at `> 8` when one execution produces 4, so a genuine double execution
— exactly 8 — passed. All three were green, and all three came from a plan that showed them
passing.

**Rule:** a test is evidence only once it has been seen to fail against the defect it names.
Before relying on one, break the code the way the test claims to guard against — revert the fix,
or mutate a scratch copy — and watch it fail with that defect's message. Prefer a positive
assertion that can hold only when the mechanism ran (the redaction *marker* is present) over a
negative one that also holds whenever there was nothing to find (the secret is *absent*). In a
table-driven test, assert every declared expectation column, in both directions: a column that is
set in the table but never checked is documentation that looks like coverage. Related: [[L15]]
(never put code in a plan you have not executed), [[L22]] (measuring an input is not measuring the
outcome you wrote down).

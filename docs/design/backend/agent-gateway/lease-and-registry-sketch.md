# Lease ledger and replica registry — mechanism sketch (AGW-D15, AGW-D16)

**DRAFT MECHANISM SKETCH. EXECUTED ON PG 14, NOT RATIFIED, NOT IMPLEMENTED. Not part of the AGW-D1…AGW-D14 ratification set.** The SQL below has been run: [`evidence/pg14-execution.md`](evidence/pg14-execution.md) records the run against `8551e5c` on `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2)` — **224 legs (this document's and [`design.md`](design.md) §14 item 3's together): 162 `OK`, 60 `REFUSED`, 2 `N/A-engine`, 0 `FAIL`, exit 0**, with every **SQL-backed executable** obligation of §8 **(L1) onward** exercised and every executable planted half run. Two legs remain `N/A-engine`, and (L9)'s defensive projection re-check remains unexercised. **"UNEXECUTED" stood in this line until S11/F5 and was stale.** What has **not** changed: this is still a **draft**, `AGW-D15` ratifies only with the CP issuance contract ([`design.md`](design.md) §13 AGW-18); `AGW-D16` only with AGW-17 **and** AGW-20, its retirement clauses also with AGW-19 (§10); §10's other gates are untouched by execution; no product code carries any of this; and nothing here may be implemented as settled (`I4`). A green run says the statements do what the prose says — not that the prose may be built.
**Owner decisions:** [`decisions.md`](decisions.md) — `AGW-D15` (the DP-side lease store), `AGW-D16` (the DP-minted replica registry). **Consumer:** [`design.md`](design.md) §3.2, §4.6, §5.2, §6.1, §7.4, §8, §14.

This document exists so that the ports, tests and obligations in [`design.md`](design.md) can name the artifacts they depend on; the artifacts themselves are **this document's**, so the core gateway family (`AGW-D1`…`AGW-D14`) does not imply their correctness. The split is the round-6 and round-7 reviewers' meta-finding applied: the lease ledger's SQL and the registry's registration machinery are separate subsystem designs, each gated on something no part of the gateway design owns, and each must stand or fall on its own review rather than riding a family that is otherwise about sessions, ports and delivery. `design.md` keeps the tables whose semantics the gateway path itself defines (`agent_fleet_epoch`, `agent_identity`, `agent_session`, `agent_claim`, `agent_request_tombstone`, `agent_command`) and states, as a *contract*, what the mechanisms below must guarantee; everything that could be wrong about *how* they guarantee it is here.

**What this sketch owns**

| Artifact | Kind |
|---|---|
| `sm_data.agent_lease_grant` + `sm_data.agent_lease_grant_seq`, `sm_data.agent_lease` | DDL (§2.1, §2.2) |
| `sm_data.agent_replica`, `sm_data.agent_replica_cap`, `sm_data.agent_replica_incarnation` | DDL (§2.3–§2.5) |
| The lease request protocol, steps 0–3 (reserve · CP call · fenced completion · adoption · takeover) | SQL (§3) |
| The replica registration transaction (caps, one active incarnation, out-of-order reports) | SQL (§4) |
| Retirement and retention of both registry levels | SQL (§5) |
| The tombstone / advisory-lock interplay **as it applies to the lease ledger** | rule (§6) |
| The DP role grants and refusals for the five tables and the sequence | DCL (§7) |
| Obligations — the execution record this sketch must produce; **§8's own header carries the current count** | obligations (§8) |
| The `design.md` §8 rows that test it, **by number** (the rows stay there) | test map (§9) |
| The gates that must clear before any of it is settled | gates (§10) |

✅ **Five tables, executed (S11/F5).** These CHECKs, indexes, triggers and statements **have** been run, on `PostgreSQL 14.24`, extracted by line range from the committed tree and never rewritten by the harness: [`evidence/pg14-execution.md`](evidence/pg14-execution.md), `8551e5c`, 224 legs, 0 `FAIL`. `design.md` §12 limit 6 still applies in its **other** half: execution was the *measurable* part of "settled", and the rest — §10's four gates, the `AGW-D*` ratification order, and an implementation that does not exist — is not measurable and is not met. `BE-D13`'s sketch was executed before it was called settled; this one has been executed and is **not** being called settled.

---

## 1. The contract these mechanisms must meet

Stated first, so that the SQL below can be judged against something. `design.md` states the same two paragraphs as the consumer side of this contract (§5.2).

**The lease store (`AGW-D15`).** Three operations. `current` is a **read** — it returns an unexpired, certificate-bound projection with no CP call and is **not ledgered** (§3.0). `issue` takes the one nonce-free grant of an identity's life and is refused for any identity that has ever held a lease. `renew` takes every later grant under `SM-D28`'s single-use nonce. For `issue` and `renew`: a retry under the same request id gets **its own** original outcome and only through a session that currently owns the agent; a request whose ledger row retention removed is `REQUEST_EXPIRED` from a tombstone, never a second grant; a completion released after the session was re-claimed writes **nothing at all** — no projection and no ledger change — so the request stays `PENDING` and the agent's next owner can still re-issue it under the same request id (C-R9/F1); a timed-out request is **taken over under its own id**, never abandoned, because on a `renew` abandoning it would consume the agent's only renewal credential; and the projection `agent_lease` carries the claims, the certificate binding, both epochs and the delivered ceilings and margin as typed, CHECK-validated columns, because it is the snapshot the delivery poll joins and the report intake reads.

**The registry (`AGW-D16`).** Two levels. Ids are **DP-minted**: `replica_id` once per `(binding, replica_ref)` and reused for the life of the replica (it is the series `source_key` surrogate, [`../../architecture.md`](../../architecture.md) §8), `incarnation_id` once per **activation** of an incarnation — never re-used by un-retiring an old row (C-R9/F4) — and living outside series identity. Only a `TRUSTED` `ActualStateReport` registers anything. A string is admitted only under a closed grammar. Both levels are **capped**, and the **stable** cap is a locked cardinality row rather than prose, counting **active** rows only, so a fleet that churns replica names does not exhaust it permanently; the **ephemeral** cap is a count over active *and* retired-but-unpruned rows, so it is released by `registry_retention` rather than by retirement. **The counting differs; the serialization does not:** the **binding-wide `agent_replica_cap` row**, taken `FOR UPDATE` by every registration on the binding and held to transaction end, serializes registrations at **both** levels; only the **stable** level's cardinality is stored in that row, and the per-agent identity lock provides **report ordering within one agent**, never the cross-agent arbiter for the ephemeral cap (§4's cap note). **Exactly one incarnation of a replica is active**, by index rather than by prose. Retirement is a timestamp, not a delete; deletion is the migrator's and only when nothing references the row.

---

## 2. DDL

### 2.1 `agent_lease_grant` and its sequence

```sql
-- sm_data.agent_lease_grant — request/outcome ledger for issue and renew, keyed by the port requestId. Written BEFORE the CP is
-- called (state PENDING: the reservation that makes concurrent duplicates and post-crash retries observe one another), completed
-- EXACTLY ONCE (COMPLETED with the grant as relayed and its validation outcome, or FAILED with a code). `current` is a READ and is
-- NOT ledgered (§3.0). Retained ledger_retention; every deleted row leaves an agent_request_tombstone (design.md §5.2).
CREATE SEQUENCE sm_data.agent_lease_grant_seq;                     -- explicit, because bigserial's implicit sequence needs a USAGE
                                                                    -- grant no grant list can name before the column exists (F9/R7)
CREATE TABLE sm_data.agent_lease_grant (
  request_id          uuid        NOT NULL PRIMARY KEY,           -- gateway-derived from the wire message_id (design.md §3.1 rule 2); also the id the CP is called with (§13 AGW-18)
  grant_seq           bigint      NOT NULL UNIQUE DEFAULT nextval('sm_data.agent_lease_grant_seq'),  -- total order: ties on issued_at are broken by this, so exactly one grant is ever "current"
  tenant_id           uuid        NOT NULL,
  agent_id            uuid        NOT NULL,
  fencing_epoch       bigint      NOT NULL,                       -- the epoch the request is CURRENTLY bound to; updatable by epoch transfer and by takeover (§3.1), never by anything else
  kind                text        NOT NULL,                       -- 'issue' | 'renew'
  request_fingerprint bytea       NOT NULL,                       -- sha256 over the canonical encoding of every EPOCH-INDEPENDENT input: kind, agent_id, cert_thumbprint, nonce_consumed (renew only), agent_clock, protocol_version, agent_version.
                                                                  -- The epoch is deliberately NOT in the fingerprint: a gateway crash raises the epoch, and a request whose identity included the old epoch could never be recovered from the new one (F1/R7).
  attempt_no          integer     NOT NULL DEFAULT 1,             -- which takeover attempt owns the request now
  attempt_owner       text        NOT NULL,                       -- the replica id that owns THIS attempt
  attempt_deadline    timestamptz NOT NULL,                       -- requested_at + pending_ttl at reservation; now() + pending_ttl at each takeover
  state               text        NOT NULL DEFAULT 'PENDING',
  outcome             text,                                       -- COMPLETED: 'PROJECTED' | 'SUPERSEDED_ON_ARRIVAL'; FAILED: 'GRANT_INVALID' | 'LEASE_KEY_MISMATCH' | 'CP_REFUSED' | 'TIMEOUT'.
                                                                  -- There is deliberately NO 'STALE_EPOCH' outcome (C-R9/F1): losing the fence leaves the request PENDING and
                                                                  -- RECOVERABLE, because a terminal row is one no later owner can re-drive and that no AGW-18 contract could repair.
  lease_jti           text,                                       -- unique per issued grant (index below). NULL until completion: pending_has_no_result is what stops a
                                                                  -- takeover or a transfer parking a jti on a PENDING row and poisoning the unique index (C-R9/F8).
  issued_at           timestamptz,
  expires_at          timestamptz,
  grant_payload       jsonb,                                      -- the LeaseGrant exactly as relayed (COMPLETED only): the stored reply.
                                                                  -- NOT named `grant`: that is a PostgreSQL reserved word and the CREATE TABLE does not parse with it (S9/C1).
  requested_at        timestamptz NOT NULL DEFAULT now(),
  completed_at        timestamptz,
  FOREIGN KEY (tenant_id, agent_id) REFERENCES sm_data.agent_identity (tenant_id, agent_id),
  CONSTRAINT kind_known          CHECK (kind IN ('issue','renew')),
  CONSTRAINT grant_state_known   CHECK (state IN ('PENDING','COMPLETED','FAILED')),
  CONSTRAINT attempt_positive    CHECK (attempt_no >= 1),
  CONSTRAINT completed_has_grant CHECK ((state = 'COMPLETED') = (grant_payload IS NOT NULL AND lease_jti IS NOT NULL AND issued_at IS NOT NULL AND expires_at IS NOT NULL AND outcome IN ('PROJECTED','SUPERSEDED_ON_ARRIVAL'))),
  CONSTRAINT failed_has_code     CHECK (state <> 'FAILED' OR outcome IN ('GRANT_INVALID','LEASE_KEY_MISMATCH','CP_REFUSED','TIMEOUT')),
  CONSTRAINT terminal_has_time   CHECK ((state <> 'PENDING') = (completed_at IS NOT NULL)),
  -- A PENDING row carries NO result columns at all (C-R9/F8). The runtime role's UPDATE grant necessarily includes the result
  -- columns (completion writes them), and the trigger's two PENDING → PENDING branches constrain only the attempt and the epoch —
  -- so without this CHECK a takeover could carry lease_jti = 'J' on a still-PENDING row, and agent_lease_grant_jti would then
  -- refuse the LEGITIMATE grant when it arrived. The trigger's branches also require every non-branch column unchanged (§2.1);
  -- this is the half that holds even for a statement the trigger's branch predicates happen to satisfy.
  CONSTRAINT pending_has_no_result CHECK (state <> 'PENDING'
                                          OR (outcome IS NULL AND lease_jti IS NULL AND issued_at IS NULL
                                              AND expires_at IS NULL AND grant_payload IS NULL)),
  -- A FAILED row holds NO grant material either (R10/F8). completed_has_grant constrains only the COMPLETED side, so a FAILED row
  -- could carry a jti, a window and the grant JSON — occupying agent_lease_grant_jti against a grant that was never accepted, and
  -- storing a relayed grant on a request the DP refused. FAILED means: a code, a completion time, and nothing else.
  CONSTRAINT failed_has_no_grant CHECK (state <> 'FAILED'
                                        OR (grant_payload IS NULL AND lease_jti IS NULL AND issued_at IS NULL AND expires_at IS NULL))
);
ALTER SEQUENCE sm_data.agent_lease_grant_seq OWNED BY sm_data.agent_lease_grant.grant_seq;   -- dropped with the table, as bigserial would have been
CREATE UNIQUE INDEX agent_lease_grant_jti        ON sm_data.agent_lease_grant (lease_jti);              -- one ledger row per CP-issued jti
CREATE UNIQUE INDEX agent_lease_grant_issue_once ON sm_data.agent_lease_grant (agent_id) WHERE kind = 'issue';   -- THE once-per-identity arbiter (F2/R7): a second issue request for the same agent cannot even be reserved
CREATE INDEX agent_lease_grant_pending   ON sm_data.agent_lease_grant (agent_id, attempt_deadline) WHERE state = 'PENDING';
CREATE INDEX agent_lease_grant_retention ON sm_data.agent_lease_grant (requested_at);

-- ⚠️ THE TERMINAL-ONCE GUARD, AS EXECUTABLE DDL (S9/C4). Until S9 this was a pseudo-code block in a fence with no language
-- ("allowed iff … else RAISE") while ten sentences across the design set cited it as THE enforcing artifact — L19 at its most
-- literal. It is now a function and a trigger; the prose below the block is the explanation, and THIS is the artifact.
-- Shape follows the house pattern of ../eda-walking-skeleton.md §3 (`instance_watermark_only_rises`,
-- `forbid_terminal_regression`): plpgsql, BEFORE UPDATE FOR EACH ROW, RETURN NEW on a sanctioned transition, RAISE otherwise.
-- ONE deliberate divergence: each RAISE carries an explicit SQLSTATE, because a bare RAISE EXCEPTION is `raise_exception`
-- (P0001) for every branch ("If no condition name nor SQLSTATE is specified in a RAISE EXCEPTION command, the default is to use
-- raise_exception (P0001)" — PG 14 docs, plpgsql-errors-and-messages; verified 2026-09-10) and (L6) has to assert WHICH branch
-- refused. The codes are user-defined, which the same page permits: "you are not limited to the predefined error codes, but can
-- select any error code consisting of five digits and/or upper-case ASCII letters, other than 00000" — class `AG` is not one of
-- the predefined classes of Appendix A, and none of these ends in three zeroes.
-- Ownership: created by the migrator under SET ROLE sm_dp_owner, like the tables. The runtime role needs NO grant on the
-- function: "the default privileges granted to PUBLIC are as follows: … EXECUTE privilege for functions and procedures"
-- (PG 14 docs, ddl-priv; verified 2026-09-10) — and the trigger runs "with the privileges of users who modify" the table
-- (same page, on the TRIGGER privilege), so it fires for sm_dp_runtime's UPDATEs without appearing in §7's grant list.
CREATE FUNCTION sm_data.agent_lease_grant_terminal_once() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    unchanged_result boolean;
BEGIN
    unchanged_result := NEW.outcome       IS NOT DISTINCT FROM OLD.outcome
                    AND NEW.lease_jti     IS NOT DISTINCT FROM OLD.lease_jti
                    AND NEW.issued_at     IS NOT DISTINCT FROM OLD.issued_at
                    AND NEW.expires_at    IS NOT DISTINCT FROM OLD.expires_at
                    AND NEW.grant_payload IS NOT DISTINCT FROM OLD.grant_payload
                    AND NEW.completed_at  IS NOT DISTINCT FROM OLD.completed_at;

    -- The request identity is writable on NO branch. §7's column-scoped grant says the same thing for sm_dp_runtime;
    -- this half also holds for the owner and the migrator, and it is what refuses a PENDING → PENDING fingerprint rewrite.
    IF NEW.request_id          IS DISTINCT FROM OLD.request_id
    OR NEW.tenant_id           IS DISTINCT FROM OLD.tenant_id
    OR NEW.agent_id            IS DISTINCT FROM OLD.agent_id
    OR NEW.kind                IS DISTINCT FROM OLD.kind
    OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
    OR NEW.grant_seq           IS DISTINCT FROM OLD.grant_seq
    OR NEW.requested_at        IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'lease request % identity columns are immutable', OLD.request_id
            USING ERRCODE = 'AGW10';
    END IF;

    IF OLD.state <> 'PENDING' THEN
        RAISE EXCEPTION 'lease request % is terminal (%) and immutable', OLD.request_id, OLD.state
            USING ERRCODE = 'AGW11';
    END IF;

    IF NEW.state IN ('COMPLETED','FAILED') THEN                     -- branch 1: completion (§3.3 step 3c)
        IF  NEW.attempt_no       = OLD.attempt_no
        AND NEW.attempt_owner    = OLD.attempt_owner
        AND NEW.attempt_deadline = OLD.attempt_deadline
        AND NEW.fencing_epoch    = OLD.fencing_epoch THEN
            RETURN NEW;                                             -- may write: state, outcome, lease_jti, issued_at,
        END IF;                                                     --            expires_at, grant_payload, completed_at
        RAISE EXCEPTION 'lease request % completion may not move the attempt controls (attempt %->%, owner %->%, deadline %->%, epoch %->%)',
              OLD.request_id, OLD.attempt_no, NEW.attempt_no, OLD.attempt_owner, NEW.attempt_owner,
              OLD.attempt_deadline, NEW.attempt_deadline, OLD.fencing_epoch, NEW.fencing_epoch
            USING ERRCODE = 'AGW12';
    END IF;

    IF NEW.state = 'PENDING' AND NEW.attempt_no = OLD.attempt_no + 1 THEN    -- branch 2: takeover (§3.4)
        IF  OLD.attempt_deadline < now()
        AND NEW.fencing_epoch   >= OLD.fencing_epoch
        AND unchanged_result THEN
            RETURN NEW;                                             -- may write: attempt_no, attempt_owner,
        END IF;                                                     --            attempt_deadline, fencing_epoch (upward)
        RAISE EXCEPTION 'lease request % takeover refused (deadline % vs now, epoch %->%, result columns %)',
              OLD.request_id, OLD.attempt_deadline, OLD.fencing_epoch, NEW.fencing_epoch,
              CASE WHEN unchanged_result THEN 'unchanged' ELSE 'written' END
            USING ERRCODE = 'AGW13';
    END IF;

    IF NEW.state = 'PENDING' AND NEW.attempt_no = OLD.attempt_no THEN        -- branch 3: epoch transfer (§3.1)
        IF  NEW.fencing_epoch > OLD.fencing_epoch
        AND unchanged_result THEN
            RETURN NEW;                                             -- may write: fencing_epoch (strictly up),
        END IF;                                                     --            attempt_owner, attempt_deadline
        RAISE EXCEPTION 'lease request % epoch transfer refused (epoch %->%, result columns %)',
              OLD.request_id, OLD.fencing_epoch, NEW.fencing_epoch,
              CASE WHEN unchanged_result THEN 'unchanged' ELSE 'written' END
            USING ERRCODE = 'AGW14';
    END IF;

    RAISE EXCEPTION 'lease request % admits no such transition (state %->%, attempt %->%)',
          OLD.request_id, OLD.state, NEW.state, OLD.attempt_no, NEW.attempt_no
        USING ERRCODE = 'AGW15';
END $$;
CREATE TRIGGER agent_lease_grant_terminal_once BEFORE UPDATE ON sm_data.agent_lease_grant
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_lease_grant_terminal_once();
    -- The trigger deliberately carries the SAME name as its function: the design set cites
    -- "the agent_lease_grant_terminal_once trigger" in ten places, and the two live in different namespaces.
```

**What the trigger above says, as a predicate** — it admits exactly three transitions and `RAISE`s on everything else; obligation (L6), which now names the SQLSTATE each branch raises. This block is the **explanation**; the function in the DDL above is the artifact (S9/C4):

```
-- UNCHANGED_RESULT is shorthand for: NEW.outcome IS NOT DISTINCT FROM OLD.outcome AND NEW.lease_jti IS NOT DISTINCT FROM OLD.lease_jti
--   AND NEW.issued_at IS NOT DISTINCT FROM OLD.issued_at AND NEW.expires_at IS NOT DISTINCT FROM OLD.expires_at
--   AND NEW.grant_payload IS NOT DISTINCT FROM OLD.grant_payload AND NEW.completed_at IS NOT DISTINCT FROM OLD.completed_at
--   — EXACTLY those six result columns and nothing else, which is how the function above computes it. The ATTEMPT controls
--   (attempt_no, attempt_owner, attempt_deadline, fencing_epoch) are governed per branch below, not by UNCHANGED_RESULT — C-R9/F8

allowed iff (OLD.state = 'PENDING' AND NEW.state IN ('COMPLETED','FAILED')                          -- completion: may write the result columns
             AND NEW.attempt_no = OLD.attempt_no AND NEW.attempt_owner = OLD.attempt_owner           -- and NOT the attempt controls or the
             AND NEW.attempt_deadline = OLD.attempt_deadline AND NEW.fencing_epoch = OLD.fencing_epoch)  -- epoch (R10/F8)
         OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING'                                        -- takeover (§3.4): may change attempt_no,
             AND NEW.attempt_no = OLD.attempt_no + 1 AND OLD.attempt_deadline < now()               --   attempt_owner, attempt_deadline, fencing_epoch
             AND NEW.fencing_epoch >= OLD.fencing_epoch                                             -- upward only, never downward (R8/F1)
             AND UNCHANGED_RESULT)                                                                  -- and NOTHING else (C-R9/F8)
         OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING'                                        -- epoch transfer (§3.1): may change
             AND NEW.attempt_no = OLD.attempt_no AND NEW.fencing_epoch > OLD.fencing_epoch          --   fencing_epoch (strictly up),
             AND UNCHANGED_RESULT)                                                                  --   attempt_owner and attempt_deadline
                                                                                                    --   — and NOTHING else (S1/F3)
else RAISE
```

**What the completion branch may write, exhaustively** (R10/F8): `state`, `outcome`, `lease_jti`, `issued_at`, `expires_at`, `grant_payload` and `completed_at` — and **not** `attempt_no`, `attempt_owner`, `attempt_deadline` or `fencing_epoch`. A completion that also moved the attempt controls would rewrite the ownership record of the very attempt it is completing, which is what makes an audit of "who completed this, at which attempt, on which epoch" answerable at all; and combined with `failed_has_no_grant` it means a terminal row's attempt columns are exactly what step 3b locked.

**What each `PENDING → PENDING` branch may write, exhaustively** (the list is the predicate, not a comment): the **takeover** branch may change `attempt_no`, `attempt_owner`, `attempt_deadline` and `fencing_epoch`; the **epoch transfer** branch may change `fencing_epoch`, `attempt_owner` and `attempt_deadline` — the ownership tuple — while `attempt_no` stays put, which is exactly what distinguishes an adoption from a takeover (S1/F3). Every other column — `outcome`, `lease_jti`, `issued_at`, `expires_at`, `grant_payload`, `completed_at`, and the identity columns the DCL already makes unwritable (§7) — must compare `IS NOT DISTINCT FROM` its old value, so a `PENDING` row cannot acquire a result under either recovery. `pending_has_no_result` (§2.1 DDL) is the same rule expressed as a row invariant, and the two are deliberately redundant: the CHECK holds for any statement whatever, the trigger localizes the failure to the transition that attempted it.

Checking `OLD.state` alone would let a `PENDING` row be mutated while staying `PENDING`, which is how a fingerprint or a reservation could be rewritten under a concurrent caller; and admitting no `PENDING → PENDING` transition at all is what made the round-6 form unable to express either recovery — the crash case (`fencing_epoch` moves) or the timeout case (`attempt_no` moves). Both halves of every branch are the predicate, and **the request identity columns (`request_id`, `agent_id`, `kind`, `request_fingerprint`) are not writable at all** (§7).

**Why the takeover branch also guards the epoch (R8).** §3.4's takeover statement writes `fencing_epoch = $epoch` alongside the attempt, and the round-7 form of this trigger constrained the epoch only on the *transfer* branch. A caller holding a stale epoch could therefore take a post-deadline request over and **lower** its epoch binding, after which the step-3 fence (§3.3) can never match a live session — so that caller can never complete the request, and every later owner has to wait out the new `attempt_deadline` before taking it over again. Since round 9 that is a delay rather than a loss (a lost fence writes nothing, §3.3), but it is still a delay the epoch guard removes. The `>=` closes the schema half — it stops the epoch going *backwards* whatever statement writes it. It does **not** decide *which* epoch may be written: the trigger cannot see whether `$epoch` is live, so a takeover binding the row to a raised-but-already-superseded epoch satisfies it. That half is §3.4's `WITH s … FOR SHARE OF se` fence, added for C1; the two together are what make the takeover's epoch both monotonic and current. (L6) tests the trigger's directions, (L3) the fence's window.

**Honest consequence of `agent_lease_grant_issue_once`, and the one case it does not survive.** The index binds an identity to **one** `issue` row **for the life of that ledger row**, whatever its outcome. A first `issue` that ends `FAILED` is therefore retried only **under its own request id** (the row is terminal, so the retry returns the stored refusal); a *new* request id for the same agent is refused `ISSUE_NOT_ALLOWED` while the row exists, and the operator path is re-enrolment. That is the intended reading — a nonce-free grant is not something a caller may re-attempt under a fresh identity.

⚠️ **The index is the in-horizon arbiter; it is not the permanent one, and the two do not cover the same set.** The permanent arbiter is the **existence of an `agent_lease` row** (§2.2), which is never deleted — so an identity that has ever *held* a lease is refused `issue` forever, past any retention sweep. But a first `issue` that ended `FAILED` (`GRANT_INVALID`, or `CP_REFUSED` after the CP had already answered) projects **no** `agent_lease` row, and §5.3's retention `DELETE` spares only the row a live projection names — so after `ledger_retention` **both** arbiters are gone for that identity: the index row is deleted, the tombstone is keyed by the *old* request id, and a session presenting a new `message_id` derives a **new** request id that the tombstone does not match. Such an identity can then take a second nonce-free `issue`. Two consequences, both stated rather than claimed away:

- **What is *not* breached.** `SM-D28`'s single-use renewal nonce is untouched: the reopened path exists only for an identity that never held a projected lease, so no nonce was ever issued to it and none can be bypassed. The `issue`-once *authorization* rule — "at most one nonce-free grant per identity that holds a lease" — still has a retention-proof artifact.
- **What is breached.** A CP grant may have been minted for the `FAILED` request and be live on its TTL (§12 limit 17 of [`design.md`](design.md)); a second `issue` past the horizon can therefore produce a **second live grant** for one identity, the newer of which wins the projection's total order while the older remains valid authority at the CP until it expires. The mitigation available today is configuration, not schema: `ledger_retention` ≫ the lease duration (already required, `design.md` §5.8) keeps the window shut in practice. A retention-proof artifact would need a per-identity marker outside the ledger — a `first_issue_at` column on `agent_identity`, or an `agent_lease` row written `FAILED` — and **that is a change to a `design.md` §5.2 table, so it is not made here**: it is routed with the rest of this sketch's gates (§10) and is why (L7) carries an explicit post-retention leg that **measures and records** this behaviour rather than asserting it away.

### 2.2 `agent_lease` — the projection

```sql
-- sm_data.agent_lease — the DP-side lease store (AGW-D15): the CURRENT CP-issued grant per agent, a PROJECTION of the newest
-- COMPLETED row in agent_lease_grant, WITH the admission inputs it delivered as typed, CHECK-validated columns so the DP's gate
-- and the agent's gate read the same snapshot. The delivery poll and the report intake read THIS row, never the wire.
-- A row is NEVER deleted, not even when the lease expires: expiry is a property of expires_at, and the row's EXISTENCE is the durable
-- "this identity has been granted a lease before" test that makes LeasePort.issue a once-per-identity operation (design.md §3.2, §4.6).
CREATE TABLE sm_data.agent_lease (
  tenant_id               uuid        NOT NULL,
  agent_id                uuid        NOT NULL,
  lease_jti               text        NOT NULL,                 -- SM-D28 nonce/jti of the grant
  cert_thumbprint         bytea       NOT NULL,                 -- the cnf claim: the leaf cert this lease is bound to (SM-D28 "bound to the mTLS public key")
  target_id               uuid        NOT NULL,
  binding_ids             uuid[]      NOT NULL,
  capabilities            text[]      NOT NULL,                 -- closed enum on the wire (design.md §4.12); CHECK-bounded here
  agent_revocation_epoch  bigint      NOT NULL,
  fleet_revocation_epoch  bigint      NOT NULL,
  audience                text        NOT NULL,                 -- the logical DP identity (AGW-D7)
  issued_at               timestamptz NOT NULL,                 -- CP-issued absolute instant; the primary ordering key for projection
  expires_at              timestamptz NOT NULL,
  ceiling_preflight       interval    NOT NULL,                 -- verb_ceilings as delivered on the LeaseGrant, one typed column per operation (a snapshot, not live config)
  ceiling_apply           interval    NOT NULL,
  ceiling_restart         interval    NOT NULL,
  ceiling_stop            interval    NOT NULL,
  admission_margin        interval    NOT NULL,                 -- as delivered
  grant_request_id        uuid        NOT NULL,                 -- PROVENANCE, deliberately not an FK so retention can run
  grant_seq               bigint      NOT NULL,                 -- PROVENANCE, deliberately not an FK: the tie-breaker; projection advances iff (issued_at, grant_seq) is greater
  renewed_at              timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, agent_id),
  FOREIGN KEY (tenant_id, agent_id) REFERENCES sm_data.agent_identity (tenant_id, agent_id),
  -- No FK to agent_lease_grant on either provenance column. An FK's default NO ACTION would make the migrator's retention DELETE fail
  -- for exactly the row a live projection points at, so the ledger could never be pruned while the lease it produced is current.
  -- Referential safety comes from the retention job's own predicate instead (§7).
  UNIQUE (lease_jti),
  CONSTRAINT lease_caps_known    CHECK (capabilities <@ ARRAY['apply-config','lifecycle','report-only']::text[]),
  CONSTRAINT lease_window        CHECK (expires_at > issued_at),
  CONSTRAINT ceilings_nonneg     CHECK (ceiling_preflight >= interval '0' AND ceiling_apply >= interval '0' AND ceiling_restart >= interval '0' AND ceiling_stop >= interval '0'),
  CONSTRAINT margin_nonneg       CHECK (admission_margin >= interval '0'),
  CONSTRAINT lease_admits_work   CHECK (expires_at - issued_at > GREATEST(ceiling_preflight, ceiling_apply, ceiling_restart, ceiling_stop) + admission_margin)   -- SM-D28 / AG-3 invariant: lease_duration > max(verb_ceiling) + margin
);
```

These CHECKs are the **last** line, not the control flow: `dp-core` validates a grant against the same rules **before** writing anything (§3.3 step 2); a CHECK firing here means a validator bug and aborts the whole transaction ([`design.md`](design.md) §12 limit 17).

### 2.3 `agent_replica` — the stable level

```sql
-- sm_data.agent_replica — the STABLE half of the DP-minted replica registry (AGW-D16): one row per (binding, replica_ref) a TRUSTED
-- ActualStateReport asserted, given one DP uuid that lasts as long as the replica does. replica_id IS the series source_key surrogate
-- of architecture.md §8 — part of series identity, so it must NOT change when the workload restarts.
-- A replica is RETIRED (retired_at set), never deleted by the runtime; the cap counts ACTIVE rows only (§5.1), so replica-name churn
-- across deployments cannot exhaust it permanently (F6/R7). Runtime role: SELECT, INSERT, UPDATE (retired_at, reporter_agent_id, reporter_since).
CREATE TABLE sm_data.agent_replica (
  tenant_id            uuid        NOT NULL,
  binding_id           uuid        NOT NULL,
  replica_id           uuid        NOT NULL,                    -- DP-minted (UUIDv7): the STABLE label telemetry carries; minted once, reused for every later report
  replica_ref          text        NOT NULL,                    -- the agent's stable replica identity, grammar-validated at registration
  registered_at        timestamptz NOT NULL DEFAULT now(),
  registered_by_agent  uuid        NOT NULL,                    -- NOT NULL by design: only a trusted AGENT report registers here. DeclaredInstance producers do not use this registry at all (design.md §6.1)
  registered_by_report bigint      NOT NULL,                    -- the trusted report sequence that asserted it (audit)
  reporter_agent_id    uuid        NOT NULL,                    -- WHO MAY REPORT THIS REPLICA NOW (R10/F4). DP-owned, not agent-asserted.
                                                                -- Written at first registration (§4, B2, from the AUTHENTICATED agent) and
                                                                -- thereafter only by an admitted handoff (§4, B1) that has ALREADY cleared
                                                                -- every cap — a refused report never takes ownership (S2/F1).
                                                                -- A report from any other agent is a HANDOFF and is admitted only after
                                                                -- the current reporter has gone silent for reporter_handoff_grace (§4, A5–A7 with B1–B5).
                                                                -- Without this, "a different agent is ordered by DP receive order" let a
                                                                -- DELAYED report from a displaced agent — which passes its OWN watermark —
                                                                -- retire the live incarnation and reactivate a dead one.
  reporter_since       timestamptz NOT NULL DEFAULT now(),      -- DP receive time of the handoff that made reporter_agent_id current
  retired_at           timestamptz,                             -- set when the replica has had no active incarnation for replica_idle_retirement (§5.1)
  PRIMARY KEY (tenant_id, binding_id, replica_id),
  UNIQUE (tenant_id, binding_id, replica_ref),                  -- one stable id per reported replica identity, so a restart REUSES it
  UNIQUE (replica_id),                                          -- globally unique: labels carry it without a tenant
  FOREIGN KEY (tenant_id, registered_by_agent) REFERENCES sm_data.agent_identity (tenant_id, agent_id),
  FOREIGN KEY (tenant_id, reporter_agent_id)   REFERENCES sm_data.agent_identity (tenant_id, agent_id),
  CONSTRAINT reporter_since_sane CHECK (reporter_since >= registered_at),
  -- RFC 1123 hostname grammar (lowercase, per-label <= 63, total <= 253), per LABEL rather than by character class. The total-length
  -- bound travels inside the pattern as a positive lookahead: '~' presumes ARE and '(?=re)' is an ARE-only constraint
  -- (PostgreSQL 14 docs, Table 9.19, verified 2026-09-08).
  CONSTRAINT replica_ref_grammar CHECK (replica_ref ~ '^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$')
);
CREATE INDEX agent_replica_active    ON sm_data.agent_replica (tenant_id, binding_id) WHERE retired_at IS NULL;      -- the cap's reconciliation read
CREATE INDEX agent_replica_retention ON sm_data.agent_replica (retired_at) WHERE retired_at IS NOT NULL;
```

### 2.4 `agent_replica_cap` — the cardinality row

```sql
-- sm_data.agent_replica_cap — the per-binding cardinality row the registration transaction locks (F7/R7). Caps were prose; this row
-- is the artifact. One row per (tenant, binding), upserted and SELECT … FOR UPDATE'd by every registration, so two concurrent
-- registrations at cap−1 serialize and land at the cap rather than one past it. active_replicas counts agent_replica rows with
-- retired_at IS NULL and is decremented by the retirement statement (§5.1) — the cap frees as the fleet churns.
-- Runtime role: SELECT, INSERT, UPDATE (active_replicas).
CREATE TABLE sm_data.agent_replica_cap (
  tenant_id        uuid    NOT NULL,
  binding_id       uuid    NOT NULL,
  active_replicas  integer NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, binding_id),
  CONSTRAINT active_replicas_nonneg CHECK (active_replicas >= 0)
);
```

### 2.5 `agent_replica_incarnation` — the ephemeral level

```sql
-- sm_data.agent_replica_incarnation — the EPHEMERAL half (AGW-D16). One row per ACTIVATION of an incarnation of a stable replica;
-- incarnation_id lives in labels_jsonb OUTSIDE series identity, so a restart is visible without splitting the series
-- (architecture.md §8). Exactly one active incarnation per replica, by partial unique index rather than by prose (F7/R7). A newer
-- report retires the active one and inserts the new one in ONE transaction (§4); the migrator deletes retired rows after
-- registry_retention (§5.2) and retires an incarnation that has gone SILENT (§5.0).
-- ROWS ARE NEVER UN-RETIRED (C-R9/F4). Round 8 let a returning incarnation string re-activate its old row, which kept the old
-- registered_at and so gave that row the window [t1, t4) — overlapping its own successor's whole lifetime, while §6.1 and (L15)
-- claimed successive windows are disjoint. Each activation is therefore its own row with its own DP-minted incarnation_id and its
-- own half-open interval, and UNIQUE (replica_id, incarnation) is deliberately ABSENT: the same incarnation string may appear in
-- several RETIRED rows of one replica, and it is agent_replica_one_active — not a uniqueness constraint on the string — that keeps
-- exactly one of them live. "A re-reported incarnation reuses its id" therefore holds only while that incarnation is the ACTIVE one
-- (§4, A6 = `same` with B3), which is the only case an agent can observe.
-- Runtime role: SELECT, INSERT, UPDATE (retired_at, last_seen_at, last_seen_report, last_seen_by_agent).
CREATE TABLE sm_data.agent_replica_incarnation (
  replica_id           uuid        NOT NULL,
  incarnation_id       uuid        NOT NULL,                    -- DP-minted, once per ACTIVATION: the ephemeral label
  incarnation          text        NOT NULL,                    -- the agent's ephemeral identity, grammar-validated
  registered_at        timestamptz NOT NULL DEFAULT now(),
  registered_by_report bigint      NOT NULL,
  registered_by_agent  uuid        NOT NULL,                    -- WHICH agent asserted this activation (C-R9/F7): the report `sequence` is
                                                                -- per-agent, so the stale guard of §4 A7c may only compare sequences from the
                                                                -- SAME agent. A replica whose collector changes host (a StatefulSet ordinal
                                                                -- rescheduled onto another node, one collector per node in the go-agent
                                                                -- topology) is reported by a DIFFERENT agent whose sequence counter is
                                                                -- unrelated and may be smaller.
  last_seen_by_agent   uuid        NOT NULL,                    -- WHOSE watermark last_seen_report is (S1/F2). The watermark is
                                                                -- REPORTER-SCOPED: a `sequence` is a per-agent counter, so comparing
                                                                -- one agent's report against another agent's high-water mark is
                                                                -- meaningless — and round 10 did exactly that, leaving a new reporter
                                                                -- whose counter starts low unable to refresh liveness at all until it
                                                                -- passed the displaced agent's number, while §5.0 retired a workload
                                                                -- it was actively reporting. An admitted handoff re-seeds this column and
                                                                -- last_seen_report together — but ONLY on the activation the report NAMES,
                                                                -- i.e. only when the incoming incarnation IS the active one (§4, B3 gated on
                                                                -- A6 = `same`). On an activation CHANGE the outgoing row keeps the displaced
                                                                -- reporter's values and is retired carrying them (S2/F2), because these
                                                                -- columns describe the latest trusted report naming THAT activation.
  last_seen_report     bigint      NOT NULL,                    -- THE CURRENT REPORTER'S WATERMARK for this activation (R10/F6). registered_by_report
                                                                -- records only the report that CREATED the row and is not writable, so
                                                                -- without this column an OLD delayed same-incarnation report refreshed
                                                                -- liveness while "a stale report changes nothing" was claimed. The stale
                                                                -- check reads THIS column and the refresh advances it (§4, A5–A7 with B1–B5).
  last_seen_at         timestamptz NOT NULL DEFAULT now(),      -- DP RECEIVE TIME of the most recent TRUSTED report naming this activation
                                                                -- (C-R9/F3). Never the agent's clock. This is what makes a VANISHED replica
                                                                -- observable: an incarnation is retired only by a successor, so a pod that
                                                                -- disappears under a rollout would otherwise stay active for ever and its
                                                                -- stable replica would never go idle — permanently holding a cap slot.
  retired_at           timestamptz,                             -- set when a newer incarnation of the same replica registers, or when this
                                                                -- one has been silent for incarnation_idle_retirement (§5.0)
  PRIMARY KEY (replica_id, incarnation_id),
  UNIQUE (incarnation_id),
  FOREIGN KEY (replica_id) REFERENCES sm_data.agent_replica (replica_id),
  FOREIGN KEY (registered_by_agent) REFERENCES sm_data.agent_identity (agent_id),   -- agent_id is globally UNIQUE (design.md §5.2)
  FOREIGN KEY (last_seen_by_agent)  REFERENCES sm_data.agent_identity (agent_id),
  CONSTRAINT seen_after_registered CHECK (last_seen_at >= registered_at),
  -- NOTE (S1/F2): there is deliberately NO CHECK tying last_seen_report to registered_by_report. The watermark rises monotonically
  -- only WITHIN one reporter; an admitted handoff re-seeds it to the ADMITTING report's sequence, which may be far LOWER than the
  -- displaced reporter's counter — and only on the activation that report names. Monotonicity per reporter is enforced by the
  -- incumbent refresh statement's `last_seen_report < $sequence` predicate, guarded by `last_seen_by_agent = $agent` (§4, A7c and B3) — a constraint cannot express "monotonic per reporter"
  -- without storing a history, so this one is honestly a statement-level rule with (L12) as its test, not a schema-level one.
  CONSTRAINT incarnation_window CHECK (retired_at IS NULL OR retired_at > registered_at),    -- the F8 acceptance window [registered_at, retired_at) is
                                                                                             -- HALF-OPEN, so > (not >=) is what keeps it non-empty and
                                                                                             -- non-inverted. A row cannot be registered and retired at the
                                                                                             -- same instant: an incarnation is retired only by a SUCCESSOR's
                                                                                             -- registration (§4, B4 — which never touches the row B5 inserts) or
                                                                                             -- by the silence sweep (§5.0), both strictly later than
                                                                                             -- registered_at (C4).
  -- Exactly three alternatives, spelled out rather than approximated by a character class: uuid, hex id, decimal counter.
  CONSTRAINT incarnation_grammar CHECK (
       incarnation ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'   -- uuid (pod UID)
    OR incarnation ~ '^[0-9a-f]{12,64}$'                                                 -- hex id (container id)
    OR incarnation ~ '^[0-9]{1,20}$')                                                    -- decimal (process start epoch)
);
CREATE UNIQUE INDEX agent_replica_one_active ON sm_data.agent_replica_incarnation (replica_id) WHERE retired_at IS NULL;   -- ONE active incarnation, enforced (F7/R7)
CREATE INDEX agent_replica_incarnation_retention ON sm_data.agent_replica_incarnation (retired_at) WHERE retired_at IS NOT NULL;
CREATE INDEX agent_replica_incarnation_silent    ON sm_data.agent_replica_incarnation (last_seen_at) WHERE retired_at IS NULL;   -- the §5.0 silence sweep's read
```

---

## 3. The lease request protocol

`LeasePort.issue` and `LeasePort.renew` run steps 0–3. The `requestId` is **derived by the gateway from the wire `message_id`** of the `SessionOpen` or `LeaseRenew` that caused it ([`design.md`](design.md) §3.1 rule 2), so an agent's wire retry and a gateway's port retry are the same request.

### 3.0 `current` is a read, not a ledger entry

`LeasePort.current` reads the projection and returns it when it is unexpired and bound to the identity's certificate (the failover case, `AGW-D7`). It **writes nothing** and is **not idempotency-bearing**: like `resolve`, it is re-evaluated on replay ([`design.md`](design.md) §3.1 rule 2). Ledgering it was round 6's answer to "a retry must get its own reply", and round 7 showed the answer was wrong in two ways at once — the `NONE` outcome was not ledgered at all, so half its replies were unrecorded anyway, and a *successful* reply is time-sensitive (the projection it snapshots can be renewed a millisecond later), so replaying a stored one would return a lease the agent must not still be using. The session-resolution flow's idempotency is therefore the claim ledger's plus this ledger's for any `issue`/`renew` the flow causes; `current` contributes none and claims none.

### 3.1 Steps 0 and 1 — tombstone, reserve, adopt

```sql
-- Step 0a — SERIALIZE the request key against the retention job. Both this path and the retention job take the same
-- transaction-scoped advisory lock, so "tombstone absent" cannot be observed by a retry while the job is mid-delete (F3/R7):
SELECT pg_advisory_xact_lock(hashtextextended($request_id::text, 0));
--   Hash collisions cost spurious serialization between two unrelated request ids and NOTHING else: the lock orders, the
--   tombstone and the primary key decide. There is no correctness path through the hash.
-- Step 0b — TOMBSTONE CHECK, before anything else. The insert below is ON CONFLICT DO NOTHING, so a requestId whose row
-- retention already deleted would insert as NEW and become a second grant. One SELECT settles it:
SELECT 1 FROM sm_data.agent_request_tombstone WHERE request_id = $request_id;
--   found → REQUEST_EXPIRED: refused, audited, no write, no CP call. Not found → step 1.

-- Step 0c — THE IDENTITY LOCK, ITS OWN STATEMENT AND BEFORE THE FENCE (S8/C1; relabelled 0c in S8's correction pass because 0b is
-- the tombstone check above). Until S8 this lock lived in the `id` CTE of the
-- reservation below, beside the session fence's `s` CTE — which was wrong twice over: it took the two locks in the INVERSE order
-- to design.md §5.2's `claim` (identity first, then the session row), giving the same 40P01 cycle S8/F1 found in §4; and the order
-- WITHIN one statement is not the author's to choose. ⚠️ THE CITATION FOR THAT SECOND POINT WAS WRONG UNTIL S9/F2: this comment
-- quoted `queries-with`'s "the order in which the specified updates actually happen is unpredictable", which is a statement about
-- DATA-MODIFYING sub-statements and does NOT cover two SELECT … FOR UPDATE / FOR SHARE siblings. The honest claim is narrower and
-- still sufficient: the documentation specifies **no** acquisition order among sibling SELECT CTEs, and a side-effect-free one may
-- not survive as a separate step at all — "if a WITH query is non-recursive and side-effect-free (that is, it is a SELECT
-- containing no volatile functions) then it can be folded into the parent query … By default, this happens if the parent query
-- references the WITH query just once" (PG 14 docs, `queries-with`; verified 2026-09-10). A folded SELECT CTE's lock order is the
-- planner's, not the author's. Hoisting the identity lock into its own statement makes identity → session a matter of statement
-- sequence rather than planner choice — and it is why (L18)'s planted legs use an explicit two-statement inverse-order fixture and
-- never sibling CTEs, whose behaviour no test may demand (S9/F2).
-- ⚠️ THE LOCK MODE IS `FOR NO KEY UPDATE`, NOT `FOR UPDATE`, AND THAT IS LOAD-BEARING (S10/F4). Executing the S9 text on
--    PostgreSQL 14.24 deadlocked this path against a concurrent §3.3 step 3 in four of five runs. The cause is an edge §7's
--    model did not name: `agent_lease`, `agent_lease_grant`, `agent_session` and `agent_replica` all carry a FOREIGN KEY to
--    `agent_identity`, and "foreign key checks use the new KEY SHARE lock mode, which does not conflict with NO KEY UPDATE"
--    (PG 9.3 release notes; verified 2026-09-10) — while `FOR KEY SHARE` DOES conflict with `FOR UPDATE`, and with nothing
--    else (PG 14 docs, `explicit-locking` Table 13.3, Conflicting Row-Level Locks; verified 2026-09-10). So an identity row
--    held `FOR UPDATE` blocks every FK check on it, and step 3c's projection into `agent_lease` — a table LATER in the prefix
--    than the ledger row 3b already holds — waits on the identity row this step holds, while this step waits on that ledger
--    row: a cycle, measured. `FOR NO KEY UPDATE` conflicts with `FOR SHARE`, `FOR NO KEY UPDATE` and `FOR UPDATE` (same
--    table), so it still serializes two registrations for one agent and still serializes against `claim`'s `FOR SHARE` —
--    everything this lock was for — and it no longer blocks a sibling transaction's FK check.
SELECT 1 FROM sm_data.agent_identity WHERE agent_id = $agent AND revoked_at IS NULL FOR NO KEY UPDATE;
--   0 rows → the identity is revoked or unknown: refused, nothing reserved.
--   1 row  → this transaction holds the identity lock for its duration; continue to step 1.

-- Step 1 — RESERVE before the CP is called: the fenced reservation. `s` is the epoch-fenced session CTE (0 rows → STALE_EPOCH,
-- nothing reserved), and for kind = 'issue' the reservation requires that no lease has EVER been granted (F2/R7). `id` keeps its
-- predicates for readability, but its FOR NO KEY UPDATE is now a RE-LOCK of a row this transaction already holds — a no-op that
-- acquires nothing new — so the only lock this statement takes is the session's; the identity lock is step 0c's. The MODE must
-- match step 0c's (S10/F4): `FOR UPDATE` here would upgrade the row lock and block every concurrent FK check on the identity.
WITH s AS (
  SELECT se.tenant_id, se.agent_id, se.fencing_epoch FROM sm_data.agent_session se
   WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica   -- the fence; 0 rows → STALE_EPOCH
   FOR SHARE OF se
),
id AS (
  SELECT ai.tenant_id, ai.agent_id FROM sm_data.agent_identity ai
   WHERE ai.agent_id = $agent AND ai.revoked_at IS NULL
   FOR NO KEY UPDATE                                                   -- SAME MODE as step 0c (S10/F4): a `FOR UPDATE` here would
                                                                      -- upgrade the lock and re-introduce the FK-check cycle.
                                                                      -- re-lock of the row step 0c already holds (S8/C1): a no-op, kept
                                                                -- so the predicate stays visible in the reservation's own text
)
INSERT INTO sm_data.agent_lease_grant (request_id, tenant_id, agent_id, fencing_epoch, kind, request_fingerprint,
                                       attempt_owner, attempt_deadline)
SELECT $request_id, s.tenant_id, s.agent_id, $epoch, $kind, $fingerprint, $replica, now() + $pending_ttl
  FROM s JOIN id ON id.agent_id = s.agent_id
 WHERE $kind = 'renew'
    OR NOT EXISTS (SELECT 1 FROM sm_data.agent_lease l WHERE l.agent_id = $agent)   -- 'issue' only while never granted
ON CONFLICT (request_id) DO NOTHING
RETURNING request_id;
-- 1 row → this caller owns the request: go to step 2.
-- 0 rows, kind = 'issue', no conflicting row, and an agent_lease row exists  → ISSUE_NOT_ALLOWED (refused, audited, nothing written).
--   The unique index agent_lease_grant_issue_once refuses the same case durably even if this predicate is bypassed: a SECOND issue
--   request for the agent raises a unique violation rather than reserving, and dp-core maps it to ISSUE_NOT_ALLOWED. Two arbiters,
--   deliberately: the predicate is the readable rule, the index is the one that cannot be forgotten by a caller (L19).
-- 0 rows with a conflicting row → read it and classify, NEVER call the CP. The EPOCH TEST COMES FIRST (F1/R7):
--   Is the CALLER's epoch the agent's CURRENT epoch, owned by the caller?
--     SELECT 1 FROM sm_data.agent_session se WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica;
--     0 rows → STALE_EPOCH. Nothing is disclosed: not the state, not the grant, not whether the row exists.
--   Then, for a current-epoch caller:
--     request_fingerprint ≠ $fingerprint  → REQUEST_ID_REUSED (refused, audited). The fingerprint is epoch-INDEPENDENT, so a
--                                            recovered request compares equal across the epoch change that a crash caused.
--     state = COMPLETED                   → the stored grant, with current = (agent_lease.grant_request_id = request_id)
--     state = FAILED                      → the stored refusal code
--     state = PENDING, row.fencing_epoch = $epoch, attempt_deadline > now()   → IN_PROGRESS + retry_after (a concurrent duplicate)
--     state = PENDING, row.fencing_epoch < $epoch                            → EPOCH TRANSFER: the caller ADOPTS the request.
--       The adoption re-checks BOTH halves of "authenticated" in the statement itself, because the classification above is a
--       read and the session can be re-claimed between it and this write (R8/F1):
WITH s AS (                                                          -- CURRENT OWNER: $epoch must still be the agent's live, caller-owned epoch
  SELECT se.agent_id, se.fencing_epoch FROM sm_data.agent_session se
   WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica
   FOR SHARE OF se                                                   -- serializes against a claim committing concurrently
)
UPDATE sm_data.agent_lease_grant lg
   SET fencing_epoch    = s.fencing_epoch,
       attempt_owner    = $replica,                                  -- ADOPTION TRANSFERS OWNERSHIP, not just the epoch (S1/F3)
       attempt_deadline = now() + $pending_ttl                       -- and re-arms the deadline, so the adopted request is not
  FROM s                                                             -- simultaneously orphan-eligible (S1/F4)
 WHERE lg.request_id = $request_id AND lg.state = 'PENDING' AND lg.fencing_epoch < s.fencing_epoch
   AND lg.agent_id = s.agent_id                                      -- SAME AGENT: the row's own agent, not the caller's claim about it
RETURNING lg.request_id, lg.attempt_no;                              -- attempt_no is UNCHANGED: adoption is not a takeover
--     1 row → the request is now bound to the live session AND to this replica, with attempt_deadline re-armed by the same statement:
--              continue at step 2 (the CP call, after this transaction commits — §5.4's boundary note). A just-adopted request can
--              never need taking over first, because its deadline is now() + pending_ttl (D2 — the old parenthetical here said
--              otherwise and contradicted that re-arm). The TAKEOVER path is reached only from the classification branch below: a row
--              at the CALLER'S OWN epoch — so not adoptable — whose attempt_deadline has passed.
--     0 rows → the epoch moved, another caller adopted it, or the row is not this agent's: re-read and re-classify (STALE_EPOCH if
--              $epoch is no longer current). Without the s join the window is real: a caller that WAS the owner when it classified
--              could bind the request to an epoch that is no longer current, and step 3's fence would then match nothing, so that
--              caller could never complete the request and every later owner would wait out attempt_deadline first.
--     state = PENDING, attempt_deadline < now()                              → TAKEOVER (§3.4)
--   THE ADOPTED REQUEST IS NOW THIS REPLICA'S: attempt_owner names it, so step 3b — which requires attempt_owner = $replica —
--   admits the adopter and refuses the dead reserver's stale callback (S1/F3).
```

⚠️ **Why adoption transfers the ownership tuple rather than incrementing the attempt (S1/F3).** Round 9's adoption moved `fencing_epoch` alone, so after `R1` reserved a request and died and `R2` adopted and completed it, the terminal row still named **`R1`** as `attempt_owner` — the audit answered the wrong question, and worse, a stale `R1` callback could satisfy step 3b and acquire the completion lock. Adoption therefore moves **three** columns atomically — `fencing_epoch` (strictly up), `attempt_owner` (to the adopter) and `attempt_deadline` (re-armed) — and leaves `attempt_no` **unchanged**, because a takeover and an adoption are different failures: a takeover spends one of `takeover_attempts` on a *timed-out* request, while an adoption recovers a request whose gateway *died*, which is not the agent's fault and must not consume the recovery budget that protects the single-use nonce. The alternative the review offered — requiring a real takeover before the adopter may call the CP — was rejected for exactly that reason: it would burn the attempt budget on every gateway restart, and at `takeover_attempts` restarts a healthy agent would be forced to re-enrol.

**Both halves are enforced, not described.** The statement above writes the three columns; the trigger's transfer branch (§2.1) admits **exactly** those three and nothing else; and step 3b/3c require `attempt_owner = $replica`, so the transfer is what authorizes the adopter to complete. (L2) and (L4) carry the tests.

⚠️ **What the adoption leaves to AGW-18, and what it no longer leaves to chance.** A request whose session is re-claimed inside step 2's CP call is **not** driven terminal any more (C-R9/F1): step 3 writes nothing, the row stays `PENDING`, and the predicates above are what let the *new* owner — and only the new owner — pick it up and re-issue it under the same `request_id`. So the DP side of the recovery is closed in these statements. What is **not** closed here is whether the CP returns the original grant on that re-call: that is §13 AGW-18, and without it the row completes `FAILED('CP_REFUSED')` and the session is refused `RE_ENROLL_REQUIRED` ([`design.md`](design.md) §12 limit 17, §8 rows 39 and 42). Read "recovered" as "the request is never made unrecoverable by the DP", not as "the grant always comes back".

**Why the epoch left the fingerprint.** The round-6 form bound the request's *identity* to the epoch that created it. A gateway process death is exactly the case the ledger exists for, and it raises the epoch: the agent reconnects, claims epoch 8, and resends the same `LeaseRenew` — same `message_id`, therefore the same `request_id` — and the round-6 lookup answered `STALE_EPOCH`, which is the one answer that loses the consumed nonce forever. Splitting identity (the fingerprint, epoch-free) from binding (`fencing_epoch`, an updatable column guarded by the trigger) makes the recovery an **authenticated epoch transfer**: only a caller that *is* the agent's current session owner may adopt, the adoption is a monotonic epoch raise, and a reappearing epoch-7 stream still gets `STALE_EPOCH` because it is no longer current. [`design.md`](design.md) §8 row 42 is the test.

### 3.2 Step 2 — the CP call and validation

Call the CP **with the same `request_id`** (CP-side idempotency under it is routed, [`design.md`](design.md) §13 AGW-18) **outside any database transaction** — step 1's reservation, or the adoption or takeover that replaced it, has already committed (§5.4's boundary note), and holding a row lock or the request-key lock across an external call of unbounded latency would block the orphan sweep, the retention job and every retry of the same request — and **validate the grant in `dp-core` before writing anything**, against exactly the rules `agent_lease`'s CHECKs encode: non-negative ceilings and margin; `expires_at − issued_at > max ceiling + margin`; known capabilities; `cnf_thumbprint` = the identity's certificate; `audience` = this DP. An invalid grant is recorded `FAILED('GRANT_INVALID')` and never reaches the projection; the CHECKs are a backstop, not control flow.

### 3.3 Step 3 — take ownership, then check the fence, then complete and project

**Three statements in one transaction, in this order** (R10/F1). The ordering is the correctness argument, not a style choice: ownership of the attempt must be an **atomic, locked** decision taken *before* anything is projected, because the projection is a data-modifying CTE and cannot see a concurrent takeover.

```sql
-- Step 3a — THE FENCE, AND IT IS FIRST (reordered by S8/C1). The session must be live at the caller's OWN (agent, epoch) and owned
--           by the caller's replica. It precedes the ownership lock because §7's SHARED PREFIX is identity → session →
--           agent_lease_grant, and a step 3 that took the ledger row first would move backwards in that prefix.
--           ⚠️ S8/C1 ALSO CLAIMED THE OLD ORDER "CYCLES AGAINST A CONCURRENT RETRY OF STEP 1 ON THE SAME REQUEST ID". THAT WAS
--           WRONG, and executing it proved so (S10/F5): step 1 takes the session `FOR SHARE` and step 3's old form would have
--           taken it `FOR SHARE` too, and `FOR SHARE` does not conflict with `FOR SHARE` (PG 14 docs, `explicit-locking`
--           Table 13.3, Conflicting Row-Level Locks; verified 2026-09-10) — so that pair could not deadlock in either order.
--           The 3a/3b swap is a PREFIX-ORDER CONFORMANCE fix, which is reason enough; it was never a deadlock fix, and no test
--           may be written that demands a deadlock from the pre-S8/C1 form ((L18-N3)). Ownership is still
--           claimed before anything is projected — 3b's lock is held for the rest of the transaction and 3c requires it — so the
--           S3/F1 property survives the reordering unchanged.
SELECT se.tenant_id, se.agent_id, se.fencing_epoch
  FROM sm_data.agent_session se
 WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica
 FOR SHARE OF se;
--   tenant_id is taken from THIS row (and from 3b's locked row, which must agree) — never from caller input (I5).
--   0 rows → the session that owns this request has been re-claimed: STOP. Write NOTHING (R9/F1) — the row stays PENDING at its own
--            epoch and is adoptable by the current owner (§3.1). Discard the grant, disclose nothing, close the stream.
--   1 row  → claim ownership at 3b.

-- Step 3b — CLAIM OWNERSHIP OF THE ATTEMPT, and hold it. Nothing below runs unless this returns a row.
--           EVERY predicate here is part of the authorization: the request id ALONE authorizes nothing (S1/F1).
SELECT request_id, tenant_id, agent_id, fencing_epoch, grant_seq, attempt_no
  FROM sm_data.agent_lease_grant
 WHERE request_id    = $request_id
   AND agent_id      = $agent                                      -- THE CALLER'S AgentSessionRef.agentId — not merely "whatever agent this row names"
   AND fencing_epoch = $epoch                                      -- THE CALLER'S AgentSessionRef.fencingEpoch — must equal the row's current binding
   AND attempt_owner = $replica                                    -- and the caller must be the replica that OWNS this attempt (S1/F3)
   AND state         = 'PENDING' AND attempt_no = $mine
 FOR UPDATE;                                                       -- needs UPDATE privilege as well as SELECT; the runtime role has it (§7)
--   0 rows → the request is not this caller's to complete: a takeover moved attempt_no or attempt_owner, the row is terminal, the
--            caller's epoch is not the row's current binding, or THE ROW IS ANOTHER AGENT'S. STOP: write NOTHING, disclose NOTHING
--            (not the state, not the grant, not that the row exists), reply IN_PROGRESS or STALE_EPOCH per §3.1's classification,
--            and discard the grant held in memory.
--   1 row  → this caller owns the attempt AND HOLDS THE ROW LOCK for the rest of the transaction (and the session row from 3a), so no takeover, no transfer and no
--            orphan sweep can commit against it until this transaction ends ("Row-level locks are released at transaction end …",
--            PG 14 docs, explicit-locking, Row-Level Locks; verified 2026-09-09).
--   Under READ COMMITTED this is the atomic test, not a hopeful read: "The search condition of the command (the WHERE clause) is
--   re-evaluated to see if the updated version of the row still matches the search condition" (PG 14 docs, transaction-iso, Read
--   Committed; verified 2026-09-09) — so if a takeover committed while this statement waited, attempt_no no longer matches $mine and
--   the row is skipped rather than returned.

-- Step 3c — project iff newest, and complete exactly once. Safe now: 3b holds the ledger row's lock, so `s` below cannot go stale
--           underneath the projection and no other attempt can be writing this request.
```

```sql
WITH g AS (                                                        -- the ledger row this caller LOCKED at 3b — the SAME predicates,
  SELECT request_id, tenant_id, agent_id, fencing_epoch, grant_seq, attempt_no   -- so the statement is self-sufficient rather than
    FROM sm_data.agent_lease_grant                                 -- trusting that 3b ran (S1/F1)
   WHERE request_id = $request_id AND agent_id = $agent AND fencing_epoch = $epoch
     AND attempt_owner = $replica AND state = 'PENDING' AND attempt_no = $mine   -- still true: 3b's lock is held
),
s AS (                                                             -- THE FENCE, joined to the LOCKED ledger row AND pinned to the
  SELECT se.tenant_id, se.agent_id, se.fencing_epoch               -- caller's own (agent, epoch, replica): the row's binding and the
    FROM sm_data.agent_session se                                  -- caller's claim must be the same value, not merely compatible
    JOIN g ON se.agent_id = g.agent_id AND se.fencing_epoch = g.fencing_epoch
   WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch        -- S1/F1: without these two, one replica owning both A and B could
     AND se.owner_replica_id = $replica                            -- complete B's request from A's session handling
   FOR SHARE OF se                                                 -- a claim committing concurrently serializes against this
),
proj AS (
  INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_jti, cert_thumbprint, target_id, binding_ids, capabilities,
                                   agent_revocation_epoch, fleet_revocation_epoch, audience, issued_at, expires_at,
                                   ceiling_preflight, ceiling_apply, ceiling_restart, ceiling_stop, admission_margin, grant_request_id, grant_seq)
  SELECT g.tenant_id, g.agent_id, $jti, $cnf_thumbprint, $target, $bindings, $caps, $agent_epoch, $fleet_epoch, $audience,
         $issued_at, $expires_at, $c_preflight, $c_apply, $c_restart, $c_stop, $margin, g.request_id, g.grant_seq
    FROM g JOIN s ON s.tenant_id = g.tenant_id AND s.agent_id = g.agent_id   -- 0 rows from s → NOTHING is projected
   WHERE $valid
  ON CONFLICT (tenant_id, agent_id) DO UPDATE
     SET lease_jti = EXCLUDED.lease_jti, cert_thumbprint = EXCLUDED.cert_thumbprint, target_id = EXCLUDED.target_id,
         binding_ids = EXCLUDED.binding_ids, capabilities = EXCLUDED.capabilities,
         agent_revocation_epoch = EXCLUDED.agent_revocation_epoch, fleet_revocation_epoch = EXCLUDED.fleet_revocation_epoch,
         audience = EXCLUDED.audience, issued_at = EXCLUDED.issued_at, expires_at = EXCLUDED.expires_at,
         ceiling_preflight = EXCLUDED.ceiling_preflight, ceiling_apply = EXCLUDED.ceiling_apply,
         ceiling_restart = EXCLUDED.ceiling_restart, ceiling_stop = EXCLUDED.ceiling_stop,
         admission_margin = EXCLUDED.admission_margin, grant_request_id = EXCLUDED.grant_request_id, grant_seq = EXCLUDED.grant_seq, renewed_at = now()
   WHERE (EXCLUDED.issued_at, EXCLUDED.grant_seq) > (sm_data.agent_lease.issued_at, sm_data.agent_lease.grant_seq)   -- total order: newest wins, ties broken by grant_seq
  RETURNING grant_request_id
)
UPDATE sm_data.agent_lease_grant lg
   SET state   = CASE WHEN $valid THEN 'COMPLETED' ELSE 'FAILED' END,
       outcome = CASE WHEN NOT $valid THEN $refusal_code
                      WHEN EXISTS (SELECT 1 FROM proj) THEN 'PROJECTED' ELSE 'SUPERSEDED_ON_ARRIVAL' END,
       lease_jti  = CASE WHEN $valid THEN $jti END,
       issued_at  = CASE WHEN $valid THEN $issued_at END,
       expires_at = CASE WHEN $valid THEN $expires_at END,
       grant_payload = CASE WHEN $valid THEN $grant_json END,
       completed_at = now()
  FROM s                                                           -- THE FENCE AS A JOIN: s empty → 0 rows → NOTHING is written at all
 WHERE lg.request_id = $request_id AND lg.state = 'PENDING' AND lg.attempt_no = $mine   -- the attempt fence; terminal_once makes a second write impossible
   AND s.agent_id = lg.agent_id;
-- 1 row  → completed. The reply is the grant THIS request obtained, with current = (outcome = 'PROJECTED' and
--           agent_lease.grant_request_id still = request_id).
-- 0 rows → cannot happen once 3a and 3b have both returned a row: 3b's lock pins attempt_no and state, and 3a proved the fence.
--           It is kept as the belt-and-braces predicate it always was; if it ever fires, the transaction has a bug and writes nothing.
--           (Before R10/F1 this statement was step 3 in its entirety, and its zero-row case was load-bearing — which was the defect:
--           `proj` is a data-modifying CTE sharing the statement's snapshot, so it could install its grant into agent_lease while the
--           OUTER update matched zero rows against a concurrently taken-over row. The projection moved; the ledger did not; "0 rows
--           means this caller writes nothing" was false. Ownership is now decided and LOCKED at 3b, before any projection exists.)
```

⚠️ **Why ownership had to move into its own locked statement (R10/F1).** The round-9 form put the ownership test in a CTE (`g`) with no lock and re-checked `attempt_no = $mine` only in the outer `UPDATE`. Under READ COMMITTED the outer update's `WHERE` **is** re-evaluated against the committed row version, so it correctly wrote nothing after a concurrent takeover — but `proj` had already run in the same statement, against the statement's own snapshot, and had already written `agent_lease`. The interleaving: attempt 1 evaluates `g`; attempt 2's takeover commits; attempt 1's `proj` installs attempt 1's **older** grant as the current lease; attempt 1's outer `UPDATE` affects zero rows; the ledger row remains `PENDING` under attempt 2 while the projection now holds a grant no completed request records. Locking the ledger row first makes ownership and projection one decision: after 3b, a takeover cannot commit until this transaction ends, so `proj` can no longer outlive the ownership it assumed.

⚠️ **Why losing the fence writes nothing at all (C-R9/F1).** Until round 9 an empty `s` completed the row `FAILED('STALE_EPOCH')`. That is **terminal** — the trigger admits no transition out of `FAILED` — so §3.1's classification handed the stored failure to every later retry and **no owner ever re-called the CP**. A CP that honoured `request_id` (AGW-18 (a)) or offered a status lookup (AGW-18 (b)) would never have been consulted, which made the gate meaningless for the very case it exists for and contradicted the "the nonce is never spent on a grant no row records" sentence of [`design.md`](design.md) §5.2. Leaving the row `PENDING` is what keeps the two recoveries reachable, and it costs nothing: the row is already bounded by `attempt_deadline` and `takeover_attempts`, so it ends either recovered or `FAILED('TIMEOUT')` with `RE_ENROLL_REQUIRED` — never lingering.

**What is now recoverable inside the DP, and what still needs AGW-18.** *In-DP, no external contract needed:* the request **survives** the loss of its session — it stays `PENDING`, the new owner adopts it, and the DP re-issues the same request to the CP rather than starting a new one, so the DP never spends a second nonce and never abandons the first. *Still AGW-18's:* whether that re-call **returns the grant**. With (a) or (b) the original grant comes back and the nonce is spent exactly once; with neither, the CP sees a nonce it has already consumed, answers refused, the row completes `FAILED('CP_REFUSED')`, and the session is refused `RE_ENROLL_REQUIRED` — the same operator event as today's fallback, reached by the same bounded path. The difference round 9 forced is that this is now a *CP* limitation rather than a DP one: nothing in these statements forecloses the repair.

### 3.4 Takeover — a compare-and-swap on the attempt, not a comment

A `PENDING` request past its `attempt_deadline` is taken over **under its own id**, with owner, attempt number and deadline all moving in one statement, so "who owns this attempt now" is a row rather than a race (`F4`/R7):

```sql
WITH s AS (                                                        -- THE SAME FENCE §3.1's adoption takes: $epoch must still be the agent's
  SELECT se.agent_id, se.fencing_epoch                             -- live epoch, owned by THIS replica (C1)
    FROM sm_data.agent_session se
   WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica
   FOR SHARE OF se                                                 -- a claim committing concurrently serializes against this
)
UPDATE sm_data.agent_lease_grant lg
   SET attempt_no       = lg.attempt_no + 1,
       attempt_owner    = $replica,
       attempt_deadline = now() + $pending_ttl,
       fencing_epoch    = s.fencing_epoch                          -- the live epoch, read from the session row, not from the caller
  FROM s
 WHERE lg.request_id       = $request_id
   AND lg.agent_id         = s.agent_id                            -- SAME AGENT: the row's own agent, not the caller's claim about it
   AND lg.state            = 'PENDING'
   AND lg.attempt_deadline < now()
   AND lg.attempt_no       = $expected                             -- CAS: I read attempt N, I take over N+1
   AND lg.attempt_no       < $takeover_attempts
RETURNING lg.attempt_no;
-- 1 row → this caller owns attempt N+1: re-call the CP with the SAME request_id (step 2) and complete at step 3 with attempt_no = N+1.
-- 0 rows → one of: THE FENCE — $epoch is no longer the agent's current epoch, or this replica no longer owns it (s is empty) →
--                  STALE_EPOCH, nothing written, the row still takeable by whoever does own the agent now;
--                  someone else took it over (attempt_no moved) → IN_PROGRESS + retry_after;
--                  the deadline has not passed        → IN_PROGRESS + retry_after;
--                  attempt_no >= takeover_attempts    → THE CAP: this caller completes the request itself — FENCED EXACTLY LIKE THE
--                    TAKEOVER ABOVE, because this is an agent-bound write (R10/F3):
--                      WITH s AS (SELECT se.agent_id FROM sm_data.agent_session se
--                                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica
--                                  FOR SHARE OF se)
--                      UPDATE sm_data.agent_lease_grant lg
--                         SET state = 'FAILED', outcome = 'TIMEOUT', completed_at = now()
--                        FROM s
--                       WHERE lg.request_id = $request_id AND lg.state = 'PENDING'
--                         AND lg.agent_id = s.agent_id                 -- the row must be THIS agent's, not merely a known request id
--                         AND lg.attempt_no >= $takeover_attempts      -- the cap is re-read in the statement, never trusted from the caller's SELECT
--                         AND lg.attempt_deadline < now();             -- and a LIVE attempt is never killed (the R8/F4 guard)
--                    — and the session is refused RE_ENROLL_REQUIRED, an operator event, never a silent nonce-free grant
--                    (design.md §12 limit 17; the fallback while §13 AGW-18 is open).
--                    0 rows → the caller is not the agent's current owner, or the cap/deadline predicates do not hold: nothing is
--                    written and the request is left for its real owner or for the orphan sweep (§5.4).
```

⚠️ **The takeover is fenced for the same reason the adoption is (C1).** Round 8's first pass fenced §3.1's epoch transfer and left this statement writing `fencing_epoch = $epoch` from the caller's own memory, on the strength of the same classification *read*. The window is identical: a caller that was the owner at epoch 8 classifies, a new claim commits epoch 9, and the unfenced takeover binds the request to the dead epoch 8 — `8 >= 7` satisfies the trigger's takeover branch, and step 3's fence then matches nothing, so that caller can never complete the request and every later owner must wait out the new `attempt_deadline` before taking it over. Since C-R9/F1 that is a delay rather than a loss (a lost fence writes nothing), and the fence turns the delay into zero rows. The `s` join closes it: with the epoch moved, the takeover writes **zero rows** and the request stays `PENDING` and takeable by the replica that now owns the agent. (L3) and [`design.md`](design.md) §8 row 35 carry the barrier leg.

`pending_ttl` and `takeover_attempts` are configuration ([`design.md`](design.md) §5.8). Taking a request over is what preserves `SM-D28`'s single-use nonce: abandoning it for a new id would spend the agent's only renewal credential on a grant no row records.

⚠️ **Every caller-driven terminal write is fenced; the unfenced case was a hole (R10/F3).** Round 8 argued that the cap completion "takes no epoch fence, deliberately", on the grounds that it grants nothing and any caller may record the same refusal. **That reasoning was wrong**, and the paragraph is withdrawn: [`design.md`](design.md) §3.1 rule 3 requires every operation that writes agent-bound state to carry the `AgentSessionRef` and check its epoch *inside the statement*, and `agent_lease_grant` rows are agent-bound state. Unfenced, a stale session — or any session that learned another agent's `request_id`, which is derived from a wire `message_id` — could terminalize a **different** agent's live renewal as `FAILED('TIMEOUT')`, and because a `renew` nonce is single-use that pushes a healthy agent to `RE_ENROLL_REQUIRED`: an availability attack across the tenant boundary, written by the one statement in the protocol that had opted out of the fence.

The objection the old paragraph raised is real and is answered by moving the work, not by dropping the fence: **a request nobody is driving is terminalized by the orphan sweep (§5.4), never by an arbitrary session.** So an exhausted request whose agent has no live session at all is still cleaned up — by the one **statement** that legitimately presents no session — while every *caller-driven* completion carries the same fence as every other agent-bound write. (Which *actor* runs §5.4 is a scheduling recommendation, not an enforced boundary; §5.4's and §5.1's notes state that and the safety property that makes it acceptable, S11/F4.)

**Where the attempt bound actually lives.** `attempt_no` is durable (a `NOT NULL` column with `attempt_positive`) and monotonic (the trigger admits only `+1`), but **no schema object bounds it against `takeover_attempts`** — a CHECK cannot reference configuration. The bound is the two statements above: the CAS refuses at `attempt_no >= $takeover_attempts` and the cap completion fires only there. That is a statement-level bound with a test ((L3)), not a database-level one, and it is written this way rather than described as "bounded" (`L19`).

---

## 4. The registration transaction

One transaction per `TRUSTED`, grammar-valid `ActualStateReport`. **It has two phases and the boundary between them is the correction of record (S2/F1): PHASE A reads and decides and writes nothing to the registry; PHASE B writes, and is reached only when every gate has already passed.** Round 10's form interleaved them — the reporter handoff wrote ownership and re-seeded a watermark at 4a(i), *before* the ephemeral cap was consulted at 4b — so a report that ended `REPLICA_CAP_EXCEEDED` had already seized reporter ownership. "A refused report changes nothing" was false, and no amount of ordering *within* the old shape fixed it: the decision had to be complete before the first write.

### 4.0 The normative state table

⚠️ **Upstream of every row: the A1 fence (S7/C1, reordered after A0 in S8/F1).** A report from a superseded session or a non-owning replica never reaches this table at all — it returns `STALE_EPOCH` and writes nothing, so there is no registry path and therefore no row for it. Every row below presumes A0 and A1 both passed.

**This table is the contract; the SQL below implements it.** Every row has exactly one deterministic path, named by its phase steps. If a future change leaves a row with two paths or none, the change is wrong.

| stable row | active incarnation | reporter | incoming | cap | required result | path |
|---|---|---|---|---|---|---|
| none | none | first authenticated agent | `X` | below | mint stable + `X` | A0·A1·A2·A3(absent) → **A4 and A5 BYPASSED** (S2/F3) → A6 = `none` **by definition** · A7a(stable slot) · A7b(**not executed**: keyed by a `$replica_id` that B2 has not minted yet; the count is 0 by construction, and `0 < cap` holds because both caps are validated positive at configuration load — [`design.md`](design.md) §5.8, S3/F4) · A7c(not applicable) → B2(mint)·B5 |
| active | `X` | incumbent | `X` | any | refresh / no-op | A0·A1·A2·A3(active)·A4·A5(incumbent)·A6(same)·A7a/A7b(no slot consumed, both skipped)·**A7c** → B3(incumbent refresh); or A7c stops with **disposition `STALE`** (trust unaffected, S3/F2) and no Phase B runs |
| active | `X` | incumbent | `Y` | below | retire `X`, insert `Y` | A0·A1·A2·A3·A4·A5·A6(changed)·A7b(ok)·A7c → B4·B5 (no B3: the report names `Y`, not `X`) |
| active | `X` | incumbent | `Y` | full | refuse; registry byte-identical | A0·A1·A2·A3·A4·A5·A6(changed)·**A7b refuses** → no Phase B at all |
| active | `X` | new agent after grace | `X` | any | handoff + re-seed `X` | A0·A1·A2·A3·A4·A5(handoff)·A6(same)·A7a/A7b(no slot)·**A7c not applied to a handoff** (D1) → B1·B3(handoff form, no watermark predicate) |
| active | `X` | new agent after grace | `Y` | below | handoff, **preserve `X`'s history**, retire `X`, insert `Y` | A0·A1·A2·A3·A4·A5(handoff)·A6(changed)·A7b(ok) → B1·B4(`X` **unmodified** then retired, S2/F2)·B5 — **no B3 at all** |
| active | `X` | new agent after grace | `Y` | full | refuse; ownership and `X` byte-identical | A0·A1·A2·A3·A4·A5(handoff **decided, not written**)·A6(changed)·**A7b refuses** → no Phase B; `reporter_agent_id`, `reporter_since` and `X`'s three `last_seen_*` all unchanged (S2/F1) |
| active | `X` | displaced agent inside grace | any | any | `REPORTER_DISPLACED`; registry byte-identical | A0·A1·A2·A3·A4·**A5 refuses** → no Phase B |
| retired | none | previous reporter | `X` | below | defined un-retire path | A0·A1·A2·A3(retired)·A4(0 rows)·A5(incumbent — the liveness fallback is not consulted for the incumbent branch)·A6(none)·**A7a and A7b both decided here** → B2(un-retire + counter)·B5 |
| retired | none | new reporter after grace | `X` | below | admitted handoff + activation | A0·A1·A2·A3(retired)·A4(0 rows)·A5(handoff; liveness = `COALESCE(NULL, reporter_since)` = `reporter_since`)·A6(none)·A7a·A7b → B1·B2(un-retire + counter)·B5 |
| two, one per agent | irrelevant (distinct replicas) | two distinct authenticated agents | a new `replica_ref` each | at `max_replicas_per_binding − 1` | **stable** cap never exceeded | A0(distinct identities, no mutual serialization)·A1·A2(the lock under test)·A3·A7a → exactly one B2; the other refused at A7a. This is the **only** cap fixture reachable through the public path, and it is the **stable** one: the ephemeral cap's concurrency is over-determined by A0, A5 and A4, so it has no fixture and (iv-b) is removed (S4/F1). Preconditions and the planted half — remove **only** A2's `FOR UPDATE`, barrier after both A3 reads, expect `max + 1` — are (L11)'s first leg and [`design.md`](design.md) §8 row 43 |

Two rows the round-10 text could not satisfy at all, now traceable: **first registration** had no path (its gate needed an incumbent `reporter_agent_id` and a `$replica_id` that does not exist yet — S2/F3), and **cap-refused handoff** had a path whose result contradicted the required one (S2/F1). One row had a path with the wrong result: the changed-incarnation handoff overwrote the outgoing activation's history (S2/F2).

### 4.1 Phase A — read and decide

```sql
-- A0. PER-AGENT SERIALIZATION — and it is FIRST because §7's STRICT SHARED PREFIX puts agent_identity ahead of agent_session
--     and of every registry table (S8/F1; the prefix/branch-tails model itself corrected in S9/F1). Lock the reporting identity's row.
--      ⚠️ THE LOCK MODE IS `FOR NO KEY UPDATE`, NOT `FOR UPDATE`, AND THAT IS LOAD-BEARING (S10/F4). Executing the S9 text on
--         PostgreSQL 14.24 deadlocked this path against a concurrent §3.3 step 3 in four of five runs. The cause is an edge §7's
--         model did not name: `agent_lease`, `agent_lease_grant`, `agent_session` and `agent_replica` all carry a FOREIGN KEY to
--         `agent_identity`, and "foreign key checks use the new KEY SHARE lock mode, which does not conflict with NO KEY UPDATE"
--         (PG 9.3 release notes; verified 2026-09-10) — while `FOR KEY SHARE` DOES conflict with `FOR UPDATE`, and with nothing
--         else (PG 14 docs, `explicit-locking` Table 13.3, Conflicting Row-Level Locks; verified 2026-09-10). So an identity row
--         held `FOR UPDATE` blocks every FK check on it, and step 3c's projection into `agent_lease` — a table LATER in the prefix
--         than the ledger row 3b already holds — waits on the identity row this step holds, while this step waits on that ledger
--         row: a cycle, measured. `FOR NO KEY UPDATE` conflicts with `FOR SHARE`, `FOR NO KEY UPDATE` and `FOR UPDATE` (same
--         table), so it still serializes two registrations for one agent and still serializes against `claim`'s `FOR SHARE` —
--         everything this lock was for — and it no longer blocks a sibling transaction's FK check.
SELECT 1 FROM sm_data.agent_identity WHERE agent_id = $agent AND revoked_at IS NULL FOR NO KEY UPDATE;
--     0 rows → the agent is REVOKED or unknown: the report is not TRUSTED at all (design.md §4.8) and nothing below runs. This
--       predicate — and not A1's — is the registry-side artifact for the revocation case ((L17)'s revocation leg, F2 leg (j)).
--     WHY IT PRECEDES THE FENCE (S8/F1): S7/C1 added the session fence *before* this lock, which inverted the order design.md
--     §5.2's `claim` uses — claim takes `agent_identity … FOR SHARE` and then writes `agent_session` — and produced a genuine
--     deadlock cycle: claim holding identity-S and waiting on the session row, a report holding session-S and waiting for
--     identity-X, resolved by PostgreSQL aborting one with 40P01, repeatedly under a reconnect storm. Every transaction that
--     touches these tables now acquires §7's shared prefix in the same order; this statement is that prefix's first row after
--     the advisory request key, and the tables that come AFTER the prefix are reached by two branch tails that differ (S9/F1).

-- A1. THE FENCE (added S7/C1, reordered after A0 by S8/F1). design.md §3.1 rule 3 promises that every agent-bound write —
--     `report` named among them — checks the caller's epoch "in the write statement, not a pre-check", and design.md §8 row 3
--     requires a stale replica's report to return STALE_EPOCH. Before S7/C1 this section carried neither: A0 locked the identity
--     row, which covers REVOCATION only, and no statement here joined agent_session at all — so a report from a superseded
--     session would have registered and refreshed liveness. The fence is the SAME `s` CTE shape the lease ledger uses (§3.1).
WITH s AS (
  SELECT se.tenant_id, se.agent_id, se.fencing_epoch
    FROM sm_data.agent_session se
   WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica   -- the fence; 0 rows → STALE_EPOCH
   FOR SHARE OF se                                              -- a claim committing concurrently serializes against this
)
SELECT tenant_id, agent_id, fencing_epoch FROM s;
--     0 rows → STALE_EPOCH: the caller is not the agent's current session owner. NOTHING below runs, no registry row is written,
--       no watermark moves, no last_seen_at is refreshed, and NOTHING is disclosed — not the active incarnation, not the minted
--       ids, not whether the replica exists (the same non-disclosure rule §3.1's classification applies to the lease ledger).
--       The `tenant_id` every Phase B write uses comes from THIS row, never from caller input (`I5`).
--     1 row  → continue at A2.
--     TWO MECHANISMS, and each is a statement rather than a claim (S7/C1 correction — an earlier comment here asserted per-statement
--     predicates the SQL did not contain, and said B3/B4 "inherit" a fence A4 never joined; both were false):
--       (i) A1's `FOR SHARE OF se` is the primary one. PG 14: "A shared lock blocks other transactions from performing UPDATE,
--           DELETE, SELECT FOR UPDATE or SELECT FOR NO KEY UPDATE on these rows", and "row-level locks are released at transaction
--           end" (explicit-locking, Row-Level Locks; verified 2026-09-10). design.md §5.2's `claim` writes the session row as
--           `INSERT … ON CONFLICT (tenant_id, agent_id) DO UPDATE` — an UPDATE of that row — so a concurrent claim CANNOT commit a
--           new epoch while this transaction holds the lock.
--      (ii) Each of the SEVEN fenced writes in Phase B carries the fence predicate in its OWN WHERE (the five UPDATEs: B1, B2's
--           cap counter, B3's incumbent form, B3's handoff form, B4) or as `INSERT … SELECT … WHERE EXISTS (…)` (the two inserts:
--           B2's replica insert, B5's activation insert) — seven statements, not five (S8/F3) — which is what makes rule 3's
--           "in the write statement, not a pre-check" literally true of this section, and is the planted-violation target.
--     (L17) is the obligation and plants per statement; (L18) is the lock-order obligation.

-- A2. Per-binding cardinality: materialize and lock the cap row — the serialization point for every registration on this binding.
INSERT INTO sm_data.agent_replica_cap (tenant_id, binding_id, active_replicas)
VALUES ($tenant, $binding, 0)
ON CONFLICT (tenant_id, binding_id) DO NOTHING;
SELECT active_replicas FROM sm_data.agent_replica_cap
 WHERE tenant_id = $tenant AND binding_id = $binding
 FOR UPDATE;
--     THE ONE WRITE PHASE A PERFORMS, and it is stated rather than hidden: the upsert may insert a cardinality row with
--     active_replicas = 0. It carries nothing about this report, 0 is the true count for a binding with no replicas, it is
--     idempotent, and agent_replica_cap rows are never deleted (§5.2) — so a report refused later leaves it behind as a correct
--     row about the binding, not as a registry mutation the refusal should have prevented. Every OTHER write is Phase B's.

-- A3. THE STABLE READ — three branches, kept apart because they need different gates (S2/F3).
SELECT replica_id, (retired_at IS NULL) AS active, reporter_agent_id, reporter_since FROM sm_data.agent_replica
 WHERE tenant_id = $tenant AND binding_id = $binding AND replica_ref = $replica_ref;
--     0 rows   → FIRST REGISTRATION of this (binding, replica_ref). There is no incumbent reporter and no replica_id yet, so
--                A4 and A5 DO NOT RUN — the reporter gate is BYPASSED, not passed. Nothing is asserted about a reporter here
--                because there is nothing to displace; the authoritative inputs are the AUTHENTICATED reporting agent ($agent,
--                proven by mTLS and resolved at A0) and the DP-resolved binding — never a caller-supplied reporter identity.
--                Go to A7 for the stable-slot decision, then B2 (mint) and B5. Round 10 merged this branch with "1 retired row"
--                and sent both to a gate that reads reporter_agent_id and $replica_id: for a first report both are absent, so
--                the branch had no defined path at all (S2/F3).
--     1 retired → the replica is known but holds no slot. A4 will read 0 active incarnations; A5 still runs, against the DURABLE
--                incumbent this row names, with liveness falling back to reporter_since (A5). Go to A4.
--     1 active  → the steady state and every change to it. Go to A4.

-- A4. THE ACTIVE-INCARNATION READ (skipped only on the first-registration branch), locked for the rest of the transaction.
SELECT incarnation_id, incarnation, registered_by_report, registered_by_agent, last_seen_report, last_seen_by_agent, last_seen_at
  FROM sm_data.agent_replica_incarnation
 WHERE replica_id = $replica_id AND retired_at IS NULL
 FOR UPDATE;                                                    -- FOR UPDATE needs UPDATE privilege as well as SELECT (§7)
--     0 rows → no active incarnation (every one silence-retired, §5.0, or the stable row is retired).
--     1 row  → the active activation, and the only row A6 classifies against.
```

**A5 — the reporter gate (DECISION ONLY; its writes are B1).** Who may say anything about this replica:

- `$agent = reporter_agent_id` → the **incumbent**: proceed to A6, and A7's watermark guard applies.
- Otherwise the incumbent's liveness is `COALESCE(active.last_seen_at, agent_replica.reporter_since)` — the active activation's DP-observed `last_seen_at` when there is one, and the stable row's `reporter_since` when there is not (there need not be one: §5.0 may have retired every activation, or the stable row may be retired). Falling back to `reporter_since` rather than admitting a handoff unconditionally gives a **newly installed** reporter the same grace to produce its first report that an established one gets between reports, so a third agent cannot displace an agent that has just taken over and not yet registered an activation; in the long-idle case it decides the same way an unconditional rule would, because such a replica's `reporter_since` is far older than the grace.
  - that value `>= now() - $reporter_handoff_grace` → **`REPORTER_DISPLACED`**. The incumbent is still reporting, so this is not a handoff: **Phase B is not entered**, the report's **`disposition` is `REFUSED(REPORTER_DISPLACED)`** — its `trust` is [`design.md`](design.md) §4.8's separate verdict and this refusal does not change it (S3/F2) — and it is counted `dp_agent_replica_reports_refused_total{reason="REPORTER_DISPLACED"}`. Stop.
  - older than the grace → **HANDOFF, ADMITTED — as a decision.** Nothing is written yet. Carry the decision to A6 and A7; the ownership write is **B1** and happens only if A7 also passes. **The handoff does not pass through A7's watermark guard** (D1): the guard is the incumbent's own ordering rule, and a handoff has no meaningful ordering against a counter that is not its own.
- **Why a gate and not "DP receive order":** receive order alone let a delayed report from a **displaced** agent — one that passes its *own* watermark, e.g. `P` at `sequence = 901` after `Q` had taken over — retire `Q`'s live activation and reactivate `P`'s dead one; per-agent ordering cannot see that, because the two counters are unrelated (C-R9/F7). **Residual, stated:** a delayed report from a displaced reporter arriving *after* the grace is indistinguishable from a genuine move back to that host and is admitted — bounded by `reporter_handoff_grace`, measured by (L12), and carried as [`design.md`](design.md) §12 limit 21.

**A6 — the incarnation classification**, against A4's row: `none` (A4 returned 0 rows, **or** A4 did not run because A3 found no stable row — the first-registration branch) · `same` (`incarnation = $incarnation`) · `changed`.

**A7 — the two cap decisions and the watermark guard, all before any Phase B write.**

```sql
-- A7a. THE STABLE SLOT — needed only when the stable row is absent or retired (a slot is being CONSUMED):
--      active_replicas >= max_replicas_per_binding → REPLICA_CAP_EXCEEDED. Phase B is not entered; the report's DISPOSITION is
--        REFUSED(REPLICA_CAP_EXCEEDED) — its TRUST is design.md §4.8's separate verdict and this refusal does not change it (S3/F2) —
--        no registry row is written, no counter moves, and
--        dp_agent_replicas_registered_total{outcome="REPLICA_CAP_EXCEEDED"} counts it (design.md §8 row 41). The refusal covers the
--        UN-RETIRE case as well as the mint case — un-retiring at the cap would put active_replicas one past it (R8/F6).
--      On the "1 active" branch NO stable slot is consumed and this decision is skipped entirely.

-- A7b. THE EPHEMERAL SLOT — needed only when A6 = `none` or `changed` (a new activation row will be inserted):
SELECT count(*) FROM sm_data.agent_replica_incarnation WHERE replica_id = $replica_id;
--      >= max_incarnations_per_replica → REPLICA_CAP_EXCEEDED. Phase B is not entered: no ownership is seized, no watermark is
--        re-seeded, nothing is retired, nothing is inserted, no counter moves, and the same metric counts it. THIS is the ordering
--        S2/F1 corrects — round 10 evaluated this count AFTER the handoff had already written ownership and a watermark.
--      The count includes RETIRED-but-unpruned rows, and since C-R9/F4 every ACTIVATION is a row, so a replica that flaps between
--      two strings accumulates rows at the flap rate: this cap is released by `registry_retention`, not by retirement, and there is
--      no exemption for a string seen before because a returning string consumes a NEW row.
--      On A6 = `same` NO ephemeral slot is consumed and this decision is skipped entirely.
--      ON THE ABSENT-STABLE-ROW BRANCH (first registration) THIS SELECT IS NOT EXECUTED AT ALL: it is keyed by $replica_id, which does
--      not exist until B2 mints it, and the count it would return is 0 BY CONSTRUCTION — a replica_id that has never existed has no
--      activation rows. The decision is therefore satisfied without a statement, which is stated here rather than left to be inferred
--      from a SELECT that could not run. B5's $replica_id on that branch is B2's RETURNING value; on every other branch it is A3's.

-- A7c. THE WATERMARK GUARD — the incumbent's stale-report rule (R10/F6), scoped to its own reporter (S1/F2), and NOT applied to an
--      admitted handoff (D1):
--      $agent is the INCUMBENT AND last_seen_by_agent = $agent AND last_seen_report >= $sequence
--        → STALE: Phase B is not entered. Changes nothing — not the activation, not last_seen_at, not last_seen_report — and is
--          answered with A4's incarnation_id (design.md §8 row 43). Stop.
--          TRUST IS NOT WHAT THIS DECIDES (S3/F2). trust ∈ {TRUSTED, UNTRUSTED} is computed by design.md §4.8 from the lease, the
--          epoch and the report's scope ALONE, and nothing in §4 changes it: a stale report from a live, in-scope agent is TRUSTED
--          and stale at the same time, which is not a contradiction because the two words answer different questions ("may this
--          report be believed?" and "does it carry anything new?"). §4's output is the orthogonal DISPOSITION —
--          APPLIED | STALE | REFUSED(reason) — and STALE is one of its values, not a trust verdict. A TRUSTED-but-STALE report must
--          also not move the ACTUAL-STATE projection that status() reads: that rule already exists and is not re-invented here —
--          ActualStatePort's required REPLAY identity is the PAIR (DP-resolved agent_id, item_id) → per-item requestId (design.md §3.1 rule 2's report
--          exception, §3.2; the batch message_id is transport correlation only) and NEVER its
--          sequence, which is a FRESHNESS hint scoped per (agent, binding, replica) with unresolved reset behaviour (S5/F1, S5/F2); and
--          design.md §8 row 6 requires a report at or below that replica's watermark to be COMMITTED "without effect; never
--          overwrites newer state". WHERE that per-(agent, binding, replica) watermark, the canonical current projection and
--          this disposition are STORED is dp-core's report intake, which this design set does not specify — design.md §13
--          AGW-20 tracks that missing artifact; §4's registry decisions are keyed by the registry's OWN per-activation watermark
--          (last_seen_report) and are complete without it.
--      last_seen_by_agent <> $agent is unreachable for an incumbent, because a handoff is the only way that column changes and a
--      handoff re-seeds it at B3.
```

⚠️ **The refusal boundary — what this design set guarantees, and what it does not (S2/F1, corrected by S3/F3).** S2 claimed the refusal's report row and its counter are "recorded in the same transaction as the reads". **That claim is retracted: this design set contains no statement that persists a report, so it cannot guarantee the atomicity of persisting one.** §5.2's six tables include no report table, and [`design.md`](design.md) §3.5 requires `status(replica)` to read **`dp-core`'s** canonical current per-replica `ActualStateReport` projection — so the actual-state store, the report's disposition and the per-`(agent, binding, replica)` sequence watermark are all **`dp-core`'s**, and none of them is specified here. Writing a table for them would be inventing another subsystem inside a sketch that is already two subsystems too large.

**What IS guaranteed, by artifacts in this section:**

1. **Phase A writes no registry row.** Every refusal — `REPORTER_DISPLACED`, either `REPLICA_CAP_EXCEEDED`, `STALE` — is decided before Phase B is entered, so `agent_replica` (reporter columns included), `agent_replica_incarnation` (all `last_seen_*` included) and `agent_replica_cap.active_replicas` are unchanged by a refused report. A2's zero-row cardinality materialization is the one stated exception and is correct independently of the report. This is what (L11)'s and (L12)'s byte-identical assertions measure, and it needs no report table to be true.
2. **No rollback is relied on.** The refusal is not an aborted Phase B; it is a decision taken before any Phase B statement exists. The alternative — write, then abort — is what round 10's shape amounted to and is rejected, because an aborted transaction cannot record the refusal it aborted for.
3. **The registry's own ordering is complete without the report store.** §4's decisions read `last_seen_report`, the registry's per-activation watermark, which lives in a table this sketch owns.

**What is NOT guaranteed here, and is routed rather than assumed:** that the report's row and its disposition are persisted at all, that they are persisted in this transaction, or that they are persisted atomically with anything. **The refusal metric is a Micrometer counter in the JVM's registry, not an audit record.** There is no outbox or after-commit artifact in this design, so no atomicity or ordering relative to PostgreSQL is guaranteed: an ordinary completed call is expected to increment it, while a crash may lose an increment or expose one for work that did not commit. `AGW-20` must choose and test stronger semantics if operators need them; until then the metric is best-effort only. **Routed:** the report-intake persistence contract — the report row, its `trust` label ([`design.md`](design.md) §4.8), its `disposition` (S3/F2) and the per-`(agent, binding, replica)` sequence watermark that [`design.md`](design.md) §8 row 6 already requires — belongs to the **`BE-D*` owners**, who own `dp-core`'s actual-state store, and is tracked as [`design.md`](design.md) §13 `AGW-20`; that item also decides whether metrics remain best-effort or gain an after-commit/outbox artifact. Until they specify it, the sentence a reader may rely on is exactly (1)–(3) above.

### 4.2 Phase B — write

Reached only when A5, A7a, A7b and A7c have all passed. Statements in this order, in the **same** transaction as Phase A:

```sql
-- B1. HANDOFF OWNERSHIP — only on an admitted handoff, and only now that the caps have passed (S2/F1):
UPDATE sm_data.agent_replica
   SET reporter_agent_id = $agent, reporter_since = now()
 WHERE tenant_id = $tenant AND binding_id = $binding AND replica_id = $replica_id
   AND EXISTS (SELECT 1 FROM sm_data.agent_session se                       -- THE FENCE, in the write statement (rule 3, S7/C1)
                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica);

-- B2. STABLE MINT OR UN-RETIRE, plus the counter — only when A3 was `absent` or `retired`.
INSERT INTO sm_data.agent_replica (tenant_id, binding_id, replica_id, replica_ref, registered_by_agent, registered_by_report, reporter_agent_id)
SELECT $tenant, $binding, $new_uuid, $replica_ref, $agent, $sequence, $agent      -- first registration: the authenticated agent is the first reporter
 WHERE EXISTS (SELECT 1 FROM sm_data.agent_session se                            -- THE FENCE, in the write statement (rule 3, S7/C1);
                WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch         -- INSERT … SELECT … WHERE EXISTS is the shape both
                  AND se.owner_replica_id = $replica)                            -- inserts use (B2 here, B5 below)
ON CONFLICT (tenant_id, binding_id, replica_ref) DO UPDATE
   SET retired_at = NULL                                        -- a returning replica_ref un-retires; the id is REUSED, never re-minted
 WHERE sm_data.agent_replica.retired_at IS NOT NULL
RETURNING replica_id, (registered_by_report = $sequence) AS minted;
--     Exactly 1 row, always: A3 proved the row is absent (the INSERT lands) or retired (the DO UPDATE's WHERE holds). That is why
--     A3's read is not redundant: in the round-7 form this statement WAS the admission test, and "if a row was locked but not
--     updated because an ON CONFLICT DO UPDATE ... WHERE condition was not satisfied, the row will not be returned" (PG 14 docs,
--     sql-insert, RETURNING; verified 2026-09-09) — so on the steady-state path it returned ZERO rows and the transaction had no
--     replica_id at all. The DO UPDATE touches retired_at ONLY, so an un-retired row keeps naming its OLD reporter and B1 is what
--     changes it: the reporter is adjudicated, never inherited by the act of un-retiring.
UPDATE sm_data.agent_replica_cap SET active_replicas = active_replicas + 1
 WHERE tenant_id = $tenant AND binding_id = $binding
   AND EXISTS (SELECT 1 FROM sm_data.agent_session se                       -- THE FENCE, in the write statement (rule 3, S7/C1)
                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica);
--     Reached only from B2, so the counter counts slot consumption exactly once. The round-7 form ran this UNCONDITIONALLY after the
--     upsert, so every re-report of a living replica incremented it and a binding reached permanent REPLICA_CAP_EXCEEDED within
--     minutes of ordinary reporting (R8/F7). (L11)'s reconciliation pins it: active_replicas = count(*) WHERE retired_at IS NULL.

-- B3. THE ACTIVATION'S OWN LIVENESS — written ONLY when A6 = `same`, i.e. only for the activation the report actually names (S2/F2):
--     incumbent path:
UPDATE sm_data.agent_replica_incarnation
   SET last_seen_at = now(), last_seen_report = $sequence, last_seen_by_agent = $agent
 WHERE replica_id = $replica_id AND incarnation_id = $active_incarnation_id AND retired_at IS NULL
   AND last_seen_by_agent = $agent AND last_seen_report < $sequence
   AND EXISTS (SELECT 1 FROM sm_data.agent_session se                       -- THE FENCE, in the write statement (rule 3, S7/C1)
                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica);      -- A7c as a statement predicate: the watermark can only rise
--     handoff path (same statement without the two watermark predicates, because the new reporter's counter is unrelated to the old
--     one's and A7c did not apply — D1):
UPDATE sm_data.agent_replica_incarnation
   SET last_seen_at = now(), last_seen_report = $sequence, last_seen_by_agent = $agent
 WHERE replica_id = $replica_id AND incarnation_id = $active_incarnation_id AND retired_at IS NULL
   AND EXISTS (SELECT 1 FROM sm_data.agent_session se                       -- THE FENCE, in the write statement (rule 3, S7/C1)
                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica);
--     now() is the DP's RECEIVE time, never the agent's clock (C-R9/F3). Neither statement moves a cap counter and neither mints.
--     ON A6 = `changed` NEITHER RUNS. The outgoing activation is NOT re-seeded: last_seen_at is defined as the DP receive time of the
--     latest trusted report naming THAT activation, and a report naming Y is not a report naming X, so writing Q's identity, sequence
--     and receive time onto X would make X's history a record of something that never happened (S2/F2). X keeps P's
--     last_seen_by_agent, last_seen_report and last_seen_at, and is then retired at B4 carrying them.

-- B4. RETIRE THE OUTGOING ACTIVATION — only when A6 = `changed`. It is retired EXACTLY AS IT STOOD; no field but retired_at moves.
UPDATE sm_data.agent_replica_incarnation
   SET retired_at = now()
 WHERE replica_id = $replica_id AND incarnation_id = $active_incarnation_id AND retired_at IS NULL
   AND EXISTS (SELECT 1 FROM sm_data.agent_session se                       -- THE FENCE, in the write statement (rule 3, S7/C1)
                  WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch AND se.owner_replica_id = $replica);

-- B5. INSERT THE INCOMING ACTIVATION — when A6 = `none` or `changed`. A PLAIN INSERT, never an un-retire (C-R9/F4).
INSERT INTO sm_data.agent_replica_incarnation (replica_id, incarnation_id, incarnation, registered_by_report, registered_by_agent, last_seen_report, last_seen_by_agent)
SELECT $replica_id, $new_uuid, $incarnation, $sequence, $agent, $sequence, $agent
 WHERE EXISTS (SELECT 1 FROM sm_data.agent_session se                            -- THE FENCE, same INSERT … SELECT … WHERE EXISTS
                WHERE se.agent_id = $agent AND se.fencing_epoch = $epoch         -- shape as B2 (rule 3, S7/C1)
                  AND se.owner_replica_id = $replica)
RETURNING incarnation_id;
--     The NEW activation is where the reporting agent's identity, sequence and receive time belong: registered_by_* is its immutable
--     creation audit, last_seen_* its initial watermark, last_seen_at its DEFAULT now(). registered_at = last_seen_at at insert,
--     which seen_after_registered admits.
--     No ON CONFLICT and no target for one: UNIQUE (replica_id, incarnation) is gone, so a string this replica has carried before gets
--     a NEW row, a NEW incarnation_id and a NEW half-open window that cannot overlap the earlier one's. Round 8 un-retired the old row
--     instead, keeping its registered_at and giving it a window spanning its own successor's lifetime — while §6.1 and (L15) claimed
--     successive windows are disjoint — and wrote registered_by_report through a DO UPDATE, a column the runtime grant omits, so that
--     path would have raised 42501 (C-R9/F6).
--     agent_replica_one_active is the enforcing artifact for "one active activation": if B4 is ever skipped or reordered, this insert
--     RAISES and the transaction aborts. A backstop, not the control flow.
```

⚠️ **Why B3/B4/B5 are sequenced statements and not one statement (R8/F7, C5).** The round-7 form was a single statement whose `retire` data-modifying CTE was a sibling of an `INSERT … ON CONFLICT` **on the same table**, and read the resulting abort as a safety property. PostgreSQL 14 makes that order *never* guaranteed: "The sub-statements in `WITH` are executed concurrently with each other and with the main query. Therefore, when using data-modifying statements in `WITH`, the order in which the specified updates actually happen is unpredictable", and "avoid writing `WITH` sub-statements that could affect the same rows changed by the main statement or a sibling sub-statement" (PG 14 docs, `queries-with`; verified 2026-09-09); all sub-statements share one snapshot and cannot see each other's effects. So an insert applied before the retire would abort a **legitimate** activation change non-deterministically. Sequenced statements in one transaction give the same atomicity with a defined order, and the same reasoning is why **every cap decision is in Phase A**: a decision taken after a write is a decision taken too late.

⚠️ **Why a handoff re-seeds the *named* activation's watermark instead of starting a new activation (S1/F2, corrected by S2/F2).** Two mechanisms were consistent and this is the choice: **(b)**, a reporter-scoped watermark. **(a)** — a handoff always closes the old activation and inserts a new one even when the string is unchanged — was rejected because it would give the *same still-running process* two activations and two acceptance windows split at an instant when nothing about the workload changed, and §6.1 would then reject a healthy replica's in-flight batch labelled with the pre-handoff id as `INCARNATION_RETIRED`. **(b)** keeps one activation per process lifetime, so §6.1's window semantics and (L15)'s disjointness are untouched. What (b) guarantees and where: `replica_id` untouched (the stable row is re-pointed, never re-minted); a displaced reporter's delayed message cannot refresh liveness because **A5 refuses it before A7c or B3 run**; the new reporter's first low sequence refreshes immediately because B3's handoff form carries no watermark predicate; one active activation (`agent_replica_one_active`); and the immutable creation audit is never rewritten, which is why the watermark is its own pair of columns. **The re-seed applies to the activation the report NAMES and to no other** — that is S2/F2, and it is why B3 is gated on A6 = `same`. **Limit 21's residual is unchanged and its wording stands.**

⚠️ **The two caps differ in where the NUMBER lives; they do not differ in what serializes them — and for the ephemeral cap the serialization is over-determined (S2/F4).** `max_replicas_per_binding` is counted by A2's locked cardinality row; `max_incarnations_per_replica` is counted by A7b's `count(*)`, with no cardinality row of its own. Both decisions are taken while A2's row lock is held, and that lock is held to transaction end ("Row-level locks are released at transaction end or during savepoint rollback", PG 14 docs, `explicit-locking`; verified 2026-09-09), so registrations on one binding serialize whichever cap they consume. **For the ephemeral cap that lock is not the only serializer and is not even the binding one:** contention would require two transactions reaching A7b for the *same* `replica_id`, and the public report path forbids it twice over — A0's identity lock serializes one agent's reports, and A5 admits only one reporter per replica at a time. So round 9's claimed "overshoot by one per concurrently-reporting agent" was impossible (round 10 established that), **and round 10's replacement test for it was impossible too** (S2/F4): a second agent reporting the same replica is refused `REPORTER_DISPLACED` at A5 and never reaches A7b. **And S3's third attempt was impossible too (S4/F1):** A4 locks the replica's active activation `FOR UPDATE`, so even removing A2's lock leaves A4's — plus `agent_replica_one_active` behind it — serializing the pair, and no plant that removes only A2 can produce `max + 1` on an ephemeral fixture. The conclusion is that **the ephemeral cap has no concurrency fixture at all**, because A0, A5 and A4 each prevent the contention independently; A2's binding-wide lock is proven on the **stable** cap instead, where two distinct agents registering two new `replica_ref`s on one binding *is* reachable — (L11)'s first leg and [`design.md`](design.md) §8 row 43. The ephemeral cap keeps only its ordinary serial boundary test, (L11)(iv-a).

`ReportAck` then carries the `replica_id` (stable, unchanged for the life of the replica) and the **active** `incarnation_id` — from **A4**'s row on the no-op, stale-report and steady-state paths, and from **B5**'s `RETURNING` on a first registration or an activation change ([`design.md`](design.md) §4.3, §6.1). A report refused at **any Phase A gate** — grammar, `REPORTER_DISPLACED`, either cap — carries **no** ids.

---

## 5. Retirement and retention

**Retirement is a maintenance job in two stages, and the registration transaction performs neither** (C-R9/F5). The load-bearing half of that sentence is the second: no registration writes a retirement, which is what removed C-R9/F5's deadlock. Stage one retires a **silent incarnation** (§5.0); stage two retires the **stable replica** that stage one left with no active incarnation (§5.1). Both run on the job's cadence, per binding, taking that binding's cap row **before** any replica or incarnation row. ⚠️ **"The migrator's" was a guarantee with no enforcing artifact and is withdrawn across §5 (S11/F4):** the measured guarantees are narrower: §5.1 is safe under two concurrent runners on one binding, and §5.4 is safe against step-3 completion in both ledger-lock orders — §5.1 and (L16) state the mechanisms and tests. The migrator remains only the recommended **scheduler**; central open item 39 assigns the scheduling owner and duplicate-runner posture to the `BE-D*` owners. What **is** privilege-enforced, and stays so, is **deletion**: the runtime role holds no `DELETE` on any of the eleven tables and no `INSERT` on `agent_request_tombstone`, so §5.2's two statements and §5.3 step 2 genuinely are the migrator's ((L14), measured).

### 5.0 An incarnation retires when it goes silent

Without this, a replica that *vanishes* never becomes idle (C-R9/F3). An incarnation is otherwise retired only by a **successor** (§4, B4), so a pod that disappears under a rollout — the next pod has a new name, hence a new `replica_ref` and a new stable replica, not a successor incarnation — keeps its last incarnation `retired_at IS NULL` for ever. §5.1 then never sees the stable replica as idle, its cap slot is never released, and `max_replicas_per_binding` fills permanently: exactly the exhaustion §5.1 and [`design.md`](design.md) §8 row 41 claim is impossible. Liveness is therefore **DP-observed**, from `last_seen_at` (§2.5), which every `TRUSTED` report naming the activation refreshes at DP receive time:

```sql
-- The maintenance job, per binding, after that binding's cap row is locked (§5.1 step 1).
-- Nothing restricts this to the migrator: the runtime role holds UPDATE (retired_at) on this table
-- for B4, so it can execute this statement too (S10/F3, measured). The statement is safe under
-- concurrent execution by any role — see §5.1's property note (S11/F4); the migrator is the
-- RECOMMENDED scheduler, not an enforced one.
UPDATE sm_data.agent_replica_incarnation i
   SET retired_at = now()
  FROM sm_data.agent_replica r
 WHERE i.replica_id = r.replica_id
   AND r.tenant_id = $tenant AND r.binding_id = $binding
   AND i.retired_at IS NULL
   AND i.last_seen_at < now() - $incarnation_idle_retirement;                              -- new config (§5.8 of design.md), DP clock only
```

`incarnation_idle_retirement` must exceed the longest legitimate gap between two reports of a live replica — expressed as `k × report_interval`, not guessed — or a healthy replica is retired under itself and its next report registers a fresh activation: visible rather than silent, but churn the cap then pays for. The window this closes is bounded by that setting: a vanished replica holds its slot for at most `incarnation_idle_retirement + replica_idle_retirement` plus one job interval.

⚠️ **PRECONDITION, and it is not met today (R10/F5): this sweep has no liveness source unless a healthy workload reports periodically.** `last_seen_at` is refreshed **only** by an `ActualStateReport`, and only by §4's B3 — the refresh for the activation that report names. [`design.md`](design.md) §4.3's catalogue defines `Heartbeat` as a separate message carrying **no** replica or incarnation identity, and neither that document nor [`../../go-agent/design.md`](../../go-agent/design.md) specifies a periodic actual-state cadence — a stable, unchanged workload may therefore heartbeat indefinitely while sending no new report, and this sweep would retire its incarnation as silent. **`report_interval` is therefore an *ask* on the agent design, routed as [`design.md`](design.md) §13 AGW-19, and until it is settled §5.0 must not run** and `AGW-D16`'s retirement property is conditional on it. Two things this sweep must **not** do instead: take liveness from `Heartbeat` (an agent's heartbeat would keep every incarnation it ever reported alive, including a pod deleted an hour ago — reintroducing exactly the C-R9/F3 exhaustion), or infer liveness from the agent's own clock (the report's `sent_at` plays no part anywhere on this path, `design.md` §4.8).

### 5.1 A stable replica retires when it goes idle

**One statement; its tested concurrency boundary is stated below** (`F6`/R7; scope corrected in C2, ownership corrected in C-R9/F5; the exclusivity claim **withdrawn** in S11/F4). ⚠️ **Nothing enforces that only the migrator runs §5.0, §5.1, §5.3 or §5.4, and this document no longer claims otherwise. The evidence proves only the named interleavings: concurrent §5.1 runners on one binding and §5.4 versus step 3 in both lock orders. It does not claim arbitrary pairwise concurrency among all four operations.** Three rounds of text said "the migrator's alone" and named **deployment** plus the job's own transaction as the enforcement — but deployment is not an artifact in this design set, no scheduler, role, lease or advisory key is named anywhere, and the DCL was **measured** letting `sm_dp_runtime` run every one of these statements (S10/F3): the columns they write — `agent_replica_incarnation.retired_at`, `agent_replica.retired_at`, `agent_replica_cap.active_replicas`, `agent_lease_grant.state/outcome/completed_at` — are exactly the columns the runtime path legitimately writes at **B4**, **B2**'s un-retire, **A7/B2**'s counter and **step 3c**, so no column-scoped grant can refuse them without breaking the report and lease paths. A guarantee with no enforcing artifact is not a guarantee (`L19`), so the old broad guarantee is **retracted rather than replaced by another universal claim.**

**The property, and where each half is enforced.** Two concurrent executions of these statements, by any roles, in any interleaving:

1. **cannot corrupt the cap counter** — every execution takes the binding's **one** `agent_replica_cap` row `FOR UPDATE` at step 1 and holds it to commit (§7 clause 2), so the counter is mutated under a mutex; the decrement is computed from `retired`, the `RETURNING` of *this* transaction's own `UPDATE`, so it counts only rows this transaction retired; and `GREATEST(… , 0)` bounds it below;
2. **cannot retire a live row** — `idle`'s predicates (`retired_at IS NULL`, `NOT EXISTS` an active incarnation, and the `replica_idle_retirement` horizon) are re-evaluated by the engine against the committed row version under the lock: "The search condition of the command (the `WHERE` clause) is re-evaluated to see if the updated version of the row still matches the search condition" (PG 14 docs, `transaction-iso`, Read Committed; verified 2026-09-11). A replica that acquired an active incarnation, or was already retired, while a sweep waited is therefore skipped rather than retired twice;
3. **cannot deadlock** — §7's prefix/tail model: the retirement transaction enters at the cap row and takes neither identity nor session, and §5.3's and §5.4's advisory keys are taken key-before-row like every other path.

**The enforcing tests are named, not assumed:** clauses 1 and 2 are `(L13)`'s two-concurrent-sweeps leg (one runner deliberately being `sm_dp_runtime`), clause 3 is `(L13)`'s registration-vs-sweep barrier with its planted half, and §5.4's equivalent is `(L16)`'s sweep-vs-completion leg.

**What remains, stated as an ownership recommendation rather than a guarantee:** these jobs **should** be scheduled in the migrator — one runner, on one cadence — because that is where `ledger_retention`'s `DELETE`s must run anyway (those the DCL *does* refuse to the runtime role, (L14)), because a single runner makes the job's interval the only input to the cap-release latency below, and because §5.3's lock-order note relies on there normally being no second concurrent run. **Which component owns that schedule is not settled here**; central open item 39 routes the component, transaction unit, cadence, duplicate-runner posture, retry/deadlock handling and operational evidence to the `BE-D*` owners, and until they answer, a plan may not assume a scheduler exists. Round 8 also ran it as the registration transaction's last step, which inverted the lock order: registration locks the **cap row** (§4 step 2) and would then lock **replica rows**, while this statement locks replica rows `FOR UPDATE` and *then* updates the cap row — two parties acquiring the same two locks in opposite orders on the same binding, which is a deadlock however narrowly the statement is scoped. The "no new lock and no new lock order" sentence C2 added was therefore **false**, and scoping alone could not have fixed it. **One order everywhere: the binding's cap row first, its replica and incarnation rows second.** The migrator holds that order by construction:

```sql
-- Step 1, per binding, iterating bindings in (tenant_id, binding_id) order:
SELECT active_replicas FROM sm_data.agent_replica_cap
 WHERE tenant_id = $tenant AND binding_id = $binding
 FOR UPDATE;                                                                               -- the SAME first lock every registration takes
-- Step 2, §5.0's silence sweep for this binding (above).
-- Step 3, the idle sweep for this binding:
WITH idle AS (
  SELECT r.tenant_id, r.binding_id, r.replica_id
    FROM sm_data.agent_replica r
   WHERE r.retired_at IS NULL
     AND r.tenant_id = $tenant AND r.binding_id = $binding
     AND NOT EXISTS (SELECT 1 FROM sm_data.agent_replica_incarnation i
                      WHERE i.replica_id = r.replica_id AND i.retired_at IS NULL)          -- no active incarnation (§5.0 is what makes this reachable)
     AND COALESCE((SELECT max(i.retired_at) FROM sm_data.agent_replica_incarnation i
                    WHERE i.replica_id = r.replica_id), r.registered_at) < now() - $replica_idle_retirement
   ORDER BY r.replica_id
   FOR UPDATE
),
retired AS (
  UPDATE sm_data.agent_replica r SET retired_at = now()
    FROM idle WHERE r.replica_id = idle.replica_id
  RETURNING r.tenant_id, r.binding_id
)
UPDATE sm_data.agent_replica_cap c
   SET active_replicas = GREATEST(c.active_replicas - x.n, 0)
  FROM (SELECT tenant_id, binding_id, count(*) AS n FROM retired GROUP BY 1, 2) x
 WHERE c.tenant_id = x.tenant_id AND c.binding_id = x.binding_id;
```

`replica_idle_retirement` is new configuration ([`design.md`](design.md) §5.8). **The cap counts active rows only**, and the decrement is in the same transaction as the retirement, so a deployment that churns replica names — a new pod name per rollout, a renamed node — consumes and releases cap slots instead of exhausting them once and forever. [`design.md`](design.md) §8 row 41 tests churn beyond the cap with the concurrent count below it: registrations succeed.

⚠️ **What changes for a binding sitting at the cap.** Because retirement is now the job's, a slot is released **on the migrator's cadence, not on the next report**. A binding at `max_replicas_per_binding` whose replicas have all gone idle therefore refuses registrations `REPLICA_CAP_EXCEEDED` until the next sweep, rather than freeing a slot inside the report that would have used it. That is a stated latency — bounded by the job interval, and the reason `max_replicas_per_binding` must be set above the *peak* live population rather than exactly at it — accepted deliberately in exchange for a single lock order. The alternative, keeping retirement inside the registration transaction, is what C-R9/F5 measured as a deadlock between two parties taking the cap row and the replica rows in opposite orders; scoping (C2) removed the cross-tenant reach and the fleet-wide scan but could not remove that inversion, so the C2 note's claim of "no new lock order" is **withdrawn** rather than repaired. The `I5`-shaped reading C2 gave still holds and is now trivially true: a registration mutates no registry row outside its own `(tenant, binding)` because it mutates no other row at all.

### 5.2 The migrator deletes, and only when nothing points at the row

```sql
-- Retired incarnations: pruned after registry_retention.
DELETE FROM sm_data.agent_replica_incarnation WHERE retired_at < now() - $registry_retention;

-- Retired stable replicas: pruned only after registry_retention AND only when no series row of THE SAME TENANT still names the
-- replica. The series dimension holds replica_id (architecture.md §8) — replica_id IS the source_key surrogate — so deleting a
-- replica a series still references would orphan the dimension of stored samples. Retention is therefore predicated, not scheduled.
DELETE FROM sm_data.agent_replica r
 WHERE r.retired_at < now() - $registry_retention
   AND NOT EXISTS (SELECT 1 FROM sm_data.agent_replica_incarnation i WHERE i.replica_id = r.replica_id)
   AND NOT EXISTS (SELECT 1 FROM sm_data.series s
                    WHERE s.tenant_id = r.tenant_id                       -- TENANT-SCOPED (S11/F1d): see the note below
                      AND s.source_key = r.replica_id::text);
--   agent_lease rows are NEVER deleted (their existence is the "ever granted" test); agent_replica_cap rows are never deleted
--   (they are one small row per binding and the cardinality authority).
```

⚠️ **RETRACTION — `sm_data.series` IS defined, and the S10/F2 "blocked by dependency" claim was wrong (S11/F1).** S10 searched `docs/` only, found no design document that creates the relation, and concluded none does. The relation is created by a **migration**, not by a document: [`../../../../backend/src/main/resources/db/migration/V001__telemetry_schema.sql`](../../../../backend/src/main/resources/db/migration/V001__telemetry_schema.sql), whose own lines 3-5 say **"THIS FILE IS THE SOURCE OF TRUTH FOR THE TELEMETRY SCHEMA. `docs/design/architecture.md` §8 describes intent; where the two differ, THIS WINS."** It creates `placement`, `binding_policy`, **`series`**, `metrics_raw` (+ partitions), `metrics_rollup_1h` and `events_raw`. So this statement **is** executable today, it is executed in the evidence run, and the three legs of `(L13)` measure it. Everything the S10 text said about *not dropping the predicate* and *not inventing the table* stands; what was false is "no document in this set defines it" and the `BLOCKED-dependency` verdict that followed from it.

⚠️ **Why the `s.tenant_id = r.tenant_id` predicate was added (S11/F1d), and what it changes.** `series`'s keys are all tenant-scoped — `PRIMARY KEY (tenant_id, series_id)` and `UNIQUE (tenant_id, instance_id, placement_id, metric_name, package_version, source_key, labels_fingerprint)` — so a `source_key` **value** is unique only *within* a tenant, and `series` rows of two tenants may legitimately carry the same string. `agent_replica` likewise carries `tenant_id` in its own `PRIMARY KEY (tenant_id, binding_id, replica_id)`. Without the predicate, a foreign tenant's series row naming the same string **withholds** this delete — the failure direction is a **permanently stalled retention**, not an orphaned dimension, so nothing was ever at risk of being deleted wrongly; but the statement's correctness then rested on the *unstated* fact that `replica_id` happens to be globally unique (`agent_replica`'s global `UNIQUE (replica_id)`, §2.3) rather than on anything the statement says — exactly `L19`'s shape. The predicate makes the decision local to the tenant that owns the row, matches `I5`'s habit of never deciding across a tenant boundary, and is what `(L13)`'s third leg measures: a same-string `source_key` in **another** tenant must not influence this delete.

**Bootstrap position, because it is not free to choose.** The migration must run **after** the walking skeleton's Phase 0 and **before** its Phase 1: Phase 0's `CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner` carries no `IF NOT EXISTS`, so anything that creates the schema first makes Phase 0 fail; V001's own `CREATE SCHEMA IF NOT EXISTS sm_data` is then a notice-only no-op ("Do nothing (except issuing a notice) if a schema with the same name already exists", PG 14 docs, `sql-createschema`; verified 2026-09-11) and the schema keeps Phase 0's ownership. V001 then sets `search_path` to `sm_data` and creates unqualified, so its tables land there ("it is also the schema in which new tables will be created if the `CREATE TABLE` command does not specify a schema name", PG 14 docs, `ddl-schemas`; verified 2026-09-11); run as `sm_dp_migrator` under `SET ROLE sm_dp_owner` — Flyway's `initSql`, the convention Phase 1 states for itself — its tables are born owned by `sm_dp_owner`. **One consequence is recorded rather than papered over:** Phase 1's `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES TO sm_dp_runtime` runs afterwards and "does not affect privileges assigned to already-existing objects" (PG 14 docs, `sql-alterdefaultprivileges`; verified 2026-09-11), so `sm_dp_runtime` holds **no** privilege on `placement` or `series`. That is correct and deliberate: those tables' runtime grants belong to the `DM-D*`/`BE-D*` owners, and this statement is the migrator's.

🔴 **AND EXECUTING IT FOUND A GAP BETWEEN TWO COMMITTED DOCUMENTS, WHICH IS ROUTED RATHER THAN REPAIRED AS CENTRAL OPEN ITEM 38 (S11/F1a, measured `42501`).** V001's first statement is `CREATE SCHEMA IF NOT EXISTS sm_data`, and "to create a schema, the invoking user must have the `CREATE` privilege for the current database" (PG 14 docs, `sql-createschema`, Notes; verified 2026-09-11). **That privilege check runs before the `IF NOT EXISTS` test**, so it fires even though Phase 0 has already created the schema: run as `sm_dp_migrator` under `SET ROLE sm_dp_owner`, V001 raises `42501: permission denied for database`. The walking skeleton's Phase 0 grants **no database-level privilege to any `sm_dp_*` role** — it creates the schema as the cluster administrator and grants only `USAGE ON SCHEMA` — so **the Flyway migrator role as that document defines it cannot execute V001 as written.** Three closes exist and none is this document's to pick: add `GRANT CREATE ON DATABASE … TO sm_dp_owner` to Phase 0; or have the installer (not Flyway) run V001; or drop V001's `CREATE SCHEMA` line so it relies on Phase 0 having run. **Routed to the `BE-D*` owners** — it is their migration and their bootstrap — and the choice is install-runbook material that belongs beside Phase 0's own `REVOKE`-needs-the-superuser note. The harness supplies the missing grant as a **HARNESS-ONLY** step, printed in the log and labelled there, exactly as it supplies the role passwords Phase 0 also deliberately omits; **V001's bytes are not edited**, because a harness that repairs its input measures nothing.

### 5.3 The lease ledger's retention

**One bounded batch per transaction, and the lock is taken as a statement, not as an intention** (R8/F3). The round-7 form was a single set-based `DELETE` with the advisory lock described only in prose — a statement that cannot take a per-key lock at all, so the job side of the F3 serialization had no artifact. The job therefore selects a **bounded** batch, locks every key in it, and only then deletes and tombstones:

```sql
-- Step 1 — choose a bounded batch, take the SAME advisory lock the admission paths take on each key, and return the ids.
--          One statement, so the lock is acquired for every key the caller is about to delete.
WITH batch AS (
  SELECT request_id FROM sm_data.agent_lease_grant
   WHERE requested_at < now() - $ledger_retention
     AND state <> 'PENDING'                                                     -- terminal rows only: see the PENDING note below
     AND request_id NOT IN (SELECT grant_request_id FROM sm_data.agent_lease)   -- never the row a live projection names
   ORDER BY request_id                                                          -- the INTENDED lock order; not a guarantee — see the note
   LIMIT $retention_batch                                                       -- BOUNDED: see the note below
)                                                                               -- deliberately NO row-level FOR UPDATE here: see the lock-order note
SELECT request_id, pg_advisory_xact_lock(hashtextextended(request_id::text, 0)) FROM batch ORDER BY request_id;
--   → $ids, the batch whose request keys this transaction now holds.

-- Step 2 — delete those ids and tombstone them, in the SAME transaction, while those locks are still held — and RE-CHECK EVERY
--          PREDICATE, because step 1 evaluated them BEFORE the locks were granted (C-R9/F2):
WITH gone AS (
  DELETE FROM sm_data.agent_lease_grant lg
   WHERE lg.request_id = ANY ($ids)
     AND lg.requested_at < now() - $ledger_retention                            -- re-checked
     AND lg.state <> 'PENDING'                                                  -- re-checked
     AND NOT EXISTS (SELECT 1 FROM sm_data.agent_lease l                        -- re-checked: THE ONE THAT BITES
                      WHERE l.grant_request_id = lg.request_id)
  RETURNING lg.request_id
)
INSERT INTO sm_data.agent_request_tombstone (request_id, expired_at)
SELECT request_id, now() FROM gone
ON CONFLICT (request_id) DO NOTHING;
--   Only rows the DELETE actually removed are tombstoned: `gone` is its RETURNING, so a row the re-check spared is left alone AND
--   left un-tombstoned — it must stay replayable, not become REQUEST_EXPIRED.
-- Commit releases every advisory lock (they are transaction-scoped; there is no unlock to forget). A retry for any id in the batch
-- blocked at its own step 0a and, on release, reads the committed tombstone → REQUEST_EXPIRED, never a second grant.
```

⚠️ **Why step 2 re-checks what step 1 already checked — and what that re-check is actually worth here (C-R9/F2, corrected in S1/F6).** Step 1's predicates are evaluated against the snapshot its `SELECT` takes ("a `SELECT` query sees a snapshot of the database as of the instant the query begins to run", PG 14 docs, `transaction-iso`; verified 2026-09-09) and **before** its advisory locks are granted, so in general a row that was prunable when it was selected need not be prunable when the lock arrives. Repeating the predicates inside the `DELETE`, under the locks, is what makes the decision and the deletion the same decision — and that is the right shape for a retention job.

**But for *this* batch, all three re-checks are defensive rather than raced, and saying so is the honest form.** The batch selects `state <> 'PENDING'`; `terminal_once` admits no transition out of a terminal state; `requested_at` is immutable and `now()` only advances; and `agent_lease` is written **only** by §3.3's step 3c, whose `g` requires `state = 'PENDING'`. So no legal transition can make a selected row younger than the horizon, non-terminal, or newly named by `agent_lease.grant_request_id`. Round 10 claimed a planted race for the projection re-check; it described an unreachable interleaving and is withdrawn (§8 row 37, (L9)). The re-checks stay — they are cheap, and they are the guard against a *future* statement that projects from a non-`PENDING` row — but the execution record must mark them **unexercised** rather than report a race test that cannot be written. The re-check that genuinely is load-bearing is **§5.4's**, where a concurrent adoption really does move `attempt_deadline` under the lock.

⚠️ **Why a `PENDING` row is never pruned, and what bounds it instead.** Since C-R9/F1 a `PENDING` row is a **recoverable** request — its session may have been lost, and the agent's next owner is entitled to adopt it and re-issue it under the same id — so deleting one would destroy exactly the state that repair reads, and tombstoning it would refuse the repair. Retention therefore takes **terminal rows only**, which leaves the row nobody ever returns for: the bound on that is not retention but the **orphan sweep** of §5.4, a separate statement in a separate pass. Two passes, never one: a request is ended in one transaction and erased in another, so no statement both terminalizes and deletes.

⚠️ **Why the batch must be bounded.** Each key costs one advisory lock held to commit, and "both advisory locks and regular locks are stored in a shared memory pool whose size is defined by the configuration variables `max_locks_per_transaction` and `max_connections`. Care must be taken not to exhaust this memory or the server will be unable to grant any locks at all" (PG 14 docs, `explicit-locking`, "Advisory Locks"; verified 2026-09-09). `retention_batch` is therefore **new configuration** ([`design.md`](design.md) §5.8) and the job loops over batches rather than sweeping the horizon in one transaction. An unbounded sweep would make retention itself the outage. The identical shape applies to `agent_claim`'s retention, which is [`design.md`](design.md) §7.4's statement.

⚠️ **Lock order, and what is and is not guaranteed here.** Two different orders matter, and only one of them is a property of the SQL.

**Guaranteed, and load-bearing: key before row.** Every party takes the **request key first and the ledger row second**. An admission path takes the key at its step 0a and touches the row afterwards; step 1 above selects **without** `FOR UPDATE` so it takes only advisory locks, and the row is not touched until step 2, under the key. Had the job taken the row first and then waited for the key, the cycle would close and PostgreSQL would abort one transaction — retention becoming a source of failed renewals. This ordering is in the statements, not in the prose.

**Not guaranteed: the order in which the batch's keys are locked.** `pg_advisory_xact_lock` sits in step 1's select list, and PostgreSQL "will effectively evaluate output expressions after sorting and limiting, so long as those expressions are not referenced in `DISTINCT`, `ORDER BY` or `GROUP BY`" — while also recording that versions before 9.6 "did not provide any guarantees about the timing of evaluation of output expressions versus sorting and limiting; it depended on the form of the chosen query plan" (PG 14 docs, `sql-select`, "SELECT List"; verified 2026-09-09). So current PostgreSQL evaluates the locks after the sort, which is the intent, but that is **planner behaviour rather than a contract**, and the docs promise nothing about the order among the sorted rows. The design therefore does **not** rest on it: **one migrator instance runs retention** (`design.md` §5.8, the install runbook's job), so there is normally no second run to order against; and if two runs ever overlap, PostgreSQL's deadlock detector aborts one and the job retries the batch. The cost of that is duplicated work, never a second grant and never a lost tombstone — a deleted row and its tombstone are one transaction, so an aborted run leaves the ledger row in place to be re-picked. A row another run has already deleted simply drops out of step 2's `DELETE`, which is a no-op for it, not an error.

---

### 5.4 The orphan sweep — ending what nobody drives

Round 9 asserted that "the migrator first completes an over-age `PENDING` row" and shipped **no statement, no lock protocol and no test** for it (R10/F2) — the same `L19` shape the whole family keeps rediscovering — while (L9)'s interleaving asked §5.3's terminal-only batch to select "an old `PENDING` row", which it cannot. The failure it leaves open: `dp-core` reserves a request, the process dies, the agent never retries (it re-enrolled, was decommissioned, or simply never came back), and the row lives for ever — never adopted, never taken over, never prunable, and counted for ever in `dp_agent_lease_requests_pending`.

**Since R10/F3 this sweep's statement is the *only statement* that can end an undriven request**, because every caller-driven terminal write carries a session fence and an orphan by definition has no session to present. ⚠️ **That is a property of the STATEMENT, not of the actor (S11/F4):** whoever executes §5.4 can end such a request, and the DCL does not restrict that to the migrator.

⚠️ **§5.4 is a SAFE, IDEMPOTENT MAINTENANCE OPERATION, and the "migrator's alone" claim is withdrawn (S11/F4) — §5.1's note carries the full statement of the property and the reasoning; this is the §5.4-specific half.** The runtime role *can* run both of these statements, measured (S10/F3): the columns they write — `agent_lease_grant.state/outcome/completed_at` — are exactly the columns **step 3c** writes on the runtime path, so no column-scoped grant can refuse them without breaking the lease path. What the DCL *does* enforce is narrower and is stated in (L14): **no `DELETE` on any of the eleven tables**, **no `INSERT` on `agent_request_tombstone`**, the identity columns unwritable, and no `nextval` without the sequence `USAGE` grant. **Why concurrent execution is harmless here:** step 2 re-checks **both** of step 1's predicates under the request-key advisory lock and against the committed row version (PG 14 docs, `transaction-iso`, Read Committed; verified 2026-09-11), so a request that was adopted, taken over or completed while this sweep waited is skipped; the `terminal_once` trigger admits no second transition out of a terminal state, so a second sweep cannot re-terminalize a row; and `(L16)`'s sweep-vs-completion leg measures the one contended case — the request ends with **exactly one** outcome and neither transaction raises `40P01`. **The migrator remains the recommended scheduler** for the reason §5.1 gives, and which component owns that schedule is routed there, not guaranteed here.

```sql
-- Step 1 — bounded batch, and the SAME request-key advisory lock §5.3 step 1 takes, so this serializes against an adoption or a
--          takeover in flight on the same request (§6). No row-level FOR UPDATE: key first, row second, as everywhere else.
WITH batch AS (
  SELECT request_id FROM sm_data.agent_lease_grant
   WHERE state = 'PENDING'
     AND attempt_deadline < now() - $orphan_grace
   ORDER BY request_id LIMIT $retention_batch
)
SELECT request_id, pg_advisory_xact_lock(hashtextextended(request_id::text, 0)) FROM batch ORDER BY request_id;
--   → $ids

-- Step 2 — terminalize, RE-CHECKING both predicates under the locks (step 1 evaluated them before the locks were granted, exactly
--          the R9/F2 lesson):
UPDATE sm_data.agent_lease_grant
   SET state = 'FAILED', outcome = 'TIMEOUT', completed_at = now()
 WHERE request_id = ANY ($ids)
   AND state = 'PENDING'                                                   -- re-checked
   AND attempt_deadline < now() - $orphan_grace;                           -- re-checked
--   The terminal_once trigger admits this: it is the completion branch (PENDING → FAILED), and R10/F8 requires the attempt controls
--   and fencing_epoch to be unchanged, which this statement does not touch.
--   Deletion is NOT here. The row is now terminal and becomes eligible for §5.3's retention batch on a LATER run, which is what
--   writes its tombstone.
```

**`orphan_grace` is new configuration** ([`design.md`](design.md) §5.8) and must be **≫ `pending_ttl` × `takeover_attempts`**, so that no live caller can be mid-recovery on a request this sweep touches: a request still inside its takeover budget is somebody's to drive, and the sweep must not race it.

⚠️ **The transaction boundary, stated exactly — the sweep and a recovery cannot both win (S1/F4).** Round 9 asserted that a racing adoption leaves the sweep to "re-check a changed deadline and skip", but **adoption did not change any deadline** and nothing said which statements shared a transaction; a request past `orphan_grace` could therefore be adopted, and the sweep — locking after the adopter's transaction committed — would still see the stale deadline and terminalize a **live** recovery as `TIMEOUT`. Two changes close it, and both are statements rather than prose:

1. **Adoption re-arms `attempt_deadline`** (§3.1, S1/F3). The orphan predicate `attempt_deadline < now() - $orphan_grace` therefore **cannot hold** on an adopted row at the adopter's commit, so there is no stale-deadline window left to lose.
2. **The recovery's DB work is one transaction that ends before the CP is called.** The boundary is: `BEGIN` → **step 0a** takes the request-key advisory lock → **step 0b** checks the tombstone → **step 0c** takes the identity lock → **step 1**'s fenced reservation, or §3.1's classification and the adoption (or takeover) `UPDATE` → **`COMMIT`** → *then* the CP call (§3.2) → *then* a **new** transaction for step 3a/3b/3c. **The CP call must never run inside a database transaction** — it is an external call of unbounded latency, and holding the request-key lock and a row lock across it would block the sweep, every retry of the same request and the retention job for as long as the CP takes. That is why the ledger exists at all: the `PENDING` row, not a held lock, is what carries ownership across the external call.

So the two orders are the only two, and both are safe: the sweep **waits** on the request key and then finds a re-armed deadline, skipping the row (its step-2 predicates are re-checked under the lock and no longer hold); or the sweep **wins first**, the row is terminal at `FAILED('TIMEOUT')`, and the would-be adopter's classification (§3.1) returns the **stored** refusal rather than adopting — the caller never reaches the CP, and the agent is refused `RE_ENROLL_REQUIRED`. There is no interleaving in which a request is both handed to a live owner and terminalized.

**What this sweep is not.** It is not a recovery: a `renew` whose nonce the CP consumed is not repaired by being marked `TIMEOUT`, and the agent's next session is refused `RE_ENROLL_REQUIRED` — the same operator event §12 limit 17 already names, reached because nobody came back to drive the recovery rather than because the DP foreclosed it.

## 6. Tombstones and the advisory lock

The tombstone table itself is `design.md`'s (§5.2) — both ledgers share it. What is this sketch's is how the lease path and the retention job avoid racing over it.

Both ledgers insert `ON CONFLICT DO NOTHING`, and both admission paths check the tombstone in a step 0. Under `READ COMMITTED` those two facts are not enough: a retry can read "no tombstone" at the instant the retention job has deleted the ledger row but not yet committed its tombstone insert, and then insert its own row as new — precisely the second grant the tombstone exists to prevent (`F3`/R7). **Both sides therefore take a transaction-scoped advisory lock on the request key before they touch it:**

```sql
SELECT pg_advisory_xact_lock(hashtextextended($request_id::text, 0));
```

taken by the lease path before step 0b (§3.1), by the claim path before its own tombstone check ([`design.md`](design.md) §5.2), and by the retention job **as the statement in §5.3 step 1** — one lock per key in the batch, acquired before the `DELETE` (the order *among* those keys is not a guarantee the SQL gives; §5.3's lock-order note says what is and is not promised). The lock is released at commit or abort — there is no leak path and no explicit unlock, because these are transaction-level advisory locks: "they are automatically released at the end of the transaction, and there is no explicit unlock operation" (PG 14 docs, `explicit-locking`; verified 2026-09-09). **Hash collisions cause spurious serialization, never incorrectness:** two unrelated request ids that hash alike simply wait for one another; the tombstone row and the primary key remain the deciders. [`design.md`](design.md) §8 row 37 tests the interleaving under a barrier.

**Round 7 stated this rule and shipped no artifact on the job side** — the retention statements were set-based `DELETE`s and "under the advisory lock" was prose, which a set `DELETE` cannot honour for a per-request key. §5.3 now carries the acquisition as SQL and a bound on the batch; [`design.md`](design.md) §7.4 carries the same shape for `agent_claim`. Until both execute (§10 gate 3) this is a written mechanism, not a measured one.

---

## 7. DCL — the DP roles on these five tables and the sequence

Moved here from [`design.md`](design.md) §7.4, which keeps the grants for the tables it keeps. Role topology as [`../eda-walking-skeleton.md`](../eda-walking-skeleton.md) §3.1: `sm_dp_runtime` mutates within a narrow column scope and never deletes; the migrator owns DDL and retention.

| Table | `sm_dp_runtime` |
|---|---|
| `agent_lease` | `SELECT`, `INSERT`, `UPDATE (lease_jti, cert_thumbprint, target_id, binding_ids, capabilities, agent_revocation_epoch, fleet_revocation_epoch, audience, issued_at, expires_at, ceiling_preflight, ceiling_apply, ceiling_restart, ceiling_stop, admission_margin, grant_request_id, grant_seq, renewed_at)` — never `tenant_id`/`agent_id` |
| `agent_lease_grant` | `SELECT`, `INSERT`, `UPDATE (state, outcome, lease_jti, issued_at, expires_at, grant_payload, completed_at, fencing_epoch, attempt_no, attempt_owner, attempt_deadline)` — the request identity columns (`request_id`, `tenant_id`, `agent_id`, `kind`, `request_fingerprint`, `grant_seq`, `requested_at`) are **never** writable; the `terminal_once` trigger confines the writable ones to the three admitted transitions (§2.1) |
| `agent_replica` | `SELECT`, `INSERT`, `UPDATE (retired_at, reporter_agent_id, reporter_since)` — `retired_at` is written by **B2**'s un-retire on the runtime path and by **§5.1**'s sweep on the maintenance path, and **this grant does not distinguish them** (S10/F3, S11/F4): the sweep's exclusivity to the migrator is scheduling, not privilege, and §5.1 states the safety property that makes that acceptable; the two reporter columns only for **B2**'s first-registration seed and **B1**'s admitted **handoff**, both of which run only after every Phase A gate has passed (R10/F4, S2/F1) |
| `agent_replica_cap` | `SELECT`, `INSERT`, `UPDATE (active_replicas)` |
| `agent_replica_incarnation` | `SELECT`, `INSERT`, `UPDATE (retired_at, last_seen_at, last_seen_report, last_seen_by_agent)` — `last_seen_at`, `last_seen_report` **and** `last_seen_by_agent` are **B3**'s refresh, which must advance the watermark as well as the timestamp or an old delayed report refreshes liveness (C-R9/F3, R10/F6), and which runs **only for the activation the report names** (S2/F2); `retired_at` is **B4**'s retire and never carries another field with it. **`registered_by_report` stays deliberately NOT writable** — it records the report that *created* the activation, which is exactly why R10/F6 needed a separate `last_seen_report` rather than reusing it, and it is what round 8's `DO UPDATE` un-retire would have hit as `42501` (C-R9/F6) |
| **no `DELETE` on any of the five** | retention is the migrator's (§5) |

⚠️ **And here it is as SQL, because (L14) cannot be executed against a table of prose (S9/C2).** The table above is the explanation; this block is the artifact, and the two agree row for row and column for column. Run by the migrator under `SET ROLE sm_dp_owner`, like the §2 DDL.

```sql
-- Runtime grants for the FIVE tables this sketch owns. Mixed table-level + column-scoped form, as
-- ../eda-walking-skeleton.md §3's DCL uses it and measured on PG 14 there.
GRANT SELECT, INSERT, UPDATE (lease_jti, cert_thumbprint, target_id, binding_ids, capabilities,
                              agent_revocation_epoch, fleet_revocation_epoch, audience, issued_at, expires_at,
                              ceiling_preflight, ceiling_apply, ceiling_restart, ceiling_stop, admission_margin,
                              grant_request_id, grant_seq, renewed_at)
    ON sm_data.agent_lease TO sm_dp_runtime;                              -- never tenant_id / agent_id
GRANT SELECT, INSERT, UPDATE (state, outcome, lease_jti, issued_at, expires_at, grant_payload, completed_at,
                              fencing_epoch, attempt_no, attempt_owner, attempt_deadline)
    ON sm_data.agent_lease_grant TO sm_dp_runtime;                        -- never request_id, tenant_id, agent_id,
                                                                          -- kind, request_fingerprint, grant_seq,
                                                                          -- requested_at; the terminal_once trigger
                                                                          -- (§2.1) confines the writable ones further
GRANT SELECT, INSERT, UPDATE (retired_at, reporter_agent_id, reporter_since)
    ON sm_data.agent_replica TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (active_replicas)
    ON sm_data.agent_replica_cap TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (retired_at, last_seen_at, last_seen_report, last_seen_by_agent)
    ON sm_data.agent_replica_incarnation TO sm_dp_runtime;                -- registered_by_report stays unwritable
-- No DELETE on any of the five for sm_dp_runtime: retention is the migrator's (§5).
GRANT USAGE ON SEQUENCE sm_data.agent_lease_grant_seq TO sm_dp_runtime;   -- INSERT alone cannot evaluate nextval(); the round-7
                                                                          -- finding was that bigserial hid this requirement (F9/R7)
-- No grant is needed on sm_data.agent_lease_grant_terminal_once(): EXECUTE on functions is a PUBLIC default
-- ("the default privileges granted to PUBLIC are as follows: … EXECUTE privilege for functions and procedures",
-- PG 14 docs, ddl-priv; verified 2026-09-10), and a trigger runs with the privileges of the user who modifies
-- the table (same page, TRIGGER privilege) — so it fires for sm_dp_runtime without appearing in this list.
```

⚠️ **THE LOCK-ORDER MODEL: A STRICT SHARED PREFIX, THEN TWO BRANCH TAILS (S8/F1, S8/C1; the model itself corrected in S9/F1).** S8 wrote this as one linear order through all six tables and then listed retirement as an exception marked conforming — a rule its own table broke, which is not a rule. The model below is the one the SQL actually implements, and the exception is folded into it rather than kept beside it.

**The strict shared prefix — every transaction that reaches these rows takes them in this order and never moves backwards within it:**

> advisory request key (where the path has one) → `agent_identity` → `agent_session` → `agent_lease_grant` / `agent_lease` → the **binding-scoped** `agent_replica_cap` row

**The branch tails — after the cap row, two transactions legitimately diverge:**

> **registration tail** (§4): `agent_replica` → `agent_replica_incarnation`
> **retirement tail** (§5.1): `agent_replica_incarnation` → `agent_replica`

**The invariant, in five clauses:**

1. **No transaction moves backwards within the strict prefix.** Having locked a table in the prefix, a transaction never afterwards locks one earlier in it. This is the clause S7/C1 violated and S8/F1 repaired.
2. **Every registration and every retirement transaction for a binding locks that binding's `agent_replica_cap` row `FOR UPDATE` before touching either tail** — registration at **A2**, retirement at **§5.1 step 1** — and holds it to commit, because row-level locks are released only at transaction end ("Row-level locks are released at transaction end or during savepoint rollback", PG 14 docs, `explicit-locking`; verified 2026-09-09).
3. **The opposite tails are therefore mutually exclusive on the same binding.** Two transactions can only interleave their tails if both are past clause 2's lock on the same cap row, and clause 2 makes that impossible — so registration's `agent_replica` → `agent_replica_incarnation` and retirement's `agent_replica_incarnation` → `agent_replica` cannot deadlock against each other. **The enforcing test is (L13)**, whose same-binding barrier runs a registration and the migrator's sweep concurrently and whose planted half removes the cap-row lock; that test is what makes this clause a claim with an artifact rather than an argument.
4. **Different bindings share no registry rows**, so tails on different bindings cannot interleave at all. This rests on two schema facts and not on convention: `agent_replica`'s `PRIMARY KEY (tenant_id, binding_id, replica_id)` with `UNIQUE (replica_id)` puts every replica in exactly one binding (§2.3), and `agent_replica_incarnation`'s `FOREIGN KEY (replica_id) REFERENCES sm_data.agent_replica (replica_id)` puts every activation under exactly one replica and therefore one binding (§2.5).
5. ⚠️ **A FOREIGN KEY is a lock edge, and the prefix's parent rows may therefore never be held in a mode `KEY SHARE` conflicts with (S10/F4).** This clause exists because executing the S9 text deadlocked: *every* `INSERT` or `UPDATE` of a child row takes a **`KEY SHARE`** lock on its parent — "foreign key checks use the new `KEY SHARE` lock mode, which does not conflict with `NO KEY UPDATE`" (PG 9.3 release notes; verified 2026-09-10) — and `FOR KEY SHARE` conflicts with **`FOR UPDATE` and with nothing else** (PG 14 docs, `explicit-locking`, Table 13.3 *Conflicting Row-Level Locks*; verified 2026-09-10). The FK edges into the prefix's own parents are:
   * **`agent_identity` is the parent of NINE foreign keys, and every one of them is a `KEY SHARE` edge onto the identity row.** Enumerated from the built catalog rather than from memory (`pg_constraint` on the `dce059d` build): `agent_session (tenant_id, agent_id)` · `agent_claim (tenant_id, agent_id)` · `agent_command (tenant_id, agent_id)` · `agent_lease_grant (tenant_id, agent_id)` · `agent_lease (tenant_id, agent_id)` · `agent_replica (tenant_id, registered_by_agent)` · `agent_replica (tenant_id, reporter_agent_id)` · `agent_replica_incarnation (registered_by_agent)` · `agent_replica_incarnation (last_seen_by_agent)` — the last two referencing `agent_identity (agent_id)` through its global `UNIQUE`, the rest the `(tenant_id, agent_id)` pair. **Which prefix transaction writes which child:** `claim` writes `agent_session` and `agent_claim` ([`design.md`](design.md) §5.2); the command feed and the two acks write `agent_command`; the lease reservation at **step 1** and every ledger write after it write `agent_lease_grant`; **step 3c** writes `agent_lease`; and registration writes `agent_replica` at **B1** and **B2** and `agent_replica_incarnation` at **B3**, **B4** and **B5**. So a transaction sitting anywhere in the prefix or in either tail may need `KEY SHARE` on an identity row at any moment. **Therefore the prefix's identity lock is `FOR NO KEY UPDATE`, never `FOR UPDATE`** — lease **step 0c**, step 1's `id` re-lock and registration **A0** all use that mode — and that one mode is sufficient for **all nine** edges, because `FOR KEY SHARE` conflicts with `FOR UPDATE` alone (Table 13.3). The only other holder of an identity row is a concurrent `claim`, which takes `FOR SHARE`; `KEY SHARE` does not conflict with that either, so no FK check anywhere in the design set can be blocked by a lock this design set takes. What `FOR NO KEY UPDATE` still does conflict with is `FOR SHARE`, `FOR NO KEY UPDATE` and `FOR UPDATE`, so it goes on serializing two registrations of one agent and goes on serializing against `claim` — everything the lock was for. **The measured failure it removes:** step 3 holds the ledger row at **3b** and then needs `KEY SHARE` on the identity for 3c's projection into `agent_lease`, while a concurrent step-1 retry holds the identity at **0c** and waits for that ledger row — a cycle, `40P01` ((L18-P3), and `(L18)/L18-P3-mechanism-probe-1` and `-3` measure the wait and its absence directly).
   * **`agent_replica` is the parent of `agent_replica_incarnation`** (`FOREIGN KEY (replica_id)`), so **B5**'s activation insert — and **B3**'s and **B4**'s updates, which are children of the same row — take `KEY SHARE` on the replica row. A *concurrent* transaction can never be holding that row in a conflicting mode: **A2** materializes and locks the binding's `agent_replica_cap` row `FOR UPDATE` before either tail is entered (clause 2 — **A2** is the cap lock; **A3** is the stable read and takes no lock at all), and the only other holder of a replica row, retirement's `idle` CTE `FOR UPDATE`, is behind that same cap mutex. The tail's own transaction holding the row is irrelevant: a lock never conflicts with itself. So this edge needs no mode change, and clause 2 is what makes that true rather than luck.

**Why the tails may differ at all:** retirement's step 3 can only decide which replicas are idle *after* step 2 has retired the silent incarnations, so its order is forced by its own data dependency. The production SQL is **not** reordered to fit a prettier rule; the rule is written to describe it, and clauses 2–4 are what make the divergence safe.

| Transaction | Prefix locks, in order | Tail | Conforms |
|---|---|---|---|
| `claim` (design.md §5.2) | `agent_identity` (FOR SHARE) → `agent_session` (INSERT … ON CONFLICT DO UPDATE) | — | ✔ — it is the prefix's reference order (design.md §5.2's pointer) |
| Lease step 0c + step 1 (§3.1) | advisory key (0a) → `agent_identity` (**FOR NO KEY UPDATE**, **0c**; clause 5) → `agent_session` (FOR SHARE, step 1's `s` CTE) → `agent_lease_grant` (INSERT) | — | ✔ **since S8/C1** — the identity lock was hoisted out of step 1's `id` CTE, where the documentation specifies **no** acquisition order among sibling `SELECT` CTEs (S9/F2) |
| Lease step 2 (the CP call) | none — runs **outside** any transaction (§5.4's boundary note) | — | ✔ |
| Lease step 3 (§3.3) | `agent_session` (FOR SHARE, **3a**) → `agent_lease_grant` (FOR UPDATE, **3b**) → `agent_lease` (3c's projection, **+ `KEY SHARE` on `agent_identity` by FK — clause 5**) | — | ✔ **since S8/C1** for the prefix order; the FK edge was unnamed until S10/F4, and it is what deadlocked against step 0c's old `FOR UPDATE` |
| Epoch transfer (§3.1) | `agent_session` (FOR SHARE) → `agent_lease_grant` (UPDATE) | — | ✔ |
| Takeover and the cap completion (§3.4) | `agent_session` (FOR SHARE) → `agent_lease_grant` (UPDATE) | — | ✔ |
| Registration (§4) | `agent_identity` (**FOR NO KEY UPDATE**, **A0**; clause 5) → `agent_session` (FOR SHARE, **A1**) → `agent_replica_cap` (FOR UPDATE, **A2**) | **registration tail**: `agent_replica` (A3/B1/B2) → `agent_replica_incarnation` (A4/B3/B4/B5) | ✔ prefix **since S8/F1**; tail by clauses 2–4 |
| Retirement (§5.0 + §5.1, migrator) — **one transaction per binding**, §5.0's sweep being §5.1's step 2 | `agent_replica_cap` (FOR UPDATE, **§5.1 step 1**) | **retirement tail**: `agent_replica_incarnation` (step 2 = §5.0) → `agent_replica` (FOR UPDATE, step 3's `idle` CTE); step 3's counter `UPDATE` is a **re-lock** of the step-1 row, acquiring nothing new | ✔ prefix (it enters at the cap row and takes neither identity nor session); tail by clauses 2–4. ⚠️ **§5.0's half must not run until §13 AGW-19 closes** — it has no liveness source — so today only steps 1 and 3 execute |
| Ledger retention (§5.3, migrator) | advisory request keys → `agent_lease_grant` (DELETE) → `agent_request_tombstone` (INSERT) | — | ✔ — key before row (§5.3's own note) |
| Orphan sweep (§5.4, migrator) | advisory request keys → `agent_lease_grant` (UPDATE) | — | ✔ |

**Audited, and the audit is now checkable against the model rather than against a sentence:** the only transactions taking **both** an identity and a session lock are lease step 0c + step 1 and registration A0 + A1, and both take identity first (clause 1). The only transactions taking both a **session** and a **ledger** lock are lease step 1 and lease step 3, and both take the session first (clause 1). The only transactions reaching a **tail** are registration and retirement, and both pass clause 2 first. Two implementations that took the tails in opposite orders can no longer both claim conformance by pointing at a linear rule they each half-satisfy: conformance is clauses 1–4, and clause 3 names the test.

⚠️ **Every locking clause needs an `UPDATE` grant, not only a `SELECT` one.** "You must have `SELECT` privilege on each column used in a `SELECT` command. The use of `FOR NO KEY UPDATE`, `FOR UPDATE`, `FOR SHARE` or `FOR KEY SHARE` requires `UPDATE` privilege as well (for at least one column of each table so selected)" (PG 14 docs, `sql-select`, Required Privileges; verified 2026-09-09). Audited against every statement the runtime role executes here: **§3.3 step 3b's `FOR UPDATE` on `agent_lease_grant` itself** (granted a column-scoped `UPDATE` on that table above, which is what makes the R10/F1 ownership lock legal — a `SELECT`-only grant would have failed at runtime, and it is the newest statement in the sketch so it is the likeliest to have been missed) · §3.1's `FOR NO KEY UPDATE` on `agent_identity` (granted `UPDATE` on the epoch and `revoked_at` by [`design.md`](design.md) §7.4; the mode is clause 5's, S10/F4) · §3.1/§3.3/§3.4's `FOR SHARE OF se` on `agent_session` (column-scoped `UPDATE`, same place) · §4 A0's `FOR NO KEY UPDATE` on `agent_identity` · §4 A1's `FOR SHARE OF se` on `agent_session` · §4 A2's `FOR UPDATE` on `agent_replica_cap` (`UPDATE (active_replicas)`) · §4 A4's `FOR UPDATE` on `agent_replica_incarnation` (`UPDATE (retired_at, last_seen_at, last_seen_report, last_seen_by_agent)`). All **seven** are satisfied (the count said six until S9 while the list held seven), and none is satisfied *accidentally* — a future statement that locks a table the runtime role may only read would fail at runtime, which is why (L14) executes each of them rather than reasoning about them. §5.0's, §5.1's, §5.3's and §5.4's locking clauses belong to jobs the **migrator schedules** — but they are not outside the runtime role's *privileges*: measured on PG 14.24, `sm_dp_runtime` can execute §5.0, both of §5.1's statements and both of §5.4's, because their locking clauses and their writes fall inside grants the report and lease paths already need (S10/F3). The DCL refuses exactly §5.2's two `DELETE`s and §5.3 step 2's `DELETE` + tombstone `INSERT`.

**Obligation (L14) is the enforcing artifact for this table**, not the table itself: the planted least-privilege test executes **every statement in §3 and §4 (Phase A's A0, A1, A2, A3, A4 and A7b, and Phase B's B1–B5 including both forms of B3's refresh)** as `sm_dp_runtime` and expects success, executes §5.0's and §5.1's statements as `sm_dp_runtime` and expects them to **succeed as well** — measured, S10/F3: the DCL cannot refuse them without breaking the report path, so their exclusivity to the migrator is scheduling and not privilege — and executes one identity-column write, one `DELETE` on any of the eleven tables, one `INSERT INTO agent_request_tombstone` and one `nextval` without the sequence grant and expects **`42501`** for each. ⚠️ **That sentence said "expects `42501`" for §5.0 and §5.1 until S11 and was false** — it was S10/F3's own finding, left standing in this one place; nothing in the harness ever asserted it (S11/F3).

---

## 8. Obligations — what execution must prove

**The set is currently (L1)–(L18)** — this header is the one place a count is written, so every other reference says "(L1) onward" and cannot go stale (S8/F2). Numbered independently of [`design.md`](design.md) §5.2's `(a)`–`(s)`, which stay there for the tables that stay there. All are unmet; §10 is the gate.

- **(L1) Lease ledger, the reservation.** Step 1 reserves before any CP call; two concurrent identical `renew` calls yield one `PENDING` row and one `IN_PROGRESS`; a retry after completion returns its own grant; a reused id with any different epoch-independent input is `REQUEST_ID_REUSED`; a tombstoned id is `REQUEST_EXPIRED`.
- **(L2) Epoch transfer.** A `PENDING` row whose `fencing_epoch` is below the agent's current epoch is adopted by the **current owner** and by nobody else: a caller at a non-current epoch gets `STALE_EPOCH` and can observe neither the state nor the grant nor the row's existence; adoption is monotonic; the reappearing old-epoch stream still gets `STALE_EPOCH`. The fingerprint compares **equal** across the transfer. **And adoption transfers a coherent ownership tuple (S1/F3):** after `R1` reserves and dies and `R2` adopts, the row's `attempt_owner` is `R2`, its `attempt_deadline` is re-armed, and `attempt_no` is **unchanged** — so the takeover budget is not spent on a gateway restart; the terminal row names `R2`; and a stale `R1` callback acquires nothing at 3b. Asserted with the trigger's transfer branch narrowed back to `fencing_epoch` alone, where the adoption **must** fail to move the owner and `R1`'s callback **must** be able to lock the row. **Two adversarial legs, both statement-level:** an adoption whose `$epoch` stopped being current between the classification read and the write is refused by the `s` join (the row keeps its old epoch and stays adoptable) — asserted with a barrier that commits a new claim inside that window; and an adoption presenting another agent's `request_id` is refused by `lg.agent_id = s.agent_id` even when the caller owns its own session.
- **(L3) Takeover.** Ten concurrent post-deadline retries produce **exactly one** takeover (`attempt_no` = 2) and nine `IN_PROGRESS`; a crash after a takeover leaves the row takeable again only after the **new** `attempt_deadline`; at `attempt_no = takeover_attempts` the caller completes the row `FAILED('TIMEOUT')` and the session is refused `RE_ENROLL_REQUIRED`; a step-3 completion carrying a superseded `attempt_no` writes nothing. **And the cap completion cannot kill a live attempt, nor another agent's request (R10/F3):** a caller that lost the CAS and then issues the cap completion against a row whose `attempt_deadline` has **not** passed writes zero rows (the statement's cap and deadline predicates), and a caller presenting a **valid session of its own** together with **another agent's** `request_id` also writes zero rows (the `s` fence plus `lg.agent_id = s.agent_id`) — asserted with the fence removed, where that caller **must** terminalize the victim's live renewal as `FAILED('TIMEOUT')` and drive it to `RE_ENROLL_REQUIRED`, which is the cross-tenant availability hole round 9 left open. **And the takeover is fenced on a live epoch:** with a barrier that commits a new claim (epoch 9) between the taking-over caller's classification read at epoch 8 and its takeover write, the takeover writes **zero rows**, `attempt_no` does not move, the row stays `PENDING` with its original `fencing_epoch`, and the epoch-9 owner takes it over on its next attempt — asserted with the `s` join removed as well, where the same interleaving **must** bind the row to the dead epoch, after which that caller's completion writes nothing and the request is stalled until the new `attempt_deadline` (the planted-violation half). A takeover presenting another agent's `request_id` writes zero rows even when the caller owns its own session.
- **(L4) Fenced completion writes NOTHING, and the request survives it.** Step 3 is fenced on the ledger row's **own** agent and (possibly transferred) epoch: with the session re-claimed while a completion is paused, the completion writes **no projection and no ledger change at all** — `agent_lease` is byte-identical, the ledger row is still `PENDING` at its original `attempt_no` and `fencing_epoch`, `completed_at IS NULL`, and no `outcome` is set. The paused caller receives no grant. **The recovery leg is the point:** the agent's new session owner then adopts that same row (§3.1) and re-calls the CP under the same `request_id` — asserted in both AGW-18 postures, with the CP stub honouring the id (the grant is recovered, one ledger row, nonce spent once) and ignoring it (`FAILED('CP_REFUSED')` → `RE_ENROLL_REQUIRED`). **And ordering does not matter:** the stale completion arriving *before* the new owner's retry must leave the row adoptable exactly as if it had never arrived. **The S1/F1 cross-agent leg — the one that matters most, because it is an authorization test and not a race test:** one gateway replica owns sessions for agents **A** and **B**; a completion carrying **B's** `request_id` issued from **A's** session handling (A's `AgentSessionRef`, A's epoch, the same replica) returns **zero rows at 3b**, projects nothing, writes nothing and discloses nothing — both agents' `agent_lease` and `agent_lease_grant` rows byte-identical afterwards. Run it **twice**, with A's and B's `fencing_epoch` **equal** and **different**, so an accidental epoch coincidence cannot make it pass; and run it with 3b's `agent_id = $agent` predicate removed, where it **must** pass the fence and complete B's request from A's session. **And the S1/F3 owner leg:** a completion from a replica that is not `attempt_owner` — the dead reserver's stale callback after another replica adopted the request — returns zero rows at 3b, and the terminal row names the replica that actually completed it. **The R10/F1 barrier leg:** with a takeover committing between attempt 1's ownership read (3b) and its projection (3c), attempt 1 **projects nothing and writes nothing** — `agent_lease` byte-identical, `agent_lease_grant` still `PENDING` under attempt 2 — asserted with 3b's `FOR UPDATE` weakened to a plain `SELECT`, where the same interleaving **must** install attempt 1's older grant into `agent_lease` while the ledger stays under attempt 2 (the planted-violation half, and the exact defect round 9 shipped). Zero rows in `agent_lease_grant` ever carry `outcome = 'STALE_EPOCH'` — `failed_has_code` does not admit the value.
- **(L5) Projection order.** The projection advances only for a newer `(issued_at, grant_seq)`; equal `issued_at` grants resolve by `grant_seq` with exactly one `PROJECTED` and one `SUPERSEDED_ON_ARRIVAL`; an invalid grant lands `FAILED('GRANT_INVALID')` with nothing projected and the ledger row retained.
- **(L6) The trigger — every leg names the SQLSTATE it expects (S9/C4).** `agent_lease_grant_terminal_once` is now a `plpgsql` function and a `BEFORE UPDATE` trigger in §2.1's DDL, so these legs assert a *raise from a named branch* rather than "the trigger refuses". **Positive controls first:** the three admitted transitions each succeed — a completion (`PENDING → COMPLETED` and `PENDING → FAILED`), a takeover past `attempt_deadline` at `attempt_no + 1`, and an epoch transfer at the same `attempt_no` with a strictly higher epoch. **Then the refusals, one plant each, asserted by SQLSTATE:** any write to a `COMPLETED` or `FAILED` row → **`AGW11`**; a `PENDING → PENDING` **fingerprint rewrite**, and a write to any of `request_id`, `tenant_id`, `agent_id`, `kind`, `grant_seq` or `requested_at` → **`AGW10`** (the identity guard runs before the branch selection, so this is the code even when the attempt columns also move); a completion that also changes `attempt_no`, `attempt_owner`, `attempt_deadline` or `fencing_epoch` → **`AGW12`**, one plant per column; a takeover with an **unexpired** `attempt_deadline`, **a takeover that lowers the epoch** (the `>=` half, planted by taking a post-deadline row over with a lower `$epoch`), and a takeover that writes any result column → **`AGW13`**; an epoch "transfer" that lowers or preserves the epoch, and a transfer that writes any result column → **`AGW14`**; a `PENDING → PENDING` statement that moves `attempt_no` by anything other than `0` or `+1` → **`AGW15`**, the no-such-transition branch. The result-column plants run **once per writable result column** (`outcome`, `lease_jti`, `issued_at`, `expires_at`, `grant_payload`, `completed_at`) on **both** `PENDING → PENDING` branches (C-R9/F8), which is 12 plants expecting `AGW13`/`AGW14`. **A leg that expects a bare `P0001` is a failed leg:** the codes are what distinguish the branches, and a branch that raised the wrong one would otherwise read green. `pending_has_no_result` is asserted independently of the trigger by a direct `UPDATE` as the table owner **with the trigger disabled** (`ALTER TABLE … DISABLE TRIGGER agent_lease_grant_terminal_once`): the CHECK must still refuse with `23514`, and in particular a `PENDING` row must never be able to hold a `lease_jti` — that value would occupy `agent_lease_grant_jti` and make the legitimate grant carrying it unstorable. **`failed_has_no_grant` the same way (R10/F8):** each of `grant_payload`, `lease_jti`, `issued_at` and `expires_at` planted on a `FAILED` row is refused by the CHECK with the trigger disabled, and every `(state = 'FAILED', result column)` combination is planted through the trigger too, where the code is `AGW11`.
- **(L7) Issue once per identity, and the horizon at which the index stops arbitrating.** A direct `issue` for an agent that already has an `agent_lease` row — **expired or not** — is refused `ISSUE_NOT_ALLOWED` and writes zero rows; two concurrent first-issue requests **with different request ids** yield one `PENDING` row and one refusal (the identity `FOR UPDATE` serializes them; `agent_lease_grant_issue_once` refuses the loser even if the predicate is bypassed); zero `kind = 'issue'` rows exist for an identity with a lease row; and the same refusal holds **after `ledger_retention` has deleted the `issue` ledger row**, because `agent_lease` is never deleted. **The measured residual (§2.1):** for an identity whose only `issue` ended `FAILED` — so no `agent_lease` row was ever projected — a *new* request id **after** retention is admitted and reaches the CP. The leg exists to record that outcome with a number, not to assert it away; closing it is a change to a [`design.md`](design.md) §5.2 table and is routed with §10's gates.
- **(L8) `current` writes nothing.** `current` on an unexpired, certificate-bound projection returns it with no CP call, adds **no** ledger row, and leaves `agent_lease` byte-identical; `current` on a missing or expired projection returns `NONE` and writes nothing.
- **(L9) The advisory lock.** With the retention job and a retry driven against the same `request_id` under a barrier, every interleaving ends in either the stored result or `REQUEST_EXPIRED` — never a second ledger row and never a second grant. **Asserted against the §5.3 statements as written**, with the retry's wait proving the barrier was hit; and asserted a second time with the job's step-1 lock acquisition removed, where the same interleaving **must** produce the second ledger row (the planted-violation half — without it the test proves nothing about the lock). **And the projection re-check is DEFENSIVE, with no test behind it, which is stated rather than dressed up (S1/F6):** the batch selects only `state <> 'PENDING'` rows, `agent_lease` is written **only** by §3.3's step 3c, and 3c's `g` requires `state = 'PENDING'` — so no legal transition can make a selected terminal row become `agent_lease.grant_request_id`, and `terminal_once` admits no transition out of a terminal state at all. Round 10's leg here described that unreachable interleaving and is **removed**: it could not have been written as a test. Step 2's three re-checks are kept as defence against a future statement that projects from a non-`PENDING` row, and the execution record must say "defensive, unexercised" rather than claim a passing race test. **What IS load-bearing and IS tested** is the tombstone/admission race above and §5.4's re-check (L16), where the predicates genuinely can change under the lock.
- **(L10) The sequence.** `nextval('sm_data.agent_lease_grant_seq')` succeeds as `sm_dp_runtime` **with** the `USAGE` grant and fails `42501` without it; `DROP TABLE agent_lease_grant` drops the sequence (the `OWNED BY` is in place).
- **(L11) Registration cardinality — and A2's binding-wide lock is proven HERE, on the STABLE cap (S4/F1).** Two concurrent registrations of distinct new `replica_ref`s on a binding at `max_replicas_per_binding − 1` land at the cap, **never** one past it; the loser is `REPLICA_CAP_EXCEEDED` at **A7a** with the report's **disposition** recording that reason (not its trust — S3/F2) and no row written; `agent_replica_cap.active_replicas` equals `count(*) FROM agent_replica WHERE retired_at IS NULL` after every case (a reconciliation assertion, run at the end of each). **The fixture, in full, because this is the one place A2's lock is observable through the public path:** two **distinct authenticated agents** (so A0's per-identity lock serializes neither of them against the other), each reporting a **distinct new `replica_ref`** on the **same binding** (so each needs a stable slot and neither touches the other's activation rows), with `active_replicas = max_replicas_per_binding − 1`. With the real lock: exactly one registration commits, the other is refused at A7a, and both the counter and the non-retired row count read exactly `max_replicas_per_binding`. **Planted half — and it is reachable, which the previous ephemeral version was not:** remove **only** A2's `FOR UPDATE` (leaving the plain `SELECT`), hold both transactions at a barrier placed after their **A3** admission reads, then release — both observe `max − 1`, both pass A7a, and the binding reaches **`max + 1`** in the counter and in the row count. Nothing else needs removing for that to happen, which is what makes it a proof of A2's lock rather than of something else. **Three legs the round-7 form would have failed:** (i) **the steady state does not move the counter** — 100 successive reports of one living `(binding, replica_ref)` leave `active_replicas` at 1 and mint nothing (the R8/F7 unconditional increment); (ii) **the steady state still answers with ids** — each of those reports returns the same `replica_id` and the active `incarnation_id` (the R8/F7 empty-`RETURNING` path) — **and refreshes `last_seen_at` without moving the cap counter or minting anything** (C-R9/F3: the only write on that path is B3's refresh); (iii) **an un-retire at the cap is refused** — with `active_replicas = max`, a report naming a **retired** `replica_ref` is `REPLICA_CAP_EXCEEDED`, not un-retired into `max + 1`; (iv) **the ephemeral cap is never overshot — proven by a REACHABLE fixture, and the layer it tests is named (S2/F4).** Round 10's replacement leg was itself impossible: it drove two agents at the **same** replica, and the second is refused `REPORTER_DISPLACED` at A5 and never reaches A7b, so it could never have been written. Two legs replace it. **(iv-a), through the public report path:** one reporter, one replica, `max_incarnations_per_replica` activations already present, one further activation change → `REPLICA_CAP_EXCEEDED`, and — the S2/F1 assertion — `agent_replica` (both reporter columns included), the active activation's three `last_seen_*` fields, `agent_replica_cap.active_replicas` and the activation row count are **all byte-identical** to their pre-report values, while the report's disposition is `REFUSED(REPLICA_CAP_EXCEEDED)` without changing trust; the ordinary completed-path metric increment is asserted separately and is not evidence of transactional coupling. **(iv-b) is REMOVED, and its removal is the finding (S4/F1).** Three successive rounds tried to build a concurrency proof for the *ephemeral* cap and each fixture was impossible for a different reason: round 9 asserted an overshoot the cap row already prevents; round 10 drove two agents at one replica, which A5 refuses as `REPORTER_DISPLACED`; S3 drove two transactions as one incumbent through an invented "registration contract entered at A2" seam, which still serialized at **A0** on the same `agent_identity` row — and, worse, **A4 locks the replica's active activation `FOR UPDATE`**, and (iv-b)'s own preconditions required one to exist, so removing A2's lock left A4's lock (and `agent_replica_one_active` behind it) still serializing the pair. **No plant that removes only A2 can produce `max + 1` on an ephemeral fixture, so no such test can exist**, and the invented seam is deleted rather than re-described. The reason is the one `AGW-D16` already states: ephemeral serialization is **over-determined** — A0, A5 and A4 each prevent the contention independently — so it is not a property the public path can exhibit and not one worth a fixture. **A2's binding-wide serialization is instead proven where it is genuinely load-bearing and reachable: the STABLE cap, in this obligation's first leg above.**

(v) **First registration (S2/F3).** The **first-ever** `TRUSTED` report for a `(binding, replica_ref)`: exactly **one** `agent_replica` row and **one** active activation exist afterwards, `ReportAck` returns **both** DP-minted ids, `active_replicas` rose by **exactly one** (asserted by re-reading it, not inferred), `reporter_agent_id` and `reporter_since` are initialised from the **authenticated** reporting agent, and the activation's `registered_by_*` and `last_seen_*` are initialised from that same agent and its `$sequence`. A **retry of the same report** (same agent, same `replica_ref`, same `incarnation`) mints **nothing**, leaves `active_replicas` unchanged and returns the **same** two ids. And the path is asserted to have **bypassed** the reporter gate rather than passed it: with no stable row there is no incumbent to displace, so a fixture that makes A5 run on this branch (planted) fails on the absent `reporter_agent_id`/`$replica_id` — which is the round-10 defect.
- **(L12) One active incarnation, and order.** `agent_replica_one_active` refuses a second active incarnation of a replica; a restart **reuses** `replica_id` and mints one `incarnation_id` with the previous retired; the same incarnation re-reported is a no-op **and is answered with the active `incarnation_id`**; an **out-of-order** report (`sequence` ≤ the active incarnation's **`last_seen_report`**, compared only when `last_seen_by_agent` is the reporting agent — never against `registered_by_report`, which is immutable creation audit) retires nothing, inserts nothing, keeps the newer incarnation active **and is answered with the active ids** — and its **`trust` is unaffected by its staleness** ([`design.md`](design.md) §4.8: lease, epoch and scope only, so a live in-scope agent's stale report asserts `TRUSTED`) while its **`disposition` is `STALE`** (S3/F2). **The projection leg:** with `200` applied and a delayed `150` following, the registry, the `RuntimeStatus` `status(replica)` returns and the convergence projection are **all byte-identical** to their post-`200` values, per [`design.md`](design.md) §8 row 6's existing watermark rule — the registry's own decision uses `last_seen_report`, and the per-`(agent, binding, replica)` watermark and canonical current projection are the routed `AGW-20` report-intake contract's, not this sketch's. **The registry-visible halves of report intake's five legs — they run against these tables TODAY while their projection halves wait on AGW-20 ([`design.md`](design.md) §8 row 6, S4/F2 / C1):** **(a) agent counter reset — NOT testable here, and the S4 claim that it was is withdrawn (S5/F1).** These columns carry **no epoch**, so A7c and B3 cannot detect a new session epoch at all; and even if they could, a fencing epoch changes on any ordinary reconnect and does not prove the agent's counter restarted, so re-baselining on it would refresh liveness from an old buffered low-`sequence` report. What these tables support is monotonic ordering **within one reporter's own counter, with no reset handling**. The required outcomes — a genuine reset must not strand liveness, an ordinary reconnect must not admit stale data — are stated as AGW-20's in [`design.md`](design.md) §8 row 6 leg (a) and are executable **only** once it settles the mechanism. **(b) duplicate identical report** — the same report replayed writes **nothing**: no activation inserted or retired, `last_seen_report` and `last_seen_at` unchanged, the same `replica_id`/`incarnation_id` answered. **(d) two replicas in one batch** — R1 at `sequence = 50` and R2 at `sequence = 7` in one batch each register or refresh against **their own** activation's watermark, so R1's `50` cannot suppress R2's `7`. **(c)**, and the byte-identical-replay half of **(b)** and **(e)**, need a stored fingerprint and a stored result that this sketch does not own — they are AGW-20's and are stated in row 6 rather than asserted here. **The progression leg, which is what makes "the watermark advances" observable (S1/F5):** activation at `sequence = 100` → an accepted refresh at `200` (both `last_seen_report` and `last_seen_at` move) → a **delayed** `150`, which changes **neither** `last_seen_report` **nor** `last_seen_at`, retires nothing, inserts nothing and returns the active ids; `registered_by_report` reads `100` throughout. **And the incarnation change is deterministic:** 200 successive genuine incarnation changes on one replica all succeed — no run aborts on `agent_replica_one_active` — which is the property the round-7 single-statement form could not have, since `WITH`'s sub-statement order is unpredictable (§4). **Two more legs C-R9 forces:** (i) **a returning incarnation string gets a NEW row, never its old one back** — report incarnation `A`, then `B`, then `A` again: three rows, three distinct `incarnation_id`s, the first `A` row retired with its own window and the second `A` row registered later, and **no row's `registered_at` ever changes** (C-R9/F4 — round 8 un-retired the first `A`, giving it a window that spanned `B`'s whole life); (ii) **the stale guard does not fire across agents** — with a stable replica reported first by agent `P` at `sequence = 900` and then, after the workload is rescheduled onto another host, by agent `Q` at `sequence = 5` with a **new** incarnation string, `Q`'s report is a genuine change: `P`'s incarnation is retired, `Q`'s is inserted with `registered_by_agent = Q`, and the `ReportAck` carries the **new** `incarnation_id` — not `P`'s (C-R9/F7). The same report from `P` at `sequence = 5` after `900` is still refused as stale. **And the reporter gate holds against a delayed displaced report (R10/F4):** with `P` reporting incarnation `X` at `sequence = 900`, then a handoff to `Q` after `reporter_handoff_grace` which registers `Y`, a **delayed `P` report at `sequence = 901`** — which passes `P`'s own watermark — is refused `REPORTER_DISPLACED` — **disposition `REFUSED`, trust untouched** — counted, and changes nothing: `Y` stays active, `X` stays retired with its window closed, and `reporter_agent_id` is still `Q`. **And a cross-agent handoff on the SAME incarnation string advances liveness (S1/F2):** `P` reports incarnation `X` at `sequence = 900`; after `reporter_handoff_grace` agent `Q` reports the **same** `X` at `sequence = 5` — the gate admits the handoff, which re-seeds the active row's `last_seen_by_agent = Q` and `last_seen_report = 5` and refreshes `last_seen_at`; `Q` at `6` refreshes again; a delayed `P` at `901` inside the grace is refused `REPORTER_DISPLACED` and changes nothing; and §5.0 does **not** retire the incarnation `Q` is actively reporting. Asserted with the handoff's re-seed removed, where `Q`'s reports **must** fail to advance liveness (its `5` and `6` are below `P`'s `900`) and the sweep **must** retire a live workload — which is precisely what round 10 shipped. **And the handoff that ALSO changes the incarnation registers the new activation (D1) while preserving the outgoing one's history (S2/F2):** `P` reports `X` at `900`; after the grace `Q` reports **`Y`** at `sequence = 5` — the handoff is admitted, **`X` is retired carrying `P`'s `last_seen_by_agent`, `last_seen_report = 900` and `P`'s `last_seen_at` unchanged** (B3 does not run on an activation change, so nothing of `Q`'s is written onto `X`; its `registered_by_*` are immutable throughout), **`Y` is inserted and is the active incarnation** with `last_seen_by_agent = Q` and `last_seen_report = 5`, and the `ReportAck` carries `Y`'s new `incarnation_id` with the unchanged `replica_id`. Planted half: re-seed the outgoing row before retiring it and `X`'s stored history **must** show `Q` and `5` for a report that never named `X`. Asserted with the (ii)-skip removed — routing the handoff through the stale guard — where the re-seed makes `last_seen_report >= $sequence` true by equality, the flow stops as "STALE", and **`Y` must never be registered**. **And the gate decides with no active incarnation to read** (the coordinator's review of R10/F4): a replica whose every incarnation the §5.0 sweep retired, reported by a **new** agent, is an **admitted handoff** — not `REPORTER_DISPLACED` — because the incumbent's liveness falls back to `reporter_since`, which is long past the grace; `reporter_agent_id`/`reporter_since` are rewritten and the new activation registers. Two more legs on that fallback: a replica whose reporter was installed **just now** and has registered no incarnation yet **refuses** a third agent (`reporter_since` is inside the grace), and a **retired** stable row un-retired at 3b keeps naming its old reporter until the gate's handoff write changes it — a refused report leaves the row retired and the counter untouched, because the gate is evaluated before 3b writes anything. **And the stale guard is a watermark, not a timestamp (R10/F6):** an older same-incarnation report from the current reporter leaves `last_seen_at` **and** `last_seen_report` unchanged, so it cannot keep a dead incarnation alive; only a report above the watermark advances either.
- **(L13) Retirement, churn and retention.** A replica with no active incarnation for `replica_idle_retirement` is retired and the cap decremented in one transaction; **churn beyond the cap with the concurrent active count below it succeeds** (register `2 × max` replica_refs serially with retirement in between); the retention path's incarnation delete removes retired rows past `registry_retention`; the stable delete removes a retired replica **only** when no incarnation row and no **same-tenant** `series` row reference it, and is a no-op — not an error — while one does. ⚠️ **The S10/F2 "blocked by dependency" claim is RETRACTED (S11/F1):** `sm_data.series` **is** defined — by the repo's telemetry migration `backend/src/main/resources/db/migration/V001__telemetry_schema.sql`, which declares itself the source of truth for that schema; S10 searched `docs/` only and so concluded no document defines it. The evidence run bootstraps that migration from the committed file bytes, md5-pinned, between the walking skeleton's Phase 0 and Phase 1 (§5.2's bootstrap note), and the statement runs. **Three legs, measured, and the third is what proves the tenant predicate:** (i) with no incarnation row and no `series` row naming the replica, the delete **removes** it; (ii) with a `series` row in the replica's **own** tenant whose `source_key` is `replica_id::text`, the delete is **withheld** — zero rows, a no-op and not an error, and the replica row survives; (iii) with a `series` row carrying **that same `source_key` string under a different tenant**, the delete **proceeds** — the foreign-tenant row does not influence the decision, which is the property `s.tenant_id = r.tenant_id` buys and which the unscoped form would have failed by stalling retention for ever (§5.2's note). The `series` fixtures insert a `placement` row first, because `series` carries `FOREIGN KEY (tenant_id, placement_id) REFERENCES placement`. And the ledger retention job's batch is **bounded by `retention_batch`**, proven by a horizon holding more expired rows than one batch, which the job clears in several transactions without the advisory-lock pool erroring. **For the measured registration-versus-retirement pairing, no deadlock occurs (C-R9/F5):** a registration transaction on a binding writes **no** `agent_replica.retired_at` and **no** other binding's rows at all; and with the migrator's sweep and a registration driven concurrently against the **same** binding under a barrier, **both commit** — the sweep takes that binding's cap row before its replica rows, the same order the registration takes, so there is no cycle; asserted with the sweep's cap-row lock removed, where the pair **must** deadlock and one of them must abort. **This is the enforcing test for §7's invariant clauses 2 and 3** — that registration and retirement both take the binding's `agent_replica_cap` row `FOR UPDATE` before either enters its tail, and are therefore never interleaved despite their tails running in opposite directions; §7 cites (L13) and (L13) cites §7, so neither can be changed alone (S9/F1). **And the sweep is safe against ITSELF, which is what makes §5.1 a maintenance operation rather than an exclusivity claim (S11/F4):** two §5.1 sweeps driven concurrently on the **same** binding under a barrier — one as `sm_dp_migrator`, the other deliberately as **`sm_dp_runtime`**, the role the DCL cannot refuse it to ((L14), S10/F3) — **both commit, neither raises `40P01`**, the idle replica is retired **exactly once**, a **live** replica on the same binding is not retired at all, and `active_replicas` is decremented exactly once and still reconciles with `count(*) … WHERE retired_at IS NULL`. The mechanism is in the statements: step 1's `FOR UPDATE` on the one cap row is the binding's mutex, so the second sweep's step 3 runs only after the first commits, and under `READ COMMITTED` its `idle` CTE evaluates against a snapshot that already shows `retired_at IS NOT NULL` — "The search condition of the command (the `WHERE` clause) is re-evaluated to see if the updated version of the row still matches the search condition" (PG 14 docs, `transaction-iso`, Read Committed; verified 2026-09-11) — so it selects nothing, `retired` is empty and the counter `UPDATE` joins an empty set. `GREATEST(c.active_replicas − x.n, 0)` bounds the counter below even if that argument were ever wrong. **And a vanished replica does become idle (C-R9/F3):** a replica whose last incarnation is never succeeded — the pod disappears under a rollout and the next pod reports a **different** `replica_ref` — has that incarnation retired by §5.0 once `last_seen_at` is older than `incarnation_idle_retirement`, and is itself retired by §5.1 `replica_idle_retirement` later, releasing its cap slot; asserted with §5.0 removed, where the replica stays active for ever and the binding reaches permanent `REPLICA_CAP_EXCEEDED`, which is the round-8 behaviour. A **live** replica reporting inside `incarnation_idle_retirement` is never retired under itself — **and that leg is only meaningful if a healthy unchanged workload reports at all**, which is the periodic-report cadence §5.0 names as its precondition and [`design.md`](design.md) §13 AGW-19 routes to the `AG-D*` owners: the test therefore drives reports at `report_interval` explicitly and records that it is exercising an **assumed** agent behaviour, not an observed one.
- **(L14) Least privilege — what the DCL actually enforces, corrected against a measured run (S10/F3).** Every statement in §3 and §4 (Phase A's A0, A1, A2, A3, A4, A7b **and** Phase B's B1–B5, B3's liveness refresh in both its incumbent and handoff forms included) executes as `sm_dp_runtime` and succeeds — including the **returning-incarnation** path, which under round 8's `DO UPDATE` form would have written the ungranted `registered_by_report` and raised `42501` (C-R9/F6); step 3b's `SELECT … FOR UPDATE` on `agent_lease_grant` succeeds because that role holds a column-scoped `UPDATE` on the table (§7) — the locking clause needs it, and (L14) proves it rather than assuming it; every locking clause a runtime statement carries is exercised, since `FOR UPDATE`/`FOR NO KEY UPDATE`/`FOR SHARE` need an `UPDATE` grant as well as `SELECT` (§7). **The grants under test are the SQL block of §7, not its table (S9/C2)** — a column named in the table but missing from a `GRANT`, or the reverse, is a defect this leg must surface. **THE REFUSALS, and only these:** an identity-column write on any of the eleven tables; a `DELETE` on any of them (§5.2's two and §5.3 step 2's included); an `INSERT INTO agent_request_tombstone` (the runtime role holds `SELECT` only — [`design.md`](design.md) §5.2); and `nextval` on the ledger sequence without its `USAGE` grant — each raising **`42501`**. ⚠️ **What (L14) claimed until S10 and must not claim again:** that "§5.0's, §5.1's, §5.3's and §5.4's statements raise `42501` as `sm_dp_runtime` (they are the migrator's)". **Measured on PostgreSQL 14.24, five of those eight statements SUCCEED as `sm_dp_runtime`** — §5.0, §5.1 step 1, §5.1 step 3, §5.4 step 1 and §5.4 step 2 — because the columns they write are precisely the columns B4, B2's un-retire, A7/B2's counter and step 3c write on the runtime path, so no column-scoped grant can refuse them without breaking those paths. **This obligation therefore asserts that they succeed**, with their row effects checked, and the migrator's exclusivity is recorded as a **scheduling/ownership** property rather than a privilege boundary. §5.3 **step 1** was already carved out on the same reasoning ("a granted `SELECT` plus an advisory lock") and stays that way; the carve-out was right and its neighbours were wrong.
- **(L15) The retired-incarnation acceptance window.** The window rule is [`design.md`](design.md) §6.1's and its test is §8 row 44, but the **rows** it reads are this sketch's, so the obligation is recorded here: a sample labelled with a retired `incarnation_id` commits iff its `ts` ∈ `[registered_at, retired_at)` — **half-open**: the lower end inclusive, the upper exclusive, with `incarnation_window` keeping it non-empty (`retired_at > registered_at`) — and is otherwise `INCARNATION_RETIRED` with a gap marker. The legs: `ts = registered_at` of a retired incarnation **commits**; `ts = retired_at` is **refused** and commits under the incoming incarnation instead, so a replica's successive windows are **disjoint** and one instant never carries two valid labels; an incarnation registered and retired in one transaction cannot exist, because an incarnation is retired only by a successor's registration (§4, B4 never touches the row B5 inserts) or by §5.0's silence sweep, both strictly later; **and successive windows of one replica are disjoint even when the same incarnation *string* returns** — report `A`, `B`, then `A` again and the three rows carry three windows that do not overlap, because B5 is a plain `INSERT` and no row is ever un-retired into a window spanning its own successor's lifetime (C-R9/F4). The `metrics_raw` primary-key consequence is [`design.md`](design.md) §6.1's. **Not met, and one half is not schema-closable:** `ts` is agent-supplied, so no constraint here can distinguish a genuinely buffered sample from a backdated live one (`design.md` §6.1's stated residual and §12 limit 20).
- **(L16) The orphan sweep (R10/F2).** A request reserved and then abandoned — the process dies after step 1 and the agent never retries — is `PENDING` for ever until §5.4 runs: after `orphan_grace` past its `attempt_deadline` the migrator's sweep completes it `FAILED('TIMEOUT')` (the trigger's completion branch, with the attempt controls untouched per (L6)), and a **later** §5.3 run then prunes it and writes its tombstone, so a subsequent replay of that `request_id` is `REQUEST_EXPIRED` rather than a new request. **The legs:** a row still inside `pending_ttl × takeover_attempts` is **not** swept (the `orphan_grace` predicate, re-checked under the lock); **the boundary leg (S1/F4):** with a recovery paused **after** its adoption transaction has committed and **before** its CP call, the sweep run against that same request terminalizes **nothing** — it waits on the request key, then finds the re-armed `attempt_deadline` and skips the row, which then completes normally; asserted with the adoption's `attempt_deadline` write removed, where the sweep **must** terminalize a request already handed to a live owner; and in the opposite order the sweep wins first, the row is terminal, and the would-be adopter's classification returns the **stored** `FAILED('TIMEOUT')` without ever calling the CP; a sweep racing an adoption or a takeover on the same request serializes on the shared request-key advisory lock and then skips the row, because the re-checked `state`/`attempt_deadline` no longer hold — driven under a barrier in both orders; and the sweep never writes a row whose `state` has become terminal meanwhile. ⚠️ **(L16) asserts NO privilege boundary, and the sentence that did is withdrawn (S11/F3).** Until S11 this obligation ended "and it raises `42501` as `sm_dp_runtime` (it is the migrator's, (L14))" — which **contradicts (L14) as S10 corrected it**: both of §5.4's statements were **measured succeeding** as `sm_dp_runtime`, because the columns they write are the ones step 3c writes on the runtime path. (L16) now **defers to (L14) for every DCL question** and asserts none of its own; the harness's legs never tested a `42501` here, so the claim had no artifact either (`L19`). **What replaces it is a two-order safety test (S11/F4, completed S12/F3): §5.4 run concurrently with step-3 completion on the SAME `request_id` leaves exactly one terminal outcome.** Completion-first: step 3 holds **3b**'s `FOR UPDATE`; the sweep waits, then writes zero after re-checking `state = 'PENDING'`; both commit, the row is `COMPLETED` and one lease is projected. Sweep-first: §5.4 step 2 terminalizes and holds the ledger row; step 3b waits, then returns zero after the row becomes `FAILED('TIMEOUT')`; step 3c is not reached, no lease is projected, and both transactions commit without `40P01`. ⚠️ **And what serializes them is named exactly:** it is the **ledger row lock**, not the request-key advisory lock — step 3 runs in its own transaction and takes no advisory key, because step 0a belongs to the reserve/adopt transaction that has already committed (§5.4's boundary note). The advisory key is what serializes the sweep against an **adoption or takeover**, which do run step 0a; the two legs are different and are asserted separately. **What it is not:** it does not recover a consumed nonce — the agent's next session is refused `RE_ENROLL_REQUIRED` — and the test asserts that outcome rather than a repair.
- **(L17) A stale-epoch report writes nothing (S7/C1).** With a report whose `$epoch` is below the agent's current `fencing_epoch`, or whose `$replica` is not the session's `owner_replica_id`, §4 **writes no registry row, mints nothing, moves no `last_seen_report`, refreshes no `last_seen_at`, touches no cap counter and retires nothing**, and the call returns `STALE_EPOCH` disclosing neither the active incarnation nor the minted ids nor whether the replica exists. **There are SEVEN fenced writes in Phase B, not five (S8/F3), and each is planted SEPARATELY:** **B1** (the handoff ownership `UPDATE`), **B2's replica `INSERT`**, **B2's cap-counter `UPDATE`**, **B3's incumbent refresh `UPDATE`**, **B3's handoff refresh `UPDATE`**, **B4** (the retire `UPDATE`) and **B5** (the activation `INSERT`). **Branch-complete fixtures, each reaching the statements it names:** *first registration* → B2 insert, B2 cap, B5; *un-retire* → B2's retired path and B2 cap; *same-incarnation refresh* → B3 incumbent; *handoff refresh* → B1 and B3 handoff; *changed incarnation* → B4 and B5. **Per-statement planted halves, each stating the concrete failure it must show:** remove **B1**'s fence → a superseded session seizes reporter ownership; remove **B2's insert** fence → a superseded session mints a stable replica and consumes a cap slot; remove **B2's cap-counter** fence → the counter moves for a report that wrote no row, breaking (L11)'s reconciliation; remove **B3 incumbent**'s → a superseded session refreshes liveness and defeats §5.0's silence retirement; remove **B3 handoff**'s → the same, on the handoff path; remove **B4**'s → **a superseded changed-incarnation report retires the current activation**, which is the sharpest of them; remove **B5**'s → a superseded session inserts an activation and mints an `incarnation_id` telemetry will then carry. **And the eighth, whole-mechanism half:** keep all seven predicates and remove only **A1's `FOR SHARE OF se`**, then commit a new claim (raising the epoch) between A1 and B1 — the seven predicates **must still write nothing**, which is what proves they are load-bearing in their own right and not decoration on top of the lock. **Cross-agent leg, in (L4)'s shape:** a report naming agent **B**'s binding and replica, issued from agent **A**'s session on a replica that legitimately owns A, writes nothing — A1 is keyed on `$agent` from the `AgentSessionRef` and the fence cannot be satisfied by owning *a* session. **Revocation leg:** with `agent_identity.revoked_at` set between two attempts, **A0**'s `revoked_at IS NULL` predicate is the registry-side artifact that refuses the second — a different statement from A1's, and named separately because the two failures are different.
- **(L18) Lock order under contention — SIX INDEPENDENTLY NAMED LEGS (S8/F1, S8/C1; split and de-flaked in S9/F2).** `(L18)` is the family name; a citation that means one leg names the leg. **Every planted leg uses an EXPLICIT test-only two-statement inverse-order fixture and NEVER sibling CTE evaluation** — the documentation specifies no acquisition order among sibling `SELECT` CTEs (§3.1 step 0c), so a negative test may not demand a deadlock from them; a fixture that did would be flaky by construction. Every planted leg runs with `lock_timeout` and `statement_timeout` set to **5 s**, requires `40P01` in **exactly one** of the two transactions inside that bound and a **commit** in the other, and afterwards re-runs the **production** form of the same pair green — the fixture is test-only, so "restored" means the production statements pass again, not that anything was un-edited.
  - **(L18-P1) claim vs registration, positive.** Initial rows: one `agent_identity`, one `agent_session` at epoch *n*, one binding with a cap row. `claim` acquires `agent_identity` FOR SHARE first; registration acquires `agent_identity` **FOR NO KEY UPDATE** first (**A0**; the mode is §7 clause 5's, S10/F4). Barrier: `claim` pauses between its identity lock and its `agent_session` write; registration pauses between **A0** and **A1**. Release both. **Both commit, neither raises `40P01`**, and if the claim raised the epoch first the report is `STALE_EPOCH` having written nothing — `agent_replica`, `agent_replica_incarnation` and `agent_replica_cap` byte-identical.
  - **(L18-P2) claim vs lease reservation (0c + 1), positive.** Same identity and session rows plus an empty `agent_lease_grant`. `claim` first lock: `agent_identity` FOR SHARE. Lease first lock: `agent_identity` **FOR NO KEY UPDATE** (**step 0c**; §7 clause 5, S10/F4). Barrier: `claim` between identity and session write; lease between **0c** and **step 1**. **Both commit, no `40P01`**; a reservation whose epoch was superseded reserves nothing and `agent_lease_grant` is byte-identical.
  - **(L18-P3) lease step 1 retry vs step 3 on ONE request id, positive.** Initial rows: one `PENDING` `agent_lease_grant` row for that id. Step 1's retry first lock: `agent_session` FOR SHARE. Step 3's first lock: `agent_session` FOR SHARE (**3a**) — both take the session before the ledger. Barrier: the retry pauses after its session lock; step 3 pauses between **3a** and **3b**. **Both commit, no `40P01`**; the retry writes no second row (`ON CONFLICT DO NOTHING`) and `agent_lease_grant` holds exactly one row for the id.
  - **(L18-N1) claim vs registration, planted.** Test-only fixture standing in for registration: statement 1 `SELECT … FROM sm_data.agent_session … FOR SHARE`; **barrier**; statement 2 `SELECT … FROM sm_data.agent_identity … FOR NO KEY UPDATE` (production's mode since S10/F4; it conflicts with `claim`'s `FOR SHARE`, which is what makes the cycle). Run against **production** `claim` (identity FOR SHARE, then the `agent_session` upsert). Release: fixture takes the session lock, then `claim` takes the identity lock and requests the session row, then the fixture requests identity. Expected: **`40P01` in exactly one transaction within 5 s, the other commits.** Then re-run **(L18-P1)** green.
  - **(L18-N2) claim vs lease reservation, planted.** Same fixture shape, standing in for the pre-S8/C1 reservation: statement 1 `SELECT … FROM sm_data.agent_session … FOR SHARE`; **barrier**; statement 2 `SELECT … FROM sm_data.agent_identity … FOR NO KEY UPDATE` — **written as two statements precisely because the original defect's sibling-CTE form gives no author-controlled order and could not be demanded to deadlock (S9/F2)**. Run against production `claim`. Expected: **`40P01` in exactly one, within 5 s; the other commits.** Then re-run **(L18-P2)** green.
  - **(L18-N3) lease step 1 vs step 3, planted — a SYNTHETIC cycle, standing in for no historical form (corrected in S10/F5).** The S9 version asked for a fixture of `agent_lease_grant FOR UPDATE`, barrier, `agent_session` **FOR SHARE**, run against a production step-1 retry, and required `40P01`. **Executing it showed that pair cannot deadlock at all**: the retry takes `agent_session` `FOR SHARE` through step 1's `s` CTE and the fixture asked for `FOR SHARE` too, and `FOR SHARE` does not conflict with `FOR SHARE` (PG 14 docs, `explicit-locking` Table 13.3; verified 2026-09-10) — which is also why the pre-S8/C1 step-3 order never cycled and why that S8/C1 sentence is now corrected in §3.3 and §7. The leg is therefore redefined as what it can honestly be: a **synthetic** two-statement inverse-order fixture in a **conflicting** mode — statement 1 `SELECT … FROM sm_data.agent_lease_grant WHERE request_id = $r … FOR UPDATE`; **barrier**; statement 2 `SELECT … FROM sm_data.agent_session … **FOR UPDATE**` — run against **production step 3** for the same `$r` (**3a**'s `agent_session … FOR SHARE`, then **3b**'s `agent_lease_grant … FOR UPDATE`), which is the production statement that actually takes both of these locks. **Step 1's reservation cannot serve as the partner, and that too was measured:** its `INSERT … ON CONFLICT (request_id) DO NOTHING` does not wait on a row that is merely *locked* — only on one an in-flight transaction has *updated* — so it never joins a cycle built from a `FOR UPDATE` fixture. The leg requires **`40P01` in exactly one of the two within 5 s, the other committing**, and it proves **only** that the harness detects a cycle on this table pair, so that **(L18-P3)**'s green is a measurement and not an absence of instrumentation. It stands in for **no** production form, past or present, and the report must say so.

---

## 9. Tests this sketch owns

The tests live in [`design.md`](design.md) §8 — one table, one place, so no scenario can be quietly dropped by moving a document. These rows test **this** sketch; a change here that breaks one of them is a change to this document's correctness, not the gateway family's.

| `design.md` §8 row | What it settles here |
|---|---|
| 2 | `SplitBrainFencedIT` — **(L17)**: a report from a superseded session or a non-owning replica writes nothing here and returns `STALE_EPOCH` (the A1 fence, S7/C1); **(L18-P1)**: `claim` and this transaction run concurrently for one agent, both commit and neither raises `40P01`, each taking §7's strict shared prefix in order; **(L18-N1)** is its planted inverse-order half (S8/F1, S8/C1; the model corrected and the legs named in S9/F1, S9/F2) |
| 30 | `StolenLeaseGrantsNothingIT` — `cnf_thumbprint` binding on renewal and in the poll join |
| 31 | `LeaseLedgerContract` — delayed, concurrent and replayed `issue`/`renew`; total projection order |
| 32 | `FirstLeaseIssuedIT` · `FailoverKeepsLeaseIT` — the first `issue`; `current` as an unledgered read |
| 33 | `GrantValidationContract` — validation before any write; `FAILED('GRANT_INVALID')` |
| 35 | `LeaseReservationRaceIT` · `TerminalOnceTriggerContract` — reservation race, ten-way takeover CAS, the attempt cap, the trigger's three transitions and the SQLSTATE each refusal raises (S9/C4) |
| 37 | `LedgerRetentionContract` · `TombstoneRefusesReplayContract` — the advisory-lock interleaving, the **bounded batch** whose first statement acquires the lock per key, and the predicated `DELETE`; **(L18-P3)**: the step-1-retry/step-3 pair on one `request_id` — the row's own "another actor on the same id" shape — both commit with no `40P01`, which held only once §7 clause 5 changed the identity lock's mode (S10/F4); **(L18-N3)** is the SYNTHETIC cycle beside it, proving the harness would have seen one (S10/F5) |
| 38 | `ReplicaGrammarContract` · `StableReplicaIdSurvivesRestartContract` · `UnknownReplicaContract` — the two grammars, stable-id reuse, unknown-label rejection |
| 39 | `LeaseCompletionIsFencedIT` — the step-3 fence under a re-claim |
| 40 | `ExpiredLeaseMustRenewIT` · `IssueOncePerIdentityContract` — `ISSUE_NOT_ALLOWED`, the concurrent first-issue pair |
| 41 | `ReplicaRegistryCapIT` — both caps, churn beyond the cap with the active count below it, **the steady state moving neither the counter nor the ids**, and an un-retire refused at the cap |
| 42 | `LeaseRecoveryAcrossEpochIT` — the authenticated epoch transfer after a gateway process death, **fenced in its own statement** (a claim committing inside the adoption window writes zero rows), under both halves of the AGW-18 CP assumption; **(L18-P2)** and **(L18-N2)**: the reconnecting `claim` against an in-flight reservation (**0c + 1**) — this is the row that already runs a claim at a new epoch concurrently with a live lease request — both commit with no `40P01`, and the planted two-statement inverse fixture requires it in exactly one (S9/F2) |
| 43 | `ReplicaRegistrationRaceIT` · `OutOfOrderReportContract` — the registration transaction's cardinality, its stale-report guard, and the **determinism** of 200 successive incarnation changes |
| 44 | `RetiredIncarnationWindowContract` · `BackdatedIncarnationSampleContract` · `DuplicateTimestampAcrossIncarnationsContract` — the acceptance interval, the boundary instant, the same-`ts` collision, and the **measured** backdating residual ((L15); the rule itself is [`design.md`](design.md) §6.1's) |

---

## 10. Gates — nothing here is settled until these clear

1. **AGW-18 — the CP issuance contract** ([`design.md`](design.md) §13 AGW-18): CP-side idempotency under the DP's `request_id`, or a CP status lookup by it. Without it the ledger's timeout recovery has no correct form that preserves `SM-D28`'s single-use nonce, and the stated fallback (`RE_ENROLL_REQUIRED` after `takeover_attempts`) is an operator event per crash. **This gates `AGW-D15`.**
2. **AGW-17 — verification of reported replica identities** ([`design.md`](design.md) §13 AGW-17): runtime verification against the Kubernetes API, container runtime or host, **or** the architect group's explicit acceptance of the grammar-bounded residual as the one `dp-remote`'s reported hostnames already carry. **This gates `AGW-D16`** and, with it, `AGW-D13`'s `I6` clause ([`design.md`](design.md) §12 limit 14). ⚠️ **AGW-17 is not the only gate on `AGW-D16` (S5/F5):** its report-**ordering** clauses (the per-activation watermark, `last_seen_by_agent` scoping, the stale-report rule) and its **liveness**-dependent clauses (`last_seen_at`, §5.0's silence retirement, and therefore the stable cap's release) also require [`design.md`](design.md) §13 **AGW-20**, and §5.0 additionally requires **AGW-19**'s periodic-report prerequisite. Only the report-semantics-free portion — this document's DDL, the two grammar CHECKs, `agent_replica_one_active`, the cap row's arithmetic and stable-id reuse — is buildable against AGW-17 alone.
3. 🟡 **PG-14 execution with evidence — PARTIALLY MET (scope corrected S12/F2).** The DDL, DCL, triggers, indexes and every statement in §3–§5 have been executed on PostgreSQL 14, proving every **SQL-backed executable** obligation in §8, (L1) onward, with the run recorded in the evidence form [`../evidence/`](../evidence/) prescribes — a legs table, the failures and their fixes, and the final green run — at [`evidence/pg14-execution.md`](evidence/pg14-execution.md): `8551e5c`, `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2)`, **224 legs, 162 `OK`, 60 `REFUSED`, 2 `N/A-engine`, 0 `FAIL`, exit 0**. ⚠️ **The executable-SQL sub-portion is MET and the other three gates are not, which is exactly why they are separate gates:** gates 1, 2 and 4 are decisions and ratification order, and no amount of green SQL closes any of them. **The gate as a whole remains partial:** (L15)'s sample-admission half is `N/A-engine` — the admission statement is written in no document of this set — and (L9)'s projection re-check is recorded **defensive, unexercised**, because the interleaving it guards is unreachable (S1/F6).
4. **Family order.** `AGW-D15` depends on `SM-D28` and `AGW-D7` and ratifies after both; `AGW-D16` ratifies after `BE-D*` and `AG-D*` **and only when both AGW-17 and AGW-20 close, with its retirement clauses additionally conditional on AGW-19** (gate 2 above for AGW-17, and design §13 AGW-20 and AGW-19 respectively — this gate does not cite itself). Neither may be ratified as part of the `AGW-D1`…`AGW-D14` set.

---

## Forward pointer, open items (plan 015 T0.1, 2026-10-08)

Every citation of "open item 34"–"39" in this document is the **Agent Gateway's** numbering. Since 2026-10-08 those are central open items **42–47**: 34 → 42, 35 → 43, 36 → 44, 37 → 45, 38 → 46 (closed, moot), 39 → 47. The central items 34–39 are different questions and are unaffected; the map is in [`DECISIONS.md`](../../DECISIONS.md) after the open-items table. The text above is unedited.

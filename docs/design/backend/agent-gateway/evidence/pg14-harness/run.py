#!/usr/bin/env python3
"""PG 14 evidence harness for the Agent Gateway design set (AGW-D*).

WHAT IS UNDER TEST is the SQL the committed documents carry, extracted by line
range by extract.py. This harness never rewrites that SQL. It only:
  * supplies fixture values for the documents' own $placeholders,
  * executes documented multi-statement sequences in documented order,
  * derives planted-violation variants from the extracted text by a STATED
    textual removal, which is printed into the log, and
  * observes SQLSTATEs and row counts.

Everything the harness itself adds (role passwords, fixture rows, barriers) is
labelled HARNESS-ONLY and is not part of the design.

Usage:  python run.py --fresh --out ../runs/$(date +%Y%m%dT%H%M%S)
"""
import argparse, io, json, os, re, subprocess, sys, threading, time, traceback, uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta, timezone

import psycopg
from psycopg import errors as pgerr
from psycopg.types.json import Jsonb

HERE = os.path.dirname(os.path.abspath(__file__))
EXTRACTED = os.path.join(HERE, "extracted")

# ---------------------------------------------------------------------------
# HARNESS-ONLY constants. Not design material.
# ---------------------------------------------------------------------------
IMAGE = "postgres:14.24"
DB = "postgres"
SUPER = "postgres"
SUPERPW = "evidence"
PW = {"sm_dp_migrator": "harness_migrator", "sm_dp_runtime": "harness_runtime"}
LOCK_TIMEOUT_MS = 5000

# ---------------------------------------------------------------------------
# Result model
# ---------------------------------------------------------------------------
@dataclass
class LegResult:
    obligation: str
    leg: str
    phase: str            # production | planted | restored | n-a
    expected: str
    observed: str
    sqlstate: str = ""
    elapsed_ms: int = 0
    verdict: str = "OK"   # OK | REFUSED | FAIL | N/A-engine | BLOCKED-dependency
    detail: str = ""


class Recorder:
    def __init__(self, out):
        self.out = out
        os.makedirs(out, exist_ok=True)
        self.log = open(os.path.join(out, "log.txt"), "w")
        self.results: list[LegResult] = []
        self.meta = {}

    def say(self, s=""):
        self.log.write(s + "\n")
        self.log.flush()
        print(s)

    def sql(self, label, text, params=None):
        self.say(f"--- {label}")
        for l in text.rstrip().split("\n"):
            self.say("    " + l)
        if params:
            self.say(f"    -- HARNESS fixture values: {params}")

    def record(self, r: LegResult):
        self.results.append(r)
        self.say(f"### [{r.verdict}] {r.obligation}/{r.leg} [{r.phase}] "
                 f"expected={r.expected} observed={r.observed} "
                 f"sqlstate={r.sqlstate or '-'} {r.elapsed_ms}ms")
        if r.detail:
            self.say(f"      {r.detail}")
        self.say()


# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------
def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def container_up(rec, name, port, fresh):
    if fresh:
        sh(["docker", "rm", "-f", name])
        r = sh(["docker", "run", "-d", "--name", name,
                "-e", f"POSTGRES_PASSWORD={SUPERPW}",
                "-p", f"{port}:5432", IMAGE])
        if r.returncode:
            sys.exit("docker run failed: " + r.stderr)
        rec.say(f"HARNESS: docker run -d --name {name} -p {port}:5432 {IMAGE}")
    for _ in range(120):
        r = sh(["docker", "exec", name, "pg_isready", "-U", SUPER])
        if r.returncode == 0:
            break
        time.sleep(1)
    else:
        sys.exit("container never became ready")
    time.sleep(1)


def psql(rec, name, role, sql_text, label, one_txn=True):
    """Run a whole script through psql as `role`. Returns (rc, out, err)."""
    cmd = ["docker", "exec", "-i", name, "psql", "-v", "ON_ERROR_STOP=1",
           "-U", role, "-d", DB, "-q"]
    if one_txn:
        cmd.append("-1")
    rec.say(f"=== psql as {role}: {label}  ({'single transaction' if one_txn else 'autocommit'})")
    r = subprocess.run(cmd, input=sql_text, capture_output=True, text=True)
    if r.stdout.strip():
        rec.say(r.stdout.rstrip())
    if r.returncode:
        rec.say("PSQL ERROR:\n" + r.stderr.rstrip())
    return r.returncode, r.stdout, r.stderr


# ---------------------------------------------------------------------------
# Extraction access
# ---------------------------------------------------------------------------
def boot_range(b):
    """How a bootstrap input is cited: a line range, or 'whole file' for the migration."""
    if b.get("whole_file"):
        return "whole file"
    return "L%s-%s" % (b["first_line"], b["last_line"])


class Blocks:
    def __init__(self, rec):
        man = json.load(open(os.path.join(EXTRACTED, "extract.json")))
        self.man = man
        self.by_key = {}
        for b in man["blocks"]:
            b["text"] = open(os.path.join(EXTRACTED, b["file"])).read()
            self.by_key[(b["doc"], b["section"], b["ordinal"])] = b
        self.bootstrap = {}
        for b in man["bootstrap"]:
            b["text"] = open(os.path.join(EXTRACTED, b["file"])).read()
            self.bootstrap[b["name"]] = b
        rec.meta["commit"] = man["commit"]
        if man["commit"] == "WORKTREE":
            rec.say("!! extract.json was built from the UNCOMMITTED working tree. "
                    "DEVELOPMENT RUN — this is not evidence.")
        rec.meta["bootstrap_md5"] = {
            "%s (%s %s)" % (b["name"], b["path"], boot_range(b)): b["md5"]
            for b in man["bootstrap"]}
        rec.meta["block_md5"] = {f"{b['doc']} §{b['section']}#{b['ordinal']} "
                                 f"L{b['first_line']}-{b['last_line']}": b["md5"]
                                 for b in man["blocks"]}

    def get(self, doc, section, ordinal=1):
        return self.by_key[(doc, section, ordinal)]

    def cite(self, doc, section, ordinal=1):
        b = self.get(doc, section, ordinal)
        return f"{b['doc']}.md §{b['section']} L{b['first_line']}-{b['last_line']}"


# ---------------------------------------------------------------------------
# SQL slicing / binding
# ---------------------------------------------------------------------------
def strip_comment(line):
    out, i, in_s, in_d = [], 0, False, False
    while i < len(line):
        c = line[i]
        if in_s:
            out.append(c)
            if c == "'":
                in_s = False
        elif in_d:
            out.append(c)
            if c == '"':
                in_d = False
        elif c == "'":
            in_s = True; out.append(c)
        elif c == '"':
            in_d = True; out.append(c)
        elif c == "-" and i + 1 < len(line) and line[i + 1] == "-":
            break
        else:
            out.append(c)
        i += 1
    return "".join(out)


def split_statements(text):
    """Split a doc block into (label, sql) statements at top-level semicolons.

    label is the nearest preceding comment line, which is how the documents name
    their steps ('-- Step 0a, …', '-- A0. …', '-- B3 …').
    """
    stmts, buf, label, pending = [], [], None, None
    depth, in_s, in_dollar, tag = 0, False, False, ""
    for raw in text.split("\n"):
        s = raw.strip()
        if s.startswith("--") and depth == 0 and not buf:
            pending = s.lstrip("- ").strip()
            continue
        code = strip_comment(raw)
        buf.append(code)
        i = 0
        while i < len(code):
            c = code[i]
            if in_dollar:
                if code.startswith(tag, i):
                    in_dollar = False; i += len(tag); continue
            elif in_s:
                if c == "'":
                    in_s = False
            else:
                m = re.match(r"\$[a-zA-Z_]*\$", code[i:])
                if m:
                    in_dollar, tag = True, m.group(0); i += len(tag); continue
                if c == "'":
                    in_s = True
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                elif c == ";" and depth == 0:
                    sql = "\n".join(buf).strip()
                    sql = sql[:sql.rindex(";") + 1] if ";" in sql else sql
                    if sql.strip(" ;\n"):
                        stmts.append((pending or label or f"stmt{len(stmts)+1}", sql))
                        label, pending = None, None
                    buf = []
            i += 1
    tailtext = "\n".join(buf).strip()
    if tailtext:
        stmts.append((pending or f"stmt{len(stmts)+1}", tailtext))
    return stmts


PH = re.compile(r"\$([a-zA-Z_][a-zA-Z0-9_]*)")


def bind(sql):
    """The documents' $name placeholders become psycopg named parameters.
    Nothing else in the text changes."""
    return PH.sub(lambda m: f"%({m.group(1)})s", sql)


def needed(sql):
    return sorted(set(PH.findall(sql)))


# ---------------------------------------------------------------------------
# Bootstrap + the design set's own DDL/DCL
# ---------------------------------------------------------------------------
SET_ROLE = "SET ROLE sm_dp_owner;\n"   # HARNESS-ADDED prefix, exactly as §2/§5.2/§7 require


def boot_cite(b):
    return f"{b['path']} {boot_range(b)}, md5 {b['md5']}"


def bootstrap(rec, blocks, name):
    """Phase 0 → V001 (the telemetry migration) → Phase 1.

    The order is the install order, and it is forced by the inputs' own text, not chosen:
      * Phase 0 runs `CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner` with NO `IF NOT EXISTS`,
        so it must run FIRST — a schema created by anything else makes it fail (42P06). V001's
        own `CREATE SCHEMA IF NOT EXISTS sm_data` is therefore a notice-only no-op here, which
        is exactly right: the schema keeps Phase 0's `sm_dp_owner` ownership.
      * V001 is the repo's ONLY Flyway V-file, so Flyway runs it before any later migration;
        the walking skeleton's Phase 1 is still prose and would become a later V-file.
      * V001 carries `SET search_path TO sm_data` and unqualified CREATEs, so its tables land
        in sm_data; run under `SET ROLE sm_dp_owner` they are born owned by sm_dp_owner, the
        same convention Phase 1 states for itself.
      * Consequence, recorded rather than worked around: Phase 1's
        `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES TO sm_dp_runtime` runs AFTER V001 and
        "does not affect privileges assigned to already-existing objects" (PG 14 docs,
        sql-alterdefaultprivileges; verified 2026-09-11), so sm_dp_runtime gets no privilege on
        `placement`/`series`. Correct: the telemetry tables' runtime grants are the DM-D*/BE-D*
        owners', not this design set's, and sketch §5.2's DELETE is the migrator's.
    """
    b0 = blocks.bootstrap["phase0"]
    rc, _, err = psql(rec, name, SUPER, b0["text"], f"bootstrap phase 0 ({boot_cite(b0)})")
    if rc:
        sys.exit("phase 0 failed")

    rec.say("=== HARNESS-ONLY (not design material): give the two LOGIN roles passwords so psycopg")
    rec.say("=== can connect over TCP as the real roles. Phase 0 deliberately carries no credentials.")
    pwsql = "".join(f"ALTER ROLE {r} PASSWORD '{p}';\n" for r, p in PW.items())
    rec.say(pwsql.rstrip())
    if psql(rec, name, SUPER, pwsql, "HARNESS-ONLY role passwords")[0]:
        sys.exit("password setup failed")

    rec.say("=== HARNESS-ONLY (not design material), and it records a REAL GAP BETWEEN TWO COMMITTED")
    rec.say("=== DOCUMENTS (S11/F1a). V001's first statement is `CREATE SCHEMA IF NOT EXISTS sm_data`,")
    rec.say("=== and 'To create a schema, the invoking user must have the CREATE privilege for the")
    rec.say("=== current database' (PG 14 docs, sql-createschema, Notes; verified 2026-09-11). That")
    rec.say("=== check precedes the IF NOT EXISTS test, so it fires even though Phase 0 has already")
    rec.say("=== created the schema — measured here as 42501 'permission denied for database'. The")
    rec.say("=== walking skeleton's Phase 0 grants NO database-level privilege to any sm_dp_* role, so")
    rec.say("=== the Flyway migrator CANNOT run V001 as written. V001's bytes are NOT touched; the")
    rec.say("=== harness supplies the missing install-time grant, exactly as it supplies the role")
    rec.say("=== passwords above, and the gap is reported to the BE-D* owners.")
    if psql(rec, name, SUPER, f"GRANT CREATE ON DATABASE {DB} TO sm_dp_owner;\n",
            "HARNESS-ONLY database CREATE grant (see the note above)")[0]:
        sys.exit("database CREATE grant failed")

    if apply_telemetry(rec, blocks, name, "bootstrap"):
        sys.exit("telemetry migration failed")

    b1 = blocks.bootstrap["phase1"]
    rc, _, err = psql(rec, name, "sm_dp_migrator", b1["text"], f"bootstrap phase 1 ({boot_cite(b1)})")
    if rc:
        sys.exit("phase 1 failed")


def apply_telemetry(rec, blocks, name, label):
    """V001, verbatim, as sm_dp_migrator under the HARNESS-ADDED `SET ROLE sm_dp_owner;`.

    That one added line is Flyway's `initSql` in the walking skeleton's own Phase 1 preamble —
    the harness supplies it because there is no Flyway here, and nothing else is added. Returns
    the psql return code.
    """
    bt = blocks.bootstrap["telemetry"]
    rec.say(f"=== {label}: telemetry migration ({boot_cite(bt)})")
    rec.say("=== HARNESS-ADDED first line: SET ROLE sm_dp_owner;  (Flyway initSql; everything else verbatim)")
    rc, _, _ = psql(rec, name, "sm_dp_migrator", SET_ROLE + bt["text"],
                    f"{label}: V001 telemetry schema")
    return rc


def apply_group(rec, blocks, name, label, specs):
    """specs: list of (doc, section, ordinal). Concatenated, SET ROLE prefixed, one transaction."""
    parts, cites = [], []
    for doc, sec, ordn in specs:
        b = blocks.get(doc, sec, ordn)
        cites.append(f"{b['doc']}.md §{b['section']}#{b['ordinal']} L{b['first_line']}-{b['last_line']} md5={b['md5']}")
        parts.append(f"-- >>> {cites[-1]}\n{b['text']}")
    text = SET_ROLE + "\n".join(parts)
    rec.say(f"=== group {label}: " + " | ".join(cites))
    rec.say("=== HARNESS-ADDED first line: SET ROLE sm_dp_owner;  (everything else is verbatim)")
    rc, out, err = psql(rec, name, "sm_dp_migrator", text, label)
    if rc:
        rec.record(LegResult("BOOTSTRAP", label, "production", "executes clean",
                             "psql error", "", 0, "FAIL", err.strip()[:800]))
        return False
    return True


# ---------------------------------------------------------------------------
# Connections and fixtures  (HARNESS-ONLY plumbing)
# ---------------------------------------------------------------------------
TABLES = ["agent_command", "agent_request_tombstone", "agent_claim",
          "agent_replica_incarnation", "agent_replica_cap", "agent_replica",
          "agent_lease", "agent_lease_grant", "agent_session",
          "agent_identity", "agent_fleet_epoch"]

# V001's tables. Truncated with the rest between legs so a (L13) series fixture cannot leak
# into a later leg. They are NOT part of the design set — the harness only ever puts fixture
# rows in them, never DDL.
TELEMETRY_TABLES = ["series", "placement"]


class Ctx:
    def __init__(self, rec, blocks, port):
        self.rec, self.blocks, self.port = rec, blocks, port
        self.fx = {}
        self.container = None

    # ---- the documents' own statements ------------------------------------
    def stmts(self, doc, sec, ordn=1):
        return split_statements(self.blocks.get(doc, sec, ordn)["text"])

    def stmt(self, doc, sec, ordn, i):
        """One statement of a doc block, verbatim, with its citation."""
        st = self.stmts(doc, sec, ordn)
        b = self.blocks.get(doc, sec, ordn)
        return st[i][1], f"{b['doc']}.md §{b['section']} L{b['first_line']}-{b['last_line']} stmt[{i}] ({st[i][0][:40]})"

    def rebuild(self):
        rebuild(self.rec, self.blocks, self.container)
        self.reset()

    def conn(self, role="sm_dp_runtime", autocommit=False, lock_timeout=None, stmt_timeout=None):
        c = psycopg.connect(host="127.0.0.1", port=self.port, dbname=DB,
                            user=role, password=PW[role], autocommit=autocommit)
        if lock_timeout:
            with c.cursor() as cur:
                cur.execute(f"SET lock_timeout = {lock_timeout}")
                cur.execute(f"SET statement_timeout = {stmt_timeout or lock_timeout}")
            if not autocommit:
                c.commit()
        return c

    def owner(self):
        c = psycopg.connect(host="127.0.0.1", port=self.port, dbname=DB,
                            user="sm_dp_migrator", password=PW["sm_dp_migrator"],
                            autocommit=True)
        with c.cursor() as cur:
            cur.execute("SET ROLE sm_dp_owner")
        return c

    # ---- fixtures: HARNESS-ONLY rows, never design material ----------------
    def reset(self):
        with self.owner() as c, c.cursor() as cur:
            cur.execute("TRUNCATE " + ", ".join("sm_data." + t
                                                for t in TABLES + TELEMETRY_TABLES) + " CASCADE")
            cur.execute("ALTER SEQUENCE sm_data.agent_lease_grant_seq RESTART WITH 1")
        self.seed()

    def seed(self):
        f = {
            "tenant": uuid.uuid4(), "tenant2": uuid.uuid4(),
            "agentA": uuid.uuid4(), "agentB": uuid.uuid4(),
            "target": uuid.uuid4(), "binding": uuid.uuid4(), "binding2": uuid.uuid4(),
            "thumbA": b"\x01" * 32, "thumbB": b"\x02" * 32,
            "replicaA": "pod-a", "replicaB": "pod-b",
        }
        with self.owner() as c, c.cursor() as cur:
            cur.execute("INSERT INTO sm_data.agent_fleet_epoch (tenant_id) VALUES (%s), (%s)",
                        (f["tenant"], f["tenant2"]))
            for a, t in (("agentA", f["thumbA"]), ("agentB", f["thumbB"])):
                cur.execute("""INSERT INTO sm_data.agent_identity
                    (tenant_id, agent_id, target_id, cert_thumbprint, enrolled_at)
                    VALUES (%s,%s,%s,%s, now())""", (f["tenant"], f[a], f["target"], t))
            # HARNESS-ONLY: agent_command FKs the backend's apply_step_intent triple,
            # so the fixture needs a scope/request/intent chain. Backend tables, not AGW's.
            f["instance"], f["apply"], f["intent"], f["placement"] = (uuid.uuid4(), uuid.uuid4(),
                                                                      uuid.uuid4(), uuid.uuid4())
            cur.execute("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (%s,%s)",
                        (f["tenant"], f["instance"]))
            cur.execute("""INSERT INTO sm_data.apply_request
                (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,
                 requested_by, requested_at, state)
                VALUES (%s,%s,%s,%s,'apply',1,'rolling-no-validation','harness', now(), 'REQUESTED')""",
                        (f["apply"], f["tenant"], f["instance"], f["placement"]))
            cur.execute("""INSERT INTO sm_data.apply_step_intent
                (intent_id, tenant_id, apply_id, replica_ref, step, idempotency_key, config_hash, created_at)
                VALUES (%s,%s,%s,'pod-a','MATERIALIZE',%s,'h', now())""",
                        (f["intent"], f["tenant"], f["apply"], str(uuid.uuid4())))
        self.fx = f
        return f

    def lease(self, agent, thumb=None, caps=None, bindings=None, target=None,
              issued="now()", expires="now() + interval '1 hour'", agent_epoch=0, fleet_epoch=0):
        """HARNESS-ONLY fixture row in agent_lease (the poll's join contract)."""
        f = self.fx
        b = [f["binding2"]] if bindings == "other" else (bindings or [f["binding"]])
        t = f["target"] if target != "other" else uuid.uuid4()
        with self.owner() as c, c.cursor() as cur:
            cur.execute(f"""INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_jti, cert_thumbprint,
                    target_id, binding_ids, capabilities, agent_revocation_epoch, fleet_revocation_epoch,
                    audience, issued_at, expires_at, ceiling_preflight, ceiling_apply, ceiling_restart,
                    ceiling_stop, admission_margin, grant_request_id, grant_seq)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'dp', {issued}, {expires},
                        interval '1 minute', interval '1 minute', interval '1 minute',
                        interval '1 minute', interval '1 minute', %s, 1)""",
                        (f["tenant"], agent, str(uuid.uuid4()), thumb or f["thumbA"], t, b,
                         caps or ["apply-config", "lifecycle"], agent_epoch, fleet_epoch, uuid.uuid4()))

    def new_intent(self, step="VERIFY"):
        """config_hash is required exactly for MATERIALIZE/VERIFY/ROLLBACK (backend CHECK)."""
        """HARNESS-ONLY: another apply_step_intent row so a second command has a distinct intent."""
        iid = uuid.uuid4()
        with self.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.apply_step_intent
                (intent_id, tenant_id, apply_id, replica_ref, step, idempotency_key, config_hash, created_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s, now())""",
                        (iid, self.fx["tenant"], self.fx["apply"], "pod-" + str(iid)[:4], step,
                         str(uuid.uuid4()), "h" if step in ("MATERIALIZE", "VERIFY", "ROLLBACK") else None))
        return iid

    def command(self, rec=None, command_id=None, tenant=None, agent=None, intent=None,
                operation="apply", capability="apply-config", state="QUEUED", generation=1,
                offered_epoch=None):
        """HARNESS-ONLY fixture row in agent_command."""
        cid = command_id or uuid.uuid4()
        with self.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.agent_command (tenant_id, command_id, agent_id, apply_id,
                             intent_id, target_id, binding_id, generation, idempotency_key, operation,
                             required_capability, state, offered_epoch, offered_at, delivered_at)
                           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,
                                   CASE WHEN %s IN ('OFFERED','DELIVERED') THEN now() END,
                                   CASE WHEN %s = 'DELIVERED' THEN now() END)""",
                        (tenant or self.fx["tenant"], cid, agent or self.fx["agentA"], self.fx["apply"],
                         intent or self.fx["intent"], self.fx["target"], self.fx["binding"], generation,
                         str(uuid.uuid4()), operation, capability, state,
                         offered_epoch if offered_epoch is not None
                         else (1 if state in ("OFFERED", "DELIVERED") else None), state, state))
        return cid

    def session_for(self, agent, replica="pod-a", epoch=1):
        """HARNESS-ONLY: put the ownership row into an exact (owner, epoch) state.

        It DELETEs and re-INSERTs rather than UPDATEs, because
        agent_session_epoch_only_rises legitimately refuses an out-of-band epoch
        decrease (AGW01) or an owner change that does not raise the epoch (AGW02) —
        the trigger is under test elsewhere and must not be worked around by the
        fixture, so the fixture does not UPDATE at all.
        """
        with self.owner() as c, c.cursor() as cur:
            cur.execute("DELETE FROM sm_data.agent_session WHERE agent_id = %s", (agent,))
            cur.execute("""INSERT INTO sm_data.agent_session
                (tenant_id, agent_id, owner_replica_id, fencing_epoch, protocol_version,
                 agent_version, claimed_at, last_seen_at)
                VALUES (%s,%s,%s,%s,1,'v', now(), now())""",
                        (self.fx["tenant"], agent, replica, epoch))
        return epoch


# ---------------------------------------------------------------------------
# Leg helpers
# ---------------------------------------------------------------------------
class LegFail(Exception):
    pass


def timed(fn):
    t0 = time.time()
    try:
        return fn(), int((time.time() - t0) * 1000)
    finally:
        pass


def run_stmt(rec, cur, sql, params=None, label="statement"):
    bound = bind(sql)
    rec.sql(label, sql, params)
    cur.execute(bound, params or {})
    rows = cur.fetchall() if cur.description else []
    return cur.rowcount, rows


def expect_error(rec, fn, sqlstate, obligation, leg, phase, what):
    t0 = time.time()
    try:
        fn()
    except psycopg.Error as e:
        got = e.sqlstate or ""
        ms = int((time.time() - t0) * 1000)
        ok = got == sqlstate
        return LegResult(obligation, leg, phase, f"SQLSTATE {sqlstate}: {what}",
                         f"SQLSTATE {got}: {str(e).splitlines()[0][:160]}", got, ms,
                         "REFUSED" if ok else "FAIL")
    ms = int((time.time() - t0) * 1000)
    return LegResult(obligation, leg, phase, f"SQLSTATE {sqlstate}: {what}",
                     "no error raised", "", ms, "FAIL")


def remove(rec, sql, snippet, why):
    """Derive a planted variant from the extracted statement by a STATED removal."""
    if sql.count(snippet) != 1:
        raise LegFail(f"planted removal not unique ({sql.count(snippet)}x): {snippet!r}")
    out = sql.replace(snippet, "")
    rec.say(f"    -- PLANTED VARIANT: removed {snippet!r} ({why})")
    return out


def stmt_starting(text, prefix):
    """Select one whole statement out of an extracted block, verbatim."""
    for label, sql in split_statements(text):
        if sql.lstrip().upper().startswith(prefix.upper()):
            return sql
    raise LegFail(f"no statement starting {prefix!r} in block")


# ---------------------------------------------------------------------------
# Rebuild-from-extract: the universal restore after a planted DDL change.
# Everything it runs is extracted text (plus the SET ROLE prefix and the two
# schema lines sliced verbatim out of bootstrap phase 0).
# ---------------------------------------------------------------------------
def rebuild(rec, blocks, container):
    p0 = blocks.bootstrap["phase0"]["text"]
    lines = []
    for want in ("CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner;",
                 "GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime;"):
        hits = [l for l in p0.split("\n") if l.strip().startswith(want)]
        if not hits:
            raise LegFail(f"phase 0 no longer contains {want!r}")
        lines.append(want)
    rec.say("--- RESTORE: drop and re-create sm_data from the EXTRACTED text "
            "(phase 0 schema lines, V001, phase 1, then the four design groups)")
    rc, _, err = psql(rec, container, SUPER, "DROP SCHEMA sm_data CASCADE;\n" + "\n".join(lines) + "\n",
                      "restore: drop schema + re-create (phase-0 lines, verbatim)")
    if rc:
        raise LegFail("restore: schema re-create failed: " + err[:300])
    # DROP SCHEMA … CASCADE took placement/series with it; V001 is part of the restore, in the
    # same position it holds in bootstrap().
    if apply_telemetry(rec, blocks, container, "restore"):
        raise LegFail("restore: telemetry migration failed")
    b1 = blocks.bootstrap["phase1"]
    if psql(rec, container, "sm_dp_migrator", b1["text"], "restore: bootstrap phase 1")[0]:
        raise LegFail("restore: phase 1 failed")
    for label, specs in DESIGN_GROUPS:
        if not apply_group(rec, blocks, container, "restore: " + label, specs):
            raise LegFail("restore: " + label + " failed")


DESIGN_GROUPS = [
    ("design §5.2 DDL (six tables + agent_session_epoch_only_rises)", [("design", "5.2", 1)]),
    ("sketch §2 DDL (five tables + agent_lease_grant_terminal_once)",
     [("sketch", "2.1", 1), ("sketch", "2.2", 1), ("sketch", "2.3", 1),
      ("sketch", "2.4", 1), ("sketch", "2.5", 1)]),
    ("design §7.4 DCL (six tables)", [("design", "7.4", 1)]),
    ("sketch §7 DCL (five tables + sequence)", [("sketch", "7", 1)]),
]


# ---------------------------------------------------------------------------
# Concurrency: two transactions, named barriers, bounded timeouts
# ---------------------------------------------------------------------------
class Side:
    def __init__(self, name, conn, barrier, rec):
        self.name, self.conn, self.barrier, self.rec = name, conn, barrier, rec
        self.cur = conn.cursor()
        self.outcome, self.sqlstate, self.msg = None, "", ""

    def wait(self, tag):
        self.rec.say(f"    -- BARRIER {tag}: {self.name} arrives")
        self.barrier.wait(timeout=30)

    def run(self, sql, params=None, label=None):
        return run_stmt(self.rec, self.cur, sql, params, label or f"{self.name}")


def run_pair(ctx, rec, fa, fb, role_a="sm_dp_runtime", role_b="sm_dp_runtime",
             lock_timeout=None, names=("A", "B")):
    """Run two transactions concurrently. Each fn gets a Side; returning normally
    commits, raising rolls back. Returns two dicts."""
    barrier = threading.Barrier(2)
    out = {}

    def worker(nm, fn, role):
        conn = ctx.conn(role, autocommit=False, lock_timeout=lock_timeout)
        side = Side(nm, conn, barrier, rec)
        try:
            fn(side)
            conn.commit()
            out[nm] = {"outcome": "commit", "sqlstate": "", "msg": ""}
        except psycopg.Error as e:
            conn.rollback()
            detail = getattr(getattr(e, "diag", None), "message_detail", None) or ""
            msg = (str(e).splitlines()[0] + (" | DETAIL: " + " ".join(detail.split()) if detail else ""))
            rec.say(f"    -- {nm} raised {e.sqlstate}: {msg[:600]}")
            out[nm] = {"outcome": "error", "sqlstate": e.sqlstate or "", "msg": msg[:600]}
        except BaseException as e:
            try:
                conn.rollback()
            except Exception:
                pass
            out[nm] = {"outcome": "error", "sqlstate": getattr(e, "sqlstate", "") or "",
                       "msg": f"{type(e).__name__}: {str(e).splitlines()[0][:200]}"}
        finally:
            try:
                barrier.abort()
            except Exception:
                pass
            conn.close()

    ta = threading.Thread(target=worker, args=(names[0], fa, role_a))
    tb = threading.Thread(target=worker, args=(names[1], fb, role_b))
    t0 = time.time()
    ta.start(); tb.start(); ta.join(60); tb.join(60)
    return out.get(names[0], {"outcome": "hung", "sqlstate": "", "msg": ""}), \
           out.get(names[1], {"outcome": "hung", "sqlstate": "", "msg": ""}), \
           int((time.time() - t0) * 1000)



# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------
def write_reports(rec, out, meta):
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump({"meta": meta, "legs": [asdict(r) for r in rec.results]}, f, indent=2)
    counts = {}
    for r in rec.results:
        counts[r.verdict] = counts.get(r.verdict, 0) + 1
    by_ob = {}
    for r in rec.results:
        by_ob.setdefault(r.obligation, []).append(r)
    with open(os.path.join(out, "results.md"), "w") as f:
        w = f.write
        w("# PG 14 evidence run — Agent Gateway design set\n\n")
        w(f"* commit under test: `{meta.get('commit')}`\n")
        w(f"* engine: `{meta.get('version','?')}`\n")
        w(f"* container: `{meta.get('container')}`  image `{IMAGE}`  host port {meta.get('port')}\n")
        w(f"* started: {meta.get('started')}   finished: {meta.get('finished')}\n\n")
        w("## Bootstrap line ranges and md5\n\n| file and range | md5 |\n|---|---|\n")
        for n, m in meta.get("bootstrap_md5", {}).items():
            w(f"| {n} | `{m}` |\n")
        w("\n## Extracted blocks under test\n\n| block | md5 |\n|---|---|\n")
        for k, m in meta.get("block_md5", {}).items():
            w(f"| {k} | `{m}` |\n")
        w("\n## Verdict counts\n\n| verdict | legs |\n|---|---|\n")
        for k in ("OK", "REFUSED", "N/A-engine", "BLOCKED-dependency", "FAIL"):
            if k in counts:
                w(f"| {k} | {counts[k]} |\n")
        w(f"| **total** | **{len(rec.results)}** |\n")
        w("\n## Legs\n\n| obligation | leg | phase | verdict | expected | observed | SQLSTATE | ms |\n")
        w("|---|---|---|---|---|---|---|---|\n")
        for ob in sorted(by_ob, key=oblig_sort):
            for r in by_ob[ob]:
                w(f"| {ob} | {r.leg} | {r.phase} | **{r.verdict}** | {md_cell(r.expected)} | "
                  f"{md_cell(r.observed)} | `{r.sqlstate or '-'}` | {r.elapsed_ms} |\n")
    return counts


def md_cell(s):
    return (s or "").replace("|", "\\|").replace("\n", " ")[:300]


def oblig_sort(ob):
    m = re.match(r"\(L(\d+)\)", ob)
    if m:
        return (2, int(m.group(1)), ob)
    m = re.match(r"§14\.3\(([a-z])\)", ob)
    if m:
        return (1, ord(m.group(1)), ob)
    return (0, 0, ob)


# ---------------------------------------------------------------------------
# Small assertion helpers
# ---------------------------------------------------------------------------
def chk(rec, ob, leg, phase, expected, observed, good, detail="", sqlstate="", ms=0):
    rec.record(LegResult(ob, leg, phase, expected, observed, sqlstate, ms,
                         "OK" if good else "FAIL", detail))
    return good


def refused(rec, ob, leg, phase, want_state, fn, what, detail=""):
    r = expect_error(rec, fn, want_state, ob, leg, phase, what)
    r.detail = detail
    rec.record(r)
    return r.verdict == "REFUSED"


def rowcount_of(ctx, rec, sql, params, label, role="sm_dp_runtime", conn=None):
    own = conn is None
    c = ctx.conn(role, autocommit=True) if own else conn
    try:
        with c.cursor() as cur:
            n, rows = run_stmt(rec, cur, sql, params, label)
        return n, rows
    finally:
        if own:
            c.close()


def scalar(ctx, q, params=None):
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute(q, params) if params else cur.execute(q)
        r = cur.fetchone()
    return r[0] if r else None


# ---------------------------------------------------------------------------
# Leg registry
# ---------------------------------------------------------------------------
LEGS = []


def leg(obligation, name):
    def deco(fn):
        LEGS.append((obligation, name, fn))
        return fn
    return deco


# ===========================================================================
# design.md §14 item 3 — the DDL obligations, by that item's own lettering
# ===========================================================================
CLAIM_STEP0A = ("design", "5.2", 3, 0)
CLAIM_STEP0B = ("design", "5.2", 3, 1)
CLAIM_STEP1 = ("design", "5.2", 3, 2)
CLAIM_STEP2 = ("design", "5.2", 3, 3)
POLL = ("design", "5.2", 2, 0)
ACK_TERMINAL = ("design", "5.2", 4, 0)
ACK_RECEIVED = ("design", "5.2", 4, 1)


def claim_params(ctx, replica="pod-a", rid=None, thumb=None, fp=b"fp1"):
    return {"request_id": rid or uuid.uuid4(), "thumbprint": thumb or ctx.fx["thumbA"],
            "replica": replica, "pv": 1, "av": "v1", "fingerprint": fp}


def do_claim(ctx, rec, params, role="sm_dp_runtime"):
    sql, cite = ctx.stmt(*CLAIM_STEP2)
    return rowcount_of(ctx, rec, sql, params, f"claim step 2 [{cite}]", role)


@leg("§14.3(a)", "claim-monotonic-and-exclusive")
def l_a(ctx, rec):
    ob = "§14.3(a)"
    sql, cite = ctx.stmt(*CLAIM_STEP2)
    pa, pb = claim_params(ctx, "pod-a"), claim_params(ctx, "pod-b")

    def A(side):
        side.run(sql, pa, f"claim A [{cite}]")
        side.wait("both claims have written")

    def B(side):
        side.wait("both claims have written")
        side.run(sql, pb, f"claim B [{cite}]")

    ra, rb, ms = run_pair(ctx, rec, A, B)
    epochs = scalar(ctx, "SELECT array_agg(fencing_epoch ORDER BY fencing_epoch) FROM sm_data.agent_claim")
    sess = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s", (ctx.fx["agentA"],))
    owner = scalar(ctx, "SELECT owner_replica_id FROM sm_data.agent_session WHERE agent_id=%s", (ctx.fx["agentA"],))
    good = ra["outcome"] == "commit" and rb["outcome"] == "commit" and epochs == [1, 2] and sess == 2 and owner == "pod-b"
    chk(rec, ob, "two-concurrent-claims", "production",
        "both commit; ledger epochs [1,2]; session at 2 owned by the later claimant",
        f"A={ra['outcome']} B={rb['outcome']} ledger={epochs} session={sess} owner={owner}", good, ms=ms)

    cname = scalar(ctx, """SELECT conname FROM pg_constraint WHERE conrelid='sm_data.agent_claim'::regclass
                            AND contype='u'""")
    rec.say(f"--- PLANTED (DDL): ALTER TABLE sm_data.agent_claim DROP CONSTRAINT {cname} "
            f"(the UNIQUE (tenant_id, agent_id, fencing_epoch) of the extracted §5.2 DDL)")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute(f"ALTER TABLE sm_data.agent_claim DROP CONSTRAINT {cname}")
        try:
            cur.execute("""INSERT INTO sm_data.agent_claim (request_id, tenant_id, agent_id,
                           request_fingerprint, fencing_epoch) VALUES (%s,%s,%s,'x',2)""",
                        (uuid.uuid4(), ctx.fx["tenant"], ctx.fx["agentA"]))
            dup = True
        except psycopg.Error as e:
            dup = False
    chk(rec, ob, "two-concurrent-claims", "planted",
        "with the UNIQUE dropped a second ledger row may claim epoch 2 (the guard is real)",
        "duplicate epoch accepted" if dup else "still refused — the UNIQUE was not the arbiter", dup)
    ctx.rebuild()
    n, _ = rowcount_of(ctx, rec, sql, claim_params(ctx, "pod-a"), f"claim after restore [{cite}]")
    chk(rec, ob, "two-concurrent-claims", "restored", "claim writes exactly one row again",
        f"{n} row(s)", n == 1)


@leg("§14.3(b)", "agent_session_epoch_only_rises")
def l_b(ctx, rec):
    ob = "§14.3(b)"
    ctx.session_for(ctx.fx["agentA"], "pod-a", 5)
    A = ctx.fx["agentA"]
    with ctx.owner() as c:
        with c.cursor() as cur:
            cur.execute("""UPDATE sm_data.agent_session SET fencing_epoch = 6, owner_replica_id='pod-b'
                            WHERE agent_id=%s""", (A,))
            n1 = cur.rowcount
            cur.execute("UPDATE sm_data.agent_session SET last_seen_at = now() WHERE agent_id=%s", (A,))
            n2 = cur.rowcount
    chk(rec, ob, "positive-controls", "production",
        "a legal raise (epoch 5→6 with a new owner) and a last_seen_at-only heartbeat both succeed",
        f"raise={n1} row, heartbeat={n2} row", n1 == 1 and n2 == 1)

    def lower():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_session SET fencing_epoch = 1 WHERE agent_id=%s", (A,))
    refused(rec, ob, "AGW01-epoch-decrease", "production", "AGW01", lower,
            "a fencing_epoch decrease is refused")

    def steal():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_session SET owner_replica_id='pod-z' WHERE agent_id=%s", (A,))
    refused(rec, ob, "AGW02-owner-without-epoch", "production", "AGW02", steal,
            "an owner change that does not raise the epoch is refused")

    rec.say("--- PLANTED (DDL): DROP TRIGGER agent_session_epoch_only_rises ON sm_data.agent_session")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("DROP TRIGGER agent_session_epoch_only_rises ON sm_data.agent_session")
        cur.execute("UPDATE sm_data.agent_session SET fencing_epoch = 1 WHERE agent_id=%s", (A,))
        n = cur.rowcount
    got = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s", (A,))
    chk(rec, ob, "AGW01-epoch-decrease", "planted",
        "without the trigger the epoch goes backwards", f"{n} row updated, epoch now {got}",
        n == 1 and got == 1)
    ctx.rebuild()
    A2 = ctx.fx["agentA"]
    ctx.session_for(A2, "pod-a", 5)

    def lower2():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_session SET fencing_epoch = 1 WHERE agent_id=%s", (A2,))
    refused(rec, ob, "AGW01-epoch-decrease", "restored", "AGW01", lower2,
            "the re-created trigger refuses the decrease again")


@leg("§14.3(c)", "column-scoped-grants")
def l_c(ctx, rec):
    ob = "§14.3(c)"
    ctx.session_for(ctx.fx["agentA"], "pod-a", 1)
    A = ctx.fx["agentA"]
    cases = [
        ("agent_session.tenant_id", f"UPDATE sm_data.agent_session SET tenant_id = '{uuid.uuid4()}'"),
        ("agent_identity.agent_id", f"UPDATE sm_data.agent_identity SET agent_id = '{uuid.uuid4()}'"),
        ("agent_command.command_id", f"UPDATE sm_data.agent_command SET command_id = '{uuid.uuid4()}'"),
        ("agent_claim DELETE", "DELETE FROM sm_data.agent_claim"),
        ("agent_lease_grant DELETE", "DELETE FROM sm_data.agent_lease_grant"),
        ("agent_request_tombstone INSERT",
         f"INSERT INTO sm_data.agent_request_tombstone (request_id, expired_at) VALUES ('{uuid.uuid4()}', now())"),
    ]
    for name, sql in cases:
        def f(sql=sql):
            with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
                run_stmt(rec, cur, sql, None, f"as sm_dp_runtime: {name}")
        refused(rec, ob, f"42501-{name}", "production", "42501", f,
                "the runtime role's column-scoped grant refuses it")
    with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
        n, _ = run_stmt(rec, cur, "UPDATE sm_data.agent_session SET last_seen_at = now() WHERE agent_id = %(a)s",
                        {"a": A}, "positive control as sm_dp_runtime")
    chk(rec, ob, "positive-control-granted-column", "production",
        "the same role may write a granted column", f"{n} row(s)", n == 1)


@leg("§14.3(e)", "agent_command-unique-per-intent")
def l_e(ctx, rec):
    ob = "§14.3(e)"
    ctx.command(rec)
    def dup():
        ctx.command(rec, command_id=uuid.uuid4())
    refused(rec, ob, "23505-second-row-per-intent", "production", "23505", dup,
            "UNIQUE (tenant_id, apply_id, intent_id, agent_id) refuses a second command for one intent/agent")


@leg("§14.3(f)", "queued-to-superseded")
def l_f(ctx, rec):
    ob = "§14.3(f)"
    cid = ctx.command(rec)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""UPDATE sm_data.agent_command SET state='SUPERSEDED', outcome='SUPERSEDED'
                        WHERE command_id=%s""", (cid,))
        n = cur.rowcount
        oe = None
        cur.execute("SELECT offered_epoch FROM sm_data.agent_command WHERE command_id=%s", (cid,))
        oe = cur.fetchone()[0]
    chk(rec, ob, "never-offered-may-be-superseded", "production",
        "a QUEUED row with offered_epoch NULL moves to SUPERSEDED", f"{n} row, offered_epoch={oe}",
        n == 1 and oe is None)

    cid2 = ctx.command(rec, command_id=uuid.uuid4(), intent=ctx.new_intent())
    def bad():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_command SET state='OFFERED' WHERE command_id=%s", (cid2,))
    refused(rec, ob, "23514-offered_has_epoch", "production", "23514", bad,
            "OFFERED with a NULL offered_epoch is refused")


@leg("§14.3(h)", "global-uniques-refuse-cross-tenant-reuse")
def l_h(ctx, rec):
    ob = "§14.3(h)"
    def dup_agent():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.agent_identity (tenant_id, agent_id, target_id,
                            cert_thumbprint, enrolled_at) VALUES (%s,%s,%s,%s, now())""",
                        (ctx.fx["tenant2"], ctx.fx["agentA"], ctx.fx["target"], b"\\x09" * 32))
    refused(rec, ob, "23505-UNIQUE(agent_id)", "production", "23505", dup_agent,
            "the same agent_id under another tenant is refused")
    cid = ctx.command(rec)
    def dup_cmd():
        ctx.command(rec, command_id=cid, tenant=ctx.fx["tenant2"], intent=ctx.new_intent())
    refused(rec, ob, "23505-UNIQUE(command_id)", "production", "23505", dup_cmd,
            "the same command_id under another tenant is refused")


@leg("§14.3(j)", "cap_matches_op")
def l_j(ctx, rec):
    ob = "§14.3(j)"
    def bad():
        ctx.command(rec, operation="restart", capability="apply-config")
    refused(rec, ob, "23514-restart-tagged-apply-config", "production", "23514", bad,
            "cap_matches_op refuses a restart tagged apply-config")
    cid = ctx.command(rec, operation="restart", capability="lifecycle", intent=ctx.new_intent("LIFECYCLE"))
    chk(rec, ob, "positive-control", "production", "restart + lifecycle is accepted",
        f"command {str(cid)[:8]} inserted", cid is not None)


FENCED = [
    ("poll (CommandFeedPort)", POLL, lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e,
                                                     "replica": "pod-a", "limit": 10,
                                                     "notice_retry_interval": timedelta(seconds=60)}),
    ("terminal ack (CommandAckPort.ack)", ACK_TERMINAL,
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "replica": "pod-a",
                     "command": ctx.fx.get("cmd"), "outcome": "APPLIED", "detail": None}),
    ("lease reservation, step 1 (LeasePort)", ("sketch", "3.1", 1, 3),
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "replica": "pod-a",
                     "request_id": uuid.uuid4(), "kind": "renew", "fingerprint": b"fp",
                     "pending_ttl": timedelta(seconds=60)}),
    ("lease adoption, step 1 re-arm", ("sketch", "3.1", 1, 4),
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "replica": "pod-a",
                     "request_id": ctx.fx.get("rid"), "pending_ttl": timedelta(seconds=60)}),
    ("lease takeover (§3.4)", ("sketch", "3.4", 1, 0),
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "replica": "pod-a",
                     "request_id": ctx.fx.get("rid"), "pending_ttl": timedelta(seconds=60),
                     "expected": 1, "takeover_attempts": 3}),
    ("registry A1 fence (§4 Phase A)", ("sketch", "4.1", 1, 1),
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "replica": "pod-a"}),
    # self-contained since S10/F1 — it carries its own WITH s
    ("delivery ack (CommandAckPort.received)", ACK_RECEIVED,
     lambda ctx, e: {"agent": ctx.fx["agentA"], "epoch": e, "command": ctx.fx.get("cmd")}),
]


@leg("§14.3(d)", "stale-epoch-writes-nothing")
def l_d(ctx, rec):
    ob = "§14.3(d)"
    live = 4
    ctx.session_for(ctx.fx["agentA"], "pod-a", live)
    ctx.fx["cmd"] = ctx.command(rec, state="OFFERED")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_command SET offered_epoch=%s, offered_at=now() WHERE command_id=%s",
                    (live, ctx.fx["cmd"]))
    rid = uuid.uuid4()
    ctx.fx["rid"] = rid
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO sm_data.agent_lease_grant (request_id, tenant_id, agent_id, fencing_epoch,
                        kind, request_fingerprint, attempt_owner, attempt_deadline)
                       VALUES (%s,%s,%s,%s,'renew','fp','pod-a', now() - interval '1 hour')""",
                    (rid, ctx.fx["tenant"], ctx.fx["agentA"], live))
    stale = live - 1
    for name, spec, mk in FENCED:
        try:
            sql, cite = ctx.stmt(*spec)
        except Exception as e:
            chk(rec, ob, f"stale-{name}", "production", "0 rows at a stale epoch",
                f"statement not usable: {e}", False)
            continue
        n, _ = rowcount_of(ctx, rec, sql, mk(ctx, stale), f"{name} at stale epoch {stale} [{cite}]")
        chk(rec, ob, f"stale-{name}", "production",
            "0 rows written at a superseded epoch", f"{n} row(s)", n == 0)
    # nothing moved
    left = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant WHERE state<>'PENDING'")
    st = scalar(ctx, "SELECT state FROM sm_data.agent_command WHERE command_id=%s", (ctx.fx["cmd"],))
    chk(rec, ob, "registry-and-ledger-unchanged", "production",
        "no ledger row terminalised and the command still OFFERED",
        f"terminal ledger rows={left}, command state={st}", left == 0 and st == "OFFERED")


@leg("§14.3(g)", "claim-refuses-revoked-thumbprint")
def l_g(ctx, rec):
    ob = "§14.3(g)"
    sql, cite = ctx.stmt(*CLAIM_STEP2)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_identity SET revoked_at=now(), agent_revocation_epoch=1 WHERE agent_id=%s",
                    (ctx.fx["agentA"],))
    n, _ = rowcount_of(ctx, rec, sql, claim_params(ctx), f"claim of a revoked identity [{cite}]")
    chk(rec, ob, "revoked-then-claim", "production", "0 rows", f"{n} row(s)", n == 0)

    ctx.reset()
    order = []

    def A(side):  # the claim holds agent_identity FOR SHARE
        side.run(sql, claim_params(ctx), f"claim [{cite}]")
        order.append("claim-wrote")
        side.wait("claim has written, revoke may start")
        time.sleep(0.4)
        order.append("claim-commits")

    def B(side):  # the revoke must wait for it
        side.wait("claim has written, revoke may start")
        side.run("UPDATE sm_data.agent_identity SET revoked_at=now(), agent_revocation_epoch=1 "
                 "WHERE agent_id=%(a)s", {"a": ctx.fx["agentA"]}, "revoke")
        order.append("revoke-wrote")

    ra, rb, ms = run_pair(ctx, rec, A, B, role_b="sm_dp_runtime")
    good = ra["outcome"] == "commit" and rb["outcome"] == "commit" and order.index("claim-commits") < order.index("revoke-wrote")
    chk(rec, ob, "revoke-serialises-behind-the-claim", "production",
        "the claim's FOR SHARE makes a concurrent revoke wait for its commit; both then commit",
        f"order={order} A={ra['outcome']} B={rb['outcome']}", good, ms=ms)

    n2, _ = rowcount_of(ctx, rec, sql, claim_params(ctx), "claim after the revoke committed")
    chk(rec, ob, "claim-after-revoke", "production", "0 rows", f"{n2} row(s)", n2 == 0)


@leg("§14.3(i)", "received-and-ack")
def l_i(ctx, rec):
    ob = "§14.3(i)"
    sql, cite = ctx.stmt(*ACK_TERMINAL)
    ctx.session_for(ctx.fx["agentA"], "pod-a", 1)
    for state in ("OFFERED", "DELIVERED"):
        cid = ctx.command(rec, state="QUEUED", intent=ctx.new_intent())
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""UPDATE sm_data.agent_command SET state=%s, offered_epoch=1, offered_at=now(),
                            delivered_at = CASE WHEN %s='DELIVERED' THEN now() END WHERE command_id=%s""",
                        (state, state, cid))
        n, _ = rowcount_of(ctx, rec, sql, {"agent": ctx.fx["agentA"], "epoch": 1, "replica": "pod-a",
                                           "command": cid, "outcome": "APPLIED", "detail": None},
                           f"terminal ack from {state} [{cite}]")
        got = scalar(ctx, "SELECT state FROM sm_data.agent_command WHERE command_id=%s", (cid,))
        chk(rec, ob, f"ack-from-{state}", "production", "1 row, state ACKED",
            f"{n} row(s), state={got}", n == 1 and got == "ACKED")
    rsql, rcite = ctx.stmt(*ACK_RECEIVED)
    cid_r = ctx.command(rec, state="OFFERED", intent=ctx.new_intent())
    p_r = {"agent": ctx.fx["agentA"], "epoch": 1, "command": cid_r}
    n, _ = rowcount_of(ctx, rec, rsql, p_r, f"received, OFFERED → DELIVERED [{rcite}]")
    st = scalar(ctx, "SELECT state FROM sm_data.agent_command WHERE command_id=%s", (cid_r,))
    oc = scalar(ctx, "SELECT outcome FROM sm_data.agent_command WHERE command_id=%s", (cid_r,))
    dt = scalar(ctx, "SELECT delivered_at IS NOT NULL FROM sm_data.agent_command WHERE command_id=%s",
                (cid_r,))
    chk(rec, ob, "received-never-terminal", "production",
        "1 row; state DELIVERED with delivered_at set and NO outcome — the row is not terminal",
        f"{n} row(s), state={st}, outcome={oc}, delivered_at set={dt}",
        n == 1 and st == "DELIVERED" and oc is None and dt is True)
    n2, _ = rowcount_of(ctx, rec, rsql, p_r, "received again on the same command")
    chk(rec, ob, "received-is-idempotent", "production",
        "0 rows the second time — DUPLICATE, not a second transition", f"{n2} row(s)", n2 == 0)
    def bad():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""UPDATE sm_data.agent_command SET state='DELIVERED', outcome='APPLIED'
                            WHERE command_id=%s""", (ctx.fx["cmd"],)) if ctx.fx.get("cmd") else None
    cid = ctx.command(rec, state="OFFERED", intent=ctx.new_intent())
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_command SET offered_epoch=1, offered_at=now() WHERE command_id=%s", (cid,))
    def bad2():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""UPDATE sm_data.agent_command SET state='DELIVERED', delivered_at=now(),
                            outcome='APPLIED' WHERE command_id=%s""", (cid,))
    refused(rec, ob, "23514-DELIVERED-with-an-outcome", "production", "23514", bad2,
            "outcome_iff_terminal refuses a DELIVERED row that carries an outcome")


@leg("§14.3(k)", "state_matches_outcome-and-the-eight-outcomes")
def l_k(ctx, rec):
    ob = "§14.3(k)"
    sql, cite = ctx.stmt(*ACK_TERMINAL)
    ctx.session_for(ctx.fx["agentA"], "pod-a", 1)
    want = {"APPLIED": "ACKED", "FAILED_NO_EFFECT": "ACKED", "FAILED_ROLLED_BACK": "ACKED",
            "APPLY_INDETERMINATE": "ACKED", "PREFLIGHT_OK": "ACKED", "PREFLIGHT_REFUSED": "ACKED",
            "REFUSED": "REFUSED", "SUPERSEDED": "SUPERSEDED"}
    for outcome, state in want.items():
        cid = ctx.command(rec, state="QUEUED", intent=ctx.new_intent())
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_command SET state='OFFERED', offered_epoch=1, offered_at=now() "
                        "WHERE command_id=%s", (cid,))
        n, _ = rowcount_of(ctx, rec, sql, {"agent": ctx.fx["agentA"], "epoch": 1, "replica": "pod-a",
                                           "command": cid, "outcome": outcome, "detail": None},
                           f"terminal ack outcome={outcome} [{cite}]")
        got = scalar(ctx, "SELECT state FROM sm_data.agent_command WHERE command_id=%s", (cid,))
        chk(rec, ob, f"outcome-{outcome}", "production", f"1 row, state {state}",
            f"{n} row(s), state={got}", n == 1 and got == state)
    cid = ctx.command(rec, state="QUEUED", intent=ctx.new_intent())
    for state, outcome in (("ACKED", "REFUSED"), ("REFUSED", "APPLIED"), ("SUPERSEDED", "APPLIED"),
                           ("ACKED", "SUPERSEDED")):
        def bad(state=state, outcome=outcome):
            with ctx.owner() as c, c.cursor() as cur:
                cur.execute("UPDATE sm_data.agent_command SET state=%s, outcome=%s, offered_epoch=1 "
                            "WHERE command_id=%s", (state, outcome, cid))
        refused(rec, ob, f"23514-{state}+{outcome}", "production", "23514", bad,
                "state_matches_outcome refuses the pair")


@leg("§14.3(p)", "report-persistence-has-no-engine-artifact")
def l_p(ctx, rec):
    ob = "§14.3(p)"
    tabs = scalar(ctx, """SELECT coalesce(string_agg(tablename, ','), '') FROM pg_tables
                           WHERE schemaname='sm_data' AND (tablename LIKE '%report%'
                              OR tablename LIKE '%actual%' OR tablename LIKE '%refus%')""")
    rec.record(LegResult(ob, "no-report-store-exists", "n-a",
                         "the design set defines NO table that could persist a report or its refusal "
                         "(durable persistence is §13 AGW-20, open)",
                         f"matching tables in sm_data: {tabs or 'none'}", "", 0,
                         "N/A-engine" if not tabs else "FAIL",
                         "Classification TRUSTED/UNTRUSTED and disposition APPLIED/STALE/REFUSED are "
                         "engine-side; there is no DDL to execute. Recorded the way the backend "
                         "evidence recorded its engine-only obligations."))


def poll_params(ctx, epoch=1, replica="pod-a"):
    return {"agent": ctx.fx["agentA"], "epoch": epoch, "replica": replica, "limit": 10,
            "notice_retry_interval": timedelta(seconds=60)}


@leg("§14.3(l)", "pollDeliverable")
def l_l(ctx, rec):
    ob = "§14.3(l)"
    sql, cite = ctx.stmt(*POLL)
    A = ctx.fx["agentA"]

    def fresh(**lease):
        nonlocal A
        ctx.reset()
        A = ctx.fx["agentA"]
        ctx.session_for(A, "pod-a", 1)
        cid = ctx.command(rec)
        ctx.lease(A, **lease)
        return cid

    negatives = [
        ("stale epoch", dict(), lambda: poll_params(ctx, epoch=0)),
        ("non-owning replica", dict(), lambda: poll_params(ctx, replica="pod-z")),
        ("lease bound to another certificate", dict(thumb=b"\\x77" * 32), lambda: poll_params(ctx)),
        ("expired lease", dict(issued="now() - interval '2 hours'", expires="now() - interval '1 hour'"),
         lambda: poll_params(ctx)),
        ("insufficient remaining time", dict(issued="now() - interval '59 minutes'",
                                             expires="now() + interval '1 minute'"), lambda: poll_params(ctx)),
        ("missing capability", dict(caps=["lifecycle"]), lambda: poll_params(ctx)),
        ("binding not in the lease", dict(bindings="other"), lambda: poll_params(ctx)),
        ("foreign target", dict(target="other"), lambda: poll_params(ctx)),
        ("revocation epoch moved", dict(agent_epoch=7), lambda: poll_params(ctx)),
    ]
    for name, lease, mk in negatives:
        fresh(**lease)
        n, _ = rowcount_of(ctx, rec, sql, mk(), f"poll — {name} [{cite}]")
        chk(rec, ob, f"returns-nothing-{name}", "production", "0 rows", f"{n} row(s)", n == 0)

    # no lease row at all
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1); ctx.command(rec)
    n, _ = rowcount_of(ctx, rec, sql, poll_params(ctx), f"poll — no agent_lease row [{cite}]")
    chk(rec, ob, "returns-nothing-no-lease-row", "production", "0 rows", f"{n} row(s)", n == 0)

    # every predicate holds
    cid = fresh()
    n, rows = rowcount_of(ctx, rec, sql, poll_params(ctx), f"poll — every predicate holds [{cite}]")
    st, oe, nn = scalar(ctx, "SELECT state FROM sm_data.agent_command WHERE command_id=%s", (cid,)), \
        scalar(ctx, "SELECT offered_epoch FROM sm_data.agent_command WHERE command_id=%s", (cid,)), \
        scalar(ctx, "SELECT next_notice_at > now() FROM sm_data.agent_command WHERE command_id=%s", (cid,))
    chk(rec, ob, "returns-and-stamps", "production",
        "1 row returned; the command is OFFERED at the caller's epoch with next_notice_at in the future",
        f"{n} row(s) state={st} offered_epoch={oe} next_notice_future={nn}",
        n == 1 and st == "OFFERED" and oe == 1 and nn is True)

    # a second poll inside next_notice_at is silent; past it, it re-notices; once the agent speaks, never
    n2, _ = rowcount_of(ctx, rec, sql, poll_params(ctx), "poll — again inside next_notice_at")
    chk(rec, ob, "no-renotice-inside-the-interval", "production", "0 rows", f"{n2} row(s)", n2 == 0)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_command SET next_notice_at = now() - interval '1 second' "
                    "WHERE command_id=%s", (cid,))
    n3, _ = rowcount_of(ctx, rec, sql, poll_params(ctx), "poll — after next_notice_at elapsed")
    chk(rec, ob, "renotices-after-the-interval", "production", "1 row", f"{n3} row(s)", n3 == 1)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_command SET notice_acked_epoch = 1, "
                    "next_notice_at = now() - interval '1 second' WHERE command_id=%s", (cid,))
    n4, _ = rowcount_of(ctx, rec, sql, poll_params(ctx), "poll — the agent has spoken on this epoch")
    chk(rec, ob, "never-again-once-acknowledged", "production", "0 rows", f"{n4} row(s)", n4 == 0)

    # two pollers, two epochs: exactly one gets the row
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 2); ctx.command(rec); ctx.lease(A)
    got = {}

    def P1(side):
        n, _ = side.run(sql, poll_params(ctx, epoch=2), "poller at the live epoch 2")
        got["live"] = n
        side.wait("both polls issued")

    def P2(side):
        n, _ = side.run(sql, poll_params(ctx, epoch=1), "poller at the superseded epoch 1")
        got["stale"] = n
        side.wait("both polls issued")

    ra, rb, ms = run_pair(ctx, rec, P1, P2)
    chk(rec, ob, "two-pollers-one-row", "production",
        "exactly one poller receives the command — the one at the live epoch",
        f"live={got.get('live')} stale={got.get('stale')} A={ra['outcome']} B={rb['outcome']}",
        got.get("live") == 1 and got.get("stale") == 0, ms=ms)

    # a fresh connection gets the same answer: no process state is involved
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1); ctx.command(rec); ctx.lease(A)
    with ctx.conn("sm_dp_runtime", autocommit=True) as c1, c1.cursor() as cur:
        n5, _ = run_stmt(rec, cur, sql, poll_params(ctx), "poll on connection 1")
    with ctx.conn("sm_dp_runtime", autocommit=True) as c2, c2.cursor() as cur:
        n6, _ = run_stmt(rec, cur, sql, poll_params(ctx), "poll on a brand-new connection")
    chk(rec, ob, "no-process-state", "production",
        "connection 1 gets the row; a brand-new connection then gets nothing, from the row alone",
        f"first={n5} second={n6}", n5 == 1 and n6 == 0)


@leg("§14.3(n)", "claim-idempotency")
def l_n(ctx, rec):
    ob = "§14.3(n)"
    step1, c1 = ctx.stmt(*CLAIM_STEP1)
    step2, c2 = ctx.stmt(*CLAIM_STEP2)
    r1 = uuid.uuid4()
    rowcount_of(ctx, rec, step2, claim_params(ctx, "pod-a", rid=r1), f"Q1 claim [{c2}]")
    rowcount_of(ctx, rec, step2, claim_params(ctx, "pod-b"), f"Q2 claim [{c2}]")
    n, rows = rowcount_of(ctx, rec, step1, {"request_id": r1}, f"delayed Q1 retry, step 1 [{c1}]")
    epoch, fp, current = rows[0] if rows else (None, None, None)
    chk(rec, ob, "retry-returns-its-own-epoch", "production",
        "the delayed retry reads epoch 1 with current = false, never epoch 2",
        f"epoch={epoch} current={current}", epoch == 1 and current is False)
    total = scalar(ctx, "SELECT count(*) FROM sm_data.agent_claim")
    sess = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s", (ctx.fx["agentA"],))
    chk(rec, ob, "retry-writes-no-row", "production", "still two ledger rows and the session at epoch 2",
        f"ledger rows={total} session epoch={sess}", total == 2 and sess == 2)

    def replay():
        with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
            run_stmt(rec, cur, step2, claim_params(ctx, "pod-c", rid=r1), "re-running step 2 under the same id")
    refused(rec, ob, "23505-second-step-2-under-one-id", "production", "23505", replay,
            "the PRIMARY KEY refuses a second ledger row for one request id, so no second epoch is minted")
    sess2 = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s", (ctx.fx["agentA"],))
    chk(rec, ob, "aborted-replay-mints-no-epoch", "production", "the session is still at epoch 2",
        f"session epoch={sess2}", sess2 == 2)

    ctx.reset()
    rid = uuid.uuid4()
    out = {}

    def A(side):
        side.wait("both duplicates ready")
        try:
            side.run(step2, claim_params(ctx, "pod-a", rid=rid), "duplicate claim A")
            out["A"] = "ok"
        except psycopg.Error as e:
            out["A"] = e.sqlstate
            raise

    def B(side):
        side.wait("both duplicates ready")
        try:
            side.run(step2, claim_params(ctx, "pod-b", rid=rid), "duplicate claim B")
            out["B"] = "ok"
        except psycopg.Error as e:
            out["B"] = e.sqlstate
            raise

    ra, rb, ms = run_pair(ctx, rec, A, B)
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_claim")
    epochs = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s",
                    (ctx.fx["agentA"],))
    commits = [x["outcome"] for x in (ra, rb)].count("commit")
    chk(rec, ob, "two-concurrent-identical-claims-mint-one-epoch", "production",
        "exactly one commits, one ledger row, one epoch",
        f"commits={commits} ledger rows={rows} session epoch={epochs} outcomes={out}",
        commits == 1 and rows == 1 and epochs == 1, ms=ms)


@leg("§14.3(r)", "retention-and-tombstones")
def l_r(ctx, rec):
    ob = "§14.3(r)"
    batch, cb = ctx.stmt("design", "7.4", 2, 0)
    delete, cd = ctx.stmt("design", "7.4", 2, 1)
    tomb_check, ct = ctx.stmt(*CLAIM_STEP0B)
    key, ck = ctx.stmt(*CLAIM_STEP0A)

    ids = []
    with ctx.owner() as c, c.cursor() as cur:
        for i in range(5):
            r = uuid.uuid4(); ids.append(r)
            cur.execute("""INSERT INTO sm_data.agent_claim (request_id, tenant_id, agent_id,
                            request_fingerprint, fencing_epoch, claimed_at)
                           VALUES (%s,%s,%s,'fp',%s, now() - interval '90 days')""",
                        (r, ctx.fx["tenant"], ctx.fx["agentA"], i + 1))
    conn = ctx.conn("sm_dp_migrator", autocommit=False)
    with conn.cursor() as cur:
        cur.execute("SET ROLE sm_dp_owner")
        n1, rows = run_stmt(rec, cur, batch, {"ledger_retention": timedelta(days=30), "retention_batch": 2},
                            f"retention step 1: bounded batch + key locks [{cb}]")
        got = [r[0] for r in rows]
        n2, _ = run_stmt(rec, cur, delete, {"ids": got, "ledger_retention": timedelta(days=30)},
                         f"retention step 2: delete + tombstone [{cd}]")
    conn.commit(); conn.close()
    left = scalar(ctx, "SELECT count(*) FROM sm_data.agent_claim")
    tombs = scalar(ctx, "SELECT count(*) FROM sm_data.agent_request_tombstone")
    chk(rec, ob, "bounded-batch-and-one-tombstone-per-deleted-row", "production",
        "retention_batch = 2 deletes exactly two rows and writes exactly two tombstones",
        f"batch={n1} deleted={n2} remaining={left} tombstones={tombs}",
        n1 == 2 and left == 3 and tombs == 2)

    n, rows = rowcount_of(ctx, rec, tomb_check, {"request_id": got[0]},
                          f"replay of a tombstoned requestId, step 0b [{ct}]")
    chk(rec, ob, "tombstone-refuses-the-replay", "production",
        "step 0b finds the tombstone row, so the retry is REQUEST_EXPIRED rather than a new claim",
        f"{n} tombstone row(s) found", n == 1)

    # the advisory key serialises a retry against the job
    live = [i for i in ids if i not in got][0]
    order = []

    def JOB(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        side.run(key, {"request_id": live}, "job takes the request key (same lock as §6)")
        order.append("job-holds-key")
        side.wait("job holds the key")
        time.sleep(0.4)
        order.append("job-commits")

    def RETRY(side):
        side.wait("job holds the key")
        side.run(key, {"request_id": live}, "retry asks for the same request key")
        order.append("retry-got-key")

    ra, rb, ms = run_pair(ctx, rec, JOB, RETRY, role_a="sm_dp_migrator")
    good = (ra["outcome"] == rb["outcome"] == "commit"
            and order.index("job-commits") < order.index("retry-got-key"))
    chk(rec, ob, "advisory-key-serialises-retry-and-job", "production",
        "the retry waits for the job's transaction to end before it holds the same request key",
        f"order={order} A={ra['outcome']} B={rb['outcome']}", good, ms=ms)


# ===========================================================================
# lease-and-registry-sketch.md §8 — obligations (L1) … (L18)
# ===========================================================================
RESERVE = ("sketch", "3.1", 1, 3)
ADOPT = ("sketch", "3.1", 1, 4)
STEP3A = ("sketch", "3.3", 1, 0)
STEP3B = ("sketch", "3.3", 1, 1)
STEP3C = ("sketch", "3.3", 2, 0)
TAKEOVER = ("sketch", "3.4", 1, 0)


BASE = datetime(2026, 9, 10, 10, 0, 0, tzinfo=timezone.utc)


def grant_params(ctx, rid, epoch=1, mine=1, replica="pod-a", valid=True, jti=None,
                 issued=None, seq_hint=None, refusal="GRANT_INVALID"):
    f = ctx.fx
    return {"request_id": rid, "agent": f["agentA"], "epoch": epoch, "replica": replica, "mine": mine,
            "jti": jti or str(uuid.uuid4()), "cnf_thumbprint": f["thumbA"], "target": f["target"],
            "bindings": [f["binding"]], "caps": ["apply-config", "lifecycle"],
            "agent_epoch": 0, "fleet_epoch": 0, "audience": "dp",
            "issued_at": issued or BASE, "expires_at": (issued or BASE) + timedelta(hours=1),
            "c_preflight": timedelta(minutes=1), "c_apply": timedelta(minutes=1),
            "c_restart": timedelta(minutes=1), "c_stop": timedelta(minutes=1),
            "margin": timedelta(minutes=1), "grant_json": Jsonb({"g": 1}), "valid": valid,
            "refusal_code": refusal}


def lease_txn(ctx, rec, steps, role="sm_dp_runtime", conn=None):
    """Run a documented multi-statement sequence in ONE transaction, in order."""
    own = conn is None
    c = conn or ctx.conn(role, autocommit=False)
    counts = []
    try:
        with c.cursor() as cur:
            for spec, params, label in steps:
                sql, cite = ctx.stmt(*spec)
                n, rows = run_stmt(rec, cur, sql, params, f"{label} [{cite}]")
                counts.append((n, rows))
        if own:
            c.commit()
    finally:
        if own:
            c.close()
    return counts


def reserve_steps(ctx, rid, epoch=1, kind="renew", replica="pod-a", fp=b"fp", ttl=60):
    # NOTE a negative ttl is a legitimate FIXTURE VALUE for the document's own $pending_ttl:
    # it is how a row is born already past its attempt_deadline. The alternative — editing
    # attempt_deadline out of band — is refused by agent_lease_grant_terminal_once (AGW14),
    # which is the trigger behaving exactly as §2.1 says it must.
    p = {"request_id": rid, "agent": ctx.fx["agentA"], "epoch": epoch, "replica": replica,
         "kind": kind, "fingerprint": fp, "pending_ttl": timedelta(seconds=ttl)}
    return [(("sketch", "3.1", 1, 0), {"request_id": rid}, "step 0a advisory request key"),
            (("sketch", "3.1", 1, 1), {"request_id": rid}, "step 0b tombstone check"),
            (("sketch", "3.1", 1, 2), {"agent": ctx.fx["agentA"]}, "step 0c identity lock"),
            (RESERVE, p, "step 1 fenced reservation")]


@leg("(L1)", "lease-ledger-reservation")
def L1(ctx, rec):
    ob = "(L1)"
    ctx.session_for(ctx.fx["agentA"], "pod-a", 1)
    rid = uuid.uuid4()
    (_, _, _, (n, _)) = lease_txn(ctx, rec, reserve_steps(ctx, rid))
    chk(rec, ob, "reserve-before-any-cp-call", "production", "one PENDING row reserved",
        f"{n} row(s); state={scalar(ctx, 'SELECT state FROM sm_data.agent_lease_grant')}", n == 1)

    (*_, (n2, _)) = lease_txn(ctx, rec, reserve_steps(ctx, rid))
    chk(rec, ob, "duplicate-renew-is-one-row", "production",
        "the second identical renew inserts nothing (ON CONFLICT DO NOTHING) → IN_PROGRESS",
        f"{n2} row(s); ledger rows={scalar(ctx, 'SELECT count(*) FROM sm_data.agent_lease_grant')}",
        n2 == 0 and scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant") == 1)

    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("INSERT INTO sm_data.agent_request_tombstone (request_id, expired_at) VALUES (%s, now())",
                    (uuid.uuid4(),))
        cur.execute("SELECT request_id FROM sm_data.agent_request_tombstone")
        tomb = cur.fetchone()[0]
    sql, cite = ctx.stmt("sketch", "3.1", 1, 1)
    n3, rows = rowcount_of(ctx, rec, sql, {"request_id": tomb}, f"step 0b for a tombstoned id [{cite}]")
    chk(rec, ob, "tombstoned-id-is-REQUEST_EXPIRED", "production",
        "step 0b finds the tombstone before anything is written", f"{n3} row(s)", n3 == 1)

    rec.say("--- PLANTED VARIANT: the reservation with its fence CTE reference removed is not a "
            "statement this harness may write; the fence is instead planted by superseding the epoch.")
    ctx.session_for(ctx.fx["agentA"], "pod-b", 2)
    (*_, (n4, _)) = lease_txn(ctx, rec, reserve_steps(ctx, uuid.uuid4(), epoch=1))
    chk(rec, ob, "reservation-is-fenced", "planted",
        "a reservation at a superseded epoch reserves nothing", f"{n4} row(s)", n4 == 0)


@leg("(L2)", "epoch-transfer-adoption")
def L2(ctx, rec):
    ob = "(L2)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))
    ctx.session_for(A, "pod-b", 2)     # the gateway died; a new owner claimed the agent
    sql, cite = ctx.stmt(*ADOPT)
    p = {"request_id": rid, "agent": A, "epoch": 2, "replica": "pod-b",
         "pending_ttl": timedelta(seconds=60)}
    n, rows = rowcount_of(ctx, rec, sql, p, f"adoption by the CURRENT owner [{cite}]")
    row = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    owner = scalar(ctx, "SELECT attempt_owner FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    att = scalar(ctx, "SELECT attempt_no FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    chk(rec, ob, "current-owner-adopts", "production",
        "the row moves to the new epoch with the new owner and the SAME attempt_no",
        f"{n} row(s) epoch={row} owner={owner} attempt_no={att}",
        n == 1 and row == 2 and owner == "pod-b" and att == 1)

    n2, _ = rowcount_of(ctx, rec, sql, {"request_id": rid, "agent": A, "epoch": 1, "replica": "pod-a",
                                        "pending_ttl": timedelta(seconds=60)},
                        "the reappearing OLD owner tries to adopt")
    chk(rec, ob, "old-owner-adopts-nothing", "production", "0 rows", f"{n2} row(s)", n2 == 0)

    n3, _ = rowcount_of(ctx, rec, sql, p, "the current owner adopts again (already at this epoch)")
    chk(rec, ob, "adoption-is-monotonic", "production",
        "a second adoption at the same epoch writes nothing (lg.fencing_epoch < s.fencing_epoch)",
        f"{n3} row(s)", n3 == 0)


@leg("(L3)", "takeover")
def L3(ctx, rec):
    ob = "(L3)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid, ttl=-60))   # born past its deadline
    sql, cite = ctx.stmt(*TAKEOVER)
    # every retry presents the SESSION OWNER's replica id: §3.4's `s` CTE fences the takeover on
    # se.owner_replica_id = $replica, so a takeover by a non-owning replica is 0 rows by construction.
    p = lambda r: {"request_id": rid, "agent": A, "epoch": 1, "replica": "pod-a",
                   "pending_ttl": timedelta(seconds=60), "expected": 1, "takeover_attempts": 3}
    wins = []
    def mk(name):
        def f(side):
            side.wait("ten retries released")
            n, _ = side.run(sql, p(name), f"takeover attempt by {name} [{cite}]")
            wins.append((name, n))
        return f
    # ten concurrent retries, run as five pairs (two connections at a time, same barrier semantics)
    for i in range(0, 10, 2):
        run_pair(ctx, rec, mk(f"pod-{i}"), mk(f"pod-{i+1}"), names=(f"r{i}", f"r{i+1}"))
    took = [w for w in wins if w[1] == 1]
    att = scalar(ctx, "SELECT attempt_no FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")
    chk(rec, ob, "ten-retries-exactly-one-takeover", "production",
        "exactly one retry moves the attempt to 2; the other nine write nothing; still one ledger row",
        f"winners={took} attempt_no={att} ledger rows={rows}",
        len(took) == 1 and att == 2 and rows == 1)

    n, _ = rowcount_of(ctx, rec, sql, dict(p("x"), expected=2),
                       "a further takeover before the NEW deadline")
    chk(rec, ob, "not-takeable-before-the-new-deadline", "production", "0 rows", f"{n} row(s)", n == 0)

    # The attempt cap on its own request id: every takeover is issued with a NEGATIVE $pending_ttl,
    # so each new attempt_deadline is already past and the next takeover is admissible.
    rid2 = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid2, ttl=-60))
    q = lambda exp: {"request_id": rid2, "agent": A, "epoch": 1, "replica": "pod-a",
                     "pending_ttl": timedelta(seconds=-60), "expected": exp, "takeover_attempts": 3}
    rowcount_of(ctx, rec, sql, q(1), "takeover 1 → 2 (deadline already past)")
    rowcount_of(ctx, rec, sql, q(2), "takeover 2 → 3 (deadline already past)")
    n2, _ = rowcount_of(ctx, rec, sql, q(3), "takeover at attempt_no = takeover_attempts")
    att2 = scalar(ctx, "SELECT attempt_no FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid2,))
    chk(rec, ob, "attempt-cap-bounds-the-takeovers", "production",
        "lg.attempt_no < $takeover_attempts refuses the takeover once the cap is reached",
        f"{n2} row(s), attempt_no still {att2}", n2 == 0 and att2 == 3)


def complete_steps(ctx, rid, epoch=1, mine=1, replica="pod-a", **kw):
    p3 = grant_params(ctx, rid, epoch, mine, replica, **kw)
    fence = {"request_id": rid, "agent": p3["agent"], "epoch": epoch, "replica": replica, "mine": mine}
    return [(STEP3A, fence, "step 3a fence"),
            (STEP3B, fence, "step 3b ownership lock"),
            (STEP3C, p3, "step 3c project + complete")]


@leg("(L4)", "fenced-completion-writes-nothing")
def L4(ctx, rec):
    ob = "(L4)"
    A, B = ctx.fx["agentA"], ctx.fx["agentB"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))
    ctx.session_for(A, "pod-b", 2)                      # re-claimed while the completion was in flight
    counts = lease_txn(ctx, rec, complete_steps(ctx, rid, epoch=1))
    st = scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    att = scalar(ctx, "SELECT attempt_no FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    comp = scalar(ctx, "SELECT completed_at FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    leases = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease")
    chk(rec, ob, "stale-completion-writes-nothing", "production",
        "3a (the fence) returns 0 rows and 3c writes NO projection and NO ledger change: the row is "
        "still PENDING at its original attempt_no with completed_at NULL, and agent_lease is empty",
        f"3a={counts[0][0]} 3b={counts[1][0]} (3b is the ownership lock on the ledger row, not the "
        f"fence) 3c={counts[2][0]} state={st} attempt_no={att} completed_at={comp} agent_lease rows={leases}",
        counts[0][0] == 0 and counts[2][0] == 0 and st == "PENDING" and att == 1
        and comp is None and leases == 0)

    # S1/F1 — the cross-agent authorization leg, at EQUAL and at DIFFERENT epochs
    for label, epB in (("equal epochs", 2), ("different epochs", 3)):
        ctx.reset()
        A, B = ctx.fx["agentA"], ctx.fx["agentB"]
        ctx.session_for(A, "pod-a", 2)
        ctx.session_for(B, "pod-a", epB)
        ridB = uuid.uuid4()
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.agent_lease_grant (request_id, tenant_id, agent_id,
                            fencing_epoch, kind, request_fingerprint, attempt_owner, attempt_deadline)
                           VALUES (%s,%s,%s,%s,'renew','fp','pod-a', now() + interval '1 hour')""",
                        (ridB, ctx.fx["tenant"], B, epB))
        steps = complete_steps(ctx, ridB, epoch=2)      # A's session, A's epoch, B's request id
        counts = lease_txn(ctx, rec, steps)
        st = scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (ridB,))
        leases = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease")
        chk(rec, ob, f"cross-agent-completion-{label}", "production",
            "3b returns 0 rows; nothing is projected and B's ledger row is untouched",
            f"3b={counts[1][0]} 3c={counts[2][0]} state={st} agent_lease rows={leases}",
            counts[1][0] == 0 and counts[2][0] == 0 and st == "PENDING" and leases == 0)

    # planted: 3b without its agent predicate, on an EQUAL-epoch fixture so the agent predicate is
    # the only thing standing between A's session and B's ledger row
    ctx.reset()
    A, B = ctx.fx["agentA"], ctx.fx["agentB"]
    ctx.session_for(A, "pod-a", 2)
    ctx.session_for(B, "pod-a", 2)
    ridB = uuid.uuid4()
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO sm_data.agent_lease_grant (request_id, tenant_id, agent_id,
                        fencing_epoch, kind, request_fingerprint, attempt_owner, attempt_deadline)
                       VALUES (%s,%s,%s,2,'renew','fp','pod-a', now() + interval '1 hour')""",
                    (ridB, ctx.fx["tenant"], B))
    sql3b, cite3b = ctx.stmt(*STEP3B)
    fence = {"request_id": ridB, "agent": A, "epoch": 2, "replica": "pod-a", "mine": 1}
    n0, _ = rowcount_of(ctx, rec, sql3b, fence, f"production 3b, A's session against B's request [{cite3b}]")
    chk(rec, ob, "cross-agent-completion-equal-epochs-3b", "production",
        "the extracted 3b returns 0 rows", f"{n0} row(s)", n0 == 0)
    planted = remove(rec, sql3b, "\n   AND agent_id      = $agent                                      ",
                     "3b's own-agent predicate — the S1/F1 authorization fence")
    n, _ = rowcount_of(ctx, rec, planted, fence, f"PLANTED 3b without the agent predicate [{cite3b}]")
    chk(rec, ob, "cross-agent-completion-equal-epochs-3b", "planted",
        "without 3b's agent predicate A's session locks B's request row (the fence is real)",
        f"{n} row(s) returned", n == 1)
    n2, _ = rowcount_of(ctx, rec, sql3b, fence, "RESTORED 3b, verbatim from the extracted block")
    chk(rec, ob, "cross-agent-completion-equal-epochs-3b", "restored",
        "the extracted 3b returns 0 rows again", f"{n2} row(s)", n2 == 0)

    # S1/F3 — a completion from a replica that is not attempt_owner
    ctx.reset()
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))
    ctx.session_for(A, "pod-b", 1)   # same epoch, different owner replica: not the attempt owner
    counts = lease_txn(ctx, rec, complete_steps(ctx, rid, epoch=1, replica="pod-b"))
    chk(rec, ob, "completion-from-a-non-owner", "production",
        "3b returns 0 rows for a caller that is not attempt_owner", f"3b={counts[1][0]}",
        counts[1][0] == 0)


@leg("(L5)", "projection-order")
def L5(ctx, rec):
    ob = "(L5)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    r_new, r_old = uuid.uuid4(), uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, r_new))
    lease_txn(ctx, rec, reserve_steps(ctx, r_old))
    lease_txn(ctx, rec, complete_steps(ctx, r_new, issued=BASE))
    lease_txn(ctx, rec, complete_steps(ctx, r_old, issued=BASE - timedelta(hours=1)))
    o_new = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (r_new,))
    o_old = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (r_old,))
    which = scalar(ctx, "SELECT grant_request_id FROM sm_data.agent_lease")
    chk(rec, ob, "older-grant-arriving-second-is-SUPERSEDED_ON_ARRIVAL", "production",
        "the newer grant stays projected; the older completes SUPERSEDED_ON_ARRIVAL",
        f"newer={o_new} older={o_old} projected={'newer' if which == r_new else which}",
        o_new == "PROJECTED" and o_old == "SUPERSEDED_ON_ARRIVAL" and which == r_new)

    # equal issued_at: grant_seq breaks the tie
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    r1, r2 = uuid.uuid4(), uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, r1))
    lease_txn(ctx, rec, reserve_steps(ctx, r2))
    same = BASE
    lease_txn(ctx, rec, complete_steps(ctx, r2, issued=same))   # higher grant_seq first
    lease_txn(ctx, rec, complete_steps(ctx, r1, issued=same))
    o1 = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (r1,))
    o2 = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (r2,))
    chk(rec, ob, "equal-issued_at-resolved-by-grant_seq", "production",
        "exactly one PROJECTED and one SUPERSEDED_ON_ARRIVAL",
        f"r1(seq low)={o1} r2(seq high)={o2}",
        sorted([o1, o2]) == ["PROJECTED", "SUPERSEDED_ON_ARRIVAL"] and o2 == "PROJECTED")

    # an invalid grant
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))
    lease_txn(ctx, rec, complete_steps(ctx, rid, valid=False))
    st = scalar(ctx, "SELECT state || '/' || outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    leases = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease")
    jti = scalar(ctx, "SELECT lease_jti FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    chk(rec, ob, "invalid-grant-lands-FAILED-with-nothing-projected", "production",
        "FAILED/GRANT_INVALID, no projection, and failed_has_no_grant leaves no grant material",
        f"{st}; agent_lease rows={leases}; lease_jti={jti}",
        st == "FAILED/GRANT_INVALID" and leases == 0 and jti is None)


@leg("(L6)", "terminal_once-per-branch-SQLSTATE")
def L6(ctx, rec):
    ob = "(L6)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)

    def fresh_pending(ttl=60):
        r = uuid.uuid4()
        lease_txn(ctx, rec, reserve_steps(ctx, r, ttl=ttl))
        return r

    # positives
    r = fresh_pending()
    lease_txn(ctx, rec, complete_steps(ctx, r))
    chk(rec, ob, "positive-completion-PENDING-to-COMPLETED", "production", "the completion branch is admitted",
        scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (r,)),
        scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (r,)) == "COMPLETED")
    r = fresh_pending()
    lease_txn(ctx, rec, complete_steps(ctx, r, valid=False))
    chk(rec, ob, "positive-completion-PENDING-to-FAILED", "production", "the completion branch is admitted",
        scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (r,)),
        scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (r,)) == "FAILED")
    r_take = fresh_pending(ttl=-60)
    sqlT, _ = ctx.stmt(*TAKEOVER)
    n, _ = rowcount_of(ctx, rec, sqlT, {"request_id": r_take, "agent": A, "epoch": 1, "replica": "pod-a",
                                        "pending_ttl": timedelta(seconds=60), "expected": 1,
                                        "takeover_attempts": 3}, "positive: takeover branch")
    chk(rec, ob, "positive-takeover-branch", "production", "attempt_no + 1 past the deadline is admitted",
        f"{n} row(s)", n == 1)
    r_tr = fresh_pending()
    ctx.session_for(A, "pod-b", 2)
    sqlA, _ = ctx.stmt(*ADOPT)
    n, _ = rowcount_of(ctx, rec, sqlA, {"request_id": r_tr, "agent": A, "epoch": 2, "replica": "pod-b",
                                        "pending_ttl": timedelta(seconds=60)},
                       "positive: epoch-transfer branch")
    chk(rec, ob, "positive-epoch-transfer-branch", "production",
        "a strictly higher epoch at the same attempt_no is admitted", f"{n} row(s)", n == 1)

    ctx.session_for(A, "pod-a", 1)
    terminal = fresh_pending()
    lease_txn(ctx, rec, complete_steps(ctx, terminal))
    pending = fresh_pending()

    def upd(sql, params):
        def f():
            with ctx.owner() as c, c.cursor() as cur:
                run_stmt(rec, cur, sql, params, "planted UPDATE")
        return f

    plants = [
        ("AGW11-write-to-a-COMPLETED-row", "AGW11",
         "UPDATE sm_data.agent_lease_grant SET outcome='PROJECTED' WHERE request_id=%(r)s", {"r": terminal}),
        ("AGW10-fingerprint-rewrite", "AGW10",
         "UPDATE sm_data.agent_lease_grant SET request_fingerprint='zz' WHERE request_id=%(r)s", {"r": pending}),
        ("AGW10-identity-column-write", "AGW10",
         "UPDATE sm_data.agent_lease_grant SET kind='issue' WHERE request_id=%(r)s", {"r": pending}),
        ("AGW12-completion-moves-attempt_no", "AGW12",
         "UPDATE sm_data.agent_lease_grant SET state='FAILED', outcome='TIMEOUT', completed_at=now(), "
         "attempt_no=attempt_no+1 WHERE request_id=%(r)s", {"r": pending}),
        ("AGW12-completion-moves-attempt_owner", "AGW12",
         "UPDATE sm_data.agent_lease_grant SET state='FAILED', outcome='TIMEOUT', completed_at=now(), "
         "attempt_owner='pod-z' WHERE request_id=%(r)s", {"r": pending}),
        ("AGW12-completion-moves-fencing_epoch", "AGW12",
         "UPDATE sm_data.agent_lease_grant SET state='FAILED', outcome='TIMEOUT', completed_at=now(), "
         "fencing_epoch=fencing_epoch+1 WHERE request_id=%(r)s", {"r": pending}),
        ("AGW13-takeover-before-the-deadline", "AGW13",
         "UPDATE sm_data.agent_lease_grant SET attempt_no=attempt_no+1, attempt_deadline=now()+interval '1 h' "
         "WHERE request_id=%(r)s", {"r": pending}),
        ("AGW13-takeover-lowers-the-epoch", "AGW13",
         "UPDATE sm_data.agent_lease_grant SET attempt_no=attempt_no+1, fencing_epoch=fencing_epoch-1 "
         "WHERE request_id=%(r)s", {"r": pending}),
        ("AGW14-transfer-that-preserves-the-epoch", "AGW14",
         "UPDATE sm_data.agent_lease_grant SET attempt_owner='pod-q' WHERE request_id=%(r)s", {"r": pending}),
        ("AGW14-transfer-that-lowers-the-epoch", "AGW14",
         "UPDATE sm_data.agent_lease_grant SET fencing_epoch=fencing_epoch-1 WHERE request_id=%(r)s",
         {"r": pending}),
        ("AGW15-attempt_no-moves-by-two", "AGW15",
         "UPDATE sm_data.agent_lease_grant SET attempt_no=attempt_no+2 WHERE request_id=%(r)s", {"r": pending}),
    ]
    for name, code, sql, params in plants:
        refused(rec, ob, name, "production", code, upd(sql, params), "the named branch raises")

    # one plant per writable result column, on BOTH PENDING → PENDING branches
    for col, val in (("outcome", "'PROJECTED'"), ("lease_jti", "'J'"), ("issued_at", "now()"),
                     ("expires_at", "now()"), ("grant_payload", "'{}'::jsonb"), ("completed_at", "now()")):
        refused(rec, ob, f"AGW13-takeover-writes-{col}", "production", "AGW13",
                upd(f"UPDATE sm_data.agent_lease_grant SET attempt_no=attempt_no+1, "
                    f"attempt_deadline=now()-interval '1 s', {col}={val} WHERE request_id=%(r)s",
                    {"r": pending}), "a takeover may write no result column")
        refused(rec, ob, f"AGW14-transfer-writes-{col}", "production", "AGW14",
                upd(f"UPDATE sm_data.agent_lease_grant SET fencing_epoch=fencing_epoch+1, {col}={val} "
                    f"WHERE request_id=%(r)s", {"r": pending}), "a transfer may write no result column")

    # the CHECKs, asserted independently of the trigger
    rec.say("--- PLANTED (DDL): ALTER TABLE sm_data.agent_lease_grant DISABLE TRIGGER "
            "agent_lease_grant_terminal_once — so pending_has_no_result / failed_has_no_grant "
            "are asserted by the CHECK alone")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("ALTER TABLE sm_data.agent_lease_grant DISABLE TRIGGER agent_lease_grant_terminal_once")
    for col, val in (("lease_jti", "'J'"), ("issued_at", "now()"), ("expires_at", "now()"),
                     ("grant_payload", "'{}'::jsonb"), ("outcome", "'PROJECTED'")):
        refused(rec, ob, f"23514-pending_has_no_result-{col}", "planted", "23514",
                upd(f"UPDATE sm_data.agent_lease_grant SET {col}={val} WHERE request_id=%(r)s",
                    {"r": pending}),
                "with the trigger disabled the CHECK still refuses a result on a PENDING row")
    for col, val in (("lease_jti", "'J'"), ("issued_at", "now()"), ("expires_at", "now()"),
                     ("grant_payload", "'{}'::jsonb")):
        refused(rec, ob, f"23514-failed_has_no_grant-{col}", "planted", "23514",
                upd(f"UPDATE sm_data.agent_lease_grant SET state='FAILED', outcome='TIMEOUT', "
                    f"completed_at=now(), {col}={val} WHERE request_id=%(r)s", {"r": pending}),
                "with the trigger disabled the CHECK still refuses grant material on a FAILED row")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_lease_grant SET outcome='PROJECTED' WHERE request_id=%s", (terminal,))
        moved = cur.rowcount
    chk(rec, ob, "AGW11-write-to-a-COMPLETED-row", "planted",
        "with the trigger disabled a terminal row is writable (the trigger is the artifact)",
        f"{moved} row(s) updated", moved == 1)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("ALTER TABLE sm_data.agent_lease_grant ENABLE TRIGGER agent_lease_grant_terminal_once")
    refused(rec, ob, "AGW11-write-to-a-COMPLETED-row", "restored", "AGW11",
            upd("UPDATE sm_data.agent_lease_grant SET outcome='PROJECTED' WHERE request_id=%(r)s",
                {"r": terminal}), "the re-enabled trigger refuses it again")


# --- registration: Phase A reads (§4.1) then Phase B writes (§4.2) ---------
A0, A1, A2, A3, A4, A5 = [("sketch", "4.1", 1, i) for i in range(6)]
A7B = ("sketch", "4.1", 2, 0)
B1, B2_INS, B2_CAP, B3_INC, B3_HAND, B4, B5 = [("sketch", "4.2", 1, i) for i in range(7)]
PHASE_B_ALL = [(B1, "B1 handoff ownership"), (B2_INS, "B2 mint / un-retire"),
               (B2_CAP, "B2 cap counter"), (B3_INC, "B3 liveness refresh, incumbent form"),
               (B3_HAND, "B3 liveness refresh, handoff form"), (B4, "B4 retire the activation"),
               (B5, "B5 insert the new activation")]


def reg_params(ctx, agent=None, epoch=1, replica="pod-a", replica_ref="pod-a", replica_id=None,
               sequence=1, incarnation=None, active=None, new_uuid=None):
    f = ctx.fx
    return {"tenant": f["tenant"], "binding": f["binding"], "agent": agent or f["agentA"],
            "epoch": epoch, "replica": replica, "replica_ref": replica_ref,
            "replica_id": replica_id, "sequence": sequence,
            "incarnation": incarnation or str(uuid.uuid4()),
            "active_incarnation_id": active, "new_uuid": new_uuid or uuid.uuid4()}


def phase_a(ctx, rec, p, conn, upto=6):
    """§4.1's Phase A reads in one transaction, in documented order.

    ⚠️ The Python names A0…A5 are STATEMENT INDICES 0…5 of sketch §4.1 block #1, and they do NOT
    line up with the document's step labels past index 2: §4.1's `-- A2.` comment covers TWO
    statements — the cap row's `INSERT … ON CONFLICT` materialize and its `SELECT … FOR UPDATE` —
    so index 3 is **A2's lock**, index 4 is **A3** (the stable read) and index 5 is **A4** (the
    active-incarnation read). The labels below name the DOCUMENT's step, which is what the log and
    the leg text must say; the constants keep their index meaning.
    """
    out = {}
    seq = ((A0, "A0 identity lock"), (A1, "A1 session fence"), (A2, "A2 cap row materialize"),
           (A3, "A2 cap row FOR UPDATE"), (A4, "A3 stable read"), (A5, "A4 activation read"))
    with conn.cursor() as cur:
        for spec, name in seq[:upto]:
            sql, cite = ctx.stmt(*spec)
            if spec == A5 and not p.get("replica_id"):
                out[name] = (0, [])
                continue
            n, rows = run_stmt(rec, cur, sql, p, f"{name} [{cite}]")
            out[name] = (n, rows)
    return out


@leg("(L7)", "issue-once-per-identity")
def L7(ctx, rec):
    ob = "(L7)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    ctx.lease(A)                                     # this identity already holds a projected lease
    (*_, (n, _)) = lease_txn(ctx, rec, reserve_steps(ctx, uuid.uuid4(), kind="issue"))
    chk(rec, ob, "issue-refused-when-a-lease-row-exists", "production",
        "step 1's NOT EXISTS clause reserves nothing for a kind='issue' request",
        f"{n} row(s)", n == 0)

    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("DELETE FROM sm_data.agent_lease")
        cur.execute("""INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_jti, cert_thumbprint,
                target_id, binding_ids, capabilities, agent_revocation_epoch, fleet_revocation_epoch,
                audience, issued_at, expires_at, ceiling_preflight, ceiling_apply, ceiling_restart,
                ceiling_stop, admission_margin, grant_request_id, grant_seq)
            VALUES (%s,%s,%s,%s,%s,%s,%s,0,0,'dp', now() - interval '2 hours', now() - interval '1 hour',
                    interval '1 minute', interval '1 minute', interval '1 minute', interval '1 minute',
                    interval '1 minute', %s, 1)""",
                    (ctx.fx["tenant"], A, str(uuid.uuid4()), ctx.fx["thumbA"], ctx.fx["target"],
                     [ctx.fx["binding"]], ["apply-config"], uuid.uuid4()))
    (*_, (n2, _)) = lease_txn(ctx, rec, reserve_steps(ctx, uuid.uuid4(), kind="issue"))
    chk(rec, ob, "issue-refused-even-when-the-lease-has-EXPIRED", "production",
        "the row's EXISTENCE is the arbiter, not its window", f"{n2} row(s)", n2 == 0)

    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    out = {}

    def mk(nm):
        def f(side):
            side.wait("both first-issue requests ready")
            try:
                for spec, params, label in reserve_steps(ctx, uuid.uuid4(), kind="issue"):
                    sql, cite = ctx.stmt(*spec)
                    side.run(sql, params, f"{nm}: {label} [{cite}]")
                out[nm] = "ok"
            except psycopg.Error as e:
                out[nm] = e.sqlstate
                raise
        return f

    ra, rb, ms = run_pair(ctx, rec, mk("I1"), mk("I2"), names=("I1", "I2"))
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant WHERE kind='issue'")
    commits = [x["outcome"] for x in (ra, rb)].count("commit")
    chk(rec, ob, "two-concurrent-first-issues-with-different-ids", "production",
        "agent_lease_grant_issue_once admits exactly one; the other is refused 23505",
        f"commits={commits} issue rows={rows} outcomes={out} "
        f"sqlstates={[ra['sqlstate'], rb['sqlstate']]}",
        commits == 1 and rows == 1 and "23505" in (ra["sqlstate"], rb["sqlstate"]), ms=ms)

    # the retention horizon at which the index stops arbitrating — MEASURED, as (L7) requires
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("DELETE FROM sm_data.agent_lease_grant")
        cur.execute("INSERT INTO sm_data.agent_request_tombstone (request_id, expired_at) "
                    "VALUES (%s, now())", (uuid.uuid4(),))
    (*_, (n3, _)) = lease_txn(ctx, rec, reserve_steps(ctx, uuid.uuid4(), kind="issue"))
    chk(rec, ob, "post-retention-a-second-issue-IS-admitted", "production",
        "MEASURED, and the design says so (§12 limit 19a): once retention has deleted the FAILED "
        "issue row and no agent_lease row was ever projected, a NEW request id reserves a second "
        "nonce-free issue — the tombstone is keyed by the OLD id and does not match",
        f"{n3} row(s) reserved", n3 == 1)


@leg("(L8)", "current-writes-nothing")
def L8(ctx, rec):
    ob = "(L8)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    ctx.lease(A)
    before = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")
    with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
        n, rows = run_stmt(rec, cur, "SELECT lease_jti, expires_at > now() AS live, renewed_at "
                                     "FROM sm_data.agent_lease WHERE agent_id = %(a)s", {"a": A},
                           "§3.0 `current` — a READ of the projection, no ledger row")
    after = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")
    ren = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease WHERE renewed_at IS NOT NULL")
    chk(rec, ob, "current-adds-no-ledger-row", "production",
        "`current` returns the projection and the ledger is byte-identical",
        f"rows={n} ledger before={before} after={after}", n == 1 and before == after == 0)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("DELETE FROM sm_data.agent_lease")
    with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
        n2, _ = run_stmt(rec, cur, "SELECT 1 FROM sm_data.agent_lease WHERE agent_id = %(a)s", {"a": A},
                         "`current` with no projection → NONE")
    chk(rec, ob, "current-on-a-missing-projection", "production", "0 rows and nothing written",
        f"{n2} row(s), ledger rows={scalar(ctx, 'SELECT count(*) FROM sm_data.agent_lease_grant')}",
        n2 == 0)


@leg("(L9)", "advisory-lock-on-the-ledger")
def L9(ctx, rec):
    ob = "(L9)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    # HARNESS-ONLY fixture: a ledger row that is ALREADY past the retention horizon. It is inserted
    # old rather than aged, because requested_at is an identity column and
    # agent_lease_grant_terminal_once refuses to move it (AGW10) — the trigger behaving as §2.1 says.
    rid = uuid.uuid4()
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO sm_data.agent_lease_grant (request_id, tenant_id, agent_id,
                        fencing_epoch, kind, request_fingerprint, attempt_owner, attempt_deadline,
                        state, outcome, completed_at, requested_at)
                       VALUES (%s,%s,%s,1,'renew','fp','pod-a', now() - interval '89 days',
                               'FAILED','TIMEOUT', now() - interval '89 days', now() - interval '90 days')""",
                    (rid, ctx.fx["tenant"], A))
    batch, cb = ctx.stmt("sketch", "5.3", 1, 0)
    dele, cd = ctx.stmt("sketch", "5.3", 1, 1)
    key, ck = ctx.stmt("sketch", "6", 1, 0)
    order = []

    def JOB(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        n, rows = side.run(batch, {"ledger_retention": timedelta(days=30), "retention_batch": 10},
                           f"§5.3 step 1: batch + per-key advisory locks [{cb}]")
        ids = [r[0] for r in rows]
        order.append(f"job-locked-{len(ids)}")
        side.wait("job holds the request keys")
        side.run(dele, {"ids": ids, "ledger_retention": timedelta(days=30)},
                 f"§5.3 step 2: predicated delete + tombstone [{cd}]")
        time.sleep(0.4)
        order.append("job-commits")

    def RETRY(side):
        side.wait("job holds the request keys")
        side.run(key, {"request_id": rid}, f"the retry's step 0a on the same key [{ck}]")
        order.append("retry-got-key")
        n, rows = side.run("SELECT count(*) FROM sm_data.agent_lease_grant WHERE request_id = %(r)s",
                           {"r": rid}, "what the retry then sees in the ledger")
        order.append(f"retry-sees-{rows[0][0]}")
        n2, rows2 = side.run("SELECT count(*) FROM sm_data.agent_request_tombstone WHERE request_id = %(r)s",
                             {"r": rid}, "and in the tombstone table")
        order.append(f"retry-tombstone-{rows2[0][0]}")

    ra, rb, ms = run_pair(ctx, rec, JOB, RETRY, role_a="sm_dp_migrator")
    good = (ra["outcome"] == rb["outcome"] == "commit"
            and order.index("job-commits") < order.index("retry-got-key")
            and "retry-sees-0" in order and "retry-tombstone-1" in order)
    chk(rec, ob, "retry-waits-and-then-sees-REQUEST_EXPIRED", "production",
        "the retry blocks on the same request key until the job commits, then finds no ledger row "
        "and exactly one tombstone — REQUEST_EXPIRED, never a second ledger row",
        f"order={order}", good, ms=ms)
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")
    chk(rec, ob, "never-a-second-ledger-row", "production", "the ledger holds no row for that id",
        f"{rows} row(s) in agent_lease_grant", rows == 0)


@leg("(L10)", "the-sequence")
def L10(ctx, rec):
    ob = "(L10)"
    with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
        n, rows = run_stmt(rec, cur, "SELECT nextval('sm_data.agent_lease_grant_seq')", None,
                           "nextval as sm_dp_runtime, WITH the USAGE grant of §7")
    chk(rec, ob, "nextval-succeeds-with-the-grant", "production", "a value is returned",
        f"nextval = {rows[0][0]}", n == 1)

    rec.say("--- PLANTED (DCL): REVOKE USAGE ON SEQUENCE sm_data.agent_lease_grant_seq FROM sm_dp_runtime")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("REVOKE USAGE ON SEQUENCE sm_data.agent_lease_grant_seq FROM sm_dp_runtime")

    def nv():
        with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
            cur.execute("SELECT nextval('sm_data.agent_lease_grant_seq')")
    refused(rec, ob, "nextval-without-the-grant", "planted", "42501", nv,
            "an INSERT grant alone cannot evaluate nextval() — the F9/R7 finding")
    ctx.rebuild()
    with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
        n2, rows2 = run_stmt(rec, cur, "SELECT nextval('sm_data.agent_lease_grant_seq')", None,
                             "nextval again after the extracted §7 DCL was re-applied")
    chk(rec, ob, "nextval-succeeds-with-the-grant", "restored", "a value is returned",
        f"nextval = {rows2[0][0]}", n2 == 1)

    rec.say("--- PLANTED (DDL): DROP TABLE sm_data.agent_lease_grant — the OWNED BY must take the sequence with it")
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("DROP TABLE sm_data.agent_lease_grant CASCADE")
        cur.execute("""SELECT count(*) FROM pg_class WHERE relkind='S'
                        AND relname='agent_lease_grant_seq'""")
        left = cur.fetchone()[0]
    chk(rec, ob, "OWNED-BY-drops-the-sequence", "planted",
        "dropping the table drops the sequence", f"{left} sequence(s) left", left == 0)
    ctx.rebuild()
    seq = scalar(ctx, "SELECT count(*) FROM pg_class WHERE relkind='S' AND relname='agent_lease_grant_seq'")
    chk(rec, ob, "OWNED-BY-drops-the-sequence", "restored", "the re-applied DDL re-creates it",
        f"{seq} sequence(s)", seq == 1)


def seed_replica(ctx, ref="pod-old", agent=None, seq=1, with_incarnation=True, retired=False):
    """HARNESS-ONLY: an already-registered replica (and optionally its active incarnation)."""
    f = ctx.fx
    rid, iid = uuid.uuid4(), uuid.uuid4()
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO sm_data.agent_replica (tenant_id, binding_id, replica_id, replica_ref,
                        registered_at, registered_by_agent, registered_by_report, reporter_agent_id,
                        reporter_since)
                       VALUES (%s,%s,%s,%s, now() - interval '10 minutes', %s,%s,%s,
                               now() - interval '10 minutes')""",
                    (f["tenant"], f["binding"], rid, ref, agent or f["agentA"], seq, agent or f["agentA"]))
        if with_incarnation:
            # a RETIRED incarnation is seeded already in the past, so §5.1's idleness predicate
            # (max(retired_at) < now() - $replica_idle_retirement) can see it
            cur.execute("""INSERT INTO sm_data.agent_replica_incarnation (replica_id, incarnation_id,
                            incarnation, registered_at, registered_by_report, registered_by_agent,
                            last_seen_report, last_seen_by_agent, last_seen_at, retired_at)
                           VALUES (%s,%s,%s, now() - interval '10 minutes', %s,%s,%s,%s,
                                   now() - interval '9 minutes',
                                   CASE WHEN %s THEN now() - interval '9 minutes' END)""",
                        (rid, iid, str(uuid.uuid4()), seq, agent or f["agentA"], seq,
                         agent or f["agentA"], retired))
        cur.execute("""INSERT INTO sm_data.agent_replica_cap (tenant_id, binding_id, active_replicas)
                       VALUES (%s,%s,1) ON CONFLICT (tenant_id, binding_id)
                       DO UPDATE SET active_replicas = sm_data.agent_replica_cap.active_replicas + 1""",
                    (f["tenant"], f["binding"]))
    return rid, iid


def seed_series(ctx, tenant, source_key):
    """HARNESS-ONLY fixture rows in V001's `placement` and `series`.

    `series` has `FOREIGN KEY (tenant_id, placement_id) REFERENCES placement`, so a series row
    cannot exist without a placement row — the fixture inserts both. `series_id` is
    `GENERATED BY DEFAULT AS IDENTITY`, so omitting it lets the implicit sequence assign one
    (PG 14 docs, sql-createtable, "GENERATED … AS IDENTITY"; verified 2026-09-11). Every other
    column is NOT NULL in V001, so every one is supplied. Nothing here is design material.
    """
    pl, inst = uuid.uuid4(), uuid.uuid4()
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id)
                       VALUES (%s,%s,%s,%s)""", (tenant, pl, inst, uuid.uuid4()))
        cur.execute("""INSERT INTO sm_data.series (tenant_id, instance_id, placement_id, solution,
                        metric_name, metric_type, unit, package_version, source_key, labels_fingerprint)
                       VALUES (%s,%s,%s,'cache','hits','counter','1','1.0',%s,'fp')
                       RETURNING series_id""", (tenant, inst, pl, source_key))
        return cur.fetchone()[0]


@leg("(L11)", "registration-cardinality")
def L11(ctx, rec):
    ob = "(L11)"
    CAP = 2                       # max_replicas_per_binding, the engine-side configuration value
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    ctx.session_for(ctx.fx["agentB"], "pod-a", 1)      # two DIFFERENT agents contend for the cap
    seed_replica(ctx, "pod-old")                       # the binding is now at CAP − 1
    ins, cins = ctx.stmt(*B2_INS)
    cap, ccap = ctx.stmt(*B2_CAP)
    a3, ca3 = ctx.stmt(*A3)
    landed, refused_at_cap = [], []

    def mk(ref, agent):
        def f(side):
            p = reg_params(ctx, agent=agent, replica_ref=ref, sequence=2)
            phase_a(ctx, rec, p, side.conn, upto=3)      # A0, A1, A2 materialize — no cap lock yet
            side.wait("both registrations are through A2")
            n, rows = side.run(a3, p, f"A2 cap row FOR UPDATE [{ca3}]")
            active = rows[0][0]
            if active < CAP:
                side.run(ins, p, f"B2 mint [{cins}]")
                side.run(cap, p, f"B2 cap counter [{ccap}]")
                landed.append(ref)
            else:
                refused_at_cap.append((ref, active))
        return f

    ra, rb, ms = run_pair(ctx, rec, mk("pod-x", ctx.fx["agentA"]), mk("pod-y", ctx.fx["agentB"]),
                          names=("X", "Y"))
    active = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica WHERE retired_at IS NULL")
    chk(rec, ob, "two-concurrent-registrations-land-AT-the-cap", "production",
        "A2's binding-wide cap-row FOR UPDATE serialises them: exactly one lands, the other is "
        "REPLICA_CAP_EXCEEDED, and the counter stops AT the cap",
        f"landed={landed} refused={refused_at_cap} active_replicas={active} live replicas={rows} "
        f"A={ra['outcome']} B={rb['outcome']}",
        len(landed) == 1 and len(refused_at_cap) == 1 and active == CAP and rows == CAP, ms=ms)

    # planted: A3 without its FOR UPDATE
    ctx.reset(); A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1); ctx.session_for(ctx.fx["agentB"], "pod-a", 1)
    seed_replica(ctx, "pod-old")
    planted = remove(rec, a3, "\n FOR UPDATE", "A2's binding-wide cap lock")
    landed2 = []

    def mk2(ref, agent):
        def f(side):
            p = reg_params(ctx, agent=agent, replica_ref=ref, sequence=2)
            phase_a(ctx, rec, p, side.conn, upto=3)
            n, rows = side.run(planted, p, "PLANTED A3 without FOR UPDATE")
            side.wait("both registrations have read the UNLOCKED cap row")
            if rows[0][0] < CAP:
                side.run(ins, p, "B2 mint")
                side.run(cap, p, "B2 cap counter")
                landed2.append(ref)
        return f

    ra, rb, ms = run_pair(ctx, rec, mk2("pod-x", ctx.fx["agentA"]), mk2("pod-y", ctx.fx["agentB"]),
                          names=("X", "Y"))
    active2 = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    chk(rec, ob, "two-concurrent-registrations-land-AT-the-cap", "planted",
        "without A2's FOR UPDATE both readers see CAP − 1 and both register: the counter passes the cap",
        f"landed={landed2} active_replicas={active2} (cap={CAP})",
        len(landed2) == 2 and active2 == CAP + 1, ms=ms)

    ctx.rebuild()
    A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1); ctx.session_for(ctx.fx["agentB"], "pod-a", 1)
    seed_replica(ctx, "pod-old")
    landed.clear(); refused_at_cap.clear()
    ra, rb, ms = run_pair(ctx, rec, mk("pod-x", ctx.fx["agentA"]), mk("pod-y", ctx.fx["agentB"]),
                          names=("X", "Y"))
    active3 = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    chk(rec, ob, "two-concurrent-registrations-land-AT-the-cap", "restored",
        "the extracted A2 stops the counter at the cap again", f"active_replicas={active3}",
        active3 == CAP, ms=ms)


@leg("(L12)", "one-active-incarnation-and-order")
def L12(ctx, rec):
    ob = "(L12)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid, iid = seed_replica(ctx, "pod-a")

    def second_active():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.agent_replica_incarnation (replica_id, incarnation_id,
                            incarnation, registered_by_report, registered_by_agent, last_seen_report,
                            last_seen_by_agent) VALUES (%s,%s,%s,2,%s,2,%s)""",
                        (rid, uuid.uuid4(), str(uuid.uuid4()), A, A))
    refused(rec, ob, "23505-agent_replica_one_active", "production", "23505", second_active,
            "the partial unique index refuses a second ACTIVE incarnation of one replica")

    # a restart: B4 retires the incumbent and B5 mints one new activation, same replica_id
    p = reg_params(ctx, replica_id=rid, sequence=3, active=iid, new_uuid=uuid.uuid4())
    conn = ctx.conn("sm_dp_runtime")
    with conn.cursor() as cur:
        for spec, name in ((B4, "B4 retire"), (B5, "B5 insert")):
            sql, cite = ctx.stmt(*spec)
            run_stmt(rec, cur, sql, p, f"{name} [{cite}]")
    conn.commit(); conn.close()
    act = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica_incarnation WHERE replica_id=%s "
                      "AND retired_at IS NULL", (rid,))
    tot = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica_incarnation WHERE replica_id=%s", (rid,))
    same = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica WHERE replica_id=%s", (rid,))
    chk(rec, ob, "restart-reuses-replica_id-and-mints-one-incarnation", "production",
        "exactly one active incarnation, two in total, and the stable id is unchanged",
        f"active={act} total={tot} replica rows={same}", act == 1 and tot == 2 and same == 1)

    new_iid = scalar(ctx, "SELECT incarnation_id FROM sm_data.agent_replica_incarnation "
                          "WHERE replica_id=%s AND retired_at IS NULL", (rid,))
    b3, c3 = ctx.stmt(*B3_INC)
    p2 = reg_params(ctx, replica_id=rid, sequence=4, active=new_iid)
    n, _ = rowcount_of(ctx, rec, b3, p2, f"B3 incumbent refresh, sequence 4 [{c3}]")
    wm = scalar(ctx, "SELECT last_seen_report FROM sm_data.agent_replica_incarnation "
                     "WHERE incarnation_id=%s", (new_iid,))
    chk(rec, ob, "re-report-of-the-same-incarnation-is-a-watermark-move-only", "production",
        "1 row, the watermark advances, no row is minted",
        f"{n} row(s), last_seen_report={wm}, total incarnations="
        f"{scalar(ctx, 'SELECT count(*) FROM sm_data.agent_replica_incarnation')}",
        n == 1 and wm == 4)

    p3 = reg_params(ctx, replica_id=rid, sequence=2, active=new_iid)
    n2, _ = rowcount_of(ctx, rec, b3, p3, "B3 incumbent refresh with an OUT-OF-ORDER sequence 2")
    wm2 = scalar(ctx, "SELECT last_seen_report FROM sm_data.agent_replica_incarnation "
                      "WHERE incarnation_id=%s", (new_iid,))
    chk(rec, ob, "out-of-order-report-changes-nothing", "production",
        "0 rows and the watermark does not move backwards", f"{n2} row(s), last_seen_report={wm2}",
        n2 == 0 and wm2 == 4)

    planted = remove(rec, b3, " AND last_seen_report < $sequence", "B3's monotonic watermark predicate")
    n3, _ = rowcount_of(ctx, rec, planted, p3, "PLANTED B3 without the watermark predicate")
    wm3 = scalar(ctx, "SELECT last_seen_report FROM sm_data.agent_replica_incarnation "
                      "WHERE incarnation_id=%s", (new_iid,))
    chk(rec, ob, "out-of-order-report-changes-nothing", "planted",
        "without the predicate the stale report drags the watermark backwards",
        f"{n3} row(s), last_seen_report={wm3}", n3 == 1 and wm3 == 2)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_replica_incarnation SET last_seen_report = 4 "
                    "WHERE incarnation_id=%s", (new_iid,))
    n4, _ = rowcount_of(ctx, rec, b3, p3, "RESTORED B3, verbatim")
    chk(rec, ob, "out-of-order-report-changes-nothing", "restored", "0 rows again",
        f"{n4} row(s)", n4 == 0)


@leg("(L13)", "retirement-churn-and-retention")
def L13(ctx, rec):
    ob = "(L13)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid, iid = seed_replica(ctx, "pod-idle", retired=True)
    s1, cs1 = ctx.stmt("sketch", "5.1", 1, 0)
    s3, cs3 = ctx.stmt("sketch", "5.1", 1, 1)
    # rebuilt on every call: ctx.reset() mints fresh tenant/binding ids
    def sweep_p():
        return {"tenant": ctx.fx["tenant"], "binding": ctx.fx["binding"],
                "replica_idle_retirement": timedelta(minutes=1)}
    p = sweep_p()
    conn = ctx.conn("sm_dp_migrator")
    with conn.cursor() as cur:
        cur.execute("SET ROLE sm_dp_owner")
        run_stmt(rec, cur, s1, sweep_p(), f"§5.1 step 1: the binding's cap row FOR UPDATE [{cs1}]")
        n, _ = run_stmt(rec, cur, s3, sweep_p(), f"§5.1 step 3: the idle sweep [{cs3}]")
    conn.commit(); conn.close()
    retired = scalar(ctx, "SELECT retired_at IS NOT NULL FROM sm_data.agent_replica WHERE replica_id=%s", (rid,))
    active = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    chk(rec, ob, "idle-replica-retired-and-the-cap-released", "production",
        "the replica is retired and active_replicas is decremented in the SAME transaction",
        f"retired={retired} active_replicas={active}", retired is True and active == 0)

    # churn beyond the cap while the concurrent active count stays below it
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    CAP = 2
    ins, _ = ctx.stmt(*B2_INS)
    capsql, _ = ctx.stmt(*B2_CAP)
    landed = 0
    for i in range(2 * CAP):
        pr = reg_params(ctx, replica_ref=f"pod-{i}", sequence=i + 1)
        conn = ctx.conn("sm_dp_runtime")
        pa = phase_a(ctx, rec, pr, conn, upto=4)
        if pa["A2 cap row FOR UPDATE"][1][0][0] < CAP:
            with conn.cursor() as cur:
                run_stmt(rec, cur, ins, pr, f"B2 mint pod-{i}")
                run_stmt(rec, cur, capsql, pr, "B2 cap counter")
            landed += 1
        conn.commit(); conn.close()
        # retire it again so the next one has room
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("UPDATE sm_data.agent_replica SET retired_at = now() WHERE retired_at IS NULL")
            cur.execute("UPDATE sm_data.agent_replica_cap SET active_replicas = 0")
    chk(rec, ob, "churn-beyond-the-cap-succeeds", "production",
        f"{2 * CAP} distinct replica_refs register serially with retirement in between",
        f"{landed} registrations landed", landed == 2 * CAP)

    # the migrator's registry retention
    ctx.reset(); A = ctx.fx["agentA"]
    rid, iid = seed_replica(ctx, "pod-gone", retired=True)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_replica SET retired_at = now() - interval '400 days' "
                    "WHERE replica_id=%s", (rid,))
    d1, cd1 = ctx.stmt("sketch", "5.2", 1, 0)
    d2, cd2 = ctx.stmt("sketch", "5.2", 1, 1)
    conn = ctx.conn("sm_dp_migrator")
    with conn.cursor() as cur:
        cur.execute("SET ROLE sm_dp_owner")
        n1, _ = run_stmt(rec, cur, d1, {"registry_retention": timedelta(minutes=1)},
                         f"§5.2 delete retired incarnations [{cd1}]")
    conn.commit(); conn.close()
    chk(rec, ob, "retired-incarnations-pruned", "production",
        "the retired incarnation past registry_retention is deleted", f"{n1} row(s) deleted", n1 == 1)

    def prune_replica():
        """§5.2's stable-replica DELETE, verbatim, as the migrator. Returns the row count."""
        conn = ctx.conn("sm_dp_migrator")
        try:
            with conn.cursor() as cur:
                cur.execute("SET ROLE sm_dp_owner")
                n, _ = run_stmt(rec, cur, d2, {"registry_retention": timedelta(minutes=1)},
                                f"§5.2 delete the retired replica [{cd2}]")
            conn.commit()
            return n
        finally:
            conn.close()

    # --- the three series legs. sm_data.series IS defined — by V001, the repo's telemetry
    # --- migration, which the harness bootstraps from the committed file bytes (S11/F1).
    chk(rec, ob, "series-relation-is-present", "production",
        "sm_data.series exists, bootstrapped from V001__telemetry_schema.sql",
        "present: %s" % scalar(ctx, "SELECT count(*) FROM pg_tables WHERE schemaname='sm_data' "
                                    "AND tablename='series'"),
        scalar(ctx, "SELECT count(*) FROM pg_tables WHERE schemaname='sm_data' "
                    "AND tablename='series'") == 1)

    # leg 1 — nothing references the replica: the DELETE removes it.
    n2 = prune_replica()
    chk(rec, ob, "stable-delete-succeeds-with-no-referencing-series", "production",
        "with no incarnation row and no series row naming the replica, the DELETE removes it",
        f"{n2} row(s) deleted", n2 == 1)

    # leg 2 — a series row in the SAME tenant naming the replica withholds the delete.
    ctx.reset()
    rid2, _ = seed_replica(ctx, "pod-referenced", retired=True, with_incarnation=False)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_replica SET retired_at = now() - interval '400 days' "
                    "WHERE replica_id=%s", (rid2,))
    seed_series(ctx, ctx.fx["tenant"], str(rid2))
    n3 = prune_replica()
    still = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica WHERE replica_id=%s", (rid2,))
    chk(rec, ob, "stable-delete-withheld-by-a-same-tenant-series", "production",
        "a series row in the replica's OWN tenant with source_key = replica_id::text withholds "
        "the delete — a no-op, not an error",
        f"{n3} row(s) deleted, replica rows remaining={still}", n3 == 0 and still == 1)

    # leg 3 — the same source_key STRING under a DIFFERENT tenant must not influence the
    # decision. This is the leg that proves the `s.tenant_id = r.tenant_id` predicate (S11/F1d):
    # without it the foreign-tenant row would withhold the delete and stall retention for ever.
    ctx.reset()
    rid3, _ = seed_replica(ctx, "pod-foreign", retired=True, with_incarnation=False)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_replica SET retired_at = now() - interval '400 days' "
                    "WHERE replica_id=%s", (rid3,))
    seed_series(ctx, ctx.fx["tenant2"], str(rid3))       # SAME source_key string, OTHER tenant
    foreign = scalar(ctx, "SELECT count(*) FROM sm_data.series WHERE source_key=%s AND tenant_id<>%s",
                     (str(rid3), ctx.fx["tenant"]))
    n4 = prune_replica()
    gone = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica WHERE replica_id=%s", (rid3,))
    chk(rec, ob, "stable-delete-ignores-a-foreign-tenant-series", "production",
        "a series row carrying the same source_key in ANOTHER tenant does not withhold the "
        "delete — the predicate is tenant-scoped",
        f"foreign series rows={foreign}, {n4} row(s) deleted, replica rows remaining={gone}",
        foreign == 1 and n4 == 1 and gone == 0)

    # registration vs the migrator's sweep, same binding, under a barrier
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    seed_replica(ctx, "pod-idle", retired=True)
    ins, _ = ctx.stmt(*B2_INS)
    capsql, _ = ctx.stmt(*B2_CAP)

    def REG(side):
        pr = reg_params(ctx, replica_ref="pod-idle", sequence=9)   # the SAME replica the sweep retires
        phase_a(ctx, rec, pr, side.conn, upto=4)      # … through A3's cap lock
        side.wait("registration holds the cap row; the sweep may start")
        time.sleep(0.4)
        side.run(ins, pr, "B2 mint")
        side.run(capsql, pr, "B2 cap counter")

    def SWEEP(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        side.wait("registration holds the cap row; the sweep may start")
        side.run(s1, sweep_p(), "§5.1 step 1: the cap row FOR UPDATE")
        side.run(s3, sweep_p(), "§5.1 step 3: the idle sweep")

    ra, rb, ms = run_pair(ctx, rec, REG, SWEEP, role_b="sm_dp_migrator", names=("REG", "SWEEP"))
    chk(rec, ob, "registration-and-the-sweep-both-commit", "production",
        "both commit and neither raises 40P01 — both take the binding's cap row first (§7 clause 2)",
        f"REG={ra['outcome']}/{ra['sqlstate']} SWEEP={rb['outcome']}/{rb['sqlstate']}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit", ms=ms)

    # planted: the sweep without its cap-row lock (§5.1 step 1 removed from the sequence)
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    seed_replica(ctx, "pod-idle", retired=True)
    rec.say("--- PLANTED: the sweep runs §5.1 step 3 WITHOUT step 1 (the binding-wide cap-row lock)")

    def REG2(side):
        pr = reg_params(ctx, replica_ref="pod-idle", sequence=9)   # the SAME replica the sweep retires
        phase_a(ctx, rec, pr, side.conn, upto=4)      # holds the cap row
        side.wait("registration holds the cap row; the unlocked sweep may start")
        time.sleep(0.8)
        side.run(ins, pr, "B2 mint (wants agent_replica)")
        side.run(capsql, pr, "B2 cap counter")

    def SWEEP2(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        side.wait("registration holds the cap row; the unlocked sweep may start")
        side.run(s3, sweep_p(), "PLANTED §5.1 step 3 alone: agent_replica FOR UPDATE, then the cap row")

    ra, rb, ms = run_pair(ctx, rec, REG2, SWEEP2, role_b="sm_dp_migrator",
                          lock_timeout=LOCK_TIMEOUT_MS, names=("REG", "SWEEP"))
    states = [ra["sqlstate"], rb["sqlstate"]]
    chk(rec, ob, "registration-and-the-sweep-both-commit", "planted",
        "without the cap-row lock the two take agent_replica_cap and agent_replica in opposite "
        "orders and one aborts with 40P01",
        f"REG={ra['outcome']}/{ra['sqlstate']} SWEEP={rb['outcome']}/{rb['sqlstate']}",
        states.count("40P01") == 1, ms=ms, sqlstate="40P01")

    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    seed_replica(ctx, "pod-idle", retired=True)
    ra, rb, ms = run_pair(ctx, rec, REG, SWEEP, role_b="sm_dp_migrator", names=("REG", "SWEEP"))
    chk(rec, ob, "registration-and-the-sweep-both-commit", "restored",
        "with step 1 back, both commit again",
        f"REG={ra['outcome']} SWEEP={rb['outcome']}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit", ms=ms)

    # --- S11/F4: TWO CONCURRENT §5.1 SWEEPS on one binding. §5.1 is a safe, idempotent
    # --- maintenance operation, so this must hold whoever runs it — the second runner here is
    # --- deliberately sm_dp_runtime, the role the DCL cannot refuse it to (S10/F3, (L14)).
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    idle_rid, _ = seed_replica(ctx, "pod-idle", retired=True)       # no active incarnation → idle
    live_rid, _ = seed_replica(ctx, "pod-live", retired=False)      # active incarnation → not idle
    rec.say("--- TWO CONCURRENT §5.1 SWEEPS, one as the migrator and one as sm_dp_runtime, on the "
            "same binding: the binding's cap row FOR UPDATE (step 1) is the mutex, and step 3's "
            "predicates are re-evaluated under it by READ COMMITTED.")

    def SWEEP_M(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        side.wait("both sweeps ready")
        side.run(s1, sweep_p(), "§5.1 step 1 as the migrator: the cap row FOR UPDATE")
        time.sleep(0.4)
        side.run(s3, sweep_p(), "§5.1 step 3 as the migrator: the idle sweep")

    def SWEEP_R(side):
        side.wait("both sweeps ready")
        side.run(s1, sweep_p(), "§5.1 step 1 as sm_dp_runtime: the cap row FOR UPDATE")
        side.run(s3, sweep_p(), "§5.1 step 3 as sm_dp_runtime: the idle sweep")

    ra, rb, ms = run_pair(ctx, rec, SWEEP_M, SWEEP_R, role_a="sm_dp_migrator",
                          role_b="sm_dp_runtime", lock_timeout=LOCK_TIMEOUT_MS,
                          names=("SWEEP-migrator", "SWEEP-runtime"))
    idle_retired = scalar(ctx, "SELECT retired_at IS NOT NULL FROM sm_data.agent_replica "
                               "WHERE replica_id=%s", (idle_rid,))
    live_retired = scalar(ctx, "SELECT retired_at IS NOT NULL FROM sm_data.agent_replica "
                               "WHERE replica_id=%s", (live_rid,))
    active = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    recount = scalar(ctx, "SELECT count(*) FROM sm_data.agent_replica WHERE retired_at IS NULL")
    chk(rec, ob, "two-concurrent-§5.1-sweeps-are-safe", "production",
        "both commit with no 40P01; the idle replica is retired exactly once; the LIVE replica is "
        "not retired; active_replicas is decremented exactly once and still reconciles with the "
        "non-retired row count",
        f"{ra['outcome']}/{ra['sqlstate'] or '-'} {rb['outcome']}/{rb['sqlstate'] or '-'} "
        f"idle_retired={idle_retired} live_retired={live_retired} "
        f"active_replicas={active} non_retired_rows={recount}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit"
        and idle_retired is True and live_retired is False
        and active == 1 and recount == 1, ms=ms)


def strip_fence(rec, sql):
    """Remove the whole `[WHERE|AND] EXISTS (SELECT 1 FROM sm_data.agent_session …)` fence from a
    Phase B statement — a stated textual removal, printed into the log."""
    key = "EXISTS (SELECT 1 FROM sm_data.agent_session"
    i = sql.index(key)
    lead = sql.rfind("WHERE", 0, i), sql.rfind("AND", 0, i)
    start = max(x for x in lead if x >= 0 and sql[x:i].strip() == ("WHERE" if x == lead[0] else "AND"))
    depth = 0
    for j in range(i, len(sql)):
        if sql[j] == "(":
            depth += 1
        elif sql[j] == ")":
            depth -= 1
            if depth == 0:
                end = j + 1
                break
    clause = sql[start:end]
    if sql.count(clause) != 1:
        raise LegFail("fence clause is not unique")
    rec.say(f"    -- PLANTED VARIANT: removed the session fence {clause[:60]!r}… from this statement")
    return sql[:start] + sql[end:]


@leg("(L14)", "least-privilege")
def L14(ctx, rec):
    ob = "(L14)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid_pending = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid_pending))
    rep_id, inc_id = seed_replica(ctx, "pod-a")
    runtime_ok = [
        (("sketch", "3.1", 1, 0), {"request_id": rid_pending}, "§3.1 step 0a advisory key"),
        (("sketch", "3.1", 1, 1), {"request_id": rid_pending}, "§3.1 step 0b tombstone check"),
        (("sketch", "3.1", 1, 2), {"agent": A}, "§3.1 step 0c identity FOR UPDATE"),
        (RESERVE, {"request_id": uuid.uuid4(), "agent": A, "epoch": 1, "replica": "pod-a",
                   "kind": "renew", "fingerprint": b"fp", "pending_ttl": timedelta(seconds=60)},
         "§3.1 step 1 reservation"),
        (ADOPT, {"request_id": rid_pending, "agent": A, "epoch": 1, "replica": "pod-a",
                 "pending_ttl": timedelta(seconds=60)}, "§3.1 adoption"),
        (STEP3A, {"agent": A, "epoch": 1, "replica": "pod-a"}, "§3.3 step 3a fence"),
        (STEP3B, {"request_id": rid_pending, "agent": A, "epoch": 1, "replica": "pod-a", "mine": 1},
         "§3.3 step 3b ownership FOR UPDATE"),
        (STEP3C, grant_params(ctx, rid_pending), "§3.3 step 3c project + complete"),
        (TAKEOVER, {"request_id": uuid.uuid4(), "agent": A, "epoch": 1, "replica": "pod-a",
                    "pending_ttl": timedelta(seconds=60), "expected": 1, "takeover_attempts": 3},
         "§3.4 takeover"),
        (A0, reg_params(ctx), "§4 A0 identity FOR UPDATE"),
        (A1, reg_params(ctx), "§4 A1 session fence"),
        (A2, reg_params(ctx), "§4 A2 cap row materialize"),
        (A3, reg_params(ctx), "§4 A2 cap row FOR UPDATE"),
        (A4, reg_params(ctx), "§4 A3 stable read"),
        (A5, reg_params(ctx, replica_id=rep_id), "§4 A4 activation FOR UPDATE"),
        (A7B, reg_params(ctx, replica_id=rep_id), "§4 A7b incarnation count"),
        (B1, reg_params(ctx, replica_id=rep_id), "§4 B1 handoff ownership"),
        (B2_INS, reg_params(ctx, replica_ref="pod-new", sequence=5), "§4 B2 mint"),
        (B2_CAP, reg_params(ctx), "§4 B2 cap counter"),
        (B3_INC, reg_params(ctx, replica_id=rep_id, active=inc_id, sequence=5),
         "§4 B3 refresh, incumbent form"),
        (B3_HAND, reg_params(ctx, replica_id=rep_id, active=inc_id, sequence=6),
         "§4 B3 refresh, handoff form"),
        (B4, reg_params(ctx, replica_id=rep_id, active=inc_id), "§4 B4 retire"),
        (B5, reg_params(ctx, replica_id=rep_id, sequence=7), "§4 B5 insert"),
    ]
    conn = ctx.conn("sm_dp_runtime")
    failed = []
    with conn.cursor() as cur:
        for spec, params, name in runtime_ok:
            sql, cite = ctx.stmt(*spec)
            try:
                run_stmt(rec, cur, sql, params, f"as sm_dp_runtime: {name} [{cite}]")
            except psycopg.Error as e:
                failed.append((name, e.sqlstate, str(e).splitlines()[0][:90]))
                conn.rollback()
                cur = conn.cursor()
    conn.commit(); conn.close()
    chk(rec, ob, "every-§3-and-§4-statement-runs-as-sm_dp_runtime", "production",
        f"all {len(runtime_ok)} statements execute under the §7.4/§7 grants",
        "all succeeded" if not failed else f"refused: {failed}", not failed)

    # measured S10/F3: these five write only columns the runtime role must already hold
    RUNTIME_CAN_RUN = {("sketch", "5.0", 1, 0), ("sketch", "5.1", 1, 0), ("sketch", "5.1", 1, 1),
                       ("sketch", "5.4", 1, 0), ("sketch", "5.4", 1, 1)}
    migrator_only = [
        (("sketch", "5.0", 1, 0), {"tenant": ctx.fx["tenant"], "binding": ctx.fx["binding"],
                                   "incarnation_idle_retirement": timedelta(minutes=1)}, "§5.0 silence sweep"),
        (("sketch", "5.1", 1, 0), {"tenant": ctx.fx["tenant"], "binding": ctx.fx["binding"],
                                   "replica_idle_retirement": timedelta(minutes=1)}, "§5.1 step 1 cap lock"),
        (("sketch", "5.1", 1, 1), {"tenant": ctx.fx["tenant"], "binding": ctx.fx["binding"],
                                   "replica_idle_retirement": timedelta(minutes=1)}, "§5.1 step 3 idle sweep"),
        (("sketch", "5.2", 1, 0), {"registry_retention": timedelta(minutes=1)},
         "§5.2 prune incarnations (a DELETE — genuinely refused)"),
        (("sketch", "5.3", 1, 0), {"ledger_retention": timedelta(days=30), "retention_batch": 5},
         "§5.3 step 1 batch"),
        (("sketch", "5.3", 1, 1), {"ids": [uuid.uuid4()], "ledger_retention": timedelta(days=30)},
         "§5.3 step 2 delete + tombstone (DELETE + tombstone INSERT — genuinely refused)"),
        (("sketch", "5.4", 1, 0), {"orphan_grace": timedelta(minutes=1), "retention_batch": 5},
         "§5.4 step 1 batch"),
        (("sketch", "5.4", 1, 1), {"ids": [uuid.uuid4()], "orphan_grace": timedelta(minutes=1)},
         "§5.4 step 2 terminalise"),
    ]
    for spec, params, name in migrator_only:
        sql, cite = ctx.stmt(*spec)

        def f(sql=sql, params=params, name=name):
            with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
                run_stmt(rec, cur, sql, params, f"as sm_dp_runtime: {name} [{cite}]")
        if spec == ("sketch", "5.3", 1, 0):
            # (L14) carves this one out in as many words: "Step 1 is deliberately not a refusal leg:
            # it takes no row lock and is a granted SELECT plus an advisory lock, so it *succeeds*
            # as sm_dp_runtime — asserting otherwise would be asserting a privilege boundary the DCL
            # does not draw."
            try:
                f()
                chk(rec, ob, f"runtime-may-run-{name}", "production",
                    "(L14) states this one SUCCEEDS as sm_dp_runtime", "succeeded", True)
            except psycopg.Error as e:
                chk(rec, ob, f"runtime-may-run-{name}", "production",
                    "(L14) states this one SUCCEEDS as sm_dp_runtime",
                    f"{e.sqlstate}: {str(e).splitlines()[0][:120]}", False)
        elif spec in RUNTIME_CAN_RUN:
            # corrected (L14), S10/F3: the DCL CANNOT refuse these — their columns are exactly the
            # ones B4, B2's un-retire, A7/B2's counter and step 3c write on the runtime path.
            try:
                with ctx.conn("sm_dp_runtime", autocommit=True) as c, c.cursor() as cur:
                    n_rt, _ = run_stmt(rec, cur, sql, params, f"as sm_dp_runtime: {name} [{cite}]")
                chk(rec, ob, f"runtime-may-run-{name}", "production",
                    "succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the "
                    "migrator's exclusivity here is scheduling, not privilege",
                    f"executed, {n_rt} row(s) affected", True)
            except psycopg.Error as e:
                chk(rec, ob, f"runtime-may-run-{name}", "production",
                    "succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14)",
                    f"{e.sqlstate}: {str(e).splitlines()[0][:140]}", False)
        else:
            refused(rec, ob, f"42501-as-runtime-{name}", "production", "42501", f,
                    "the DCL refuses it: no DELETE on any of the eleven tables, no tombstone INSERT")
        conn = ctx.conn("sm_dp_migrator", autocommit=True)
        try:
            with conn.cursor() as cur:
                cur.execute("SET ROLE sm_dp_owner")
                run_stmt(rec, cur, sql, params, f"as the migrator: {name}")
            ok_m, err = True, ""
        except psycopg.Error as e:
            ok_m, err = False, f"{e.sqlstate}: {str(e).splitlines()[0][:120]}"
        finally:
            conn.close()
        chk(rec, ob, f"migrator-may-run-{name}", "production", "the migrator executes it",
            "succeeded" if ok_m else err, ok_m)


@leg("(L15)", "retired-incarnation-window")
def L15(ctx, rec):
    ob = "(L15)"
    rid, iid = seed_replica(ctx, "pod-a")

    def bad_window():
        with ctx.owner() as c, c.cursor() as cur:
            cur.execute("""INSERT INTO sm_data.agent_replica_incarnation (replica_id, incarnation_id,
                            incarnation, registered_at, registered_by_report, registered_by_agent,
                            last_seen_report, last_seen_by_agent, last_seen_at, retired_at)
                           VALUES (%s,%s,%s, now(), 1,%s,1,%s, now(), now() - interval '1 minute')""",
                        (rid, uuid.uuid4(), str(uuid.uuid4()), ctx.fx["agentA"], ctx.fx["agentA"]))
    refused(rec, ob, "23514-incarnation_window", "production", "23514", bad_window,
            "retired_at must be strictly after registered_at, so the acceptance interval is never empty")
    raws = scalar(ctx, "SELECT count(*) FROM pg_tables WHERE schemaname='sm_data' "
                       "AND tablename='metrics_raw'")
    rec.record(LegResult(ob, "sample-admission-interval", "n-a",
                         "a sample labelled with a retired incarnation_id commits iff ts ∈ "
                         "[registered_at, retired_at) — design.md §6.1's rule, tested by §8 row 44",
                         f"no admission statement exists in this design set to execute "
                         f"(sm_data.metrics_raw present from V001: {raws}; the rule that reads it "
                         f"is the DM-D*/BE-D* families', and no document here carries it as SQL)",
                         "", 0, "N/A-engine",
                         "The registry rows the rule reads are this sketch's, and V001 supplies the "
                         "fact tables; the ADMISSION ITSELF is the metrics family's and is written "
                         "nowhere in this design set, so there is no statement to extract and run."))


@leg("(L16)", "orphan-sweep")
def L16(ctx, rec):
    ob = "(L16)"
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    dead = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, dead, ttl=-3600))    # abandoned, long past its deadline
    live = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, live, ttl=3600))     # a live recovery in flight
    b, cb = ctx.stmt("sketch", "5.4", 1, 0)
    u, cu = ctx.stmt("sketch", "5.4", 1, 1)
    conn = ctx.conn("sm_dp_migrator")
    with conn.cursor() as cur:
        cur.execute("SET ROLE sm_dp_owner")
        n1, rows = run_stmt(rec, cur, b, {"orphan_grace": timedelta(minutes=1), "retention_batch": 10},
                            f"§5.4 step 1: batch + per-key advisory locks [{cb}]")
        ids = [r[0] for r in rows]
        n2, _ = run_stmt(rec, cur, u, {"ids": ids, "orphan_grace": timedelta(minutes=1)},
                         f"§5.4 step 2: terminalise [{cu}]")
    conn.commit(); conn.close()
    dstate = scalar(ctx, "SELECT state || '/' || outcome FROM sm_data.agent_lease_grant WHERE request_id=%s",
                    (dead,))
    lstate = scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (live,))
    datt = scalar(ctx, "SELECT attempt_no FROM sm_data.agent_lease_grant WHERE request_id=%s", (dead,))
    chk(rec, ob, "abandoned-request-terminalised-live-one-skipped", "production",
        "the abandoned row becomes FAILED/TIMEOUT through the trigger's completion branch with its "
        "attempt controls unmoved; the live row is untouched",
        f"batch={n1} terminalised={n2} dead={dstate} attempt_no={datt} live={lstate}",
        n1 == 1 and n2 == 1 and dstate == "FAILED/TIMEOUT" and datt == 1 and lstate == "PENDING")
    jti = scalar(ctx, "SELECT lease_jti FROM sm_data.agent_lease_grant WHERE request_id=%s", (dead,))
    chk(rec, ob, "no-grant-material-on-the-swept-row", "production",
        "failed_has_no_grant leaves the terminal row with a code and nothing else",
        f"lease_jti={jti}", jti is None)

    # --- S11/F4/S12-F3: the sweep CONCURRENT WITH a step-3 completion on the SAME request id.
    # --- Run BOTH lock orders. Whichever transaction reaches the ledger row first, the other's
    # --- re-checked predicates refuse it, so the request has exactly one outcome. What serializes
    # --- them here is the LEDGER ROW lock — 3b's
    # --- FOR UPDATE against step 2's UPDATE — not the request-key advisory lock, because step 3
    # --- runs in its own transaction and takes no advisory key (§5.4's boundary note: step 0a
    # --- belongs to the reserve/adopt transaction, which has already committed).
    ctx.reset(); A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid, ttl=-3600))     # already past its attempt_deadline
    sql3a, _ = ctx.stmt(*STEP3A)
    sql3b, _ = ctx.stmt(*STEP3B)
    sql3c, _ = ctx.stmt(*STEP3C)
    p3 = grant_params(ctx, rid)
    fence = {"request_id": rid, "agent": A, "epoch": 1, "replica": "pod-a", "mine": 1}
    seen = {}

    def STEP3(side):
        side.run(sql3a, fence, "step 3a fence")
        side.run(sql3b, fence, "step 3b: FOR UPDATE on the ledger row")
        side.wait("step 3 holds the ledger row; the sweep may run its step 2")
        time.sleep(0.5)
        seen["3c"] = side.run(sql3c, p3, "step 3c: project + complete")[0]

    def SWEEP4(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        n, rows = side.run(b, {"orphan_grace": timedelta(minutes=1), "retention_batch": 10},
                           "§5.4 step 1: batch + per-key advisory locks")
        side.wait("step 3 holds the ledger row; the sweep may run its step 2")
        seen["sweep"] = side.run(u, {"ids": [r[0] for r in rows],
                                     "orphan_grace": timedelta(minutes=1)},
                                 "§5.4 step 2: terminalise — waits for the ledger row")[0]

    ra, rb, ms = run_pair(ctx, rec, STEP3, SWEEP4, role_a="sm_dp_runtime", role_b="sm_dp_migrator",
                          lock_timeout=LOCK_TIMEOUT_MS, names=("STEP3", "SWEEP"))
    st = scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    outc = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    leases = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease")
    chk(rec, ob, "sweep-concurrent-with-a-step-3-completion-same-request-id", "production",
        "both commit with no 40P01 and the request has EXACTLY ONE outcome: step 3 holds the "
        "ledger row, so the sweep's step 2 waits and then re-evaluates state = 'PENDING', which "
        "no longer holds — it writes 0 rows and the completion stands",
        f"STEP3={ra['outcome']}/{ra['sqlstate'] or '-'} SWEEP={rb['outcome']}/{rb['sqlstate'] or '-'} "
        f"3c wrote {seen.get('3c')} row(s), sweep step 2 wrote {seen.get('sweep')} row(s), "
        f"state={st} outcome={outc} agent_lease rows={leases}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit"
        and seen.get("3c") == 1 and seen.get("sweep") == 0
        and st == "COMPLETED" and leases == 1, ms=ms)

    # Reverse order: the orphan sweep terminalises first and holds the ledger row until commit.
    # The later completion's 3b then re-evaluates state = 'PENDING', returns zero rows and must not
    # project a lease. This is a distinct outcome from the completion-first leg above.
    ctx.reset(); A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid, ttl=-3600))
    p3 = grant_params(ctx, rid)
    fence = {"request_id": rid, "agent": A, "epoch": 1, "replica": "pod-a", "mine": 1}
    seen = {}

    def SWEEP4_FIRST(side):
        side.cur.execute("SET ROLE sm_dp_owner")
        _, rows = side.run(b, {"orphan_grace": timedelta(minutes=1), "retention_batch": 10},
                           "§5.4 step 1: batch + per-key advisory locks")
        seen["sweep"] = side.run(u, {"ids": [r[0] for r in rows],
                                     "orphan_grace": timedelta(minutes=1)},
                                 "§5.4 step 2: terminalise and hold the ledger row")[0]
        side.wait("sweep holds the terminalised ledger row; step 3 may continue")
        time.sleep(0.5)

    def STEP3_LATE(side):
        side.run(sql3a, fence, "step 3a fence")
        side.wait("sweep holds the terminalised ledger row; step 3 may continue")
        seen["3b"] = side.run(sql3b, fence,
                              "step 3b: waits, then re-checks the now-terminal ledger row")[0]
        if seen["3b"]:
            seen["3c"] = side.run(sql3c, p3, "step 3c must be unreachable")[0]

    ra, rb, ms = run_pair(ctx, rec, SWEEP4_FIRST, STEP3_LATE,
                          role_a="sm_dp_migrator", role_b="sm_dp_runtime",
                          lock_timeout=LOCK_TIMEOUT_MS, names=("SWEEP", "STEP3"))
    st = scalar(ctx, "SELECT state FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    outc = scalar(ctx, "SELECT outcome FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    leases = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease")
    chk(rec, ob, "sweep-wins-before-step-3-completion", "production",
        "both commit with no 40P01 and exactly one outcome: the sweep terminalises first; step 3b "
        "then returns 0 rows, step 3c is not reached and no lease is projected",
        f"SWEEP={ra['outcome']}/{ra['sqlstate'] or '-'} STEP3={rb['outcome']}/{rb['sqlstate'] or '-'} "
        f"sweep step 2 wrote {seen.get('sweep')} row(s), 3b returned {seen.get('3b')} row(s), "
        f"3c wrote {seen.get('3c', 0)} row(s), state={st} outcome={outc} agent_lease rows={leases}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit"
        and seen.get("sweep") == 1 and seen.get("3b") == 0 and seen.get("3c", 0) == 0
        and st == "FAILED" and outc == "TIMEOUT" and leases == 0, ms=ms)


@leg("(L17)", "stale-epoch-report-writes-nothing")
def L17(ctx, rec):
    ob = "(L17)"
    for spec, name in PHASE_B_ALL:
        ctx.reset()
        A, B = ctx.fx["agentA"], ctx.fx["agentB"]
        ctx.session_for(A, "pod-a", 5)                      # live epoch is 5; the report presents 4
        rep_id, inc_id = seed_replica(ctx, "pod-a", agent=A, seq=1)
        if spec == B5:
            with ctx.owner() as c, c.cursor() as cur:
                cur.execute("UPDATE sm_data.agent_replica_incarnation SET retired_at = now() "
                            "WHERE incarnation_id=%s", (inc_id,))
        p_stale = reg_params(ctx, epoch=4, replica_id=rep_id, active=inc_id, sequence=9,
                             replica_ref="pod-a" if spec != B2_INS else "pod-brand-new")
        sql, cite = ctx.stmt(*spec)
        n, _ = rowcount_of(ctx, rec, sql, p_stale, f"{name} at a superseded epoch [{cite}]")
        chk(rec, ob, f"stale-{name}", "production", "0 rows", f"{n} row(s)", n == 0)
        planted = strip_fence(rec, sql)
        n2, _ = rowcount_of(ctx, rec, planted, p_stale, f"PLANTED {name} with its session fence removed")
        chk(rec, ob, f"stale-{name}", "planted",
            "without the fence the superseded report's write lands", f"{n2} row(s)", n2 == 1)
        n3, _ = rowcount_of(ctx, rec, sql, p_stale, f"RESTORED {name}, verbatim from the extracted block")
        chk(rec, ob, f"stale-{name}", "restored", "0 rows again", f"{n3} row(s)", n3 == 0)

    # the whole registry is byte-identical after a stale report
    ctx.reset()
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 5)
    rep_id, inc_id = seed_replica(ctx, "pod-a", agent=A, seq=1)
    before = scalar(ctx, "SELECT md5(string_agg(t::text, '|' ORDER BY t::text)) FROM ("
                         "SELECT r.* FROM sm_data.agent_replica r) t")
    beforei = scalar(ctx, "SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text),'')) FROM ("
                          "SELECT i.* FROM sm_data.agent_replica_incarnation i) t")
    conn = ctx.conn("sm_dp_runtime")
    with conn.cursor() as cur:
        for spec, name in PHASE_B_ALL:
            sql, cite = ctx.stmt(*spec)
            run_stmt(rec, cur, sql, reg_params(ctx, epoch=4, replica_id=rep_id, active=inc_id,
                                               sequence=9, replica_ref="pod-a"),
                     f"stale {name}")
    conn.commit(); conn.close()
    after = scalar(ctx, "SELECT md5(string_agg(t::text, '|' ORDER BY t::text)) FROM ("
                        "SELECT r.* FROM sm_data.agent_replica r) t")
    afteri = scalar(ctx, "SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text),'')) FROM ("
                         "SELECT i.* FROM sm_data.agent_replica_incarnation i) t")
    cap = scalar(ctx, "SELECT active_replicas FROM sm_data.agent_replica_cap")
    chk(rec, ob, "registry-byte-identical-after-a-stale-report", "production",
        "all seven fenced writes together move nothing: agent_replica and "
        "agent_replica_incarnation hash the same and the cap counter is unchanged",
        f"replica md5 {before[:8]}→{after[:8]}, incarnation md5 {beforei[:8]}→{afteri[:8]}, "
        f"active_replicas={cap}", before == after and beforei == afteri)

    # the revocation leg: A0's own predicate, and the identity lock, planted
    ctx.reset()
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("UPDATE sm_data.agent_identity SET revoked_at = now(), agent_revocation_epoch = 1 "
                    "WHERE agent_id = %s", (A,))
    a0sql, a0cite = ctx.stmt(*A0)
    n, _ = rowcount_of(ctx, rec, a0sql, reg_params(ctx), f"A0 for a REVOKED identity [{a0cite}]")
    chk(rec, ob, "A0-refuses-a-revoked-identity", "production",
        "A0 returns 0 rows, so nothing below it runs", f"{n} row(s)", n == 0)
    planted = remove(rec, a0sql, " AND revoked_at IS NULL", "A0's revocation predicate")
    n2, _ = rowcount_of(ctx, rec, planted, reg_params(ctx), "PLANTED A0 without the revocation predicate")
    chk(rec, ob, "A0-refuses-a-revoked-identity", "planted",
        "without it a revoked agent's report proceeds past A0", f"{n2} row(s)", n2 == 1)
    n3, _ = rowcount_of(ctx, rec, a0sql, reg_params(ctx), "RESTORED A0, verbatim")
    chk(rec, ob, "A0-refuses-a-revoked-identity", "restored", "0 rows again", f"{n3} row(s)", n3 == 0)


# --- (L18): the six named lock-order legs ---------------------------------
FIX_SESSION_THEN_IDENTITY = [
    ("SELECT se.agent_id FROM sm_data.agent_session se WHERE se.agent_id = %(agent)s FOR SHARE",
     "fixture statement 1: agent_session FOR SHARE"),
    ("SELECT ai.agent_id FROM sm_data.agent_identity ai WHERE ai.agent_id = %(agent)s "
     "FOR NO KEY UPDATE",
     "fixture statement 2: agent_identity FOR NO KEY UPDATE — production's mode since S10/F4, and it "
     "still conflicts with claim's FOR SHARE (PG 14 Table 13.3) — taken in the INVERSE prefix order"),
]


def inverse_fixture(ctx, rec, statements, params, pause_after=0.6):
    """A TEST-ONLY inverse-order fixture: two sequential statements with a named barrier
    between them. Never sibling CTEs — the documentation gives no acquisition order for
    those, so no test may demand a deadlock from them (sketch §3.1 step 0c, S9/F2)."""
    def side_fn(side):
        side.run(statements[0][0], params, "L18 " + statements[0][1])
        side.wait("inverse fixture holds its FIRST lock")
        time.sleep(pause_after)          # let the production partner block on it
        side.run(statements[1][0], params, "L18 " + statements[1][1])
    return side_fn


def deadlock_leg(ctx, rec, ob, leg, fixture_fn, partner_fn, note):
    ra, rb, ms = run_pair(ctx, rec, fixture_fn, partner_fn, lock_timeout=LOCK_TIMEOUT_MS,
                          names=("FIXTURE", "PRODUCTION"))
    states = [ra["sqlstate"], rb["sqlstate"]]
    outcomes = [ra["outcome"], rb["outcome"]]
    good = states.count("40P01") == 1 and outcomes.count("commit") == 1 and ms <= 20000
    chk(rec, ob, leg, "planted",
        "40P01 in EXACTLY ONE of the two transactions inside a 5 s lock_timeout, the other commits",
        f"FIXTURE={ra['outcome']}/{ra['sqlstate'] or '-'} PRODUCTION={rb['outcome']}/"
        f"{rb['sqlstate'] or '-'} in {ms} ms", good,
        detail=note + (" || " + (ra["msg"] or rb["msg"]) if (ra["msg"] or rb["msg"]) else ""),
        sqlstate="40P01", ms=ms)
    return good


@leg("(L18)", "lock-order-under-contention")
def L18(ctx, rec):
    ob = "(L18)"
    claim, ccl = ctx.stmt(*CLAIM_STEP2)

    # ---------------- (L18-P1) claim vs registration, positive ----------------
    A = ctx.fx["agentA"]
    ctx.session_for(A, "pod-a", 1)
    seed_replica(ctx, "pod-a", agent=A)

    def CLAIM(side):
        side.wait("both transactions ready")
        side.run(claim, claim_params(ctx, "pod-b"), f"production claim [{ccl}]")

    def REGISTER(side):
        p = reg_params(ctx, replica_ref="pod-a", sequence=9)
        side.wait("both transactions ready")
        phase_a(ctx, rec, p, side.conn, upto=3)

    ra, rb, ms = run_pair(ctx, rec, CLAIM, REGISTER, names=("CLAIM", "REGISTRATION"))
    chk(rec, ob, "L18-P1-claim-vs-registration", "production",
        "both commit; neither raises 40P01; each takes agent_identity before agent_session",
        f"CLAIM={ra['outcome']}/{ra['sqlstate'] or '-'} REGISTRATION={rb['outcome']}/"
        f"{rb['sqlstate'] or '-'}", ra["outcome"] == "commit" and rb["outcome"] == "commit", ms=ms)

    # ---------------- (L18-N1) claim vs registration, planted ----------------
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    deadlock_leg(ctx, rec, ob, "L18-N1-claim-vs-registration",
                 inverse_fixture(ctx, rec, FIX_SESSION_THEN_IDENTITY, {"agent": A}),
                 lambda side: (side.wait("inverse fixture holds its FIRST lock"),
                               side.run(claim, claim_params(ctx, "pod-b"),
                                        f"production claim [{ccl}]")),
                 "the fixture stands in for the pre-S8/F1 registration order "
                 "(agent_session before agent_identity)")

    # ---------------- (L18-P2) claim vs lease reservation (0c + 1), positive ----------------
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    before = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")

    def RESERVE_SIDE(side):
        side.wait("both transactions ready")
        for spec, params, label in reserve_steps(ctx, uuid.uuid4()):
            sql, cite = ctx.stmt(*spec)
            side.run(sql, params, f"{label} [{cite}]")

    def CLAIM2(side):
        side.wait("both transactions ready")
        side.run(claim, claim_params(ctx, "pod-b"), f"production claim [{ccl}]")

    ra, rb, ms = run_pair(ctx, rec, CLAIM2, RESERVE_SIDE, names=("CLAIM", "RESERVATION"))
    after = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant")
    epoch = scalar(ctx, "SELECT fencing_epoch FROM sm_data.agent_session WHERE agent_id=%s", (A,))
    chk(rec, ob, "L18-P2-claim-vs-lease-reservation", "production",
        "both commit, no 40P01; a reservation whose epoch was superseded reserves nothing",
        f"CLAIM={ra['outcome']}/{ra['sqlstate'] or '-'} RESERVATION={rb['outcome']}/"
        f"{rb['sqlstate'] or '-'} ledger rows {before}→{after} session epoch={epoch}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit", ms=ms)

    # ---------------- (L18-N2) claim vs lease reservation, planted ----------------
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    deadlock_leg(ctx, rec, ob, "L18-N2-claim-vs-lease-reservation",
                 inverse_fixture(ctx, rec, FIX_SESSION_THEN_IDENTITY, {"agent": A}),
                 lambda side: (side.wait("inverse fixture holds its FIRST lock"),
                               side.run(claim, claim_params(ctx, "pod-b"),
                                        f"production claim [{ccl}]")),
                 "the fixture stands in for the pre-S8/C1 reservation, whose sibling-CTE form gives "
                 "no author-controlled order and therefore may not be used as the mechanism (S9/F2)")

    # ---------------- (L18-P3) step 1 retry vs step 3, positive ----------------
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))

    def RETRY(side):
        side.wait("both transactions released together")
        for spec, params, label in reserve_steps(ctx, rid):
            sql, cite = ctx.stmt(*spec)
            side.run(sql, params, f"retry {label} [{cite}]")

    def COMPLETE(side):
        side.wait("both transactions released together")
        for spec, params, label in complete_steps(ctx, rid):
            sql, cite = ctx.stmt(*spec)
            side.run(sql, params, f"{label} [{cite}]")

    # (L18-P3) says the pair simply "runs concurrently" — so it is run concurrently, REPEATEDLY,
    # because a lock cycle that forms under only some interleavings is still a lock cycle.
    REPEATS = 5
    seen, outcomes = [], []
    for i in range(REPEATS):
        ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
        rid = uuid.uuid4()
        lease_txn(ctx, rec, reserve_steps(ctx, rid))
        ra, rb, ms = run_pair(ctx, rec, RETRY, COMPLETE, lock_timeout=LOCK_TIMEOUT_MS,
                              names=("RETRY", "STEP3"))
        outcomes.append(f"#{i+1} RETRY={ra['outcome']}/{ra['sqlstate'] or '-'} "
                        f"STEP3={rb['outcome']}/{rb['sqlstate'] or '-'}")
        if "40P01" in (ra["sqlstate"], rb["sqlstate"]):
            seen.append((i + 1, ra["msg"] or rb["msg"]))
    rows = scalar(ctx, "SELECT count(*) FROM sm_data.agent_lease_grant WHERE request_id=%s", (rid,))
    chk(rec, ob, "L18-P3-step1-retry-vs-step3", "production",
        f"(L18-P3) asserts BOTH COMMIT and neither raises 40P01, in every one of {REPEATS} concurrent "
        f"repetitions, with the retry writing no second ledger row",
        f"{'; '.join(outcomes)} | ledger rows for the last id={rows}",
        not seen and rows == 1, ms=ms,
        detail=("" if not seen else
                f"FINDING: {len(seen)}/{REPEATS} repetitions raised 40P01 — " + str(seen[0][1])[:300]))

    # --- the two waits the L18-P3 cycle is built from, isolated so the mechanism is recorded ---
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    rid_p = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid_p))
    holder = ctx.conn("sm_dp_runtime")
    with holder.cursor() as cur:
        run_stmt(rec, cur, "SELECT 1 FROM sm_data.agent_identity WHERE agent_id = %(a)s FOR UPDATE",
                 {"a": A}, "probe holder: agent_identity FOR UPDATE — the mode step 0c used BEFORE "
                           "S10/F4, held synthetically here because production no longer takes it")

    def probe1():
        with ctx.conn("sm_dp_runtime", lock_timeout=2000, stmt_timeout=20000) as c, c.cursor() as cur:
            run_stmt(rec, cur, ctx.stmt(*STEP3C)[0], grant_params(ctx, rid_p),
                     "probe: step 3c, whose agent_lease INSERT carries an FK to agent_identity")
    refused(rec, ob, "L18-P3-mechanism-probe-1", "production", "55P03", probe1,
            "step 3c WAITS on a FOR UPDATE held on agent_identity — the pre-S10/F4 mode",
            detail="agent_lease's FOREIGN KEY (tenant_id, agent_id) REFERENCES agent_identity makes "
                   "the projection take a KEY SHARE lock on the identity row, which conflicts with "
                   "FOR UPDATE. §7's prefix places agent_identity BEFORE agent_lease_grant, so step 3 "
                   "moves backwards in the prefix through a lock the model does not name.")
    holder.rollback(); holder.close()

    # probe 3 — the same wait under the CORRECTED mode: it does not happen.
    # Its own request id, because this probe's 3c SUCCEEDS and would otherwise leave the row terminal.
    rid_p3 = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid_p3))
    holder = ctx.conn("sm_dp_runtime")
    with holder.cursor() as cur:
        run_stmt(rec, cur, "SELECT 1 FROM sm_data.agent_identity WHERE agent_id = %(a)s "
                           "FOR NO KEY UPDATE", {"a": A},
                 "probe holder: agent_identity FOR NO KEY UPDATE — the mode step 0c and A0 take since S10/F4")
    try:
        with ctx.conn("sm_dp_runtime", lock_timeout=2000, stmt_timeout=20000) as c, c.cursor() as cur:
            n_p3, _ = run_stmt(rec, cur, ctx.stmt(*STEP3C)[0], grant_params(ctx, rid_p3),
                               "probe: step 3c against a FOR NO KEY UPDATE hold")
        good3, obs3 = True, f"completed without waiting, {n_p3} row(s)"
    except psycopg.Error as e:
        good3, obs3 = False, f"{e.sqlstate}: {str(e).splitlines()[0][:120]}"
    chk(rec, ob, "L18-P3-mechanism-probe-3", "production",
        "step 3c does NOT wait on an identity row held FOR NO KEY UPDATE — KEY SHARE does not "
        "conflict with NO KEY UPDATE (PG 9.3 release notes; PG 14 Table 13.3)",
        obs3, good3,
        detail="this is the fix S10/F4 made: the same hold in the old FOR UPDATE mode blocks (probe 1).")
    holder.rollback(); holder.close()

    holder = ctx.conn("sm_dp_runtime")
    with holder.cursor() as cur:
        run_stmt(rec, cur, "UPDATE sm_data.agent_lease_grant SET attempt_owner = %(r)s, "
                           "fencing_epoch = fencing_epoch + 1 WHERE request_id = %(rid)s",
                 {"r": "pod-a", "rid": rid_p},
                 "probe holder: an in-flight UPDATE of the ledger row (what step 3c does)")

    def probe2():
        with ctx.conn("sm_dp_runtime", lock_timeout=2000, stmt_timeout=20000) as c, c.cursor() as cur:
            sql, _ = ctx.stmt(*RESERVE)
            run_stmt(rec, cur, sql, {"request_id": rid_p, "agent": A, "epoch": 1, "replica": "pod-a",
                                     "kind": "renew", "fingerprint": b"fp",
                                     "pending_ttl": timedelta(seconds=60)},
                     "probe: step 1's INSERT … ON CONFLICT (request_id) DO NOTHING")
    refused(rec, ob, "L18-P3-mechanism-probe-2", "production", "55P03", probe2,
            "step 1's ON CONFLICT DO NOTHING WAITS on a ledger row an in-flight transaction updated",
            detail="so the retry holds agent_identity and waits for the ledger row while step 3 holds "
                   "the ledger row and waits for agent_identity — the cycle the L18-P3 leg observed.")
    holder.rollback(); holder.close()

    # ---------------- (L18-N3) step 1 retry vs step 3, planted ----------------
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    rid = uuid.uuid4()
    lease_txn(ctx, rec, reserve_steps(ctx, rid))
    # SYNTHETIC (S10/F5): the S9 fixture asked for agent_session FOR SHARE, and FOR SHARE does not
    # conflict with FOR SHARE (PG 14 Table 13.3), so that pair could never cycle — which is also why
    # the pre-S8/C1 step-3 order never cycled either. This fixture therefore uses a CONFLICTING mode
    # and stands in for NO production form, past or present: it proves only that the harness would
    # detect a cycle on this table pair, so (L18-P3)'s green is a measurement.
    fixture = [
        ("SELECT lg.request_id FROM sm_data.agent_lease_grant lg WHERE lg.request_id = %(request_id)s "
         "FOR UPDATE", "SYNTHETIC fixture statement 1: agent_lease_grant FOR UPDATE"),
        ("SELECT se.agent_id FROM sm_data.agent_session se WHERE se.agent_id = %(agent)s FOR UPDATE",
         "SYNTHETIC fixture statement 2: agent_session FOR UPDATE (a CONFLICTING mode — the S9 "
         "version asked for FOR SHARE, which cannot conflict with the retry's FOR SHARE)"),
    ]

    def STEP3_PARTNER(side):
        # production step 3 is the partner: 3a takes agent_session FOR SHARE, 3b takes the ledger row
        # FOR UPDATE. Step 1's reservation cannot serve — its ON CONFLICT DO NOTHING does not wait on
        # a row that is merely locked (measured, S10/F5), so it never joins the cycle.
        side.wait("inverse fixture holds its FIRST lock")
        for spec, params, label in complete_steps(ctx, rid)[:2]:
            sql, cite = ctx.stmt(*spec)
            side.run(sql, params, f"production {label} [{cite}]")

    good = deadlock_leg(ctx, rec, ob, "L18-N3-step1-retry-vs-step3",
                        inverse_fixture(ctx, rec, fixture, {"request_id": rid, "agent": A}),
                        STEP3_PARTNER,
                        "SYNTHETIC cycle, standing in for no production form (S10/F5): it proves the "
                        "harness detects a cycle on the agent_lease_grant/agent_session pair, so "
                        "(L18-P3)'s green is a measurement and not an absence of instrumentation")

    # the positives are re-run green after the planted halves
    ctx.reset(); A = ctx.fx["agentA"]; ctx.session_for(A, "pod-a", 1)
    seed_replica(ctx, "pod-a", agent=A)
    ra, rb, ms = run_pair(ctx, rec, CLAIM, REGISTER, names=("CLAIM", "REGISTRATION"))
    chk(rec, ob, "L18-P1-claim-vs-registration", "restored",
        "the production pair still commits after the planted fixtures ran",
        f"CLAIM={ra['outcome']} REGISTRATION={rb['outcome']}",
        ra["outcome"] == "commit" and rb["outcome"] == "commit", ms=ms)


def catalog_snapshot(rec, ctx):
    with ctx.owner() as c, c.cursor() as cur:
        cur.execute("""SELECT tablename FROM pg_tables WHERE schemaname='sm_data' ORDER BY 1""")
        tabs = [r[0] for r in cur.fetchall()]
        cur.execute("""SELECT c.relname, t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace
                       WHERE n.nspname='sm_data' AND NOT t.tgisinternal ORDER BY 1,2""")
        trigs = cur.fetchall()
        cur.execute("""SELECT c.relname, count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace
                       WHERE n.nspname='sm_data' GROUP BY 1 ORDER BY 1""")
        idx = cur.fetchall()
    rec.say(f"CATALOG tables ({len(tabs)}): {', '.join(tabs)}")
    rec.say(f"CATALOG triggers: {trigs}")
    rec.say(f"CATALOG indexes per table: {idx}")
    return tabs, trigs, idx


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", default="agw-pg14-evidence")
    ap.add_argument("--port", type=int, default=55432)
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "runs", "dev"))
    ap.add_argument("--only", default=None, help="substring filter on 'obligation/leg'")
    a = ap.parse_args()

    out = os.path.abspath(a.out)
    rec = Recorder(out)
    started = datetime.now(timezone.utc).isoformat()
    rec.say(f"# Agent Gateway PG 14 evidence harness — run started {started}")
    blocks = Blocks(rec)
    rec.say(f"# commit under test: {blocks.man['commit']}")

    container_up(rec, a.container, a.port, a.fresh)
    with psycopg.connect(host="127.0.0.1", port=a.port, dbname=DB, user=SUPER,
                         password=SUPERPW) as c, c.cursor() as cur:
        cur.execute("select version()")
        version = cur.fetchone()[0]
    rec.say(f"# select version() -> {version}")

    bootstrap(rec, blocks, a.container)
    ok = True
    for label, specs in DESIGN_GROUPS:
        ok &= apply_group(rec, blocks, a.container, label, specs)
    if not ok:
        rec.say("!! DDL/DCL did not apply; legs cannot run")
        meta = dict(rec.meta, version=version, container=a.container, port=a.port,
                    started=started, finished=datetime.now(timezone.utc).isoformat())
        write_reports(rec, out, meta)
        return 1

    ctx = Ctx(rec, blocks, a.port)
    ctx.container = a.container
    catalog_snapshot(rec, ctx)

    for obligation, name, fn in LEGS:
        key = f"{obligation}/{name}"
        if a.only and a.only not in key:
            continue
        rec.say("=" * 100)
        rec.say(f"## {key}")
        try:
            ctx.reset()
            fn(ctx, rec)
        except Exception as e:
            rec.say(traceback.format_exc())
            rec.record(LegResult(obligation, name, "production", "leg completes",
                                 f"harness/SQL exception: {type(e).__name__}: {str(e).splitlines()[0][:200]}",
                                 getattr(e, "sqlstate", "") or "", 0, "FAIL"))

    meta = dict(rec.meta, version=version, container=a.container, port=a.port,
                started=started, finished=datetime.now(timezone.utc).isoformat())
    counts = write_reports(rec, out, meta)
    rec.say("=" * 100)
    rec.say(f"RESULT {counts}")
    # BLOCKED is not green: a dependency the design set does not provide still fails the run.
    return 1 if (counts.get("FAIL") or counts.get("BLOCKED-dependency")) else 0


if __name__ == "__main__":
    sys.exit(main())

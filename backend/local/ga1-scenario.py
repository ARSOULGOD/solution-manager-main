#!/usr/bin/env python3
"""Plan 015 T5.1 (G-A1): the scenario, driven ONLY through the served API (login + CSRF, as the Postman collection does).

Run through backend/local/ga1-agent.sh (it sets the environment). Steps are named and re-runnable from the saved state in
<state>/ga1/scenario.json:   setup enrol apply1 metrics killsm undeclare drain revoke retire      (default: all, in that order)
    ga1-agent.sh scenario                      -> every step
    GA1_STEPS="killsm undeclare" ga1-agent.sh scenario

EVERY expectation is an assertion (check()): a failed one raises, the run prints the PASS/FAIL table and exits 1. A measured finding that the
harness must not paper over (a crash-recovery path that diverges from the IT) exits 3 after everything it can record has been recorded.

Reads the database ONLY for evidence (SELECT through `docker exec ... psql`, the password passed by NAME only); writes nothing but through the
API. The enrolment token, the credentials, nonces, digests and keys are never printed (every value known to be a secret is masked as well).
"""
import atexit, datetime, json, os, re, shlex, subprocess, sys, time, urllib.request, urllib.error

DIR = os.environ["GA1_DIR"]
STATE = os.environ["GA1_STATE"]
SCRIPT = os.environ["GA1_SCRIPT"]
CACHE = os.environ["GA1_CACHE"]
CONF = os.environ["GA1_CONF"]
APP = "http://127.0.0.1:%s" % os.environ.get("SM_LOCAL_PORT", "8080")
ORIGIN = "http://localhost:%s" % os.environ.get("SM_LOCAL_PORT", "8080")
SAVE = os.path.join(DIR, "scenario.json")
TARGET_NAME = "ga1-agent-host"
INSTANCE_LABEL = os.environ.get("GA1_INSTANCE_LABEL", "ga1-cache")
SECRETS = []  # exact strings never to print
# Extra docker CLI arguments for the CONTAINER probes only (a remote daemon); database calls always use the local daemon.
PROBE = shlex.split(os.environ.get("GA1_PROBE_DOCKER_ARGS", ""))
# G-A2 (plan 015 T5.3): the same scenario against a Cache on a remote host. Every variable below is OPTIONAL; with none set the behaviour is G-A1's.
#   GA1_PROBE_CMD       container probes run [GA1_PROBE_CMD..., "docker", args...] instead of the local docker CLI
#   GA1_EVENTS_CMD      the `docker events` collector runs through [GA1_EVENTS_CMD..., "events-start", <container>] / "events-stop" <container>
#   GA1_CONF_CHECK_CMD  cache_file_holds runs [GA1_CONF_CHECK_CMD..., <a|r>, <value>] and reads "match" / "no-match"
#   GA1_BROKER_HOST GA1_BROKER_VPN GA1_BROKER_USERNAME GA1_NAMESPACE   what the Cache's broker connection is staged with
#   GA1_BROKER_PASSWORD_ENV   the NAME of an environment variable holding the broker password; it goes through setConfigSecret, never staged
#   GA1_TOPIC_PREFIX    when set, cache-hit-topic / cache-miss-topic are staged under it (a test Cache must not share topics with another)
#   GA1_CONTAINER_PREFIX / GA1_ALLOWED_CONFIG_ROOT   the agent's envelope (defaults: ga1- and the Cache's local config directory)
#   GA1_KNOWN_FINDINGS  space-separated finding ids (m3) recorded as FINDING while the run continues; the exit code is then 3
#   GA1_DPREMOTE=on     the Step 3 steps (remote_*) join STEPS after `metrics`; GA1_CACHE_R, GA1_R_CONFIG_DIR, GA1_R_HEALTH_PORT, GA1_R_ACQUISITION
#   GA1_MASK_ENV        space-separated environment variable NAMES whose values are masked in every line (any length of 3 or more)
PROBE_CMD = shlex.split(os.environ.get("GA1_PROBE_CMD", ""))
EVENTS_CMD = shlex.split(os.environ.get("GA1_EVENTS_CMD", ""))
CONF_CHECK_CMD = shlex.split(os.environ.get("GA1_CONF_CHECK_CMD", ""))
BROKER_HOST = os.environ.get("GA1_BROKER_HOST", "tcp://ga1-broker:55555")
BROKER_VPN = os.environ.get("GA1_BROKER_VPN", "default")
BROKER_USERNAME = os.environ.get("GA1_BROKER_USERNAME", "default")
NAMESPACE = os.environ.get("GA1_NAMESPACE", "ga1")
PASSWORD_ENV = os.environ.get("GA1_BROKER_PASSWORD_ENV", "")
TOPIC_PREFIX = os.environ.get("GA1_TOPIC_PREFIX", "")
CONTAINER_PREFIX = os.environ.get("GA1_CONTAINER_PREFIX", "ga1-")
ALLOWED_CONFIG_ROOT = os.environ.get("GA1_ALLOWED_CONFIG_ROOT", "")
KNOWN_FINDINGS = os.environ.get("GA1_KNOWN_FINDINGS", "").split()
DPREMOTE = os.environ.get("GA1_DPREMOTE", "") == "on"
CACHE_R = os.environ.get("GA1_CACHE_R", "")
R_CONFIG_DIR = os.environ.get("GA1_R_CONFIG_DIR", "")
R_HEALTH_PORT = int(os.environ.get("GA1_R_HEALTH_PORT", "18282"))
R_ACQUISITION = os.environ.get("GA1_R_ACQUISITION", "via-ssh")
TABLE_TITLE = os.environ.get("GA1_TABLE_TITLE", "G-A1 results")
HEALTH_WAIT = int(os.environ.get("GA1_HEALTH_WAIT", "0"))
FINDINGS = []  # (id, message) recorded while the run continued
EXPECTED_METRICS = {"cache.eviction_count", "cache.hit_count", "cache.hit_rate", "cache.miss_count", "cache.size"}


class Failure(SystemExit):
    """A failed assertion or a failed probe: the run stops, exit 1."""
    def __init__(self, msg):
        super().__init__("FAIL: " + msg)


def raise_finding(fid, msg):
    """A finding listed in GA1_KNOWN_FINDINGS is recorded and the run CONTINUES (exit 3 at the end); an unlisted one stops the run as before."""
    if fid in KNOWN_FINDINGS:
        FINDINGS.append((fid, msg))
        RESULTS.append((CURRENT[0], "FINDING %s (known, the run continued)" % fid, "FINDING", short(msg)[:300]))
        log("FINDING %s (known; the run continues): %s" % (fid, short(msg)))
        return
    raise Finding(msg)


class Finding(SystemExit):
    """A measured divergence the harness must report, not hide: exit 3."""
    def __init__(self, msg):
        self.code = 3
        super().__init__("FINDING: " + msg)
        self.code = 3


def load_env(path):
    out = {}
    for line in open(path):
        if "=" in line:
            k, v = line.rstrip("\n").split("=", 1)
            out[k] = v
    return out


secrets = load_env(os.path.join(STATE, "secrets.env"))
ids = load_env(os.path.join(STATE, "ids.env"))
SECRETS += [secrets.get(k, "") for k in secrets if k.endswith("PASSWORD")]
for _name in os.environ.get("GA1_MASK_ENV", "").split():  # G-A2: host, VPN, username, password, root path: masked whatever their length
    if len(os.environ.get(_name, "")) >= 3:
        SECRETS.append(os.environ[_name])
SECRETS.sort(key=len, reverse=True)  # a longer value that contains a shorter one is replaced first
for _extra in (os.path.join(DIR, "pki", "ks.pw"),):  # belt and braces: the keystore password is never in this process, but never printed either
    if os.path.exists(_extra):
        SECRETS.append(open(_extra).read().strip())


def short(text):
    return re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", lambda m: m.group(0)[:8], text)


MASK_ANY = set(os.environ.get(n, "") for n in os.environ.get("GA1_MASK_ENV", "").split() if len(os.environ.get(n, "")) >= 3)


def mask(text):
    for s in SECRETS:
        if s and (len(s) >= 8 or s in MASK_ANY):
            text = text.replace(s, "<redacted>")
    return text


def log(msg):
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), mask(str(msg))), flush=True)


RESULTS = []  # (step, name, status, detail)
CURRENT = ["-"]


def check(name, ok, detail=""):
    """Record one assertion; a false one raises (explicit raise, not `assert`, so -O cannot strip it)."""
    status = "PASS" if ok else "FAIL"
    RESULTS.append((CURRENT[0], name, status, short(str(detail))))
    log("CHECK %s: %s%s" % (status, name, (" - " + short(str(detail))) if detail != "" else ""))
    if not ok:
        raise Failure("%s%s" % (name, (" (" + short(str(detail)) + ")") if detail != "" else ""))


def note_unmeasured(name, why):
    RESULTS.append((CURRENT[0], name, "NOT MEASURED", why))
    log("NOT MEASURED: %s - %s" % (name, why))


def load_state():
    return json.load(open(SAVE)) if os.path.exists(SAVE) else {}


def save_state(st):
    with open(SAVE, "w") as f:
        json.dump(st, f, indent=1)
    os.chmod(SAVE, 0o600)


# ---- the API client -------------------------------------------------------------------------------------------------------------
class Api:
    def __init__(self):
        self.session = None
        self.csrf = None

    def login(self):
        code, body, headers = self._send("POST", "/api/cp/auth/login", {"username": "bootstrap-admin", "credential": secrets["SM_LOCAL_ADMIN_NEW_PASSWORD"]}, auth=False)
        if code != 200:
            raise Failure("login answered %s" % code)
        for h in headers.get_all("Set-Cookie") or []:
            pair = h.split(";", 1)[0]
            if pair.startswith("__Host-SM_SESSION="):
                self.session = pair
        self.csrf = body.get("csrfToken")
        if not (self.session and self.csrf):
            raise Failure("login returned no session or CSRF token")

    def _send(self, method, path, body=None, auth=True):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(APP + path, data=data, method=method)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if method != "GET":
            req.add_header("Origin", ORIGIN)
            if auth:
                req.add_header("X-SM-CSRF", self.csrf or "")
        if auth and self.session:
            req.add_header("Cookie", self.session)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                raw = resp.read().decode()
                return resp.status, (json.loads(raw) if raw.strip() else {}), resp.headers
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                parsed = json.loads(raw) if raw.strip() else {}
            except ValueError:
                parsed = {"raw": raw[:200]}
            return e.code, parsed, e.headers

    def call(self, method, path, body=None, expect=(200,), note=None, quiet=False):
        code, parsed, _ = self._send(method, path, body)
        if code == 401:  # the session was lost (SM restarted): sign in once more
            self.login()
            code, parsed, _ = self._send(method, path, body)
        detail = "" if code in expect else " %s" % json.dumps({k: parsed.get(k) for k in ("code", "title", "detail", "status") if isinstance(parsed, dict)})
        if not quiet or code not in expect:
            log("%s %s -> %s%s%s" % (method, short(path), code, detail, (" (%s)" % note) if note else ""))
        if code not in expect:
            raise Failure("unexpected status %s for %s %s" % (code, method, short(path)))
        return parsed


api = Api()


# ---- evidence helpers (reads only; every probe fails loudly) ----------------------------------------------------------------------
def psql(sql, **variables):
    """SELECT through `docker exec ... psql` on the LOCAL daemon. The password goes by NAME (-e PGPASSWORD); its value is only in this subprocess's environment."""
    cmd = ["docker", "exec", "-i", "-e", "PGPASSWORD", os.environ.get("GA1_PG", "ga1-pg"), "psql", "-h", "127.0.0.1", "-U", "postgres",
           "-d", "sm", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1"]
    for k, v in variables.items():
        cmd += ["-v", "%s=%s" % (k, v)]
    cmd += ["-f", "-"]
    env = dict(os.environ)
    env["PGPASSWORD"] = secrets["SM_LOCAL_SUPER_PASSWORD"]
    out = subprocess.run(cmd, input=sql, env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise Failure("psql failed: " + mask(out.stderr[:300]))
    return [line.split("|") for line in out.stdout.strip().splitlines() if line]


def db_now():
    return psql("select now()::text")[0][0]


def db_epoch():
    return float(psql("select extract(epoch from clock_timestamp())")[0][0])


def docker_probe(*args):
    """A container probe: the explicit docker context/host (GA1_PROBE_DOCKER_ARGS), or the remote command (GA1_PROBE_CMD); checked, never silent."""
    cmd = (PROBE_CMD + ["docker"] if PROBE_CMD else ["docker"] + PROBE) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise Failure("docker %s failed: %s" % (args[0], mask(r.stderr.strip()[:200])))
    return r.stdout.strip()


def started_at(name=None):
    out = docker_probe("inspect", "-f", "{{.State.StartedAt}}|{{.State.Running}}", name or CACHE)
    if not re.match(r"^\d{4}-\d\d-\d\dT[\d:.]+Z\|(true|false)$", out):
        raise Failure("unexpected StartedAt probe output")
    return out


def container_id():
    return docker_probe("inspect", "-f", "{{.Id}}", CACHE)[:8]


def container_running(name=None):
    return started_at(name).endswith("|true")


class StartWatcher:
    """A live `docker events` collector. The daemon's history is short and lossy, so it is started BEFORE the submit; it is proven attached
    by a canary (`docker top` on the container emits a `top` event), not by a sleep. With GA1_EVENTS_CMD the collector runs on the remote host
    (events-start proves the attachment there, the same way) and events-stop returns what it saw."""
    def __init__(self, path, name=None):
        self.name = name or CACHE
        self.path = path
        self.stopped = False
        if EVENTS_CMD:
            r = subprocess.run(EVENTS_CMD + ["events-start", self.name], capture_output=True, text=True)
            if r.returncode != 0:
                raise Failure("events-start failed: %s" % mask((r.stderr or "")[-200:]))
            atexit.register(lambda: None if self.stopped else subprocess.run(EVENTS_CMD + ["events-stop", self.name], capture_output=True, text=True))
            return
        self.f = open(path, "w")
        self.p = subprocess.Popen(["docker"] + PROBE + ["events", "--filter", "container=" + self.name, "--format", "{{.Action}}"], stdout=self.f, stderr=subprocess.PIPE, text=True)

        atexit.register(lambda: self.p.poll() is None and self.p.terminate())   # never leaves an orphan `docker events`, whatever raises

        def attached():
            if self.p.poll() is not None:
                raise Failure("docker events exited early: %s" % mask((self.p.stderr.read() or "")[:200]))
            docker_probe("top", self.name)
            self.f.flush()
            return "top" in open(self.path).read().splitlines()
        wait_for("the docker events collector to attach (canary)", attached, 20, 0.5)

    def stop(self):
        if EVENTS_CMD:
            r = subprocess.run(EVENTS_CMD + ["events-stop", self.name], capture_output=True, text=True)
            self.stopped = True
            if r.returncode != 0:
                raise Failure("docker events died during the window: %s" % mask((r.stderr or "")[-200:]))
            return [l for l in r.stdout.splitlines() if l.strip()].count("start")
        alive = self.p.poll() is None
        self.p.terminate()
        try:
            self.p.wait(10)
        except subprocess.TimeoutExpired:
            self.p.kill()
        self.f.close()
        if not alive:
            raise Failure("docker events died during the window")
        return [l for l in open(self.path).read().splitlines() if l.strip()].count("start")


def wait_for(what, fn, seconds=120, every=1.0):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    raise Failure("timed out after %ss waiting for %s" % (seconds, what))


def sh(*args, env=None, ok=True):
    e = dict(os.environ)
    e.update(env or {})
    r = subprocess.run(list(args), env=e, capture_output=True, text=True)
    if ok and r.returncode != 0:
        raise Failure("%s failed: %s" % (os.path.basename(args[0]) + " " + " ".join(args[1:2]), mask(r.stderr[-300:])))
    return r


def agent_log_size():
    return int(sh(SCRIPT, "agent-log-size").stdout.strip())


def agent_log_since(offset):
    return sh(SCRIPT, "agent-log-since", str(offset)).stdout


def stage_body(base, max_size, ns=None):
    ns = ns or NAMESPACE
    topics = []
    if TOPIC_PREFIX:   # a test Cache never shares a topic with another Cache on the broker
        topics = [("cache-hit-topic", "%s/%s/hit" % (TOPIC_PREFIX, ns)), ("cache-miss-topic", "POST/%s/%s/miss" % (TOPIC_PREFIX, ns))]
    rendered = [("mode", "READ"), ("provider", "caffeine"), ("namespace", ns), ("key-source", "TOPIC"), ("max-size", max_size)] + topics + [
                ("solace.enabled", True), ("solace.host", BROKER_HOST), ("solace.vpn", BROKER_VPN), ("solace.username", BROKER_USERNAME),
                ("solace.messaging-mode", "MESSAGING"), ("solace.management.enabled", False)]
    draft = {"namespace": ns, "key-source": "TOPIC", "max-size": max_size, "solace.enabled": True, "solace.host": BROKER_HOST,
             "solace.vpn": BROKER_VPN, "solace.username": BROKER_USERNAME}
    draft.update(dict(topics))
    return {
        "baseStateVersion": base,
        "componentSelection": {"instances": [{"instanceKey": "i1", "type": "cache-engine", "layout": {"x": 10, "y": 20}}]},
        "draftValues": {"i1": draft},
        "rendered": [{"instanceKey": "i1", "renderedPath": "cache-engine." + k, "value": v} for k, v in rendered],
    }


def instance_version(st, instance_id=None):
    return api.call("GET", "/api/dp/instances/%s" % (instance_id or st["instanceId"]), quiet=True)["stateVersion"]


PASSWORD_FIELD = "solace.password"   # the secret field of contracts/validation/fixtures/cache-2.4.package.json


def stage_and_apply(st, max_size, label, instance_id=None, placement_id=None, ns=None):
    instance_id, placement_id = instance_id or st["instanceId"], placement_id or st["placementId"]
    gen = api.call("POST", "/api/dp/instances/%s/config" % instance_id, stage_body(instance_version(st, instance_id), max_size, ns), note="stageConfig %s" % label)
    if PASSWORD_ENV:   # G-A2: the real broker password goes through setConfigSecret (stage, then store; apply THAT response's generation); it is never staged
        value = os.environ.get(PASSWORD_ENV, "")
        if not value:
            raise Failure("the environment variable named by GA1_BROKER_PASSWORD_ENV is not set")
        SECRETS.append(value)
        gen = api.call("POST", "/api/dp/instances/%s/config/secrets/%s" % (instance_id, PASSWORD_FIELD),
                       {"value": value, "baseStateVersion": gen["stateVersion"]}, note="setConfigSecret %s (the value is never printed)" % label)
    accepted = api.call("POST", "/api/dp/instances/%s/apply" % instance_id,
                        {"generation": gen["generation"], "strategy": "restart-all", "placementId": placement_id, "outageConfirmedBy": "ga1"},
                        expect=(202,), note="submitApply restart-all generation %s" % gen["generation"])
    return gen["generation"], accepted["applyId"]


def literal_paths(obj, needle, path="$"):
    """The JSON paths whose string (or key) contains needle; each field-list element is named by its key. Never returns a value."""
    out = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if needle in str(k):
                out.append(path + ".<key>")
            out += literal_paths(v, needle, "%s.%s" % (path, k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            name = (v.get("key") or v.get("renderedPath")) if isinstance(v, dict) else None
            tag = name if isinstance(name, str) and needle not in name else i
            out += literal_paths(v, needle, "%s[%s]" % (path, tag))
    elif isinstance(obj, str) and needle in obj:
        out.append(path + (" (whole value)" if obj == needle else " (inside a longer string)"))
    return out


PLAIN_BROKER_FIELDS = ("solace.username", "solace.vpn", "solace.host")   # staged as plain values by stage_body


def designer_secret_checks(instance_id, label):
    """The designer shows the password field as secretRef (a reference), never a value, and the literal is nowhere in the response."""
    raw = api.call("GET", "/api/dp/instances/%s/designer" % instance_id, quiet=True)
    fields = [f for c in raw["viewModel"]["components"] for sec in c["sections"] for f in sec["fields"] if f["key"] == PASSWORD_FIELD]
    check("%s: the designer lists the password field exactly once" % label, len(fields) == 1, len(fields))
    check("%s: the password field is a secretRef, never a value" % label, fields[0]["value"].get("kind") == "secretRef", fields[0]["value"].get("kind"))
    # The literal must appear nowhere but in a PLAIN field we staged ourselves with the same text: on a broker whose client username equals its
    # password (the E2E broker's), the username field legitimately shows that text, and it is excluded by key; the password field never is.
    pw = os.environ.get(PASSWORD_ENV, "\0")
    scrubbed = json.loads(json.dumps(raw))
    excluded = []
    for c in scrubbed["viewModel"]["components"]:
        for sec in c["sections"]:
            for f in sec["fields"]:
                if f["key"] in PLAIN_BROKER_FIELDS and pw in json.dumps(f.get("value")):
                    f["value"] = None
                    excluded.append(f["key"])
    for r in scrubbed["viewModel"].get("rendered", []):
        if isinstance(r, dict) and any(str(r.get("renderedPath", "")).endswith("." + k) for k in PLAIN_BROKER_FIELDS) and pw in json.dumps(r.get("value")):
            r["value"] = None
            excluded.append(r["renderedPath"])
    hits = literal_paths(scrubbed, pw)
    check("%s: the designer response does not contain the password literal" % label, not hits,
          "; ".join(filter(None, [("excluded the plain field(s) staged with the same text: %s" % ", ".join(sorted(set(excluded)))) if excluded else "",
                                  ("found at (paths only, never the value): %s" % ", ".join(hits[:12])) if hits else ""])))


def observe(st, apply_id, seconds=150, instance_id=None):
    seen = []

    def terminal():
        r = api.call("GET", "/api/dp/instances/%s/apply/observe?applyId=%s" % (instance_id or st["instanceId"], apply_id), quiet=True)
        items = r.get("items", [r])
        for i in items:
            if isinstance(i, dict) and i.get("state") not in seen:
                seen.append(i.get("state"))
        fin = [i for i in items if isinstance(i, dict) and i.get("terminal")]
        return fin[-1] if fin else None

    final = wait_for("apply %s terminal" % apply_id[:8], terminal, seconds, 2.0)
    log("apply states seen: %s; final state=%s" % (" -> ".join(str(s) for s in seen), final.get("state")))
    return final, seen


def cache_file_holds(max_size, which="a"):
    """Match / no-match only: the Cache's application.yml (bind-mounted, local; or on the remote host through GA1_CONF_CHECK_CMD) holds `max-size: <value>`.
    The file is never printed."""
    if CONF_CHECK_CMD:
        out = subprocess.run(CONF_CHECK_CMD + [which, str(max_size)], capture_output=True, text=True)
        if out.returncode != 0 or out.stdout.strip() not in ("match", "no-match"):
            raise Failure("the remote config check failed: %s" % mask((out.stderr or out.stdout)[-200:]))
        return out.stdout.strip() == "match"
    text = open(os.path.join(CONF, "application.yml")).read()
    return re.search(r"(?m)^\s*max-size:\s*%d\s*$" % max_size, text) is not None


def ladder_checks(st, generation, label):
    """The ladder reached restart-observed AT this generation, and the two host-read rungs carry the SAME read-back hash (ConfigLadderView.java:10-13:
    persisted and restart-observed are the keyed hash of the file bytes read back; they may be compared with each other, never with desired/rendered)."""
    ladder = api.call("GET", "/api/dp/instances/%s/config-ladder" % st["instanceId"], quiet=True)
    rungs = {r["rung"]: r for r in ladder["rungs"]}
    log("getConfigLadder (%s): reached=%s; rungs=%s; degradedReason=%s" % (label, ladder.get("reached"),
        [(r["rung"], r["present"], r.get("generation")) for r in ladder["rungs"]], ladder.get("degradedReason")))
    check("%s: ladder reached restart-observed" % label, ladder.get("reached") == "restart-observed", ladder.get("reached"))
    ro, pe = rungs["restart-observed"], rungs["persisted"]
    check("%s: restart-observed rung present at generation %s" % (label, generation), ro["present"] and ro.get("generation") == generation, ro.get("generation"))
    check("%s: persisted rung present at the same generation" % label, pe["present"] and pe.get("generation") == generation, pe.get("generation"))
    check("%s: persisted hash == restart-observed hash (compared, never printed)" % label,
          bool(pe.get("configHash")) and pe.get("configHash") == ro.get("configHash"))
    return ladder


def command_rows(apply_id):
    return psql("select operation, state, coalesce(outcome,'-') from sm_data.agent_command where apply_id = :'a' order by created_at", a=apply_id)


# ---- the steps ------------------------------------------------------------------------------------------------------------------
def step_setup(st):
    log("== setup: sign in as bootstrap-admin, load the cache package")
    api.login()
    fixture = json.load(open(os.environ["GA1_FIXTURE"]))
    api.call("POST", "/api/cp/solutions/load", {"package": fixture}, expect=(200, 201), note="loadPackage")
    listing = api.call("GET", "/api/cp/solutions")
    found = [s for s in listing["items"] if "cache" in json.dumps(s).lower()]
    check("the cache solution is listed after the package load", len(found) >= 1)
    cache = found[0]
    st["solutionId"] = cache["solutionId"]
    pv = cache["packageVersions"][-1]
    check("a package version is known", isinstance(pv, int), pv)
    st["packageVersion"] = pv
    st["environmentId"] = ids["SM_LOCAL_ENVIRONMENT_ID"]
    log("cache solution loaded (packageVersions=%s); environment ACME-Sub-1/Dev" % cache["packageVersions"])


def step_enrol(st):
    log("== enrol: issue a token, start the REAL agent, see the target and the instance")
    t0 = time.time()
    enrol = api.call("POST", "/api/cp/environments/%s/agent-enrolments" % st["environmentId"],
                     {"targetName": TARGET_NAME, "allowedConfigRoot": ALLOWED_CONFIG_ROOT or os.path.realpath(CONF), "allowedContainerPrefix": CONTAINER_PREFIX,
                      "solutionId": st["solutionId"], "packageVersion": st["packageVersion"]}, expect=(201,), note="issueAgentEnrolment")
    SECRETS.append(enrol["enrollmentToken"])
    st["agentId"] = enrol["agentId"]
    log("token issued (shown once, not printed); agent %s, expires %s; envelope = the Cache's config directory and prefix %s" % (enrol["agentId"][:8], enrol["expiresAt"], CONTAINER_PREFIX))
    save_state(st)
    sh(SCRIPT, "agent-start", env={"SM_AGENT_ENROLL_TOKEN": enrol["enrollmentToken"]})
    log("sm-agent started with the token in its environment (first start enrols)")

    def active():
        a = api.call("GET", "/api/cp/agents/%s" % st["agentId"], quiet=True)
        return a if a["status"] == "active" else None

    agent = wait_for("the agent to be active", active, 90, 2.0)
    st["targetId"] = agent["targetId"]
    check("the agent is active with a connected session", (agent.get("session") or {}).get("state") == "connected", (agent.get("session") or {}).get("state"))
    targets = api.call("GET", "/api/cp/targets")["items"]
    mine = [t for t in targets if t["targetId"] == st["targetId"]]
    check("listTargets shows the agent target with transport agent", len(mine) == 1 and mine[0]["transport"] == "agent", mine[0]["transport"] if mine else "absent")

    def instance():
        items = api.call("GET", "/api/dp/solutions/%s/instances" % st["solutionId"], quiet=True)["items"]
        found = [i for i in items if i["name"] == INSTANCE_LABEL]
        return found[0] if found else None

    inst = wait_for("the Cache to be auto-registered as an instance (OR-27)", instance, 150, 3.0)
    st["instanceId"] = inst["instanceId"]
    full = api.call("GET", "/api/dp/instances/%s" % st["instanceId"])
    check("the registered instance has exactly one placement", len(full["placements"]) == 1, len(full["placements"]))
    st["placementId"] = full["placements"][0]["placementId"]
    check("the instance is acquired through the agent", (inst.get("acquisition") or {}).get("mode") == "agent", json.dumps(inst.get("acquisition")))
    rows = psql("select state from sm_data.agent_discovery where instance_id = :'i'", i=st["instanceId"])
    check("the agent_discovery row is REGISTERED", rows == [["REGISTERED"]], rows)
    log("instance '%s' present after %.0fs" % (inst["name"], time.time() - t0))
    save_state(st)


def step_apply1(st):
    log("== apply1: declare-free apply with restart-all, restart observed")
    api.login()
    st["apply1_db_start"] = db_now()   # the metrics step counts only samples taken from here on
    before = started_at()
    gen, apply_id = stage_and_apply(st, 260000, "first")
    st["apply1"], st["apply1_gen"] = apply_id, gen
    final, seen = observe(st, apply_id)
    after = started_at()
    log("container %s StartedAt before=%s after=%s" % (container_id(), before, after))
    check("apply1 final state is APPLIED", final.get("state") == "APPLIED", " -> ".join(str(s) for s in seen))
    check("apply1: the container's start time moved", before != after, "%s -> %s" % (before, after))
    check("apply1: the container is running", after.endswith("|true"))
    steps = [(x["step"], x["outcome"]) for x in final.get("steps", [])]
    log("final apply observation steps=%s" % steps)
    check("apply1: every observed step succeeded", len(steps) >= 1 and all(o == "succeeded" for _, o in steps), steps)
    health = api.call("GET", "/api/dp/instances/%s/health" % st["instanceId"])
    if HEALTH_WAIT and [p["state"] for p in health["placements"]] != ["GREEN"]:
        # G-A2: the agent's health report that follows a restart can lag the apply by a report interval, and the one read just after it may be the
        # restart's own (the port was closed); the assertion is unchanged, it is read until GREEN or the bound (G-A1: no wait).
        log("getHealth read %s just after the restart; reading until GREEN (bound %ss)" % ([p["state"] for p in health["placements"]], HEALTH_WAIT))
        t_h = time.time()

        def green():
            h = api.call("GET", "/api/dp/instances/%s/health" % st["instanceId"], quiet=True)
            return h if [p["state"] for p in h["placements"]] == ["GREEN"] else None
        try:
            health = wait_for("getHealth to read GREEN", green, HEALTH_WAIT, 3.0)
            log("getHealth read GREEN %.0fs after the first read" % (time.time() - t_h))
        except SystemExit:
            pass
    check("apply1: getHealth is GREEN", [p["state"] for p in health["placements"]] == ["GREEN"], [p["state"] for p in health["placements"]])
    ladder_checks(st, gen, "apply1")
    check("apply1: the Cache's application.yml holds max-size 260000 (match/no-match)", cache_file_holds(260000))
    if PASSWORD_ENV:
        designer_secret_checks(st["instanceId"], "apply1")
    cmds = command_rows(apply_id)
    log("agent_command rows of this apply (read): %s" % cmds)
    check("apply1: the three commands PREFLIGHT, APPLY, RESTART are all ACKED",
          [c[0] for c in cmds] == ["PREFLIGHT", "APPLY", "RESTART"] and all(c[1] == "ACKED" for c in cmds), cmds)
    st["start_before_apply1"], st["start_after_apply1"] = before, after
    save_state(st)


def step_metrics(st):
    log("== metrics: five agent series, each with at least one sample taken since the apply started")

    def rows():
        r = psql("select s.metric_name, count(m.*) from sm_metrics.series s join sm_metrics.metrics_raw m on m.tenant_id = s.tenant_id and m.series_id = s.series_id "
                 "where s.instance_id = :'i' and s.source_discriminator = 'agent' and m.ts >= :'since'::timestamptz group by 1 order by 1",
                 i=st["instanceId"], since=st["apply1_db_start"])
        return r if EXPECTED_METRICS <= {x[0] for x in r} else None

    r = wait_for("samples of all five agent series since the apply started", rows, 240, 5.0)
    for x in r:
        log("series %s source=agent samples-since-apply=%s" % tuple(x))
    check("five distinct agent series each have >= 1 sample since the apply started",
          EXPECTED_METRICS <= {x[0] for x in r if int(x[1]) >= 1}, sorted(x[0] for x in r))
    st["metricsRows"] = r
    save_state(st)


def restart_row(apply_id):
    r = psql("select received_at is not null, acked_at is not null, state from sm_data.agent_command where apply_id = :'a' and operation = 'RESTART'", a=apply_id)
    return r[0] if r else None


def step_killsm(st):
    log("== killsm: kill SM between CommandReceived and CommandAck of a RESTART; restart SM; measure how it recovers")
    api.login()
    events_path = os.path.join(DIR, "starts.txt")
    for attempt in range(3):   # the first try and at most two retries of the whole step
        max_size = 270000 + attempt
        before = started_at()
        watcher = StartWatcher(events_path)
        gen, apply_id = stage_and_apply(st, max_size, "second (attempt %d)" % (attempt + 1))
        st["apply2"], st["apply2_gen"], st["apply2_max"] = apply_id, gen, max_size
        pid = int(open(os.path.join(STATE, "launcher.pid")).read().strip())

        def window():
            row = restart_row(apply_id)
            if row is None or row[0] != "t":
                return None
            return ("hit" if row[1] == "f" else "missed", row)

        verdict, snap = wait_for("the RESTART command to be received by the agent", window, 120, 0.1)
        if verdict == "hit":
            os.kill(pid, 9)
            db_kill = db_epoch()
            frozen = restart_row(apply_id)   # the row as the dead DP left it: nothing can change it now
            log("kill -9 of the launcher (pid %d); RESTART row [received, acked, state] at the poll %s, as left by the dead DP %s" % (pid, snap, frozen))
            if frozen[0] == "t" and frozen[1] == "f":
                break
            log("the ack landed between the poll and the kill: window missed")
        else:
            log("the RESTART row was already acked at the first sighting %s: window missed" % (snap,))
            db_kill = None
        # Missed: bring SM back if it was killed, let the apply finish, and try again with a new generation.
        if verdict == "hit":
            sh(SCRIPT, "restart-sm"); api.login()
        observe(st, apply_id, 240)
        watcher.stop()
        if attempt == 2:
            check("the kill landed between CommandReceived and CommandAck (3 attempts)", False, "window missed every time")
    RESULTS.append((CURRENT[0], "the kill landed with the RESTART row received and NOT acked (attempt %d)" % (attempt + 1), "PASS", "row %s" % (frozen,)))
    log("CHECK PASS: the kill landed with the RESTART row received and not acked (attempt %d)" % (attempt + 1))
    st["killWindow"] = {"attempt": attempt + 1, "frozenRow": frozen}
    sh(SCRIPT, "restart-sm")
    db_restarted = db_epoch()
    log("SM restarted %.1fs after the kill" % (db_restarted - db_kill))
    api.login()
    final, seen = observe(st, apply_id, 240)

    # ---- M3: measure how the interrupted apply was settled, and when, relative to the agent's reconnect -------------------------------
    ar = psql("select state, coalesce(resolved_by,'-'), coalesce(resolution_outcome,'-'), coalesce(reconcile_class,'-'), extract(epoch from requested_at), "
              "coalesce(extract(epoch from terminal_at),0) from sm_data.apply_request where apply_id = :'a'", a=apply_id)[0]
    sess = psql("select fencing_epoch, extract(epoch from opened_at), extract(epoch from last_seen_at), coalesce(extract(epoch from closed_at),0) "
                "from sm_data.agent_session where agent_id = :'g'", g=st["agentId"])[0]
    rel = lambda e: "%+.1fs" % (float(e) - db_kill)
    log("M3 apply_request: state=%s resolved_by=%s resolution_outcome=%s reconcile_class=%s requested %s terminal %s (vs the kill)" % (ar[0], ar[1], ar[2], ar[3], rel(ar[4]), rel(ar[5])))
    log("M3 agent_session: fencing_epoch=%s opened %s last_seen %s closed %s (vs the kill)" % (sess[0], rel(sess[1]), rel(sess[2]), rel(sess[3]) if float(sess[3]) else "-"))
    log("M3 timing: SM answered again %s, the apply was terminal %s, vs the kill; states seen: %s" % (rel(db_restarted), rel(ar[5]), " -> ".join(str(s) for s in seen)))
    lines = []
    lp = os.path.join(STATE, "launcher.log")
    if os.path.exists(lp):
        for l in open(lp, errors="replace"):
            if re.search(r"apply-recovery|reconcil|session\.absent|APPLY_INDETERMINATE|indeterminate", l):
                lines.append(short(mask(l.rstrip()))[:330])
    log("M3 launcher log lines about recovery/reconciliation (%d):" % len(lines))
    for l in lines[:14]:
        log("   | " + l)
    st["m3"] = {"state": ar[0], "resolved_by": ar[1], "resolution_outcome": ar[2], "reconcile_class": ar[3],
                "requested_vs_kill": rel(ar[4]), "terminal_vs_kill": rel(ar[5]), "sm_back_vs_kill": rel(db_restarted),
                "session_opened_vs_kill": rel(sess[1]), "states": seen, "log_lines": lines[:14]}

    # ---- the assertions that do not depend on which path settled it ------------------------------------------------------------------
    db_terminal = db_epoch()
    ts_terminal = db_now()

    def two_more_reports():
        r = psql("select count(m.*) from sm_metrics.series s join sm_metrics.metrics_raw m on m.tenant_id = s.tenant_id and m.series_id = s.series_id "
                 "where s.instance_id = :'i' and s.source_discriminator = 'agent' and s.metric_name = 'cache.size' and m.received_at > :'t'::timestamptz",
                 i=st["instanceId"], t=ts_terminal)
        return int(r[0][0]) >= 2
    wait_for("two agent telemetry reports after the apply was terminal (a second restart would have happened inside that span)", two_more_reports, 120, 3.0)
    starts = watcher.stop()
    after = started_at()
    log("container StartedAt before=%s after=%s; docker 'start' events seen live from before the submit to now: %d" % (before, after, starts))
    check("killsm: exactly one container start event in the window", starts == 1, starts)
    check("killsm: the container's start time moved", before != after, "%s -> %s" % (before, after))
    check("killsm: the container is running", after.endswith("|true"))
    cmds = command_rows(apply_id)
    log("agent_command rows of the apply (read): %s" % cmds)
    check("killsm: the three commands are ACKED, one row per operation",
          [c[0] for c in cmds] == ["PREFLIGHT", "APPLY", "RESTART"] and all(c[1] == "ACKED" for c in cmds), cmds)
    ladder_checks(st, gen, "killsm")
    check("killsm: the Cache's application.yml holds max-size %d (match/no-match)" % max_size, cache_file_holds(max_size))
    check("killsm: the apply's outcome is 'applied' (state APPLIED, or INDETERMINATE_RESOLVED resolved as applied)",
          final.get("state") == "APPLIED" or (ar[0] == "INDETERMINATE_RESOLVED" and ar[2] == "applied"), "state=%s outcome=%s" % (ar[0], ar[2]))
    st["start_before_kill"], st["start_after_kill"], st["start_events_kill"] = before, after, starts
    save_state(st)
    if final.get("state") != "APPLIED":
        raise_finding("m3", "the interrupted apply did NOT settle APPLIED by startup recovery as AgentCommandCrashRecoveryIT:175 asserts: it went %s, resolved_by=%s "
                      "resolution_outcome=%s reconcile_class=%s, terminal %s after the kill (SM back %s). See the M3 lines above."
                      % (" -> ".join(str(s) for s in seen), ar[1], ar[2], ar[3], rel(ar[5]), rel(db_restarted)))


def step_undeclare(st):
    log("== undeclare: undeclareInstance, reconnect the agent, the instance is NOT re-registered")
    api.login()
    api.call("DELETE", "/api/dp/instances/%s?workload=leave-running" % st["instanceId"], expect=(200,), note="undeclareInstance")
    items = api.call("GET", "/api/dp/solutions/%s/instances" % st["solutionId"])["items"]
    check("listInstances no longer shows the instance after undeclare", INSTANCE_LABEL not in [i["name"] for i in items], [i["name"] for i in items])
    q = "select fencing_epoch from sm_data.agent_session where agent_id = :'g'"
    before = psql(q, g=st["agentId"])[0][0]
    log_offset = None
    sh(SCRIPT, "agent-stop")
    log_offset = agent_log_size()
    sh(SCRIPT, "agent-start")
    wait_for("the agent's new session", lambda: psql(q, g=st["agentId"])[0][0] != before, 60, 1.0)
    now_epoch = psql(q, g=st["agentId"])[0][0]
    check("the agent reconnected on a new session (epoch advanced)", int(now_epoch) > int(before), "%s -> %s" % (before, now_epoch))

    # Positive control: the agent REPORTED its (still-running, labelled) container after the new session, so the "not re-registered" verdict means
    # something. Observable agent-side (the debug log's Discovered event with at least one effect = a DiscoveryReport send); the DP's receipt of it is not.
    def reported():
        for l in agent_log_since(log_offset).splitlines():
            if "reactor.Discovered" in l:
                m = re.search(r'"?effects"?[=:]\s*(\d+)', l)
                if m and int(m.group(1)) >= 1:
                    return True
        return False
    sent = False
    try:
        wait_for("the agent to send a discovery report after the reconnect", reported, 60, 1.0)
        sent = True
    except SystemExit:
        pass
    if sent:
        check("positive control: the agent sent a discovery report after the reconnect (agent-side evidence)", True)
    else:
        note_unmeasured("positive control: a discovery report after the reconnect", "no Discovered event with effects in the agent's debug log within 60 s; the DP's receipt is not observable in the database")
    ts = db_now()

    def settled():   # two agent telemetry reports after the report: the session has been processed well past the discovery exchange
        r = psql("select count(m.*) from sm_metrics.series s join sm_metrics.metrics_raw m on m.tenant_id = s.tenant_id and m.series_id = s.series_id "
                 "where s.source_discriminator = 'agent' and s.metric_name = 'cache.size' and s.instance_id = :'i' and m.received_at > :'t'::timestamptz",
                 i=st["instanceId"], t=ts)
        return int(r[0][0]) >= 2
    try:
        wait_for("agent telemetry after the report", settled, 90, 3.0)
    except SystemExit:
        # an undeclared instance's series may stop; fall back to the agent session being seen well after the report
        wait_for("the session to be seen again after the report", lambda: float(psql("select extract(epoch from now() - last_seen_at) from sm_data.agent_session where agent_id = :'g'", g=st["agentId"])[0][0]) < 10, 60, 2.0)
    items = api.call("GET", "/api/dp/solutions/%s/instances" % st["solutionId"])["items"]
    disc = psql("select state from sm_data.agent_discovery where agent_id = :'g' and container_name = :'c'", g=st["agentId"], c=CACHE)
    # Other labelled containers on a shared daemon (another session's) are seen by the agent too; the envelope must have REFUSED each.
    foreign = psql("select state, coalesce(refusal_code,'-') from sm_data.agent_discovery where agent_id = :'g' and container_name <> :'c'", g=st["agentId"], c=CACHE)
    live = psql("select count(*) from sm_data.placement where placement_id = :'p' and retired_at is null", p=st["placementId"])
    log("after the reconnect and the report: listInstances=%s; agent_discovery=%s; live placements of it=%s; container running: %s"
        % ([i["name"] for i in items], disc, live[0][0], container_running()))
    check("the instance is NOT re-registered (listInstances empty of it)", INSTANCE_LABEL not in [i["name"] for i in items], [i["name"] for i in items])
    check("the agent_discovery row is UNDECLARED", disc == [["UNDECLARED"]], disc)
    check("every OTHER discovered container (outside the envelope) was REFUSED, none registered",
          all(f == ["REFUSED", "agent.discovery.outside.envelope"] for f in foreign), "%d other rows: %s" % (len(foreign), sorted({tuple(f) for f in foreign})))
    check("no live placement remains for the old placement id", live[0][0] == "0", live[0][0])
    check("the workload is still running (leave-running)", container_running())
    save_state(st)


def step_drain(st):
    log("== drain: drainAgent; the agent disconnects and comes back after the advised delay")
    api.login()
    q = "select fencing_epoch, to_char(opened_at,'HH24:MI:SS.MS'), coalesce(to_char(closed_at,'HH24:MI:SS.MS'),'-') from sm_data.agent_session where agent_id = :'g'"
    before = psql(q, g=st["agentId"])[0]
    t = time.time()
    r = api.call("POST", "/api/dp/agents/%s/drain" % st["agentId"], None, expect=(202,), note="drainAgent")
    log("accepted at %s" % r.get("requestedAt"))
    cur = wait_for("the agent's next session", lambda: [x for x in [psql(q, g=st["agentId"])[0]] if x[0] != before[0]], 60, 0.3)[0]
    elapsed = time.time() - t
    log("session before drain: epoch=%s opened=%s closed=%s" % tuple(before))
    log("session after drain:  epoch=%s opened=%s closed=%s; host-side poll saw it %.1fs after the call" % (tuple(cur) + (elapsed,)))
    check("drain: the agent is on a new session (epoch advanced) that is open", int(cur[0]) > int(before[0]) and cur[2] == "-", "%s -> %s closed=%s" % (before[0], cur[0], cur[2]))
    events = psql("select code from sm_data.agent_event where agent_id = :'g' and code like 'gateway.drain%' order by occurred_at desc limit 6", g=st["agentId"])
    log("agent_event (read): %s" % events)
    codes = {e[0] for e in events}
    check("drain: agent_event holds gateway.drain and gateway.drain.complete", {"gateway.drain", "gateway.drain.complete"} <= codes, sorted(codes))
    save_state(st)


REFUSAL = re.compile(r"IDENTITY_REVOKED|revoked|refus|UNAUTHENTICATED|unauthenticated|Unauthenticated|certificate")


def step_revoke(st):
    log("== revoke: revokeAgent; the session is closed and the next connect is refused")
    api.login()
    q = "select fencing_epoch, coalesce(to_char(closed_at,'HH24:MI:SS.MS'),'-') from sm_data.agent_session where agent_id = :'g'"
    before = psql(q, g=st["agentId"])[0]
    t = time.time()
    a = api.call("POST", "/api/cp/agents/%s/revoke" % st["agentId"], None, expect=(200,), note="revokeAgent")
    check("revokeAgent answered status revoked", a["status"] == "revoked", a["status"])
    closed = wait_for("the session to be closed", lambda: [x for x in [psql(q, g=st["agentId"])[0]] if x[1] != "-"], 60, 0.2)[0]
    log("session closed %.1fs after the call (host-side poll); before revoke epoch=%s closed=%s; after: epoch=%s closed=%s" % ((time.time() - t,) + tuple(before + closed)))
    check("revoke: the session row is closed", closed[1] != "-", closed)
    sh(SCRIPT, "agent-stop")
    offset = agent_log_size()
    sh(SCRIPT, "agent-start")

    def refused():
        return [l for l in agent_log_since(offset).splitlines() if REFUSAL.search(l)]
    tail = wait_for("the fresh agent to be refused (a refusal line in its log)", refused, 60, 1.0)
    again = psql(q, g=st["agentId"])[0]
    log("after a fresh connect attempt: session epoch=%s closed=%s; agent log lines about the refusal: %d" % (again[0], again[1], len(tail)))
    for l in tail[:3]:
        log("  agent: " + re.sub(r"[0-9a-f]{40,}", "<hex>", l)[:220])
    check("revoke: at least one refusal line in the fresh agent's log", len(tail) >= 1, len(tail))
    check("revoke: no new session opened after the revocation", again == closed, "%s vs %s" % (again, closed))
    st["revokeRefusedLines"] = len(tail)
    save_state(st)


def step_retire(st):
    log("== retire: retireTarget after the revocation")
    api.login()
    sh(SCRIPT, "agent-stop")
    api.call("POST", "/api/cp/targets/%s/retire" % st["targetId"], None, expect=(200,), note="retireTarget")
    again = api.call("GET", "/api/cp/targets")["items"]
    check("retire: the agent target is gone from listTargets", not any(t["targetId"] == st["targetId"] for t in again))
    save_state(st)


# ---- Step 3 / OR-145 (GA1_DPREMOTE=on): dp-remote coexistence with the agent, on the SSH-Docker target of the same host -------------------
def agent_epoch(st):
    return int(psql("select fencing_epoch from sm_data.agent_session where agent_id = :'g'", g=st["agentId"])[0][0])


def agent_status(st):
    return api.call("GET", "/api/cp/agents/%s" % st["agentId"], quiet=True)


def step_remote_declare(st):
    log("== remote_declare: declare Cache R (a plain container, no com.solace.sm labels) on the registered SSH-Docker target")
    if not (CACHE_R and R_CONFIG_DIR):
        raise Failure("GA1_CACHE_R and GA1_R_CONFIG_DIR are required for the remote_* steps")
    api.login()
    targets = [t for t in api.call("GET", "/api/cp/targets")["items"] if t.get("transport") in ("ssh-docker", "ssh", "dp-remote")]
    check("exactly one SSH-Docker target is registered (the E2E host's)", len(targets) == 1, "%d found" % len(targets))
    st["rTargetId"] = targets[0]["targetId"]
    body = {"solutionId": st["solutionId"], "name": "ga2-cache-r", "targetId": st["rTargetId"], "declaredPackageVersion": st["packageVersion"],
            "placement": {"containerName": CACHE_R, "configDirectory": R_CONFIG_DIR, "containerConfigPath": "/app/external/spring/config",
                          "fileName": "application.yml", "healthPort": R_HEALTH_PORT, "metricsPorts": {"8081": R_HEALTH_PORT}},
            "acquisition": {"mode": R_ACQUISITION}}
    code_ok = api.call("POST", "/api/dp/environments/%s/instances" % st["environmentId"], body, expect=(200, 201), note="declareInstance Cache R")
    check("declareInstance answered with an instance id", bool(code_ok.get("instanceId")))
    st["rInstanceId"] = code_ok["instanceId"]
    full = api.call("GET", "/api/dp/instances/%s" % st["rInstanceId"])
    check("Cache R's instance has exactly one placement", len(full["placements"]) == 1, len(full["placements"]))
    st["rPlacementId"] = full["placements"][0]["placementId"]
    items = api.call("GET", "/api/dp/solutions/%s/instances" % st["solutionId"])["items"]
    mine = [i for i in items if i["name"] == "ga2-cache-r"]
    check("listInstances shows Cache R", len(mine) == 1, [i["name"] for i in items])
    check("Cache R is acquired by the mode it was declared with, not through the agent", (mine[0].get("acquisition") or {}).get("mode") == R_ACQUISITION, json.dumps(mine[0].get("acquisition")))
    save_state(st)


def remote_apply(st, max_size, label):
    """Stage + apply restart-all to Cache R through its placement; returns (generation, apply id, final, states, events-start count, StartedAt before/after)."""
    before = started_at(CACHE_R)
    watcher = StartWatcher(os.path.join(DIR, "starts-r.txt"), CACHE_R)
    t0 = time.time()
    gen, apply_id = stage_and_apply(st, max_size, label, st["rInstanceId"], st["rPlacementId"], NAMESPACE[:-2] + "-r" if NAMESPACE.endswith("-a") else NAMESPACE + "-r")
    final, seen = observe(st, apply_id, 180, st["rInstanceId"])
    elapsed = time.time() - t0
    starts = watcher.stop()
    after = started_at(CACHE_R)
    log("Cache R: apply terminal %.0fs after the submit; StartedAt before=%s after=%s; docker 'start' events seen live: %d" % (elapsed, before, after, starts))
    return gen, apply_id, final, seen, starts, before, after, elapsed


def step_remote_apply_agent_up(st):
    log("== remote_apply_agent_up: restart-all on Cache R through dp-remote while the agent is ACTIVE; Cache A and the agent session undisturbed")
    api.login()
    a_before, epoch_before = started_at(), agent_epoch(st)
    check("the agent is ACTIVE before the dp-remote apply", agent_status(st)["status"] == "active")
    gen, apply_id, final, seen, starts, before, after, _ = remote_apply(st, 261000, "Cache R, agent up")
    check("remote apply (agent up): final state is APPLIED", final.get("state") == "APPLIED", " -> ".join(str(x) for x in seen))
    check("remote apply (agent up): Cache R's start time moved exactly once", starts == 1 and before != after, "events=%d %s -> %s" % (starts, before, after))
    check("remote apply (agent up): Cache R is running", after.endswith("|true"))
    check("remote apply (agent up): Cache R's application.yml holds max-size 261000 (match/no-match)", cache_file_holds(261000, "r"))
    check("Cache A's start time is unchanged", started_at() == a_before, "%s vs %s" % (a_before, started_at()))
    check("the agent is still ACTIVE on the same session epoch", agent_status(st)["status"] == "active" and agent_epoch(st) == epoch_before, "%s -> %s" % (epoch_before, agent_epoch(st)))
    st["r_up"] = {"generation": gen, "apply": apply_id, "starts": starts}
    save_state(st)


def step_remote_apply_agent_down(st):
    log("== remote_apply_agent_down: stop the agent; restart-all on Cache R through dp-remote is not starved; the agent comes back on a new epoch")
    api.login()
    epoch_before = agent_epoch(st)
    sh(SCRIPT, "agent-stop")
    wait_for("the agent's session to be closed", lambda: psql("select closed_at is not null from sm_data.agent_session where agent_id = :'g'", g=st["agentId"])[0][0] == "t", 60, 1.0)
    check("the agent's session is closed after agent-stop", True)
    gen, apply_id, final, seen, starts, before, after, elapsed = remote_apply(st, 262000, "Cache R, agent down")
    check("remote apply (agent down): final state is APPLIED within 180 s (no starvation)", final.get("state") == "APPLIED" and elapsed <= 180, "%s in %.0fs" % (" -> ".join(str(x) for x in seen), elapsed))
    check("remote apply (agent down): Cache R's start time moved exactly once", starts == 1 and before != after, "events=%d %s -> %s" % (starts, before, after))
    check("remote apply (agent down): Cache R's application.yml holds max-size 262000 (match/no-match)", cache_file_holds(262000, "r"))
    mark = db_now()
    sh(SCRIPT, "agent-start")
    wait_for("the agent to be ACTIVE again", lambda: agent_status(st)["status"] == "active" and agent_epoch(st) > epoch_before, 90, 2.0)
    check("the agent is ACTIVE again on a new epoch", agent_epoch(st) > epoch_before, "%s -> %s" % (epoch_before, agent_epoch(st)))

    def report():
        r = psql("select count(m.*) from sm_metrics.series s join sm_metrics.metrics_raw m on m.tenant_id = s.tenant_id and m.series_id = s.series_id "
                 "where s.instance_id = :'i' and s.source_discriminator = 'agent' and s.metric_name = 'cache.size' and m.received_at > :'t'::timestamptz",
                 i=st["instanceId"], t=mark)
        return int(r[0][0]) >= 1
    wait_for("an agent telemetry report after the agent came back", report, 120, 3.0)
    check("an agent telemetry report arrived after the agent came back", True)
    st["r_down"] = {"generation": gen, "apply": apply_id, "starts": starts, "elapsed": round(elapsed)}
    save_state(st)


def step_remote_undeclare(st):
    log("== remote_undeclare: undeclareInstance of Cache R with workload=leave-running")
    api.login()
    api.call("DELETE", "/api/dp/instances/%s?workload=leave-running" % st["rInstanceId"], expect=(200,), note="undeclareInstance Cache R")
    items = api.call("GET", "/api/dp/solutions/%s/instances" % st["solutionId"])["items"]
    check("listInstances no longer shows Cache R", "ga2-cache-r" not in [i["name"] for i in items], [i["name"] for i in items])
    check("Cache R's container is still running (leave-running)", container_running(CACHE_R))
    save_state(st)


STEPS = ["setup", "enrol", "apply1", "metrics", "killsm", "undeclare", "drain", "revoke", "retire"]
if DPREMOTE:   # G-A2 order: setup enrol apply1 metrics remote_declare remote_apply_agent_up remote_apply_agent_down remote_undeclare killsm undeclare drain revoke retire
    STEPS[4:4] = ["remote_declare", "remote_apply_agent_up", "remote_apply_agent_down", "remote_undeclare"]


def print_table():
    print("\n==== %s ====" % TABLE_TITLE, flush=True)
    for step, name, status, detail in RESULTS:
        print(mask("%-9s %-12s %s%s" % (step, status, name, (" [" + detail + "]") if detail and status != "PASS" else "")), flush=True)
    fails = sum(1 for r in RESULTS if r[2] == "FAIL")
    extra = sum(1 for r in RESULTS if r[2] == "FINDING")
    print("%d checks: %d PASS, %d FAIL, %d NOT MEASURED%s" % (len(RESULTS), sum(1 for r in RESULTS if r[2] == "PASS"), fails, sum(1 for r in RESULTS if r[2] == "NOT MEASURED"),
                                                          (", %d FINDING" % extra) if extra else ""), flush=True)


if __name__ == "__main__":
    wanted = os.environ.get("GA1_STEPS", "").split() or STEPS
    st = load_state()
    log("%s scenario; steps: %s" % (TABLE_TITLE.replace(" results", ""), " ".join(wanted)))
    code = 0
    try:
        for name in wanted:
            CURRENT[0] = name
            if name != "setup" and not api.session:
                api.login()
            globals()["step_" + name](st)
        log("scenario finished: %s" % " ".join(wanted))
    except Finding as e:
        log(str(e))
        code = 3
    except Failure as e:
        log(str(e))
        code = 1
    finally:
        print_table()
    if code == 0 and FINDINGS:   # known findings were recorded and the run continued
        code = 3
    sys.exit(code)

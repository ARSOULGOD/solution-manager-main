"""STAND-IN MI for the skill's own Stage 3 test. It is NOT the File Mesh connector and implements none of its
logic beyond what the smoke test needs; every response says so. Behaviour, driven only by the rendered config:

- reads /app/config/application.yml (the file deploy_ssh.sh copies);
- refuses to start, logging ERROR, when server.ssl.enabled is true (it has no TLS), so a config that would need
  a keystore fails visibly instead of passing;
- serves GET /actuator/health on server.port; the `solace` component is always {"status": "UP",
  "details": {"mocked": true}} because there is no broker;
- writes its log to logging.file.name (relative to /app/work);
- source role (file.source.* keys present): reads spring.cloud.stream.bindings.input-0.destination; if it is a
  file, each line is a directory to watch (ASSUMPTION about File Mesh's file_paths.cfg, see the profile's
  smoke_test); new files are "published" by copying them to /mock-broker/<output-0 destination>/;
- sink role (file.sink.* keys): consumes /mock-broker/<input-0 destination>/ and writes each file to the
  spring.cloud.stream.bindings.output-0.destination directory.
"""
import hashlib
import http.server
import json
import os
import shutil
import threading
import time

import yaml

CFG = "/app/config/application.yml"
BROKER = "/mock-broker"


def flat(d, p=""):
    out = {}
    for k, v in (d or {}).items():
        key = f"{p}.{k}" if p else str(k)
        if isinstance(v, dict):
            out.update(flat(v, key))
        else:
            out[key] = v
    return out


cfg = flat(yaml.safe_load(open(CFG)))
os.makedirs("/app/work", exist_ok=True)
LOG = os.path.join("/app/work", str(cfg.get("logging.file.name") or "standin.log"))
role = "source" if any(k.startswith("file.source.") for k in cfg) else "sink" if any(
    k.startswith("file.sink.") for k in cfg) else "unknown"


def log(level, msg):
    line = f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {level} [standin-mi {role}] {msg}"
    print(line, flush=True)
    with open(LOG, "a") as fh:
        fh.write(line + "\n")


def topic_dir(dest):
    d = os.path.join(BROKER, str(dest).strip("/").replace("/", "__"))
    os.makedirs(d, exist_ok=True)
    return d


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def source_loop():
    src = str(cfg.get("spring.cloud.stream.bindings.input-0.destination", ""))
    out = topic_dir(cfg.get("spring.cloud.stream.bindings.output-0.destination", "unset"))
    seen = set()
    while True:
        dirs = [l.strip() for l in open(src)] if os.path.isfile(src) else [src] if os.path.isdir(src) else []
        for d in [x for x in dirs if x and os.path.isdir(x)]:
            for name in sorted(os.listdir(d)):
                p = os.path.join(d, name)
                if os.path.isfile(p) and p not in seen:
                    shutil.copy(p, os.path.join(out, name))
                    seen.add(p)
                    log("INFO", f"published {name} sha256={sha(p)} to mock topic {out} (MOCKED broker)")
        time.sleep(1)


def sink_loop():
    inp = topic_dir(cfg.get("spring.cloud.stream.bindings.input-0.destination", "unset"))
    out = str(cfg.get("spring.cloud.stream.bindings.output-0.destination", "/app/external/file-output"))
    os.makedirs(out, exist_ok=True)
    done = set()
    while True:
        for name in sorted(os.listdir(inp)):
            if name not in done:
                shutil.copy(os.path.join(inp, name), os.path.join(out, name))
                done.add(name)
                log("INFO", f"wrote {name} sha256={sha(os.path.join(out, name))} to {out}")
        time.sleep(1)


class Health(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.split("?")[0] != "/actuator/health":
            self.send_response(404)
            self.end_headers()
            return
        body = {"status": "UP", "components": {
            "standin": {"status": "UP", "details": {"note": "stand-in MI, not the real connector", "role": role}},
            "solace": {"status": "UP", "details": {"mocked": True, "broker": BROKER}}}}
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)


if cfg.get("server.ssl.enabled") is True:
    log("ERROR", "server.ssl.enabled is true but the stand-in has no TLS; key-store "
                 f"{cfg.get('server.ssl.key-store')!r} cannot be used. Exiting.")
    raise SystemExit(1)
port = int(cfg.get("server.port") or 8080)
log("INFO", f"starting stand-in MI role={role} port={port} (Solace MOCKED)")
for k, v in cfg.items():
    if isinstance(v, str) and v.startswith("${SM_SECRET_"):
        name = v[2:-1]
        log("INFO", f"{k}: secret placeholder {name} " + ("resolved from environment" if os.environ.get(name) else "NOT set"))
threading.Thread(target={"source": source_loop, "sink": sink_loop}.get(role, lambda: None), daemon=True).start()
http.server.ThreadingHTTPServer(("0.0.0.0", port), Health).serve_forever()

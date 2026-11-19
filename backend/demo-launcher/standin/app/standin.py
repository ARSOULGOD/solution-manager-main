import http.server, threading, time, os, random, datetime
CFG = "/app/external/spring/config/application.yml"
def cfg_summary():
    try:
        with open(CFG) as f: txt = f.read()
        return "config sha=%d bytes, %d lines" % (len(txt), txt.count("\n"))
    except FileNotFoundError:
        return "config file absent"
LEVELS = ["INFO"]*6 + ["WARN", "DEBUG"]
MSGS = ["cache hit ratio sampled", "eviction sweep completed", "heartbeat to broker ok",
        "namespace sync finished", "replicated entry batch", "slow lookup detected"]
def ticker():
    print("StandInCache started; reading " + CFG, flush=True)
    print("INFO " + cfg_summary(), flush=True)
    n = 0
    while True:
        n += 1
        lvl = random.choice(LEVELS)
        print("%s [standin] %s (n=%d)" % (lvl, random.choice(MSGS), n), flush=True)
        if n % 10 == 0: print("INFO " + cfg_summary(), flush=True)
        time.sleep(2)
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        body = {"/api/cache/stats": '{"hits":%d,"misses":%d}' % (random.randint(900,1000), random.randint(1,50)),
                "/api/cache/size": '{"size":%d}' % random.randint(1000,5000)}.get(self.path.split("?")[0],
                '{"name":"jvm.memory.used","measurements":[{"statistic":"VALUE","value":%d}]}' % random.randint(10**8, 2*10**8))
        self.send_response(200); self.send_header("Content-Type","application/json"); self.end_headers()
        self.wfile.write(body.encode())
threading.Thread(target=ticker, daemon=True).start()
http.server.ThreadingHTTPServer(("0.0.0.0", 8081), H).serve_forever()

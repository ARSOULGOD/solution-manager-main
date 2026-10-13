#!/usr/bin/env python3
"""Re-executes the PostgreSQL-14 evidence for eda-walking-skeleton.md §3 (DDL/DCL) and fails on drift.

What it does, in order:
  1. Extracts Phase 0 and Phase 1 from the design's ```sql block — located by its `-- PHASE 0` banner,
     never by line numbers — and prints both md5s.
  2. Starts two fresh `postgres:14` containers (trust auth, local socket only): `main` for the run and
     `negctl` for the (s) negative control that needs a cluster in which the roles do not yet exist.
  3. Runs every leg of `legs.json` in manifest order, as the recorded role, in the recorded container
     and database: plain SQL legs over `docker exec psql`; the two phase files over stdin; the (d)
     concurrency legs as two live psql sessions with a third session observing pg_stat_activity/pg_locks.
  4. Compares each SQL leg's STATUS ([OK]/[REFUSED]) and SIGNATURE — the sorted set of
     `ERROR: <SQLSTATE>`, `CONSTRAINT NAME: …` and `WARNING: …` lines — against the manifest, plus any
     `expect_contains` fragments for the inspection legs. Any difference is a failure; the run continues
     so the whole diff is visible, and the exit code is 1.
  5. Writes one log per manifest group into --out, in the same `### [STATUS] label (container role db)`
     / `--- SQL ---` / `--- RESULT ---` shape the evidence report quotes, and removes the containers.

Usage (from the repo root; Docker required):
    python3 docs/design/backend/evidence/ddl-replay/replay.py --out /tmp/ddl-replay
    python3 docs/design/backend/evidence/ddl-replay/replay.py --out /tmp/ddl-replay --keep   # leave containers up

After a deliberate DDL change: run once and read every reported difference. To propose a new baseline,
run with --record-candidate <dir>: the harness writes CANDIDATE legs.json and design-block.json into
that directory — never into this one — and exits 2 (review required) listing every value that would
change. A reviewer diffs the candidate against the tracked files and applies it in an ordinary commit.
Nothing this script does can rewrite the tracked baseline: the baseline changes only by a reviewed diff.
"""
import argparse, hashlib, json, os, re, subprocess, sys, time
from pathlib import Path

HERE = os.path.dirname(os.path.realpath(__file__))
REPO = os.path.realpath(os.path.join(HERE, '..', '..', '..', '..', '..'))  # evidence/ddl-replay → backend → design → docs → repo
DESIGN = os.path.join(REPO, 'docs', 'design', 'backend', 'eda-walking-skeleton.md')
MANIFEST = os.path.join(HERE, 'legs.json')
IMAGE = 'postgres:14'
# The signature of a result: every SQLSTATE PostgreSQL raised (`ERROR:  42501: …` → `ERROR: 42501`), every
# constraint/index/trigger NAME it reported, and every WARNING line. An independent reproduction found the
# first version of this pattern anchored `$` after the SQLSTATE, so it never matched an ERROR line and 52
# refusals were checked as "some error happened" — a wrong-SQLSTATE mutation passed. Measured, fixed, pinned
# by the README's second prove-it-fires.
SIG = re.compile(r'^ERROR:\s+(\w{5}):|^(CONSTRAINT NAME:.*)$|^(WARNING:.*)$', re.M)
BLOCK = os.path.join(HERE, 'design-block.json')  # expected md5s / ranges of the extracted phases


def extract_phases():
    """The ```sql block holding the `-- PHASE 0` banner, split at the `-- PHASE 1` banner."""
    lines = open(DESIGN, encoding='utf-8').read().split('\n')
    banner = next(i for i, l in enumerate(lines) if l.startswith('-- PHASE 0'))
    start = banner
    while not lines[start].startswith('```'):
        start -= 1
    end = banner
    while not lines[end].startswith('```'):
        end += 1
    block = lines[start + 1:end]
    # Phase 1 begins AT its banner line; the separator line above it closes Phase 0 — the same cut the
    # evidence report's sed ranges make (228–265 / 266–552 at d87c2e7), so the md5s agree.
    split = next(i for i, l in enumerate(block) if l.startswith('-- PHASE 1'))
    phase0 = '\n'.join(block[:split]) + '\n'
    phase1 = '\n'.join(block[split:]) + '\n'
    return phase0, phase1, (start + 2, start + 1 + split), (start + 2 + split, end)


def sh(*args, input=None, check=True):
    return subprocess.run(list(args), input=input, capture_output=True, text=True, check=check)


def container_name(tag, suffix):
    return f'ddl-replay-{tag}-{suffix}'


def psql(container, role, db, sql, quiet=True):
    # quiet suppresses command tags (CREATE TABLE, GRANT …); the phase-file legs run unquieted so the
    # tag multiset can be asserted against the manifest.
    p = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-U', role, '-d', db, '-v', 'VERBOSITY=verbose'] + (['-q'] if quiet else []),
                       input='\\set ON_ERROR_STOP 0\n' + sql + '\n', capture_output=True, text=True)
    out = (p.stdout + p.stderr).strip()
    return ('REFUSED' if re.search(r'^ERROR:', out, re.M) else 'OK'), out


def signature(result):
    out = set()
    for state, constraint, warning in SIG.findall(result):
        out.add('ERROR: ' + state if state else (constraint or warning))
    return sorted(out)


TAG = re.compile(r'^(SET|GRANT(?: ROLE)?|REVOKE(?: ROLE)?|BEGIN|COMMIT|CREATE \w+(?: \w+)?|ALTER \w+(?: \w+)?)$', re.M)


class RefusedPath(SystemExit):
    """A --out or --record-candidate the harness will not write to. Raised before anything is created."""


def _inside(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def validate_paths(out, candidate):
    """Resolve both output paths (symlinks included) and refuse anything that could touch tracked files.

    Rules, checked BEFORE any mkdir, file write or container start:
      * both paths are resolved with Path.resolve() — a symlink that points into the repository is judged
        by where it points, not by its name;
      * neither resolved path may lie inside the repository (not merely outside this harness directory) —
        `reference-run/`, `legs.json` and `design-block.json` are tracked evidence and are written only by
        a human in a reviewed commit;
      * the candidate directory must not exist yet — the harness creates it, so a pre-existing directory
        (or a symlink standing in for one) cannot be substituted.
    Returns (out_path, candidate_path_or_None) as resolved Paths.
    """
    repo = Path(REPO)
    out_path = Path(out).expanduser().resolve()
    if _inside(out_path, repo):
        raise RefusedPath(f'refusing --out {out}: resolves to {out_path}, inside the repository — run output '
                          'must never land on tracked evidence (reference-run/ is refreshed only by a reviewed commit)')
    cand_path = None
    if candidate is not None:
        raw = Path(candidate).expanduser()
        # resolve() follows a symlink at the leaf too; a dangling or missing leaf resolves to where it WOULD be
        cand_path = raw.resolve()
        if _inside(cand_path, repo):
            raise RefusedPath(f'refusing --record-candidate {candidate}: resolves to {cand_path}, inside the '
                              'repository — a candidate baseline is applied by a reviewed diff, never written in place')
        if raw.is_symlink() or raw.exists():
            raise RefusedPath(f'refusing --record-candidate {candidate}: it already exists — name a directory the '
                              'harness will create, so nothing can stand in for it')
    return out_path, cand_path


def design_is_clean():
    p = subprocess.run(['git', '-C', REPO, 'status', '--porcelain', '--', os.path.relpath(DESIGN, REPO)],
                       capture_output=True, text=True)
    return p.returncode == 0 and p.stdout.strip() == ''



def wait_ready(container, seconds=60):
    for _ in range(seconds):
        if subprocess.run(['docker', 'exec', container, 'pg_isready', '-q', '-U', 'postgres'], capture_output=True).returncode == 0:
            return
        time.sleep(1)
    raise SystemExit(f'{container} did not become ready')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True, help='directory for this run\'s logs (created; must resolve OUTSIDE the repository)')
    ap.add_argument('--keep', action='store_true', help='leave the containers running')
    ap.add_argument('--record-candidate', metavar='DIR', help='write CANDIDATE legs.json + design-block.json from this run '
                    'into DIR — a not-yet-existing directory resolving OUTSIDE the repository — and exit 2 for review')
    args = ap.parse_args()
    out_path, cand_path = validate_paths(args.out, args.record_candidate)  # refuses before anything is written
    if cand_path is not None and not design_is_clean():
        raise SystemExit('refusing --record-candidate: the design file has uncommitted changes — commit the DDL '
                         'change first, so a candidate baseline names a commit')
    args.out = str(out_path)
    os.makedirs(args.out, exist_ok=True)

    phase0, phase1, r0, r1 = extract_phases()
    open(os.path.join(args.out, 'phase0.sql'), 'w').write(phase0)
    open(os.path.join(args.out, 'phase1.sql'), 'w').write(phase1)
    md5 = lambda s: hashlib.md5(s.encode('utf-8')).hexdigest()
    block = json.load(open(BLOCK, encoding='utf-8'))
    actual_block = {'phase0': {'lines': list(r0), 'md5': md5(phase0)}, 'phase1': {'lines': list(r1), 'md5': md5(phase1)}}
    print(f'design {os.path.relpath(DESIGN, REPO)}')
    for ph in ('phase0', 'phase1'):
        a, e = actual_block[ph], block[ph]
        flag = '' if a['md5'] == e['md5'] else f"  ** DIFFERS from recorded {e['md5']} (lines {e['lines'][0]}-{e['lines'][1]}) **"
        print(f"  {ph}: lines {a['lines'][0]}-{a['lines'][1]} md5 {a['md5']}{flag}")

    legs = json.load(open(MANIFEST, encoding='utf-8'))
    suffix = str(int(time.time()))
    names = {'main': container_name('main', suffix), 'negctl': container_name('negctl', suffix)}
    for n in names.values():
        sh('docker', 'run', '-d', '--name', n, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', IMAGE)
    try:
        for n in names.values():
            wait_ready(n)
        version = psql(names['main'], 'postgres', 'postgres', 'SELECT version();')[1]
        print('  engine:', version.split('\n')[2].strip() if '\n' in version else version)

        failures = []
        for ph in ('phase0', 'phase1'):
            if actual_block[ph]['md5'] != block[ph]['md5']:
                failures.append((f'{ph} md5', f"recorded {block[ph]['md5']} (lines {block[ph]['lines'][0]}-{block[ph]['lines'][1]}), "
                                             f"extracted {actual_block[ph]['md5']} (lines {actual_block[ph]['lines'][0]}-{actual_block[ph]['lines'][1]}) — the design's SQL block changed"))
        logs = {}
        n_sql = n_refused = 0

        def log(group, text):
            logs.setdefault(group, []).append(text)

        for leg in legs:
            t = leg['type']
            if t == 'file':
                sql = phase0 if leg['file'] == 'phase0' else phase1
                st, out = psql(names[leg['container']], leg['role'], leg['db'], sql, quiet=False)
                errors = len(re.findall(r'^ERROR:', out, re.M))
                warnings = len(re.findall(r'^WARNING:', out, re.M))
                tags = sorted(TAG.findall(out))  # the command-tag multiset, e.g. 5× GRANT, 3× CREATE TRIGGER
                sig = signature(out)
                log(leg['group'], f"### [{st}] {leg['label']}  (container={leg['container']} role={leg['role']} db={leg['db']})\n--- RESULT ---\n{out}\n")
                problems = []
                if errors != leg['expect_errors'] or warnings != leg['expect_warnings']:
                    problems.append(f"expected {leg['expect_errors']} errors/{leg['expect_warnings']} warnings, got {errors}/{warnings}")
                if tags != leg.get('expect_tags', tags):
                    problems.append(f"command tags {leg['expect_tags']} -> {tags}")
                if sig != leg.get('signature', sig):
                    problems.append(f"signature {leg['signature']} -> {sig}")
                if problems:
                    failures.append((leg['label'], '; '.join(problems)))
                    print(f"DRIFT  {leg['label'][:70]}: {'; '.join(problems)}")
                if args.record_candidate:
                    leg['expect_errors'], leg['expect_warnings'], leg['expect_tags'], leg['signature'] = errors, warnings, tags, sig
                print(f"[{st}] {leg['label'][:70]} — errors={errors} warnings={warnings} tags={len(tags)}")
                continue
            if t == 'sessions':
                a = open(os.path.join(HERE, 'sessions', leg['a'])).read()
                b = open(os.path.join(HERE, 'sessions', leg['b'])).read()
                cmd = ['docker', 'exec', '-i', names['main'], 'psql', '-U', 'sm_dp_runtime', '-d', 'smdb']
                pa = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                pa.stdin.write(a); pa.stdin.close()
                time.sleep(1.5)
                pb = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                pb.stdin.write(b); pb.stdin.close()
                observed = ''
                if leg['observe']:
                    time.sleep(2)
                    observed = psql(names['main'], 'postgres', 'smdb',
                                    "SELECT pid, usename, state, wait_event_type, wait_event, left(query,50) AS q FROM pg_stat_activity WHERE usename='sm_dp_runtime' AND wait_event_type='Lock';\n"
                                    "SELECT locktype, mode, granted FROM pg_locks WHERE NOT granted;")[1]
                oa = pa.communicate()[0]; ob = pb.communicate()[0]
                times = [float(x) for x in re.findall(r'^Time: ([0-9.]+) ms', ob, re.M)]
                blocked = max(times) if times else 0.0
                ok = blocked >= leg['min_block_ms'] and all(f in ob for f in leg['b_contains']) and (not leg['observe'] or ('transactionid' in observed and '| f' in observed))
                st = 'OK' if ok else 'REFUSED'
                log(leg['group'], f"### [{st}] {leg['label']}  (container=main role=sm_dp_runtime db=smdb)\n--- SESSION A ({leg['a']}) ---\n{oa}\n--- SESSION B ({leg['b']}) ---\n{ob}\n--- OBSERVED (third session, postgres) ---\n{observed}\n")
                if not ok:
                    failures.append((leg['label'], f'B blocked {blocked:.0f} ms (min {leg["min_block_ms"]}); observed={observed!r}'))
                print(f"[{st}] {leg['label'][:70]} — B blocked {blocked:.0f} ms")
                continue
            # plain SQL leg
            st, out = psql(names[leg['container']], leg['role'], leg['db'], leg['sql'])
            n_sql += 1
            n_refused += st == 'REFUSED'
            sig = signature(out)
            log(leg['group'], f"### [{st}] {leg['label']}  (container={leg['container']} role={leg['role']} db={leg['db']})\n--- SQL ---\n{leg['sql']}\n--- RESULT ---\n{out}\n")
            problems = []
            if st != leg['status']:
                problems.append(f"status {leg['status']} -> {st}")
            if sig != leg['signature']:
                problems.append(f"signature {leg['signature']} -> {sig}")
            for frag in leg.get('expect_contains', []):
                if frag not in out:
                    problems.append(f'missing {frag!r}')
            if problems:
                failures.append((leg['label'], '; '.join(problems)))
                print(f"DRIFT  {leg['label'][:70]}: {'; '.join(problems)}")
            if args.record_candidate:
                leg['status'], leg['signature'] = st, sig

        for group, texts in logs.items():
            # psql pads table cells and ends output with blank lines; strip line ends and end the file with
            # exactly one newline, so the checked-in reference run passes `git diff --check`
            body = '\n'.join(line.rstrip() for line in '\n'.join(texts).split('\n')).rstrip('\n') + '\n'
            open(os.path.join(args.out, f'log-{group}.txt'), 'w', encoding='utf-8').write(body)
        print(f'\n{n_sql} SQL legs ({n_refused} refused, {n_sql - n_refused} ok) + 3 phase legs + 2 session legs; logs in {args.out}')
        if args.record_candidate:
            cdir = str(cand_path)
            os.makedirs(cdir, exist_ok=False)  # validated fresh above; a race that created it meanwhile fails here
            json.dump(legs, open(os.path.join(cdir, 'legs.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
            json.dump(actual_block, open(os.path.join(cdir, 'design-block.json'), 'w', encoding='utf-8'), indent=1)
            print(f'\nCANDIDATE baseline written to {cdir} — the tracked legs.json / design-block.json are untouched. '
                  f'{len(failures)} recorded value(s) would change (listed below); expect_contains fragments are not '
                  'regenerated. Review with:\n'
                  f'  diff -u {os.path.relpath(MANIFEST, REPO)} {os.path.join(cdir, "legs.json")}\n'
                  'then copy both files over the tracked ones in an ordinary, reviewed commit.')
            for label, why in failures:
                print(f'  ~ {label}\n      {why}')
            return 2
        if failures:
            print(f'\n{len(failures)} DIFFERENCE(S) FROM THE MANIFEST:')
            for label, why in failures:
                print(f'  - {label}\n      {why}')
            return 1
        print('\nNO DRIFT: every leg matched its recorded status and signature.')
        return 0
    finally:
        if not args.keep:
            for n in names.values():
                subprocess.run(['docker', 'rm', '-f', n], capture_output=True)


if __name__ == '__main__':
    sys.exit(main())

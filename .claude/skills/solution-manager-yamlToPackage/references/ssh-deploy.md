# Stage 3: Solution via SSH (deploy and test the MI)

> **This is a test harness, not a Solution Manager feature.** The project's one confirmed architectural position
> is that SM is a *configuration* plane, not a deployment plane: customer CI/CD deploys artifacts. The product
> applies config over Docker-on-SSH itself (`OR-111`). Stage 3 deploys an MI only so that a generated package can
> be tested end to end. Never present it as how SM deploys.

## Contents
- Inputs (ask; never guess)
- 3.1 Render the config
- 3.2 Dry run (the default)
- 3.3 Deploy
- 3.4 Verify
- 3.5 Functional smoke test
- 3.6 Teardown and rollback (offer it; never run it silently)
- When SSH is refused
- The skill's own test target
- Verdict table and gate

## Inputs (ask; never guess)

| input | notes |
|---|---|
| host, user, port | |
| auth | `--identity KEYFILE` or `--agent`. **Never ask for, accept or store a password.** |
| known_hosts | a file the user has verified. Strict host key checking is always on. |
| delivery | `docker` (image ref, optionally an image tar), `jar` or `tarball` (with its start command), and where the artifact is |
| install dir | one dedicated directory, at least two levels deep. System paths are refused. |
| start mode | `docker` or `nohup`. `systemd` is printed only: its secrets would need an EnvironmentFile on disk (I6) |
| Solace broker | host/VPN/credentials come through the Designer config (the package's broker selectors); the credentials are secrets |
| secret values | in the user's shell as `SM_SECRET_*` variables, set by them, never typed into the conversation |

## 3.1 Render the config

```bash
python3 scripts/render_config.py render --package <out-dir>/package.json --role <role> --version <v> \
  --values <designer-draft.json> --out <work>/application-<role>.yml
```

- `--values` is the Designer draft (`{field key: value}`): what the operator set for this instance. Without it you
  get package defaults. Those may be wrong for the role (see FM-D11), or unsafe: File Mesh's shared default
  `server.ssl.enabled: true` points at a key store on someone's laptop.
- Secrets become `${SM_SECRET_<PATH>}` placeholders, which the MI resolves from its environment at start. A secret in
  `--values` is refused. `deploy_ssh.sh` refuses a config that holds a literal value for any secret-named key.

## 3.2 Dry run (the default)

```bash
scripts/deploy_ssh.sh plan --host H --user U --identity KEY --name fm-source --install-dir /opt/sm-skill/fm-source \
  --config <work>/application-source.yml --delivery docker --image <ref> --secret-env SM_SECRET_… [...]
```

`plan` prints every `ssh`/`scp` command, file, remote path and start command. It makes **no network call**; the
skill's test proves this with tripwire `ssh`/`scp`/`curl` shims on `PATH`. `plan` and `apply` share one code path,
so the printed commands are exactly what `apply` runs. Show the plan to the user, then **stop for explicit
approval**.

## 3.3 Deploy

```bash
scripts/deploy_ssh.sh apply <same options> --yes-i-approved
```

- Creates the dedicated dir and its `config/`, `work/` and `app/` subfolders. Each one it creates is recorded in
  `<dir>/.sm-skill-state`.
- Backs up an existing `config/application.yml` to `application.yml.bak.<UTC timestamp>`.
- Copies the config and the artifact. For docker, it checks that the image exists on the target, or loads the image tar.
- Starts the MI:
  - **docker:** `docker run -d --name sm-skill-<name> --label sm-skill=1`, mounting `config/` read-only at
    `--config-mount` and `work/` at `/app/work`, plus `--docker-args`.
  - **nohup:** `cd work && nohup <cmd> &`.

  Records the container id or PID.
- Secrets: each `--secret-env NAME` value is read from the user's shell and sent **on SSH stdin**. It never appears on
  a command line, in a file, or in output. Caveat: `docker run -e` values remain visible to `docker inspect` on the
  target. For production, use the platform's secret store.

## 3.4 Verify

```bash
scripts/deploy_ssh.sh verify <connection> --name … --install-dir … --config <rendered yml> [--timeout 120]
```

- Polls `/actuator/health` **on the target** at `server.port` from the rendered config (https when
  `server.ssl.enabled`) until `UP`. If `management.endpoints.web.exposure.include` does not expose `health`, it
  says so rather than guessing.
- Reads the last 400 lines of `<dir>/work/<logging.file.name>` and counts `ERROR`/`Exception`, masking any secret value.
- Reports the Solace connection from the health `solace`/binder component. It prints **MOCKED** when the details
  say `mocked: true`, and **UNKNOWN** when health shows no such component, which needs `show-details`.

## 3.5 Functional smoke test

The test is data: the profile's `smoke_test:` section, run by `scripts/smoke_test.py`. A spec with
`status: unconfirmed` is refused unless the user accepts it (`--accept-unconfirmed`), and the report then carries the
assumption. **File Mesh's spec is unconfirmed.** It assumes `input-0.destination` (`file_paths.cfg`) lists the
directories to watch.

Its steps:
1. Write the watch list.
2. Drop a random file in the watched folder.
3. Expect `published <file>` in the source log.
4. Expect the file in the sink's `output-0.destination` with the same SHA-256.

For a solution with no `smoke_test:`, draft one with the user: what to send, where, and what to expect. Never run
an invented test.

## 3.6 Teardown and rollback (offer it; never run it silently)

```bash
scripts/deploy_ssh.sh teardown <connection> --name … --install-dir … --yes-i-approved
```

- Stops the recorded container or PID.
- Restores the backup.
- If `apply` created the directory, removes it. Otherwise removes only the files and subfolders `apply` created,
  leaving everything else in place. Tested: a pre-existing folder with an operator's file came back byte-identical.

## When SSH is refused

`deploy_ssh.sh` exits 4 and prints the SSH error. **Do not try workarounds:** no other ports, users or jump hosts,
and no disabling host key checks. Stay in `plan`, give the user the exact commands and the script to run from their
own machine, and interpret the output they paste back.

## The skill's own test target

`scripts/testing/local_target.sh up <root>` starts:
- the repo's `sm-standin-sshd` image on `127.0.0.1:2223`, with key auth only and a fresh ed25519 key;
- a known_hosts captured from that just-started container, which is acceptable for a local test only.

The MI is `scripts/testing/standin-mi` (image `sm-skill-standin-mi`). It is **a test double, not the File Mesh
connector**: it serves health, writes its log, refuses to start with TLS on, and moves files source → **mock
broker directory** → sink. Every report from this target must say "stand-in MI, mocked broker".
`local_target.sh down <root>` removes all of it.

## Verdict table and gate

```markdown
## Stage 3: Solution via SSH: <PASS|FAIL> (target: <real host | local stand-in, mocked broker>)
| step | verdict | evidence |
|---|---|---|
| 3.1 render (per role) | … | n keys, n secret placeholders |
| 3.2 plan | … | plan-<role>.txt |
| 3.3 apply | … | container/PID |
| 3.4 verify | … | health, log, Solace (MOCKED/real) |
| 3.5 smoke test | … | smoke.md (confirmed/unconfirmed spec) |
| 3.6 teardown | offered / done on request | |
```

Then **STOP**.

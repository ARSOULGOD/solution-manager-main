# solution-manager-yamlToPackage

An agent skill (Claude Code) for the **Solution Manager** console. It takes the YAML a solution runs with (for example
File Mesh's `application-source-file-v2.yml`) and turns it into the **Solution Package** fields (`package.json`) that
the console's dynamic Designer UI renders. It does this one role at a time, and never disturbs the rest of the package.

It is a **dev/test tool**, not product code. It changes nothing in the console's frontend, backend or contracts; it
writes package files for a person to review and copy into place.

## What it does: three stages, each ending in a report and a stop

| stage | what happens | the user decides |
|---|---|---|
| **1. Package creation** | The skill previews every YAML key as a field (section, position, shown up front or under Advanced, required or optional, versions) and asks numbered questions about anything it cannot decide alone. It creates `package.json` only after the user says "create". | Every answer, then "Create package.json? (yes/no)", then whether to copy it over the canonical package |
| **2. UI building** | Generates a contract test, loads the package into the console in a headless browser, and checks that the config round-trips back to the YAML. Runs in Docker. | Whether to go on to Stage 3 |
| **3. Solution via SSH** | Renders the config, does a dry run, deploys, checks health and logs, runs a smoke test, and offers teardown. This is a test harness; Solution Manager itself does not deploy artifacts. | Every step that changes a host |

### Guarantees in Stage 1
- **No field is ever dropped by the skill.** Every YAML key ends up as a field, an open question, or a key the user
  removed (with a recorded reason). The package is read back after writing to prove it.
- **Nothing is created until the user says so.** Until then only preview files exist.
- **Secret values never appear** in the package, the preview, the reports or the saved decisions. Secret fields are
  written as `type: secret` with `secret: true` and an empty default.
- **Suggestions are learned, not invented.** `required` and "advanced" are suggested from the repo's sample packages,
  with the evidence shown, and are applied only if the user accepts them.
- **Decisions are remembered.** The user's answers are saved in the solution's mapping profile, so the next run asks
  only about what is new.

## Requirements
- The **Solution Manager repository**. The skill reads the package schema (`contracts/solution-package.schema.json`) and
  the sample packages (`frontend/contracts/packages/`) from it.
- **Python 3.12** with **PyYAML** (tested with 6.0.1). There are no other Python dependencies.
- **Docker** for Stage 2 (Node 22 and Playwright run inside a container).
- **SSH access to a target host** for Stage 3.

## Installation
In this repository the skill is already in place. To use it in another clone, copy this folder into the
repository's skill directory:

```
<repo>/.claude/skills/solution-manager-yamlToPackage/
```

Claude Code then picks the skill up automatically when a user asks for something like "regenerate the file-mesh source
fields from this YAML".

## How to use it
Ask in plain words. For example:

> regenerate the file-mesh SOURCE role fields from application-source-file-v2.yml into our package. It targets package
> versions 1,2,3.

The skill then:
1. confirms the solution, role, YAML, existing package and target versions;
2. shows the preview: the full field table, plus numbered questions;
3. records your answers and shows only what changed;
4. asks "Create package.json?" and, on yes, writes the package and a report;
5. asks before copying anything over the canonical package, and then offers Stage 2.

The engine can also be run directly:

```bash
python3 scripts/yaml_to_package.py preview --profile references/profiles/file-mesh.yaml --role source \
  --yaml <role yaml> --target-versions 1,2,3 --package <existing package.json> --out-dir <scratch dir>
python3 scripts/yaml_to_package.py apply   ...same arguments... --answers <scratch dir>/answers.yaml
```

Exit codes: 0 done · 1 validation or read-back failed · 2 bad input · 3 draft profile · 4 version decision needed ·
5 questions still open.

A solution with no mapping profile yet goes through a propose-and-confirm step first. The skill drafts a profile and
stops with questions; nothing is generated until the profile is confirmed.

## What is in this folder

| path | contents |
|---|---|
| `SKILL.md` | The skill's operating procedure: what the agent follows. Start here for details. |
| `references/` | `mapping-guide.md` (engine rules, the questions, the answers file, the profile format), `package-schema.md`, `ui-testing.md` (Stage 2), `ssh-deploy.md` (Stage 3), and `profiles/file-mesh.yaml` (the File Mesh mapping profile, the worked example). |
| `scripts/` | The engine and its tools: `yaml_to_package.py` (preview and apply), `style.py`, `learn_samples.py`, `decisions.py`, `preview_md.py`, `validate_package.py`, `render_config.py`, `profile_proposer.py`, `gen_contract_test.py`, `run_frontend.sh`, `ui_check.mjs`, `deploy_ssh.sh`, `smoke_test.py`. |
| `scripts/tests/` | 159 unit tests with small fixtures: `python3 -m unittest discover -s scripts/tests`. |
| `examples/` | Real output on File Mesh. The source and sink runs show the first preview, the answers, the package, the diff and the report; stage2 and stage3 hold the UI and SSH runs. |
| `evals/` | Six evaluation cases (prompt, input files and graders) for checking the skill's behaviour. |

In the File Mesh source and sink examples, `<scratch>` stands for the temporary working folder the runs used.

## Status (2026-11-10)

Verified on File Mesh:
- **Source role:** 110 YAML keys previewed, with 7 questions. After the answers, 109 fields plus 1 key removed by the
  user (a case-only duplicate).
- **Sink role:** all 102 keys placed. Both package validators pass.
- **Stage 2:**
  - The contract test passes, apart from one known console defect.
  - The browser check passes 18 checks. 2 are blocked by a known console crash (a MUI DataGrid id issue in the console,
    not the package).
  - Both config round-trips pass.
- **Evals with the skill:** 7/7 and 6/6 on the source and apply cases, 3/4 on the sink preview case.

Known open items:
- **Sink preview reply.** On the sink role, the skill's reply does not yet say clearly that package-wide changes (the
  restyle and `secret: true`) also reach the source component. The preview file itself shows it.
- **Rare secret edge case.** A field marked `secret: true` on a non-secret type that also has a profile enum can still
  get its YAML value added to its option list. This happens only if the user explicitly answers "add option".
- **Thin suggestions for File Mesh.** Learning from the current samples yields 4 `required` suggestions and no
  "advanced" ones. Most of these choices are the user's, made once and then remembered.

The design and the full execution record are in the repository at
`docs/plans/tooling/001-yamltopackage-ui-and-ssh-stages.md` and `docs/plans/tooling/002-yamltopackage-preview-and-no-trim.md`.

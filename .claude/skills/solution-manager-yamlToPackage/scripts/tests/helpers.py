"""Shared test helpers: the demo fixtures and a one-call build."""
import json
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import decisions  # noqa: E402
import yaml_to_package  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SAMPLES = FIXTURES / "samples"
DEMO = FIXTURES / "demo"
PROFILE = DEMO / "profile.yaml"
ALPHA = DEMO / "alpha.yml"
BETA = DEMO / "beta.yml"
PACKAGE = DEMO / "package.json"
ANSWERS_FILE = DEMO / "answers-alpha.yaml"   # created in Task 6
ENGINE = Path(__file__).resolve().parents[1] / "yaml_to_package.py"
YAMLS = {"alpha": ALPHA, "beta": BETA}


def empty_dir():
    return Path(tempfile.mkdtemp(prefix="y2p-test-"))


def temp_file(text):
    path = empty_dir() / "file.yaml"
    path.write_text(text, encoding="utf-8")
    return path


def package_copy(**top_level_overrides):
    pkg = json.loads(PACKAGE.read_text(encoding="utf-8"))
    pkg.update(top_level_overrides)
    path = empty_dir() / "package.json"
    path.write_text(json.dumps(pkg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def fields_by_key(pkg):
    return {f["key"]: f for c in pkg["components"] for f in c.get("fields", [])}


def build_demo(role, answers=None, remembered=None, package=PACKAGE, applies_to=None, samples=SAMPLES):
    work = empty_dir()
    profile = work / "profile.yaml"
    text = PROFILE.read_text(encoding="utf-8")
    if remembered is not None:
        text = decisions.write_block(text, remembered)
    profile.write_text(text, encoding="utf-8")
    argv = ["--profile", str(profile), "--role", role, "--yaml", str(YAMLS[role]),
            "--target-versions", "1,2", "--today", "2026-11-10", "--samples", str(samples)]
    if package is not None:
        argv += ["--package", str(package)]
    if applies_to is not None:
        argv += ["--applies-to", applies_to]
    if answers is not None:
        path = work / "answers.yaml"
        path.write_text(yaml.safe_dump(answers, sort_keys=False, allow_unicode=True), encoding="utf-8")
        argv += ["--answers", str(path)]
    return yaml_to_package.build(argv)

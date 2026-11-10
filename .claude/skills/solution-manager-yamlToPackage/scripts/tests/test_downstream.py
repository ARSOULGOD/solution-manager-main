"""Task 9: the scripts that read the new report or draft profiles."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from helpers import ALPHA, ANSWERS_FILE, empty_dir, run_cli  # noqa: E402
import decisions  # noqa: E402
import validate_package  # noqa: E402

SCRIPTS = Path(__file__).resolve().parents[1]
RENDER = SCRIPTS / "render_config.py"
PROPOSER = SCRIPTS / "profile_proposer.py"


def one_secret_package(flag):
    field = {"key": "pw", "type": "secret", "version": [1]}
    if flag:
        field["secret"] = True
    return {"solution": "s", "packageVersion": [1],
            "components": [{"type": "c", "configPath": "", "version": [1], "fields": [field]}]}


def roundtrip(out, report=None, name="rt.md"):
    done = subprocess.run([sys.executable, str(RENDER), "roundtrip", "--package", str(out / "package.json"),
                           "--profile", str(out / "profile.yaml"), "--role", "alpha", "--version", "2",
                           "--yaml", str(ALPHA), "--report", str(report or out / "report.json"),
                           "--out", str(out / name)], capture_output=True)
    return done.returncode, (out / name).read_text()


class DownstreamTests(unittest.TestCase):
    def test_roundtrip_explains_stored_answers_and_passes(self):
        out = empty_dir()
        run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
        code, md = roundtrip(out)
        self.assertEqual(code, 0, md)
        for key, how in (("retries", "index"), ("ratio", "string"), ("extras", "map"), ("host", "second")):
            self.assertIn(f"`demo.alpha.{key}`: stored by you as {how}", md)
        self.assertIn("in the package, not in this YAML (kept)", md)

    def test_roundtrip_fails_a_removed_key_that_is_still_rendered(self):
        out = empty_dir()
        run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
        rep = json.loads((out / "report.json").read_text())
        rep["removed"].append({"key": "demo.alpha.username", "line": 3, "reason": "planted", "role": "alpha",
                               "component": "alpha-core"})
        (out / "planted.json").write_text(json.dumps(rep))
        code, md = roundtrip(out, report=out / "planted.json", name="rt2.md")
        self.assertEqual(code, 1)
        self.assertIn("BUT it is rendered", md)

    def test_roundtrip_lists_keys_removed_by_you(self):
        out = empty_dir()
        run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
        _, md = roundtrip(out)
        self.assertIn("demo.alpha.filename", md)
        self.assertIn("removed by you", md)
        self.assertNotIn("excluded", md)

    def test_proposer_drafts_without_exclude_or_ui_order(self):
        out = empty_dir()
        res = subprocess.run([sys.executable, str(PROPOSER), "--yaml", str(ALPHA), "--solution", "demo2",
                              "--out", str(out / "draft.yaml")], capture_output=True, text=True)
        text = (out / "draft.yaml").read_text()
        self.assertNotIn("exclude:", text)
        self.assertNotIn("ui_order:", text)
        self.assertIn(decisions.BEGIN, text)
        self.assertIn("asked in the preview", res.stdout)

    def test_validator_warns_on_a_secret_without_the_flag(self):
        warn = lambda pkg: [w for w in validate_package.validate(pkg)["warnings"] if "without secret: true" in w]
        self.assertEqual(len(warn(one_secret_package(flag=False))), 1)
        self.assertEqual(warn(one_secret_package(flag=True)), [])


if __name__ == "__main__":
    unittest.main()

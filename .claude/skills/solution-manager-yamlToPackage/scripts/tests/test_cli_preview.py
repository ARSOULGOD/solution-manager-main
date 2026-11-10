import json
import subprocess
import sys
import unittest
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import preview_md  # noqa: E402
import yaml_to_package  # noqa: E402
from helpers import (ANSWERS_FILE, ENGINE, PACKAGE, PROFILE, build_demo, empty_dir, run_cli, run_cli_err,  # noqa: E402
                     run_cli_full, temp_file)

ALPHA_KEYS = ["server.port", "server.address", "server.ssl.key-store-password", "demo.alpha.host",
              "demo.alpha.username", "demo.alpha.mode", "demo.alpha.retries", "demo.alpha.ratio", "demo.alpha.extras",
              "demo.alpha.fileName", "demo.alpha.filename", "demo.alpha.oldname", "demo.shared.binding",
              "demo.unknown.thing", "late"]
ANSWERS = yaml.safe_load(ANSWERS_FILE.read_text(encoding="utf-8"))


def answers_file(answers):
    return temp_file(yaml.safe_dump(answers, sort_keys=False, allow_unicode=True))


def package_with(change):
    """A temp copy of the demo package, changed in place by `change(pkg)`."""
    pkg = json.loads(PACKAGE.read_text(encoding="utf-8"))
    change(pkg)
    path = empty_dir() / "package.json"
    path.write_text(json.dumps(pkg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def model_of(out):
    return json.loads((out / "preview.json").read_text(encoding="utf-8"))


class CliPreviewTest(unittest.TestCase):
    def setUp(self):
        self.out = empty_dir()

    # ---- the brief's tests, verbatim but for `out`
    def test_preview_never_writes_a_package_and_exits_5(self):
        out = self.out
        self.assertEqual(run_cli("preview", "alpha", out), 5)
        self.assertFalse((out / "package.json").exists())
        model = json.loads((out / "preview.json").read_text())
        self.assertEqual(len(model["questions"]), 14)
        self.assertEqual(model["counts"], {"keys": 15, "placed": 3, "removed": 0, "open": 12})

    def test_preview_exits_0_when_everything_is_answered(self):
        out = self.out
        self.assertEqual(run_cli("preview", "alpha", out, answers=ANSWERS_FILE), 0)
        self.assertTrue(json.loads((out / "preview.json").read_text())["ready"])

    def test_bad_answers_exit_2_naming_the_entry(self):
        out = self.out
        code, err = run_cli_err("preview", "alpha", out, answers=temp_file("store: [{key: demo.alpha.ratio, as: float}]\n"))
        self.assertEqual(code, 2)
        self.assertIn("store demo.alpha.ratio", err)

    def test_preview_md_mentions_every_key_and_heading(self):
        out = self.out
        run_cli("preview", "alpha", out)
        md = (out / "preview.md").read_text()
        for key in ALPHA_KEYS:
            self.assertIn(key, md)
        for heading in ("## demo / alpha: PREVIEW (nothing created yet)", "### Questions (answer by number)",
                        "### For your information", '### Profile changes saved on "create"'):
            self.assertIn(heading, md)

    def test_second_preview_starts_with_the_changes(self):
        out = self.out
        run_cli("preview", "alpha", out)
        run_cli("preview", "alpha", out, answers=ANSWERS_FILE)
        md = (out / "preview.md").read_text()
        self.assertTrue(md.startswith("## Changes since the last preview"))
        self.assertIn("doc2:late", md)

    def test_no_secret_value_in_preview_files(self):
        out = self.out
        run_cli("preview", "alpha", out, answers=ANSWERS_FILE)
        for name in ("preview.md", "preview.json"):
            text = (out / name).read_text()
            self.assertNotIn("hunter2pass", text)
            self.assertNotIn("oldsecret1", text)

    def test_no_subcommand_is_a_usage_error(self):
        self.assertEqual(subprocess.run([sys.executable, str(ENGINE), "--profile", "x"], capture_output=True).returncode, 2)

    # ---- more
    def test_summary_line_and_model_keys(self):
        code, stdout, _ = run_cli_full("preview", "alpha", self.out)
        self.assertEqual(code, 5)
        self.assertEqual(stdout.strip(), "demo/alpha: keys=15 placed=3 removed=0 open=12 questions=14")
        model = model_of(self.out)
        self.assertEqual(list(model), ["solution", "role", "profile", "yaml", "package", "target_versions",
                                       "packageVersion", "appliesTo", "roles", "samples", "counts", "components",
                                       "questions", "removed", "info", "profile_changes", "ready"])
        self.assertEqual(model["roles"], {"all": ["alpha", "beta"],
                                          "this_role": [{"type": "alpha-core", "cardinality": "one-of-many"}],
                                          "shared": ["server"]})
        self.assertEqual(model["samples"]["packages"], 2)
        self.assertEqual([c["type"] for c in model["components"]], ["alpha-core", "server"])
        self.assertFalse(model["ready"])

    def test_questions_are_numbered_in_order(self):
        run_cli("preview", "alpha", self.out)
        qs = model_of(self.out)["questions"]
        self.assertEqual([q["n"] for q in qs], list(range(1, 15)))
        self.assertEqual([q["id"] for q in qs[-3:]],
                         ["suggest:alpha-core/demo.alpha.username/required", "restyle", "provenance"])
        lines = [q["line"] for q in qs[:-3]]
        self.assertEqual(lines, sorted(lines))
        for q in qs:
            self.assertEqual(set(q), {"n", "id", "kind", "key", "line", "text", "choices", "context"})

    def test_profile_changes_are_the_decisions_diff(self):
        run_cli("preview", "alpha", self.out)
        self.assertEqual(model_of(self.out)["profile_changes"], [])
        self.assertIn('### Profile changes saved on "create": none yet', (self.out / "preview.md").read_text())
        run_cli("preview", "alpha", self.out, answers=ANSWERS_FILE)
        changes = model_of(self.out)["profile_changes"]
        self.assertTrue(changes[0].startswith("---") and changes[1].startswith("+++"))
        self.assertTrue(any(line.startswith("+") and "demo.alpha.filename" in line for line in changes))
        self.assertTrue(any(line.startswith("+") and "restyle: accepted" in line for line in changes))

    def test_changes_list_rows_and_new_questions(self):
        run_cli("preview", "alpha", self.out)
        run_cli("preview", "alpha", self.out, answers=answers_file(
            {"suggestions": [{"key": "demo.alpha.username", "attribute": "required", "decision": "accept"}]}))
        md = (self.out / "preview.md").read_text()
        changes = md.split("\n## demo / alpha: PREVIEW")[0]
        self.assertIn("alpha-core/demo.alpha.username: required no → yes", changes)
        self.assertIn("suggest:alpha-core/demo.alpha.username/required", changes)
        self.assertNotIn("doc2:late", changes)

    def test_a_previous_preview_of_another_role_is_not_compared(self):
        run_cli("preview", "beta", self.out)
        run_cli("preview", "alpha", self.out)
        self.assertTrue((self.out / "preview.md").read_text().startswith("## demo / alpha: PREVIEW"))

    def test_an_answer_to_a_question_an_earlier_answer_raised_is_valid(self):
        def add_thing(pkg):
            pkg["components"][0]["fields"].append({"key": "demo.unknown.thing", "type": "boolean", "default": False,
                                                   "ui": {"section": "Alpha", "order": 8, "label": "Thing"},
                                                   "version": [1, 2]})
        package = package_with(add_thing)
        run_cli("preview", "alpha", self.out, package=package,
                answers=answers_file({"place": [{"key": "demo.unknown.thing", "component": "alpha-core"}]}))
        self.assertIn("type-conflict:demo.unknown.thing", [q["id"] for q in model_of(self.out)["questions"]])
        code, err = run_cli_err("preview", "alpha", self.out, package=package, answers=answers_file(
            {"place": [{"key": "demo.unknown.thing", "component": "alpha-core"}],
             "store": [{"key": "demo.unknown.thing", "as": "package-type"}]}))
        self.assertEqual((code, err), (5, ""))
        self.assertNotIn("type-conflict:demo.unknown.thing", [q["id"] for q in model_of(self.out)["questions"]])

    def test_a_new_package_needs_applies_to_before_the_open_gate(self):
        code, err = run_cli_err("preview", "alpha", self.out, package=None)
        self.assertEqual(code, 2)
        self.assertIn("--applies-to", err)
        self.assertEqual(run_cli("preview", "alpha", self.out, package=None, applies_to=">=1.0 <2.0"), 5)
        self.assertEqual(model_of(self.out)["package"], None)

    def test_apply_refuses_while_questions_are_open(self):
        code, err = run_cli_err("apply", "alpha", self.out)
        self.assertEqual(code, 5)
        self.assertIn("restyle", err)
        self.assertIn("doc2:late", err)
        self.assertFalse((self.out / "package.json").exists())

    def test_apply_checks_input_errors_first(self):
        code, err = run_cli_err("apply", "alpha", self.out, answers=temp_file("restyle: maybe\n"))
        self.assertEqual(code, 2)
        self.assertIn("restyle", err)

    def test_render_is_pure_and_shows_suggestion_evidence(self):
        run_cli("preview", "alpha", self.out)
        model = model_of(self.out)
        md = preview_md.render(model, None)
        self.assertEqual(md, (self.out / "preview.md").read_text())
        self.assertIn("two/svc2/username: required", md)
        self.assertIn("no → suggested yes (Q12)", md)
        self.assertIn("withheld (I6)", preview_md.render(model_of_answered(self.out), None))


def model_of_answered(out):
    run_cli("preview", "alpha", out, answers=ANSWERS_FILE)
    return model_of(out)


class CarriedFixesTest(unittest.TestCase):
    def test_signature_question_asked_with_the_open_restyle(self):
        """A package this YAML leaves unchanged would change only through the restyle: ask the signature now."""
        def settle(pkg):
            server = pkg["components"][1]
            for f in server["fields"]:
                if f["type"] == "secret":
                    f["default"], f["secret"] = None, True
        package = package_with(settle)
        yaml_path = temp_file("demo:\n  alpha:\n    host: old.example.invalid\n    mode: fast\n")
        run = build_demo("alpha", package=package, yaml_path=yaml_path)
        self.assertFalse(run.changed())
        self.assertIn("restyle", run.open)
        self.assertIn("provenance", run.open)
        refused = build_demo("alpha", package=package, yaml_path=yaml_path, answers={"restyle": "refuse"})
        self.assertNotIn("provenance", refused.open)

    def test_preserved_component_note_says_what_changed_it(self):
        profile = empty_dir() / "profile.yaml"
        profile.write_text(PROFILE.read_text(encoding="utf-8") + "preserve: {components: [server]}\n", encoding="utf-8")
        run = yaml_to_package.build(["--profile", str(profile), "--role", "alpha", "--yaml",
                                     str(Path(PACKAGE).parent / "alpha.yml"), "--target-versions", "1,2",
                                     "--today", "2026-11-10", "--package", str(PACKAGE),
                                     "--samples", str(Path(PACKAGE).parents[1] / "samples")])
        notes = [n for n in run.notes if n.startswith("component server preserved")]
        self.assertEqual(len(notes), 1)
        self.assertNotIn("untouched", notes[0])
        self.assertIn("secret", notes[0])

    def test_no_duplicate_lines_in_info(self):
        run = build_demo("alpha", answers=ANSWERS)
        self.assertEqual(len(run.info), len(set(run.info)))
        model = run.model()
        self.assertEqual(len(model["info"]), len(set(model["info"])))
        self.assertFalse(hasattr(run, "questions"))


if __name__ == "__main__":
    unittest.main()

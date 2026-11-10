import contextlib
import io
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import decisions  # noqa: E402
import yaml_to_package  # noqa: E402
from helpers import (ALPHA, ANSWERS_FILE, PACKAGE, PROFILE, cli_argv, empty_dir, run_cli, run_cli_err,  # noqa: E402
                     run_cli_full, temp_file)

SECRETS = ("hunter2pass", "oldsecret1")
OUTPUTS = ("package.json", "package.diff", "report.md", "report.json", "profile.yaml")
REPORT_KEYS = ["solution", "role", "profile", "yaml", "package", "target_versions", "counts", "placed", "removed",
               "coerced", "secrets_withheld", "diff", "shared_differences", "ui_differences", "notes", "validation",
               "authoring", "restyle", "suggestions"]


def with_changes(answers_path, drop_store=None, add_remove=None):
    """A temp copy of an answers file without the `store` answers of key `drop_store`, plus one `remove` entry."""
    answers = yaml.safe_load(Path(answers_path).read_text(encoding="utf-8"))
    if drop_store is not None:
        answers["store"] = [e for e in answers.get("store") or [] if e["key"] != drop_store]
    if add_remove is not None:
        answers.setdefault("remove", []).append(add_remove)
    return temp_file(yaml.safe_dump(answers, sort_keys=False, allow_unicode=True))


def first_package_answers():
    """answers-alpha.yaml for a run with no --package: no server component yet, so no server questions, and no
    restyle or signature question."""
    answers = yaml.safe_load(ANSWERS_FILE.read_text(encoding="utf-8"))
    answers["place"] = [e for e in answers["place"] if e["key"] != "server.ssl.key-store-password"]
    answers["store"] = [e for e in answers["store"] if e["key"] != "server.port"]
    del answers["restyle"], answers["provenance"]
    return temp_file(yaml.safe_dump(answers, sort_keys=False, allow_unicode=True))


def keys_of(pkg):
    return [f["key"] for c in pkg["components"] for f in c.get("fields", [])]


def package_file(pkg):
    path = empty_dir() / "package.json"
    path.write_text(json.dumps(pkg, indent=2) + "\n", encoding="utf-8")
    return path


class CliApplyTest(unittest.TestCase):
    def setUp(self):
        self.out = empty_dir()

    # ---- the brief's tests, verbatim but for `out`
    def test_apply_refuses_open_questions(self):
        out = self.out
        code, err = run_cli_err("apply", "alpha", out)
        self.assertEqual(code, 5)
        self.assertIn("doc2:late", err)
        self.assertFalse((out / "package.json").exists())

    def test_apply_writes_every_key_as_a_field_or_removed(self):
        out = self.out
        self.assertEqual(run_cli("apply", "alpha", out, answers=ANSWERS_FILE), 0)
        rep = json.loads((out / "report.json").read_text())
        self.assertEqual((rep["counts"]["keys"], rep["counts"]["removed"], rep["counts"]["open"]), (15, 1, 0))
        self.assertTrue(rep["counts"]["balanced"])

    def test_read_back_catches_a_missing_field(self):
        problems = yaml_to_package.read_back(str(PACKAGE), [{"key": "x", "component": "alpha-core",
                                                             "field_keys": ["demo.alpha.nothere"]}], [])
        self.assertEqual(problems, ["missing: alpha-core/demo.alpha.nothere"])

    def test_removed_key_drops_an_existing_field(self):
        out = self.out
        answers = with_changes(ANSWERS_FILE, drop_store="demo.alpha.host",
                               add_remove={"key": "demo.alpha.host", "reason": "not needed"})
        self.assertEqual(run_cli("apply", "alpha", out, answers=answers), 0)
        pkg = json.loads((out / "package.json").read_text())
        self.assertNotIn("demo.alpha.host", [f["key"] for c in pkg["components"] for f in c.get("fields", [])])

    def test_profile_hand_written_part_is_byte_identical(self):
        out = self.out
        run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
        merged = (out / "profile.yaml").read_text()
        self.assertTrue(merged.startswith(PROFILE.read_text()))
        block = decisions.read_block(merged)
        self.assertEqual(block["removed"], [{"key": "demo.alpha.filename", "role": "alpha", "date": "2026-11-10",
                                             "reason": "case variant; kept fileName"}])
        [ks] = [p for p in block["placed"] if p["key"] == "server.ssl.key-store-password"]
        self.assertEqual((ks["role"], ks["scope"]), ("alpha", "this-role"))

    def test_secret_values_absent_from_every_output(self):
        out = self.out
        run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
        for name in ("package.json", "package.diff", "report.md", "report.json", "profile.yaml"):
            text = (out / name).read_text()
            self.assertNotIn("hunter2pass", text, name)
            self.assertNotIn("oldsecret1", text, name)
        self.assertIn('"default": "***"', (out / "package.diff").read_text())

    def test_apply_without_package_requires_applies_to(self):  # review focus 2
        out = self.out
        self.assertEqual(run_cli("apply", "alpha", out, package=None), 2)

    # ---- more
    def test_apply_writes_the_five_files_and_never_its_inputs(self):
        answers = with_changes(ANSWERS_FILE)
        inputs = [PROFILE, PACKAGE, ALPHA, answers]
        before = [p.read_bytes() for p in inputs]
        code, stdout, stderr = run_cli_full("apply", "alpha", self.out, answers=answers)
        self.assertEqual((code, stderr), (0, ""))
        self.assertEqual(stdout.strip(), "demo/alpha: keys=15 placed=14 removed=1 open=0 questions=0")
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), sorted(OUTPUTS))
        self.assertEqual([p.read_bytes() for p in inputs], before)

    def test_the_profile_block_is_the_merge_of_the_answers(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        block = decisions.read_block((self.out / "profile.yaml").read_text())
        self.assertEqual(list(block), ["removed", "placed", "stored", "suggestions", "style", "provenance"])
        self.assertEqual(block["style"], {"restyle": "accepted", "date": "2026-11-10"})
        self.assertEqual(block["provenance"], {"choice": "dev-mode", "date": "2026-11-10"})
        [sug] = block["suggestions"]
        self.assertEqual((sug["component"], sug["field"], sug["attribute"], sug["decision"]),
                         ("alpha-core", "demo.alpha.username", "required", "accept"))
        self.assertTrue(sug["evidence"])
        self.assertIn({"key": "demo.alpha.retries", "as": "index", "role": "alpha", "date": "2026-11-10"},
                      block["stored"])

    def test_a_second_apply_with_the_merged_profile_asks_nothing(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        again = empty_dir()
        code = run_cli("apply", "alpha", again, profile=self.out / "profile.yaml")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads((again / "package.json").read_text()),
                         json.loads((self.out / "package.json").read_text()))

    def test_report_json_keys_and_entries(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        rep = json.loads((self.out / "report.json").read_text())
        self.assertEqual(list(rep), REPORT_KEYS)
        self.assertEqual(list(rep["counts"]), ["keys", "placed", "removed", "open", "coerced", "secrets_withheld",
                                               "balanced"])
        self.assertEqual(rep["counts"]["placed"], 14)
        for p in rep["placed"]:
            self.assertEqual(list(p), ["key", "line", "component", "field", "type", "shared"])
        placed = {p["key"]: p for p in rep["placed"]}
        self.assertEqual(len(placed), len(rep["placed"]))   # one entry per key, an `index` key included
        self.assertEqual(placed["demo.alpha.retries"]["field"], "demo.alpha.retries")
        self.assertEqual(rep["removed"], [{"key": "demo.alpha.filename", "line": 15, "reason": "case variant; kept fileName",
                                           "role": "alpha", "component": "alpha-core"}])
        self.assertEqual(rep["secrets_withheld"], [{"key": "server.ssl.key-store-password", "line": 5}])
        self.assertEqual(rep["restyle"]["decision"], "accepted")
        self.assertEqual(rep["suggestions"], [{"component": "alpha-core", "field": "demo.alpha.username",
                                               "attribute": "required", "decision": "accept"}])
        self.assertTrue(rep["validation"]["ok"])
        text = json.dumps(rep)
        for gone in ('"flagged"', '"excluded"', '"open":'):
            self.assertNotIn(gone, text.replace('"open": 0', ""))

    def test_a_secrets_changed_default_shows_as_stars(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        rep = json.loads((self.out / "report.json").read_text())
        [change] = [ch for ch in rep["diff"]["server"]["changed"] if ch["key"] == "server.ssl.trust-store-password"]
        self.assertEqual(change["changes"], [["default (secret withheld)", "***", None]])
        self.assertIn("***", (self.out / "report.md").read_text())

    def test_report_md_coverage_line_and_no_duplicate_lines(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        md = (self.out / "report.md").read_text()
        self.assertIn("15 YAML keys → 14 fields, 1 removed by you (balanced: yes)", md)
        lines = [line for line in md.splitlines() if line.startswith("- ")]
        self.assertEqual(len(lines), len(set(lines)), [x for x in lines if lines.count(x) > 1])
        for word in ("flagged", "excluded", "FLAGGED", "EXCLUDED"):
            self.assertNotIn(word, md)
        # each listed once: package-only fields in the diff section, shared defaults under shared differences
        self.assertIn("- in the package, not in this YAML (kept): 1: server.ssl.trust-store-password", md)
        self.assertNotIn("(kept): server/server.ssl.trust-store-password", md)
        self.assertEqual(md.count("server.port"), 1)

    def test_authoring_counts_fields_carried_over_unchanged(self):
        run_cli("apply", "alpha", self.out, answers=ANSWERS_FILE)
        new = json.loads((self.out / "package.json").read_text())
        old = json.loads(PACKAGE.read_text())
        old_fields = {(c["type"], f["key"]): f for c in old["components"] for f in c["fields"]}
        unchanged = sum(1 for c in new["components"] for f in c["fields"] if old_fields.get((c["type"], f["key"])) == f)
        self.assertEqual(new["authoring"], {"reviewedRows": min(6, unchanged), "totalRows": len(keys_of(new))})
        self.assertLess(new["authoring"]["reviewedRows"], 6)   # the accepted restyle changed fields
        rep = json.loads((self.out / "report.json").read_text())
        self.assertEqual(rep["authoring"], new["authoring"])

    def test_a_first_package_has_no_diff(self):
        code, _, err = run_cli_full("apply", "alpha", self.out, answers=first_package_answers(), package=None,
                                    applies_to=">=1.0 <2.0")
        self.assertEqual((code, err), (0, ""))
        self.assertFalse((self.out / "package.diff").exists())
        pkg = json.loads((self.out / "package.json").read_text())
        self.assertEqual(pkg["provenance"], {"author": "dev-mode"})
        for name in ("package.json", "report.md", "report.json", "profile.yaml"):
            for secret in SECRETS:
                self.assertNotIn(secret, (self.out / name).read_text(), name)

    def test_an_out_dir_that_would_overwrite_an_input_is_refused(self):
        profile = self.out / "profile.yaml"
        profile.write_bytes(PROFILE.read_bytes())
        code, err = run_cli_err("apply", "alpha", self.out, answers=ANSWERS_FILE, profile=profile)
        self.assertEqual(code, 2)
        self.assertIn("profile.yaml would overwrite an input", err)
        self.assertEqual(profile.read_bytes(), PROFILE.read_bytes())
        self.assertEqual([p.name for p in self.out.iterdir()], ["profile.yaml"])

    def test_bad_answers_write_nothing(self):
        answers = temp_file("store: [{key: demo.alpha.ratio, as: float}]\n")
        self.assertEqual(run_cli("apply", "alpha", self.out, answers=answers), 2)
        self.assertEqual(list(self.out.iterdir()), [])

    def test_a_removed_field_the_package_still_holds_fails_the_run(self):
        """The read-back gate end to end: with the removal not applied, apply exits 1 and says why."""
        answers = with_changes(ANSWERS_FILE, drop_store="demo.alpha.host",
                               add_remove={"key": "demo.alpha.host", "reason": "not needed"})
        argv = cli_argv("apply", "alpha", self.out, answers=answers)[2:]
        err = io.StringIO()
        with mock.patch.object(yaml_to_package.Run, "drop_removed", lambda run: None), \
                contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = yaml_to_package.main(argv)
        self.assertEqual(code, 1)
        self.assertIn("still present: alpha-core/demo.alpha.host", err.getvalue())
        self.assertIn("still present: alpha-core/demo.alpha.host", (self.out / "report.md").read_text())


class ReadBackTest(unittest.TestCase):
    PKG = {"solution": "demo", "components": [
        {"type": "alpha-core", "role": "alpha", "fields": [{"key": "a.one"}, {"key": "a.two.0"}]},
        {"type": "beta-core", "role": "beta", "fields": [{"key": "a.one"}, {"key": "b.only"}]},
        {"type": "server", "role": "none", "fields": [{"key": "s.x"}, {"key": "s.beta", "role": "beta"}]}]}

    def check(self, placed=(), removed=()):
        return yaml_to_package.read_back(str(package_file(self.PKG)), list(placed), list(removed))

    def test_every_field_key_of_a_placed_entry_is_checked(self):
        self.assertEqual(self.check(placed=[{"key": "a.two", "component": "alpha-core",
                                             "field_keys": ["a.two.0", "a.two.1"]}]),
                         ["missing: alpha-core/a.two.1"])
        self.assertEqual(self.check(placed=[{"key": "z", "component": "nowhere", "field_keys": ["z"]}]),
                         ["missing: nowhere/z"])

    def test_a_removed_entry_is_checked_in_its_component_by_field_keys(self):
        removed = {"key": "yaml.key", "line": 3, "role": "alpha", "component": "alpha-core", "field": "a.two",
                   "field_keys": ["a.two", "a.two.0"]}
        self.assertEqual(self.check(removed=[removed]), ["still present: alpha-core/a.two.0"])

    def test_a_removed_entry_without_component_is_checked_in_its_role_and_shared_components_only(self):  # Ruling 12
        self.assertEqual(self.check(removed=[{"key": "a.one", "line": 2, "role": "beta", "component": None,
                                              "field": None, "field_keys": []}]),
                         ["still present: beta-core/a.one"])
        self.assertEqual(self.check(removed=[{"key": "b.only", "line": 2, "role": "alpha", "component": None,
                                              "field": None, "field_keys": []}]), [])
        self.assertEqual(self.check(removed=[{"key": "s.x", "line": 2, "role": "alpha", "component": None,
                                              "field": None, "field_keys": []}]),
                         ["still present: server/s.x"])
        # a shared field that serves another role only is that role's, not this one's
        self.assertEqual(self.check(removed=[{"key": "s.beta", "line": 2, "role": "alpha", "component": None,
                                              "field": None, "field_keys": []}]), [])

    def test_a_removed_package_only_field_is_checked_by_its_field_key(self):
        self.assertEqual(self.check(removed=[{"key": "s.x", "line": None, "role": None, "component": "server",
                                              "field": "s.x", "field_keys": ["s.x"]}]),
                         ["still present: server/s.x"])


    def test_a_crlf_profile_keeps_its_bytes(self):  # final review 7
        work = empty_dir()
        crlf = work / "profile.yaml"
        crlf.write_bytes(PROFILE.read_bytes().replace(b"\n", b"\r\n"))
        out = work / "out"
        self.assertEqual(run_cli("apply", "alpha", out, answers=ANSWERS_FILE, profile=crlf), 0)
        merged = (out / "profile.yaml").read_bytes()
        self.assertTrue(merged.startswith(crlf.read_bytes()))
        self.assertNotIn(b"\n", merged.replace(b"\r\n", b""))   # one line ending throughout
        out2 = work / "out2"   # applied again from the merged profile: the same bytes, no growing blank lines
        self.assertEqual(run_cli("apply", "alpha", out2, profile=out / "profile.yaml"), 0)
        self.assertEqual((out2 / "profile.yaml").read_bytes(), merged)

if __name__ == "__main__":
    unittest.main()

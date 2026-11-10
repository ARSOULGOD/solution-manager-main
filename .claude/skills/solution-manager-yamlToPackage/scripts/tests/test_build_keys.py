import contextlib
import io
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from helpers import PACKAGE, build_demo, empty_dir, fields_by_key, temp_file  # noqa: E402

ALPHA_OPEN = {"no-component:demo.unknown.thing", "excluded:demo.alpha.oldname", "doc2:late", "list:demo.alpha.retries",
              "decimal:demo.alpha.ratio", "empty-map:demo.alpha.extras", "enum-value:demo.alpha.mode",
              "dup-key:demo.alpha.host", "type-conflict:server.port", "shared-new:server.ssl.key-store-password",
              "spelling:demo.alpha.filename"}


def package_with_alpha_field(key):
    """A copy of the demo package whose alpha-core also has a field `key`."""
    pkg = json.loads(PACKAGE.read_text(encoding="utf-8"))
    pkg["components"][0]["fields"].append(dict(pkg["components"][0]["fields"][0], key=key))
    path = empty_dir() / "package.json"
    path.write_text(json.dumps(pkg), encoding="utf-8")
    return path


class BuildKeysTest(unittest.TestCase):
    def test_alpha_counts_and_questions(self):
        run = build_demo("alpha")
        self.assertEqual(run.key_count, 15)
        self.assertTrue(run.balanced())
        self.assertEqual(sorted(p["key"] for p in run.placed), ["demo.alpha.username", "demo.shared.binding", "server.address"])
        expected = {"no-component:demo.unknown.thing": ["place", "remove"], "excluded:demo.alpha.oldname": ["place", "remove"],
                    "doc2:late": ["place", "remove"], "list:demo.alpha.retries": ["list", "join-comma", "index", "remove"],
                    "decimal:demo.alpha.ratio": ["string", "remove"], "empty-map:demo.alpha.extras": ["map", "remove"],
                    "enum-value:demo.alpha.mode": ["add-option", "string", "remove"],
                    "dup-key:demo.alpha.host": ["first", "second", "remove"],
                    "type-conflict:server.port": ["package-type", "yaml-type", "remove"],
                    "shared-new:server.ssl.key-store-password": ["all-roles", "this-role", "role-component", "remove"]}
        for qid, choices in expected.items():
            self.assertEqual(run.open[qid]["choices"], choices, qid)
        [sp] = [q for q in run.open.values() if q["kind"] == "spelling"]
        self.assertEqual(set(sp["context"]["keys"]), {"demo.alpha.fileName", "demo.alpha.filename"})
        self.assertEqual(set(run.open), ALPHA_OPEN)
        self.assertEqual(run.removed, [])

    def test_wrong_role_key_is_a_question(self):
        self.assertIn("wrong-role:demo.alpha.leak", build_demo("beta").open)

    def test_place_and_remove_answers(self):
        run = build_demo("alpha", answers={
            "remove": [{"key": "demo.alpha.filename", "reason": "case variant; kept fileName"}],
            "place": [{"key": "demo.unknown.thing", "component": "alpha-core"},
                      {"key": "late", "component": "late", "new": {"role": "none", "cardinality": "zero-or-more", "configPath": ""}}]})
        placed = {p["key"]: p["component"] for p in run.placed}
        self.assertEqual((placed["demo.unknown.thing"], placed["late"], placed["demo.alpha.fileName"]), ("alpha-core", "late", "alpha-core"))
        self.assertEqual([r["key"] for r in run.removed], ["demo.alpha.filename"])
        self.assertTrue(run.balanced())

    def test_role_decisions_stay_with_their_role(self):  # review focus 1
        block = {"removed": [{"key": "demo.shared.binding", "role": "alpha", "date": "d", "reason": "r"},
                             {"key": "demo.alpha.gone", "role": "alpha", "date": "d", "reason": "r"}]}
        beta = build_demo("beta", remembered=block)
        self.assertIn("demo.shared.binding", [p["key"] for p in beta.placed if p["component"] == "beta-core"])
        self.assertFalse(any("demo.alpha.gone" in i for i in beta.info))
        alpha = build_demo("alpha", remembered=block)
        self.assertIn("demo.shared.binding", [r["key"] for r in alpha.removed])
        self.assertTrue(any("demo.alpha.gone" in i for i in alpha.info))

    def test_scopeless_place_into_a_shared_component_stays_open(self):  # fix 1
        run = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "server"}]})
        self.assertIn("shared-new:server.ssl.key-store-password", run.open)
        self.assertNotIn("server.ssl.key-store-password", fields_by_key(run.pkg))
        self.assertTrue(run.balanced())
        own = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "alpha-core"}]})
        self.assertNotIn("shared-new:server.ssl.key-store-password", own.open)
        self.assertIn(("server.ssl.key-store-password", "alpha-core"), [(p["key"], p["component"]) for p in own.placed])

    def test_removed_component_comes_from_the_place_decision_or_the_field(self):  # fix 2a
        pkg = package_with_alpha_field("demo.unknown.thing")
        remembered = {"placed": [{"key": "demo.unknown.thing", "component": "alpha-core", "role": "alpha", "date": "d"}]}
        answers = {"remove": [{"key": "demo.unknown.thing", "reason": "not needed"}]}
        for run in (build_demo("alpha", answers=answers, remembered=remembered, package=pkg),
                    build_demo("alpha", answers=answers, package=pkg)):
            [r] = run.removed
            self.assertEqual((r["component"], r["field"], r["role"]), ("alpha-core", "demo.unknown.thing", "alpha"))
            self.assertNotIn("demo.unknown.thing", [f["key"] for f in run.find_comp("alpha-core")["fields"]])
            self.assertTrue(run.balanced())

    def test_removed_wrong_role_key_has_no_component(self):  # fix 2b
        run = build_demo("beta", answers={"remove": [{"key": "demo.alpha.leak", "reason": "alpha's key"}]},
                         package=package_with_alpha_field("demo.alpha.leak"))
        [r] = run.removed
        self.assertEqual((r["component"], r["role"]), (None, "beta"))
        self.assertIn("demo.alpha.leak", [f["key"] for f in run.find_comp("alpha-core")["fields"]])

    def test_a_key_in_document_1_and_2_counts_once(self):  # fix 3
        yml = temp_file("demo:\n  alpha:\n    username: bob\n---\ndemo:\n  alpha:\n    username: carol\n---\nlate: 1\n")
        run = build_demo("alpha", yaml_path=yml)
        self.assertEqual(run.key_count, 2)
        self.assertEqual(set(run.open), {"doc2:late"})
        self.assertEqual([p["key"] for p in run.placed], ["demo.alpha.username"])
        self.assertTrue(run.balanced())
        gone = build_demo("alpha", yaml_path=yml, answers={"remove": [{"key": "demo.alpha.username", "reason": "x"},
                                                                     {"key": "late", "reason": "x"}]})
        self.assertEqual([r["key"] for r in gone.removed], ["demo.alpha.username", "late"])
        self.assertTrue(gone.balanced())

    def test_removing_a_package_only_field(self):  # fix 4
        run = build_demo("alpha", answers={"remove": [{"component": "alpha-core", "field": "demo.alpha.legacy",
                                                       "reason": "obsolete"}]})
        self.assertNotIn("demo.alpha.legacy", fields_by_key(run.pkg))
        self.assertEqual(run.removed, [{"key": "demo.alpha.legacy", "line": None, "reason": "obsolete", "role": "alpha",
                                        "component": "alpha-core", "field": "demo.alpha.legacy",
                                        "field_keys": ["demo.alpha.legacy"]}])
        self.assertEqual(run.key_count, 15)
        self.assertTrue(run.balanced())

    def test_a_mapping_written_twice_is_bad_input(self):  # fix 5
        yml = temp_file("demo:\n  alpha:\n    username: bob\ndemo:\n  alpha:\n    host: h\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            build_demo("alpha", yaml_path=yml)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("demo", err.getvalue())
        self.assertIn("lines 1 and 4", err.getvalue())
        self.assertIn("merge", err.getvalue())


if __name__ == "__main__":
    unittest.main()

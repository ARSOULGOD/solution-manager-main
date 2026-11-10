import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from helpers import build_demo  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()

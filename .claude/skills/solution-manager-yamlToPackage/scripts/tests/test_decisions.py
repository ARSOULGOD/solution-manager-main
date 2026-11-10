import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import decisions  # noqa: E402


def temp_file(text):
    handle = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False, encoding="utf-8")
    handle.write(text)
    handle.close()
    return Path(handle.name)


CTX = {
    "yaml_keys": {"a.b", "a.list", "a.pw"},
    "open": {"list:a.list": {"kind": "list", "key": "a.list", "choices": ["list", "join-comma", "index", "remove"]}},
    "fields": {"a.pw": ("core", "a.pw")},
    "package_fields": {("core", "a.pw")},
    "secrets": {("core", "a.pw")},
    "components": {"core"},
    "remembered": {},
}


class DecisionsTest(unittest.TestCase):
    def test_block_round_trip_keeps_the_rest_byte_identical(self):
        text = "# keep me\na: 1  # inline\n"
        once = decisions.write_block(text, {"removed": [{"key": "x", "role": None, "date": "2026-11-10", "reason": "r"}]})
        twice = decisions.write_block(once, {"style": {"restyle": "accepted", "date": "2026-11-10"}})
        self.assertTrue(once.startswith(text) and twice.startswith(text))
        self.assertEqual(twice.count(decisions.BEGIN), 1)
        self.assertEqual(decisions.read_block(twice), {"style": {"restyle": "accepted", "date": "2026-11-10"}})

    def test_block_appended_without_markers_or_final_newline(self):  # review focus 4
        text = "solution: demo  # no final newline"
        self.assertTrue(decisions.write_block(text, {}).startswith(text + "\n" + decisions.BEGIN))

    def test_one_marker_is_an_error(self):
        with self.assertRaises(ValueError):
            decisions.read_block("x: 1\n" + decisions.BEGIN + "\n")

    def test_unknown_answer_section_is_rejected(self):
        with self.assertRaises(ValueError):
            decisions.load_answers(temp_file("skip: []\n"))

    def test_validate_answers_names_each_problem(self):
        errors = decisions.validate_answers({
            "remove": [{"key": "a.b"}],
            "store": [{"key": "a.list", "as": "csv"}],
            "amend": [{"key": "a.pw", "set": {"default": "x"}}],
            "place": [{"key": "a.b", "component": "core"}],
            "suggestions": [{"key": "nope", "attribute": "required", "decision": "accept"}],
        }, CTX)
        self.assertEqual(len(errors), 5)
        for needle in ("remove a.b", "store a.list", "amend a.pw", "place a.b", "suggestions nope"):
            self.assertTrue(any(e.startswith(needle) for e in errors), needle)

    def test_effective_keeps_each_role_to_itself(self):
        remembered = {"removed": [{"key": "a", "role": "alpha", "date": "d", "reason": "r"},
                                  {"key": "s", "role": None, "date": "d", "reason": "r"}]}
        self.assertEqual(set(decisions.effective(remembered, {}, "beta")["removed"]), {"s"})
        self.assertEqual(set(decisions.effective(remembered, {}, "alpha")["removed"]), {"a", "s"})

    def test_merge_dates_roles_overrides_and_forget(self):
        remembered = {"removed": [{"key": "x", "role": "alpha", "date": "2026-01-01", "reason": "old"}],
                      "suggestions": [{"component": "c", "field": "f", "attribute": "required", "decision": "reject",
                                       "date": "2026-01-01", "evidence": []}]}
        answers = {"place": [{"key": "x", "component": "core"}],
                   "forget": [{"component": "c", "field": "f", "attribute": "required"}]}
        new = decisions.merge(remembered, answers, today="2026-11-10", placements={"x": ("core", "x", "alpha")}, evidence={})
        self.assertNotIn("removed", new)
        self.assertNotIn("suggestions", new)
        self.assertEqual(new["placed"], [{"key": "x", "component": "core", "role": "alpha", "date": "2026-11-10"}])

    def test_merge_keeps_scope_and_new_and_orders_sections(self):
        answers = {"provenance": "dev-mode", "restyle": "refuse",
                   "place": [{"key": "x", "component": "late", "scope": "this-role",
                              "new": {"role": "none", "cardinality": "zero-or-more", "configPath": ""}}],
                   "store": [{"key": "l", "as": "join-comma"}]}
        new = decisions.merge({}, answers, today="2026-11-10",
                              placements={"x": ("late", "x", "alpha"), "l": ("core", "l", None)}, evidence={})
        self.assertEqual(list(new), ["placed", "stored", "style", "provenance"])
        self.assertEqual(new["placed"][0]["scope"], "this-role")
        self.assertEqual(new["placed"][0]["new"]["cardinality"], "zero-or-more")
        self.assertEqual(new["style"], {"restyle": "refused", "date": "2026-11-10"})
        self.assertEqual(new["provenance"], {"choice": "dev-mode", "date": "2026-11-10"})

    def test_load_answers_and_effective_answers_override(self):
        path = temp_file("remove: [{key: a.b, reason: dup}]\nrestyle: accept\n")
        answers = decisions.load_answers(str(path))
        self.assertEqual(decisions.load_answers(None), {})
        eff = decisions.effective({"stored": [{"key": "a.b", "as": "index", "role": None, "date": "d"}]}, answers, "alpha")
        self.assertEqual(eff["removed"], {"a.b": "dup"})
        self.assertEqual(eff["stored"], {})
        self.assertEqual(eff["restyle"], "accepted")


if __name__ == "__main__":
    unittest.main()

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

    def test_validate_happy_path_returns_no_errors(self):
        self.assertEqual(decisions.validate_answers({
            "remove": [{"key": "a.b", "reason": "dup"}],
            "store": [{"key": "a.list", "as": "join-comma"}],
            "amend": [{"key": "a.b", "set": {"required": True}}]}, CTX), [])

    def test_place_requires_component_even_with_new(self):
        ctx = dict(CTX, open={"x": {"kind": "no-component", "key": "q", "choices": []}})
        errors = decisions.validate_answers({"place": [{"key": "q", "new": {
            "role": "none", "cardinality": "zero-or-more", "configPath": ""}}]}, ctx)
        self.assertEqual(errors, ["place q: component missing"])

    def test_effective_applies_answered_suggestions_through_fields(self):
        answers = {"suggestions": [{"key": "a.pw", "attribute": "required", "decision": "accept"},
                                   {"key": "zzz", "attribute": "required", "decision": "accept"}]}
        remembered = {"suggestions": [{"component": "core", "field": "a.pw", "attribute": "required",
                                       "decision": "reject", "date": "d", "evidence": []}]}
        eff = decisions.effective(remembered, answers, "alpha", fields={"a.pw": ("core", "a.pw")})
        self.assertEqual(eff["suggestions"], {("core", "a.pw", "required"): "accept"})

    def test_effective_dedupes_amend_by_key_against_remembered_component_field(self):
        remembered = {"amendments": [{"component": "core", "field": "a.pw", "set": {"required": False},
                                      "date": "d", "reason": None}]}
        eff = decisions.effective(remembered, {"amend": [{"key": "a.pw", "set": {"required": True}}]}, "alpha",
                                  fields={"a.pw": ("core", "a.pw")})
        self.assertEqual([a["set"] for a in eff["amendments"]], [{"required": True}])

    def test_malformed_sections_are_reported(self):
        errors = decisions.validate_answers({"remove": "oops", "store": ["x", {"key": "a.list", "as": "list"}]}, CTX)
        self.assertIn("remove: must be a list of mappings", errors)
        self.assertIn("store item 0: not a mapping", errors)

    def test_secret_default_refused_by_type_or_target_but_null_allowed(self):
        bad_type = decisions.validate_answers({"amend": [{"key": "a.b", "set": {"type": "secret", "default": "x"}}]}, CTX)
        bad_target = decisions.validate_answers({"amend": [{"key": "a.pw", "set": {"default": "x"}}]}, CTX)
        null_ok = decisions.validate_answers({"amend": [{"key": "a.pw", "set": {"default": None}}]}, CTX)
        self.assertEqual((len(bad_type), len(bad_target), null_ok), (1, 1, []))

    def test_answer_replaces_only_same_role_or_shared_entries(self):
        remembered = {"placed": [{"key": "k", "component": "c1", "role": "alpha", "date": "d"},
                                 {"key": "k", "component": "c0", "role": None, "date": "d"}]}
        answers = {"place": [{"key": "k", "component": "c2", "scope": "this-role"}]}
        new = decisions.merge(remembered, answers, today="T", placements={"k": ("c2", "k", "beta")}, evidence={})
        self.assertEqual([(e["component"], e["role"]) for e in new["placed"]], [("c1", "alpha"), ("c2", "beta")])
        eff = decisions.effective(remembered, answers, "alpha")
        self.assertEqual(eff["placed"]["k"]["component"], "c2")  # alpha sees the shared one replaced by its answer

    def test_merge_role_fallback_for_key_without_placement(self):
        new = decisions.merge({}, {"remove": [{"key": "orphan", "reason": "r"}]}, today="T", placements={},
                              evidence={}, role="alpha")
        self.assertEqual(new["removed"][0]["role"], "alpha")


if __name__ == "__main__":
    unittest.main()

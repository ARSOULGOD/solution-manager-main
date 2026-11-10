import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import decisions  # noqa: E402
import helpers  # noqa: E402
from helpers import ALPHA, PROFILE, build_demo, fields_by_key, temp_file  # noqa: E402

STORE = {"store": [{"key": "demo.alpha.retries", "as": "index"}, {"key": "demo.alpha.ratio", "as": "string"},
                   {"key": "demo.alpha.extras", "as": "map"}, {"key": "demo.alpha.mode", "as": "add-option"},
                   {"key": "server.port", "as": "package-type"}, {"key": "demo.alpha.host", "as": "second"}],
         "place": [{"key": "server.ssl.key-store-password", "component": "server", "scope": "this-role"}]}


class BuildValuesTest(unittest.TestCase):
    # ---- the brief's tests, verbatim
    def test_store_answers_shape_the_fields(self):
        f = fields_by_key(build_demo("alpha", answers=STORE).pkg)
        self.assertEqual([f[f"demo.alpha.retries.{i}"]["type"] for i in range(3)], ["int"] * 3)
        self.assertEqual((f["demo.alpha.ratio"]["type"], f["demo.alpha.ratio"]["default"]), ("string", "0.5"))
        self.assertEqual((f["demo.alpha.extras"]["type"], f["demo.alpha.extras"]["default"]), ("map", None))
        self.assertEqual((f["demo.alpha.mode"]["options"], f["demo.alpha.mode"]["default"]), (["fast", "slow", "medium"], "medium"))
        self.assertEqual((f["server.port"]["type"], f["server.port"]["default"]), ("string", "8094"))
        self.assertEqual(f["demo.alpha.host"]["default"], "second.example.invalid")

    def test_this_role_scope_sets_the_field_role_and_withholds_the_secret(self):
        f = fields_by_key(build_demo("alpha", answers=STORE).pkg)["server.ssl.key-store-password"]
        self.assertEqual((f["role"], f["type"], f["default"]), ("alpha", "secret", None))

    def test_join_and_list_shapes(self):
        j = fields_by_key(build_demo("alpha", answers={"store": [{"key": "demo.alpha.retries", "as": "join-comma"}]}).pkg)
        self.assertEqual((j["demo.alpha.retries"]["type"], j["demo.alpha.retries"]["default"]), ("string", "1,2,3"))
        l = fields_by_key(build_demo("alpha", answers={"store": [{"key": "demo.alpha.retries", "as": "list"}]}).pkg)
        self.assertEqual((l["demo.alpha.retries"]["type"], l["demo.alpha.retries"]["default"]), ("list", None))

    def test_shared_default_difference_is_information(self):
        run = build_demo("alpha")
        self.assertEqual(fields_by_key(run.pkg)["server.address"]["default"], "0.0.0.0")
        self.assertIn('shared default differs (package kept): server server.address: package "0.0.0.0", YAML "127.0.0.1"', run.info)

    # ---- the outcomes stay whole
    def test_resolved_keys_are_placed_and_balanced(self):
        run = build_demo("alpha", answers=STORE)
        self.assertTrue(run.balanced())
        self.assertTrue(run.validation["ok"], run.validation["errors"])
        placed = {p["key"]: p for p in run.placed}
        for key in ("demo.alpha.retries", "demo.alpha.ratio", "demo.alpha.extras", "demo.alpha.mode", "server.port",
                    "demo.alpha.host", "server.ssl.key-store-password"):
            self.assertIn(key, placed)
            self.assertIn(key, run.placements)
            self.assertFalse([q for q in run.open.values() if q["key"] == key], key)
        self.assertEqual(placed["demo.alpha.retries"]["field_keys"],
                         ["demo.alpha.retries.0", "demo.alpha.retries.1", "demo.alpha.retries.2"])
        self.assertEqual((placed["server.ssl.key-store-password"]["component"], run.placements["server.ssl.key-store-password"]),
                         ("server", ("server", "server.ssl.key-store-password", "alpha")))
        list_notes = [c for c in run.coerced if c["key"] == "demo.alpha.retries"]
        self.assertEqual(list_notes, [])   # index: no coercion
        l = build_demo("alpha", answers={"store": [{"key": "demo.alpha.retries", "as": "list"}]})
        self.assertTrue([c for c in l.coerced if c["key"] == "demo.alpha.retries"])
        self.assertTrue(l.balanced())

    def test_package_type_reports_no_shared_difference(self):
        run = build_demo("alpha", answers=STORE)
        self.assertFalse([i for i in run.info if "server.port" in i])

    def test_secret_comment_never_becomes_helper_text(self):  # I6
        f = fields_by_key(build_demo("alpha", answers=STORE).pkg)["server.ssl.key-store-password"]
        self.assertNotIn("hunter2pass", f["ui"]["helperText"])

    # ---- the other choices
    def test_all_roles_scope_adds_the_field_without_a_role(self):
        run = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "server",
                                                      "scope": "all-roles"}]})
        f = fields_by_key(run.pkg)["server.ssl.key-store-password"]
        self.assertNotIn("role", f)
        self.assertEqual((f["type"], f["default"]), ("secret", None))
        self.assertEqual(run.placements["server.ssl.key-store-password"], ("server", "server.ssl.key-store-password", None))
        self.assertTrue(run.balanced())

    def test_role_component_choice_places_it_in_this_roles_component(self):
        run = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "alpha-core"}]})
        self.assertIn("server.ssl.key-store-password", [f["key"] for f in run.find_comp("alpha-core")["fields"]])
        self.assertNotIn("server.ssl.key-store-password", [f["key"] for f in run.find_comp("server")["fields"]])

    def test_yaml_type_first_and_enum_string(self):
        run = build_demo("alpha", answers={"store": [{"key": "server.port", "as": "yaml-type"},
                                                     {"key": "demo.alpha.host", "as": "first"},
                                                     {"key": "demo.alpha.mode", "as": "string"}]})
        f = fields_by_key(run.pkg)
        self.assertEqual((f["server.port"]["type"], f["server.port"]["default"]), ("int", 8080))
        self.assertEqual(f["demo.alpha.host"]["default"], "first.example.invalid")
        self.assertEqual((f["demo.alpha.mode"]["type"], f["demo.alpha.mode"]["default"]), ("string", "medium"))
        self.assertNotIn("options", f["demo.alpha.mode"])
        self.assertTrue(run.balanced())

    def test_an_answer_that_was_not_offered_leaves_the_question_open(self):
        run = build_demo("alpha", answers={"store": [{"key": "demo.alpha.ratio", "as": "index"}]})
        self.assertIn("decimal:demo.alpha.ratio", run.open)
        self.assertNotIn("demo.alpha.ratio", fields_by_key(run.pkg))
        self.assertTrue(run.balanced())

    def test_decimal_string_keeps_the_text(self):
        yml = temp_file("demo:\n  alpha:\n    ratio: 1.10\n")
        f = fields_by_key(build_demo("alpha", yaml_path=yml,
                                     answers={"store": [{"key": "demo.alpha.ratio", "as": "string"}]}).pkg)
        self.assertEqual(f["demo.alpha.ratio"]["default"], "1.10")

    def test_list_choices_follow_the_items(self):
        yml = temp_file("demo:\n  alpha:\n    routes:\n      - {id: a}\n      - {id: b}\n    weights: [0.5, 1.5]\n")
        run = build_demo("alpha", yaml_path=yml)
        self.assertEqual(run.open["list:demo.alpha.routes"]["choices"], ["list", "remove"])
        self.assertEqual(run.open["list:demo.alpha.weights"]["choices"], ["list", "join-comma", "remove"])

    def test_a_secret_list_never_leaks_its_values(self):  # I6
        yml = temp_file("demo:\n  alpha:\n    api-key: [k1secret, k2secret]\n")
        for how in ("join-comma", "index", "list"):
            run = build_demo("alpha", yaml_path=yml, answers={"store": [{"key": "demo.alpha.api-key", "as": how}]})
            text = repr(run.pkg) + repr(run.coerced) + repr(run.info) + repr(run.notes)
            self.assertNotIn("k1secret", text, how)
            self.assertTrue(run.balanced(), how)

    def test_role_exclusive_shared_offers_no_all_roles(self):
        text = PROFILE.read_text(encoding="utf-8") + \
            "role_exclusive:\n  - {match: server.ssl.key-store-password, roles: [alpha], reason: alpha only}\n"
        with mock.patch.object(helpers, "PROFILE", temp_file(text)):
            run = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "server",
                                                          "scope": "all-roles"}]})
            self.assertEqual(run.open["role-exclusive-shared:server.ssl.key-store-password"]["choices"],
                             ["this-role", "role-component", "remove"])
            ctx = {"open": run.open, "yaml_keys": set(run.yaml_keys), "components": {"server"}}
            errors = decisions.validate_answers({"place": [{"key": "server.ssl.key-store-password",
                                                            "component": "server", "scope": "all-roles"}]}, ctx)
            self.assertTrue([e for e in errors if "all-roles" in e], errors)
            ok = build_demo("alpha", answers={"place": [{"key": "server.ssl.key-store-password", "component": "server",
                                                         "scope": "this-role"}]})
            self.assertEqual(fields_by_key(ok.pkg)["server.ssl.key-store-password"]["role"], "alpha")
            self.assertTrue(ok.balanced())

    def test_duplicates_carry_their_value(self):
        dup = common.load_yaml_leaves(str(ALPHA))["duplicates"]
        self.assertEqual([(d["key"], d["value"]) for d in dup], [("demo.alpha.host", "second.example.invalid")])


if __name__ == "__main__":
    unittest.main()

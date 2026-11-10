import json
import sys
import unittest
from pathlib import Path
from unittest import mock

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import helpers  # noqa: E402
from helpers import ANSWERS_FILE, PROFILE, build_demo, empty_dir, fields_by_key, package_copy, temp_file  # noqa: E402

ANSWERS_ALL = yaml.safe_load(ANSWERS_FILE.read_text(encoding="utf-8"))


def without(answers, *sections):
    return {k: v for k, v in answers.items() if k not in sections}


class BuildPackageTest(unittest.TestCase):
    # ---- the brief's tests, verbatim
    def test_suggestion_question_carries_evidence(self):
        q = build_demo("alpha").open["suggest:alpha-core/demo.alpha.username/required"]
        self.assertEqual(q["choices"], ["accept", "reject"])
        self.assertIn("two/svc2/username: required", q["context"]["evidence"])

    def test_accepted_and_rejected_suggestions(self):
        acc = build_demo("alpha", answers={"suggestions": [{"key": "demo.alpha.username", "attribute": "required", "decision": "accept"}]})
        self.assertTrue(fields_by_key(acc.pkg)["demo.alpha.username"]["required"])
        rej = build_demo("alpha", remembered={"suggestions": [{"component": "alpha-core", "field": "demo.alpha.username",
            "attribute": "required", "decision": "reject", "date": "d", "evidence": []}]})
        self.assertNotIn("suggest:alpha-core/demo.alpha.username/required", rej.open)
        self.assertNotIn("required", fields_by_key(rej.pkg)["demo.alpha.username"])

    def test_restyle_question_counts_existing_fields(self):
        q = build_demo("alpha").open["restyle"]
        self.assertEqual(q["choices"], ["accept", "refuse"])
        self.assertEqual(q["context"]["totals"], {"order_changed": 4, "stock_removed": 5, "custom_kept": 1})

    def test_accepted_and_refused_restyle(self):
        acc = fields_by_key(build_demo("alpha", answers={"restyle": "accept"}).pkg)
        self.assertEqual((acc["demo.alpha.host"]["ui"]["order"], acc["demo.alpha.legacy"]["ui"]["order"]), (0, 0))
        self.assertNotIn("helperText", acc["demo.alpha.host"]["ui"])
        self.assertEqual(acc["demo.alpha.legacy"]["ui"]["helperText"], "Hand-written help.")
        ref = fields_by_key(build_demo("alpha", answers={"restyle": "refuse"}).pkg)
        self.assertEqual(ref["demo.alpha.username"]["ui"]["order"], 8)   # running style continues after 7

    def test_first_package_has_no_restyle_or_signature_question(self):  # review focus 2
        run = build_demo("alpha", package=None, applies_to=">=1.0 <2.0")
        self.assertNotIn("restyle", run.open)
        self.assertNotIn("provenance", run.open)
        f = fields_by_key(run.pkg)
        self.assertEqual(f["demo.alpha.username"]["ui"]["order"], 0)
        self.assertTrue(all("helperText" not in x["ui"] for x in f.values()))
        self.assertTrue(all(x.get("secret") for x in f.values() if x["type"] == "secret"))

    def test_signature_question_only_when_a_signed_package_changes(self):
        self.assertEqual(build_demo("alpha").open["provenance"]["choices"], ["keep", "dev-mode"])
        self.assertNotIn("provenance", build_demo("alpha", package=package_copy(provenance={"author": "dev-mode"})).open)

    def test_amendments_apply(self):
        run = build_demo("alpha", answers={"amend": [{"key": "demo.alpha.username", "set": {"ui.label": "User", "required": True}}]})
        f = fields_by_key(run.pkg)["demo.alpha.username"]
        self.assertEqual((f["ui"]["label"], f["required"]), ("User", True))

    def test_rows_never_show_secret_values(self):
        run = build_demo("alpha", answers=ANSWERS_ALL)
        self.assertNotIn("hunter2pass", json.dumps(run.rows))
        self.assertNotIn("oldsecret1", json.dumps(run.rows))
        [row] = [r for r in run.rows if r["field"] == "server.ssl.key-store-password"]
        self.assertEqual(row["default_display"], "withheld (I6)")

    def test_no_samples_is_information(self):
        run = build_demo("alpha", samples=empty_dir())
        self.assertFalse(any(q["kind"] == "suggest" for q in run.open.values()))
        self.assertIn("no samples found; no suggestions", run.samples["notes"])

    # ---- more
    def test_the_alpha_answers_close_every_question(self):
        run = build_demo("alpha", answers=ANSWERS_ALL)
        self.assertEqual(run.open, {})
        self.assertTrue(run.balanced())
        self.assertTrue(run.validation["ok"], run.validation)
        self.assertEqual(run.pkg["provenance"], {"author": "dev-mode"})
        self.assertTrue(fields_by_key(run.pkg)["demo.alpha.username"]["required"])
        text = json.dumps(run.pkg) + json.dumps(run.rows) + json.dumps(run.info) + json.dumps(run.open)
        for secret in ("hunter2pass", "oldsecret1"):
            self.assertNotIn(secret, text)

    def test_suggestion_context_and_samples(self):
        run = build_demo("alpha")
        q = run.open["suggest:alpha-core/demo.alpha.username/required"]
        self.assertEqual((q["kind"], q["key"], q["context"]["support"], q["context"]["matched"]), ("suggest", "demo.alpha.username", 2, 2))
        self.assertNotIn("demo/x/host: required", q["context"]["evidence"])   # the target solution is excluded
        self.assertEqual((run.samples["dir"], run.samples["packages"]), (str(helpers.SAMPLES), 2))
        self.assertEqual([i for i in run.open if i.startswith("suggest:")], ["suggest:alpha-core/demo.alpha.username/required"])
        self.assertTrue(run.balanced())   # a suggestion does not hold its key open

    def test_a_field_with_the_attribute_gets_no_suggestion(self):
        run = build_demo("alpha", answers={"amend": [{"key": "demo.alpha.username", "set": {"required": False}}]})
        self.assertNotIn("suggest:alpha-core/demo.alpha.username/required", run.open)
        self.assertIs(fields_by_key(run.pkg)["demo.alpha.username"]["required"], False)

    def test_refused_restyle_is_remembered_and_keeps_the_style(self):
        answers = {"place": [{"key": "server.ssl.key-store-password", "component": "server", "scope": "this-role"}]}
        run = build_demo("alpha", answers=answers, remembered={"style": {"restyle": "refused", "date": "d"}})
        self.assertNotIn("restyle", run.open)
        f = fields_by_key(run.pkg)
        self.assertEqual(f["demo.alpha.host"]["ui"]["order"], 5)
        self.assertNotIn("helperText", f["demo.alpha.username"]["ui"])   # alpha-core is sparse (one custom helper)
        ks = f["server.ssl.key-store-password"]["ui"]                   # server is stock and running
        self.assertEqual((ks["order"], ks["helperText"]), (3, "Enter “Key store password” for the “SSL” section. "
                                                              "This value is treated as sensitive."))

    def test_restyle_question_lists_every_component(self):
        q = build_demo("alpha").open["restyle"]
        self.assertEqual(q["context"]["components"], {
            "alpha-core": {"order_changed": 3, "stock_removed": 2, "custom_kept": 1},
            "server": {"order_changed": 1, "stock_removed": 3, "custom_kept": 0}})

    def test_a_package_in_db_designer_style_has_no_restyle_question(self):
        pkg = temp_file(json.dumps(build_demo("alpha", answers={"restyle": "accept"}).pkg))
        self.assertNotIn("restyle", build_demo("alpha", package=pkg).open)

    def test_the_profile_ui_order_is_ignored(self):
        variant = temp_file(PROFILE.read_text(encoding="utf-8") + "ui_order: section-index\n")
        with mock.patch.object(helpers, "PROFILE", variant):
            ref = fields_by_key(build_demo("alpha", answers={"restyle": "refuse"}).pkg)
            new = fields_by_key(build_demo("alpha", package=None, applies_to=">=1.0 <2.0").pkg)
        self.assertEqual(ref["demo.alpha.username"]["ui"]["order"], 8)
        self.assertEqual(new["demo.alpha.username"]["ui"]["order"], 0)

    def test_secret_flag_is_added_and_listed(self):
        run = build_demo("alpha")
        self.assertIs(fields_by_key(run.pkg)["server.ssl.trust-store-password"]["secret"], True)
        self.assertIn("secret: true added: server/server.ssl.trust-store-password", run.info)

    def test_signature_answers(self):
        keep = build_demo("alpha", answers={"provenance": "keep"})
        self.assertNotIn("provenance", keep.open)
        self.assertEqual(keep.pkg["provenance"], {"author": "solace", "signature": "test-signature"})
        dev = build_demo("alpha", answers={"provenance": "dev-mode"})
        self.assertEqual(dev.pkg["provenance"], {"author": "dev-mode"})

    def test_amendments_apply_last_and_never_set_a_secret_default(self):  # I6
        answers = dict(ANSWERS_ALL, amend=[
            {"key": "server.ssl.key-store-password", "set": {"default": "zzsecretzz", "ui.helperText": "Keystore."}},
            {"component": "alpha-core", "field": "demo.alpha.legacy", "set": {"ui.order": 9}}])
        run = build_demo("alpha", answers=answers)
        f = fields_by_key(run.pkg)
        self.assertIsNone(f["server.ssl.key-store-password"]["default"])
        self.assertEqual(f["server.ssl.key-store-password"]["ui"]["helperText"], "Keystore.")
        self.assertEqual(f["demo.alpha.legacy"]["ui"]["order"], 9)   # after the restyle
        self.assertIn("amendment refused (I6): server.ssl.key-store-password: a secret field takes no default", run.notes)
        text = json.dumps(run.pkg) + json.dumps(run.rows) + json.dumps(run.info) + json.dumps(run.notes)
        self.assertNotIn("zzsecretzz", text)

    def test_rows(self):
        run = build_demo("alpha", answers=without(ANSWERS_ALL, "restyle"))
        rows = {r["field"]: r for r in run.rows}
        self.assertEqual(set(rows), {f for p in run.placed for f in p["field_keys"]})
        u = rows["demo.alpha.username"]
        self.assertEqual({k: u[k] for k in ("component", "key", "type", "default_display", "shown", "required",
                                            "suggested", "role", "versions", "notes")},
                         {"component": "alpha-core", "key": "demo.alpha.username", "type": "string",
                          "default_display": '"bob"', "shown": "up front", "required": True,
                          "suggested": {"required": True}, "role": "alpha", "versions": [1, 2], "notes": ["new"]})
        self.assertEqual(rows["demo.alpha.host"]["notes"], ['was "old.example.invalid"'])
        self.assertEqual(rows["server.address"]["notes"], ['YAML says "127.0.0.1"'])
        self.assertEqual(rows["server.port"]["notes"], ["YAML says 8080"])
        self.assertEqual(rows["server.port"]["default_display"], '"8094"')
        self.assertEqual(rows["server.ssl.key-store-password"]["role"], "alpha")
        self.assertEqual(rows["demo.alpha.username"]["line"], 9)
        self.assertEqual((rows["demo.alpha.host"]["section"], rows["demo.alpha.host"]["order"]), ("Alpha", 5))

    def test_rejected_suggestion_shows_false_and_valuefrom_rows(self):
        rej = build_demo("alpha", answers={"suggestions": [{"key": "demo.alpha.username", "attribute": "required",
                                                            "decision": "reject"}]})
        [u] = [r for r in rej.rows if r["field"] == "demo.alpha.username"]
        self.assertEqual((u["required"], u["suggested"]), (False, {"required": False}))
        pkg = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        pkg["components"][1]["fields"][1]["valueFrom"] = {"connection": "host"}
        run = build_demo("alpha", package=temp_file(json.dumps(pkg)))
        [a] = [r for r in run.rows if r["field"] == "server.address"]
        self.assertEqual(a["default_display"], "from connection")
        self.assertIn("valueFrom", a["notes"])

    def test_a_secret_never_shows_its_old_default(self):  # I6
        pkg = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        pkg["components"][1]["fields"].append(dict(pkg["components"][1]["fields"][2], key="server.ssl.key-store-password",
                                                   default="oldsecret2"))
        run = build_demo("alpha", package=temp_file(json.dumps(pkg)))
        [row] = [r for r in run.rows if r["field"] == "server.ssl.key-store-password"]
        self.assertEqual((row["default_display"], row["notes"]), ("withheld (I6)", ["secret: true added"]))
        text = json.dumps(run.rows) + json.dumps(run.info) + json.dumps(run.open) + json.dumps(run.pkg)
        for secret in ("oldsecret1", "oldsecret2", "hunter2pass"):
            self.assertNotIn(secret, text)

    def test_more_information_lines(self):
        run = build_demo("alpha", answers=ANSWERS_ALL)
        self.assertIn("in the package, not in this YAML (kept): alpha-core/demo.alpha.legacy", run.info)
        self.assertIn("in the package, not in this YAML (kept): server/server.ssl.trust-store-password", run.info)
        yml = temp_file("demo:\n  alpha:\n    username: yes\n    fileName: [a, b]\n")
        coerced = build_demo("alpha", yaml_path=yml, answers={"store": [{"key": "demo.alpha.fileName", "as": "list"}]})
        self.assertTrue([i for i in coerced.info if i.startswith("coerced: demo.alpha.username: ")], coerced.info)
        self.assertTrue([i for i in coerced.info if i.startswith("coerced: demo.alpha.fileName: ")], coerced.info)
        relabel = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        relabel["components"][0]["fields"][0]["ui"]["label"] = "Primary host"
        self.assertIn("reviewed labels kept: 1", build_demo("alpha", package=temp_file(json.dumps(relabel)), answers={
            "store": [{"key": "demo.alpha.host", "as": "second"}]}).info)


    # ---- final review fix wave
    def test_a_field_without_ui_order_is_mixed_and_offers_the_restyle(self):  # final review 2
        pkg = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        del pkg["components"][1]["fields"][1]["ui"]["order"]   # server.address
        path = temp_file(json.dumps(pkg))
        run = build_demo("alpha", package=path)
        self.assertEqual(run.styles["server"][0], "mixed")
        self.assertIn("restyle", run.open)
        self.assertEqual(fields_by_key(build_demo("alpha", package=path, answers={"restyle": "accept"}).pkg)[
            "server.address"]["ui"]["order"], 1)
        self.assertNotIn("order", fields_by_key(build_demo("alpha", package=path, answers={"restyle": "refuse"}).pkg)[
            "server.address"]["ui"])

    def test_a_field_without_ui_order_keeps_none_when_its_ui_is_rebuilt(self):  # final review 2
        pkg = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        del pkg["components"][0]["fields"][0]["ui"]["order"]   # demo.alpha.host
        work = empty_dir()
        profile = work / "profile.yaml"
        profile.write_text(PROFILE.read_text(encoding="utf-8").replace("preserve_reviewed_ui: true",
                                                                         "preserve_reviewed_ui: false"), encoding="utf-8")
        run = helpers.yaml_to_package.build([
            "--profile", str(profile), "--role", "alpha", "--yaml", str(helpers.ALPHA), "--target-versions", "1,2",
            "--today", "2026-11-10", "--samples", str(helpers.SAMPLES), "--package", str(temp_file(json.dumps(pkg))),
            "--answers", str(temp_file("store: [{key: demo.alpha.host, as: second}]\n"))])
        self.assertIn("demo.alpha.host", [p["key"] for p in run.placed])
        ui = fields_by_key(run.pkg)["demo.alpha.host"]["ui"]
        self.assertNotIn("order", ui)
        self.assertEqual([e for e in run.validation["errors"] if "order" in e], [])

    def test_secret_flag_on_a_non_secret_type_withholds_its_default(self):  # final review 6 (I6)
        pkg = json.loads(helpers.PACKAGE.read_text(encoding="utf-8"))
        for f in pkg["components"][0]["fields"]:   # alpha-core: host, mode, legacy
            f["secret"] = True
            if f["key"] == "demo.alpha.legacy":
                f["default"] = "legacyvalue1"
        answers = {"store": [{"key": "demo.alpha.host", "as": "second"}, {"key": "demo.alpha.mode", "as": "add-option"}],
                   "amend": [{"key": "demo.alpha.host", "set": {"ui.label": "Host"}}]}
        run = build_demo("alpha", package=temp_file(json.dumps(pkg)), answers=answers)
        f = fields_by_key(run.pkg)
        for key in ("demo.alpha.host", "demo.alpha.mode", "demo.alpha.legacy"):
            self.assertEqual((f[key].get("secret"), f[key].get("default")), (True, None), key)
        text = json.dumps(run.pkg) + json.dumps(run.rows) + " ".join(run.info + run.notes)
        for value in ("legacyvalue1", "first.example.invalid", "second.example.invalid", "old.example.invalid"):
            self.assertNotIn(value, text)
        self.assertIn("secret default withheld: alpha-core/demo.alpha.legacy", run.notes)
        self.assertEqual({r["default_display"] for r in run.rows if r["field"] == "demo.alpha.host"}, {"withheld (I6)"})
        ctx = run.answer_context()
        self.assertIn(("alpha-core", "demo.alpha.host"), ctx["secrets"])

    def test_forgetting_the_restyle_asks_it_again(self):  # final review 4, Ruling 36
        remembered = {"style": {"restyle": "refused", "date": "2026-01-01"}}
        self.assertNotIn("restyle", build_demo("alpha", remembered=remembered).open)
        self.assertIn("restyle", build_demo("alpha", remembered=remembered,
                                            answers={"forget": [{"decision": "restyle"}]}).open)
        prov = {"provenance": {"choice": "keep", "date": "2026-01-01"}}
        self.assertNotIn("provenance", build_demo("alpha", remembered=prov).open)
        self.assertIn("provenance", build_demo("alpha", remembered=prov,
                                               answers={"forget": [{"decision": "provenance"}]}).open)

    def test_remembered_package_decisions_are_shown(self):  # final review 8
        remembered = {"style": {"restyle": "accepted", "date": "2026-01-01"},
                      "provenance": {"choice": "keep", "date": "2026-01-02"}}
        run = build_demo("alpha", remembered=remembered)
        self.assertIn("restyle: accepted (remembered 2026-01-01)", run.info)
        self.assertIn("signature: keep (remembered 2026-01-02)", run.info)
        answered = build_demo("alpha", remembered=remembered, answers={"restyle": "refuse", "provenance": "keep"})
        self.assertFalse([i for i in answered.info if "(remembered" in i], answered.info)

    def test_restyle_question_says_reviewed_rows_reset(self):  # final review 9
        q = build_demo("alpha").open["restyle"]
        after = build_demo("alpha", answers={"restyle": "accept"}).pkg["authoring"]["reviewedRows"]
        self.assertEqual(q["context"]["reviewedRows"], {"package": 6, "accepted": after})
        self.assertIn(f"authoring.reviewedRows goes from 6 to {after}", q["text"])

if __name__ == "__main__":
    unittest.main()

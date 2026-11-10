import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import learn_samples  # noqa: E402

SAMPLES = Path(__file__).resolve().parent / "fixtures" / "samples"


class LearnSamplesTest(unittest.TestCase):
    def setUp(self):
        self.ix = learn_samples.build_index(str(SAMPLES), "demo")

    def test_index_skips_target_virtual_and_duplicates(self):
        self.assertEqual(self.ix.packages, 2)
        refs = [f.ref for f in self.ix.fields]
        self.assertEqual(len(refs), 8)
        self.assertFalse(any(r.startswith("demo/") for r in refs))
        self.assertIn("one/client/PASSWORD", refs)
        self.assertNotIn("two/client/PASSWORD", refs)
        self.assertNotIn("two/svc2/password", refs)

    def test_unreadable_samples_are_noted(self):
        notes = " ".join(self.ix.notes)
        self.assertIn("skipped broken/package.json", notes)
        self.assertIn("skipped nocomp/package.json", notes)

    def test_majority_suggests_required_with_evidence(self):
        [s] = learn_samples.suggest(self.ix, "username")
        self.assertEqual((s.attribute, s.support, s.matched), ("required", 2, 2))
        self.assertIn("one/svc/USERNAME: required", s.evidence)

    def test_split_vote_gives_nothing_and_the_target_is_excluded(self):
        self.assertEqual(learn_samples.suggest(self.ix, "host"), [])
        [s] = learn_samples.suggest(learn_samples.build_index(str(SAMPLES), "nobody"), "host")
        self.assertEqual((s.support, s.matched), (2, 3))

    def test_evidence_shows_the_condition(self):
        [s] = learn_samples.suggest(self.ix, "redis_url")
        self.assertEqual(s.evidence, ("one/svc/REDIS_URL: required, only when CACHE_PROVIDER = redis",))

    def test_no_suggestion_without_a_majority_or_a_match(self):
        for seg in ("timeout", "password", "ip"):
            self.assertEqual(learn_samples.suggest(self.ix, seg), [], seg)

    def test_find_samples_dir_walks_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages_dir = Path(tmp) / "frontend" / "contracts" / "packages"
            packages_dir.mkdir(parents=True)
            start = Path(tmp) / "a" / "b"
            start.mkdir(parents=True)
            self.assertEqual(learn_samples.find_samples_dir(str(start)),
                             str(packages_dir.resolve()))

    def test_no_samples_is_a_note(self):
        ix = learn_samples.build_index(None, "demo")
        self.assertEqual((ix.packages, ix.fields), (0, ()))
        self.assertIn("no samples found; no suggestions", ix.notes)

    def _tmp_samples(self, packages):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        for name, pkg in packages.items():
            d = Path(tmp.name) / name
            d.mkdir()
            (d / "package.json").write_text(json.dumps(pkg))
        return tmp.name

    def test_malformed_components_are_noted_not_fatal(self):
        good = {"type": "ok", "fields": [{"key": "A", "required": True}]}
        d = self._tmp_samples({"bad": {"solution": "bad", "components": [
            5, {"type": ["x"], "fields": []}, {"type": "t", "fields": 5},
            {"type": "u", "fields": [7, {"key": 3}]}, good]},
            "nosol": {"components": [good]}})
        ix = learn_samples.build_index(d, "none")
        notes = " ".join(ix.notes)
        for frag in ("not an object", "string type", "component t has no fields list"):
            self.assertIn(frag, notes)
        self.assertIn("skipped bad/package.json", notes)
        self.assertEqual([f.ref for f in ix.fields], ["bad/ok/A"])
        self.assertEqual(ix.packages, 2)

    def test_advanced_majority_and_tie(self):
        def pkg(sol, adv):
            ui = {"advanced": True} if adv else {}
            return {"solution": sol, "components": [
                {"type": "c", "fields": [{"key": "retry", "ui": ui}]}]}
        d = self._tmp_samples({"a": pkg("a", True), "b": pkg("b", True), "c": pkg("c", False)})
        # same (type, key) dedups; use distinct component types instead
        for n in "abc":
            p = Path(d) / n / "package.json"
            data = json.loads(p.read_text())
            data["components"][0]["type"] = "c" + n
            p.write_text(json.dumps(data))
        [s] = learn_samples.suggest(learn_samples.build_index(d, "none"), "retry")
        self.assertEqual((s.attribute, s.support, s.matched), ("advanced", 2, 3))
        self.assertEqual(s.evidence, ("a/ca/retry: advanced", "b/cb/retry: advanced",
                                      "c/cc/retry: up front"))
        tie = self._tmp_samples({"a": pkg("a", True), "b": pkg("b", False)})
        for n in "ab":
            p = Path(tie) / n / "package.json"
            data = json.loads(p.read_text())
            data["components"][0]["type"] = "c" + n
            p.write_text(json.dumps(data))
        self.assertEqual(learn_samples.suggest(learn_samples.build_index(tie, "none"), "retry"), [])


if __name__ == "__main__":
    unittest.main()

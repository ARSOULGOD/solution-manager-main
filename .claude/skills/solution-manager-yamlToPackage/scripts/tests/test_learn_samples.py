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


if __name__ == "__main__":
    unittest.main()

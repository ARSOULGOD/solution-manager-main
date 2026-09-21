"""No-Docker tests for the harness's write-path refusals.

An external review showed the first version could be pointed at its own tracked evidence: `--out
reference-run` overwrote the checked-in logs before any validation ran, and the candidate check compared
lexical paths so a symlink outside the harness that pointed into it passed. These tests exercise
`validate_paths` directly — no containers, no design extraction — and are the executable form of the
README's claim that nothing the harness does can modify the tracked baseline.

    python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py -v
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import replay  # noqa: E402

HERE = Path(replay.HERE)
REPO = Path(replay.REPO)


class WritePathRefusals(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='ddl-replay-test-')).resolve()

    def test_out_pointing_at_the_tracked_reference_run_is_refused(self):
        with self.assertRaises(replay.RefusedPath) as cm:
            replay.validate_paths(str(HERE / 'reference-run'), None)
        self.assertIn('inside the repository', str(cm.exception))

    def test_out_anywhere_inside_the_repository_is_refused_even_outside_the_harness_directory(self):
        with self.assertRaises(replay.RefusedPath):
            replay.validate_paths(str(REPO / 'docs' / 'somewhere-else'), None)

    def test_out_given_as_a_relative_path_into_the_repo_is_refused(self):
        cwd = os.getcwd()
        try:
            os.chdir(REPO)
            with self.assertRaises(replay.RefusedPath):
                replay.validate_paths('docs/design/backend/evidence/ddl-replay/reference-run', None)
        finally:
            os.chdir(cwd)

    def test_candidate_directory_inside_the_repository_is_refused(self):
        with self.assertRaises(replay.RefusedPath) as cm:
            replay.validate_paths(str(self.tmp / 'out'), str(REPO / 'candidate'))
        self.assertIn('inside the repository', str(cm.exception))

    def test_external_symlink_pointing_into_the_harness_is_refused_by_where_it_resolves(self):
        link = self.tmp / 'candidate'
        link.symlink_to(HERE)  # the attack: a name outside, a target inside
        with self.assertRaises(replay.RefusedPath) as cm:
            replay.validate_paths(str(self.tmp / 'out'), str(link))
        self.assertIn('inside the repository', str(cm.exception),
                      'the refusal must come from the RESOLVED path, not the name')

    def test_external_symlink_pointing_at_the_reference_run_as_out_is_refused(self):
        link = self.tmp / 'out'
        link.symlink_to(HERE / 'reference-run')
        with self.assertRaises(replay.RefusedPath):
            replay.validate_paths(str(link), None)

    def test_a_pre_existing_candidate_directory_is_refused_even_outside_the_repository(self):
        existing = self.tmp / 'existing'
        existing.mkdir()
        with self.assertRaises(replay.RefusedPath) as cm:
            replay.validate_paths(str(self.tmp / 'out'), str(existing))
        self.assertIn('already exists', str(cm.exception))

    def test_a_dangling_symlink_as_candidate_is_refused(self):
        link = self.tmp / 'dangling'
        link.symlink_to(self.tmp / 'nowhere')
        with self.assertRaises(replay.RefusedPath):
            replay.validate_paths(str(self.tmp / 'out'), str(link))

    def test_the_legitimate_shape_is_accepted_and_returned_resolved(self):
        out, cand = replay.validate_paths(str(self.tmp / 'out'), str(self.tmp / 'fresh-candidate'))
        self.assertEqual(out, self.tmp / 'out')
        self.assertEqual(cand, self.tmp / 'fresh-candidate')
        self.assertFalse(cand.exists(), 'validation creates nothing')
        self.assertFalse(out.exists(), 'validation creates nothing')

    def test_out_alone_outside_the_repository_is_accepted(self):
        out, cand = replay.validate_paths(str(self.tmp / 'out'), None)
        self.assertEqual(out, self.tmp / 'out')
        self.assertIsNone(cand)

    def test_repo_root_is_resolved_not_lexical(self):
        # A symlink to the repository itself must still count as inside it.
        link = self.tmp / 'repo-alias'
        link.symlink_to(REPO)
        with self.assertRaises(replay.RefusedPath):
            replay.validate_paths(str(link / 'docs' / 'x'), None)


if __name__ == '__main__':
    unittest.main()

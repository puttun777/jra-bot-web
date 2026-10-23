import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.golden import constants as C, legacy

GIT_ENV = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")


def git(p, *a):
    subprocess.run(["git", "-C", str(p), *a], check=True, capture_output=True, env=GIT_ENV)


class GuardTest(unittest.TestCase):
    def test_wrong_commit_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            git(t, "init", "-q")
            Path(t, "f").write_text("x")
            git(t, "add", ".")
            git(t, "commit", "-q", "-m", "x")
            with self.assertRaisesRegex(legacy.LegacyError, "固定値と異なります"):
                legacy.verify_checkout(t)

    def test_not_a_git_repo_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            with self.assertRaises(legacy.LegacyError):
                legacy.verify_checkout(t)

    def test_dirty_tree_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            git(t, "init", "-q")
            Path(t, "f").write_text("x")
            git(t, "add", ".")
            git(t, "commit", "-q", "-m", "x")
            head = subprocess.run(["git", "-C", t, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
            self.assertEqual(legacy.verify_checkout(t, expected=head), head)
            Path(t, "f").write_text("changed")
            with self.assertRaisesRegex(legacy.LegacyError, "未コミット"):
                legacy.verify_checkout(t, expected=head)

    def test_pinned_commit_is_full_sha(self):
        self.assertRegex(C.PINNED_COMMIT, r"^[0-9a-f]{40}$")
        self.assertTrue(C.PINNED_COMMIT.startswith("f7dbf69"))

    def test_sandbox_blocks_network_and_restores(self):
        import socket
        with legacy.sandbox():
            with self.assertRaises(RuntimeError):
                socket.create_connection(("127.0.0.1", 9))
            self.assertEqual(os.environ["TZ"], C.TIMEZONE)
        self.assertNotEqual(socket.create_connection, legacy._block_network)

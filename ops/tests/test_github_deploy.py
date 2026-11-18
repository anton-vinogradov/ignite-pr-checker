"""Run the updater's real file transaction with local JARs and stand-ins for external services."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "github-deploy.py"
SPEC = importlib.util.spec_from_file_location("github_deploy_under_test", SCRIPT)
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)

OLD = {"version": "1.52.0", "commit": "1" * 40, "dirty": False}
NEW = {"version": "1.52.1", "commit": "2" * 40, "dirty": False}


def jar(info):
    """A real JAR holding the build metadata that its process reports through /api/status."""
    out = io.BytesIO()
    properties = "\n".join(f"build.{key}={str(value).lower()}" for key, value in info.items()) + "\n"
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr(zipfile.ZipInfo("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\n")
        archive.writestr(zipfile.ZipInfo("BOOT-INF/classes/META-INF/build-info.properties"), properties)
    return out.getvalue()


class InterruptedInstall(BaseException):
    """A process lost after replacing app.jar cannot execute an exception handler or cleanup."""


class LocalDeployer(deploy.Deployer):
    """GitHub serves one candidate; systemctl and the process it starts are represented locally.

    The production transaction, JSON writes, backup links, replacements, digest comparison and health comparison
    all run unchanged. The clock advances after one failed health observation instead of waiting sixty seconds.
    """

    def __init__(self, app, env, old_jar, new_jar):
        super().__init__(app, env)
        self.old_jar = old_jar
        self.new_jar = new_jar
        self.payload = new_jar
        self.run = {
            "id": 101, "run_number": 7, "run_attempt": 1,
            "head_sha": NEW["commit"], "head_branch": "main", "path": deploy.WORKFLOW,
            "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
            "head_repository": {"full_name": deploy.REPO},
        }
        self.api_calls = []
        self.downloads = []
        self.actions = []
        self.health_checks = []
        self.running = True
        self.new_live = NEW.copy()
        self.interrupt_on_start = False
        self.old_restart_fails = False
        self.starts = 0

    def api(self, path):
        self.api_calls.append(path)
        digest = hashlib.sha256(self.new_jar).hexdigest()
        if path.startswith("actions/workflows/"):
            return {"workflow_runs": [self.run]}
        if path == "actions/runs/101/attempts/1/jobs":
            return {"jobs": [{
                "name": deploy.BUILD_JOB, "conclusion": "success", "steps": [{
                    "name": f"Deploy JAR {NEW['version']} {digest}", "conclusion": "success",
                }],
            }]}
        tag = f"deploy-101-1-{NEW['commit']}"
        if path == f"releases/tags/{tag}":
            return {"tag_name": tag, "draft": False, "prerelease": True, "assets": [{
                "name": deploy.ASSET, "size": len(self.new_jar), "digest": "sha256:" + digest,
            }]}
        raise AssertionError(f"Unexpected GitHub request: {path}")

    def download(self, tag, size):
        self.downloads.append((tag, size))
        with self.downloaded.open("xb") as out:
            out.write(self.payload)
        return hashlib.sha256(self.payload).hexdigest()

    def live(self):
        if not self.running:
            return {}
        content = self.jar.read_bytes()
        if content == self.old_jar:
            return {} if self.old_restart_fails and self.starts else OLD.copy()
        if content == self.new_jar:
            return self.new_live.copy()
        return {}

    def systemctl(self, action):
        self.actions.append(action)
        if action == "stop":
            self.running = False
        elif action == "start":
            self.starts += 1
            if self.interrupt_on_start:
                raise InterruptedInstall()
            self.running = True
        elif action != "reset-failed":
            raise AssertionError(f"Unexpected systemctl action: {action}")

    def wait_for(self, expected):
        self.health_checks.append(expected)
        with patch.object(deploy.time, "monotonic", side_effect=[0, 0, 61]), patch.object(deploy.time, "sleep"):
            return super().wait_for(expected)


class GithubDeployTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.app = Path(self.directory.name)
        self.env = self.app / "env"
        self.env.write_text("SERVER_ADDRESS=127.0.0.1\n")
        self.old_jar = jar(OLD)
        self.new_jar = jar(NEW)
        self.older_jar = jar({"version": "1.51.0", "commit": "0" * 40, "dirty": False})
        (self.app / "app.jar").write_bytes(self.old_jar)
        (self.app / "app.jar.prev").write_bytes(self.older_jar)

    def updater(self):
        return LocalDeployer(self.app, self.env, self.old_jar, self.new_jar)

    def test_success_publishes_only_the_confirmed_previous_jar(self):
        """Given a working old JAR, when the exact candidate answers, then the old JAR becomes the rollback copy."""
        updater = self.updater()

        updater.deploy()

        self.assertEqual(self.new_jar, updater.jar.read_bytes())
        self.assertEqual(self.old_jar, (self.app / "app.jar.prev").read_bytes())
        self.assertEqual(["stop", "start"], updater.actions)
        self.assertEqual([NEW], updater.health_checks)
        self.assertEqual("succeeded", deploy.read_json(updater.status)["state"])
        self.assertFalse(updater.pending.exists())
        self.assertFalse(updater.backup.exists())
        self.assertFalse(updater.downloaded.exists())

    def test_hash_mismatch_keeps_the_running_service_and_backup_untouched(self):
        """Given a corrupted download, when its hash differs, then neither the running JAR nor its backup changes."""
        updater = self.updater()
        updater.payload = self.new_jar[:-1] + b"x"

        updater.deploy()

        self.assertEqual(self.old_jar, updater.jar.read_bytes())
        self.assertEqual(self.older_jar, (self.app / "app.jar.prev").read_bytes())
        self.assertEqual([], updater.actions)
        self.assertEqual("artifact-invalid", deploy.read_json(updater.status)["detail"])
        self.assertFalse(updater.pending.exists())
        self.assertFalse(updater.downloaded.exists())

    def test_the_right_version_with_the_wrong_live_commit_is_rolled_back(self):
        """Given the right version but another live commit, when startup is checked, then the old revision returns."""
        updater = self.updater()
        updater.new_live["commit"] = "3" * 40

        updater.deploy()

        self.assertEqual(self.old_jar, updater.jar.read_bytes())
        self.assertEqual(self.older_jar, (self.app / "app.jar.prev").read_bytes())
        self.assertEqual(["stop", "start", "stop", "reset-failed", "start"], updater.actions)
        self.assertEqual([NEW, OLD], updater.health_checks)
        self.assertEqual(OLD, updater.live())
        state = deploy.read_json(updater.status)
        self.assertEqual("rolled_back", state["state"])
        self.assertEqual("health-check-failed", state["detail"])
        self.assertFalse(updater.pending.exists())

    def test_a_new_process_recovers_an_interrupted_swap_before_reading_github(self):
        """Given a process lost after swapping JARs, when another updater starts, then recovery precedes GitHub reads."""
        updater = self.updater()
        updater.interrupt_on_start = True
        with self.assertRaises(InterruptedInstall):
            updater.deploy()
        self.assertEqual(self.new_jar, updater.jar.read_bytes())
        self.assertTrue(updater.pending.exists())
        self.assertEqual(self.old_jar, updater.backup.read_bytes())

        recovered = self.updater()
        recovered.deploy()

        self.assertEqual([], recovered.api_calls)
        self.assertEqual(self.old_jar, recovered.jar.read_bytes())
        self.assertEqual(OLD, recovered.live())
        self.assertEqual("interrupted", deploy.read_json(recovered.status)["detail"])
        self.assertFalse(recovered.pending.exists())

    def test_a_rolled_back_candidate_is_not_installed_again_by_the_next_poll(self):
        """Given a rolled-back candidate, when the same successful run is polled again, then it is not retried."""
        updater = self.updater()
        updater.new_live["commit"] = "3" * 40
        updater.deploy()

        next_poll = self.updater()
        next_poll.deploy()

        self.assertEqual([], next_poll.downloads)
        self.assertEqual([], next_poll.actions)
        self.assertEqual(self.old_jar, next_poll.jar.read_bytes())
        self.assertEqual(self.older_jar, (self.app / "app.jar.prev").read_bytes())

    def test_a_failed_rollback_keeps_the_recovery_transaction(self):
        """Given a previous JAR that no longer starts, when rollback fails, then its pending recovery is retained."""
        updater = self.updater()
        updater.new_live["commit"] = "3" * 40
        updater.old_restart_fails = True

        with self.assertRaisesRegex(RuntimeError, "could not restore"):
            updater.deploy()

        self.assertTrue(updater.pending.exists())
        self.assertEqual(self.old_jar, updater.backup.read_bytes())
        self.assertEqual(self.older_jar, (self.app / "app.jar.prev").read_bytes())
        state = deploy.read_json(updater.status)
        self.assertEqual("failed", state["state"])
        self.assertEqual("rollback-failed", state["detail"])


if __name__ == "__main__":
    unittest.main()

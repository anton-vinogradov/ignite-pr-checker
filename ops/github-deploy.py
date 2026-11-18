#!/usr/bin/env python3
"""Install the JAR from the latest successful manual Deploy run."""

import fcntl
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen


REPO = "anton-vinogradov/ignite-pr-checker"
WORKFLOW = ".github/workflows/deploy.yml"
BUILD_JOB = "Publish deploy candidate"
ASSET = "ignite-pr-checker.jar"
SERVICE = "ignite-pr-checker.service"
MAX_JAR = 128 * 1024 * 1024
IN_PROGRESS = {"downloading", "starting"}


class InvalidArtifact(Exception):
    pass


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def read_json(path):
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        return {}


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_json(path, value):
    fd, name = tempfile.mkstemp(dir=path.parent, prefix="." + path.name + "-")
    try:
        with os.fdopen(fd, "w") as out:
            os.fchmod(out.fileno(), 0o644)
            json.dump(value, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
        sync_dir(path.parent)
    finally:
        Path(name).unlink(missing_ok=True)


def get_json(url, timeout=15):
    request = Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "ignite-pr-checker-deploy"})
    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read(1024 * 1024))


def identity(status):
    return {key: status.get(key) for key in ("version", "commit", "dirty")}


class Deployer:
    def __init__(self, app, env):
        self.app = app
        self.env = env
        self.jar = app / "app.jar"
        self.downloaded = app / ".github-deploy.jar"
        self.backup = app / ".github-deploy.previous"
        self.pending = app / ".github-deploy.pending.json"
        self.status = app / "github-deploy.json"
        self.state = read_json(self.status)

    def api(self, path):
        return get_json(f"https://api.github.com/repos/{REPO}/{path}")

    def latest(self):
        runs = self.api("actions/workflows/deploy.yml/runs?event=workflow_dispatch&branch=main&status=success&per_page=1")
        for run in runs["workflow_runs"]:
            commit = run["head_sha"]
            if (run["path"] == WORKFLOW and run["head_branch"] == "main" and run["event"] == "workflow_dispatch"
                    and run["status"] == "completed" and run["conclusion"] == "success"
                    and run["head_repository"]["full_name"] == REPO and re.fullmatch(r"[0-9a-f]{40}", commit)
                    and all(type(run[key]) is int and run[key] > 0 for key in ("id", "run_number", "run_attempt"))):
                return run
        return None

    def artifact(self, run):
        run_id, attempt, commit = run["id"], run["run_attempt"], run["head_sha"]
        jobs = self.api(f"actions/runs/{run_id}/attempts/{attempt}/jobs")["jobs"]
        signatures = [
            match for job in jobs if job["name"] == BUILD_JOB and job["conclusion"] == "success"
            for step in job["steps"] if step["conclusion"] == "success"
            if (match := re.fullmatch(r"Deploy JAR ([0-9][0-9A-Za-z.\-]{0,79}) ([0-9a-f]{64})", step["name"]))
        ]
        if len(signatures) != 1:
            raise InvalidArtifact("the Deploy run did not record one JAR digest")
        version, digest = signatures[0].groups()
        tag = f"deploy-{run_id}-{attempt}-{commit}"
        release = self.api(f"releases/tags/{tag}")
        assets = [asset for asset in release["assets"] if asset["name"] == ASSET]
        if release["tag_name"] != tag or release["draft"] or not release["prerelease"] or len(assets) != 1:
            raise InvalidArtifact("the Deploy release does not name one published JAR")
        asset = assets[0]
        if asset["digest"] != "sha256:" + digest or not 0 < asset["size"] <= MAX_JAR:
            raise InvalidArtifact("the release asset does not match the Deploy run")
        return version, digest, tag, asset["size"]

    def download(self, tag, size):
        # Use the fixed repository and asset name. The workflow's recorded digest covers every byte.
        request = Request(f"https://github.com/{REPO}/releases/download/{tag}/{ASSET}",
                          headers={"User-Agent": "ignite-pr-checker-deploy"})
        sha = hashlib.sha256()
        with urlopen(request, timeout=30) as response, self.downloaded.open("xb") as out:
            remaining = size
            while chunk := response.read(min(1024 * 1024, remaining + 1)):
                remaining -= len(chunk)
                if remaining < 0:
                    raise InvalidArtifact("the download is larger than the release asset")
                sha.update(chunk)
                out.write(chunk)
            if remaining:
                raise OSError("incomplete JAR download")
            out.flush()
            os.fsync(out.fileno())
        return sha.hexdigest()

    def live(self):
        settings = {}
        for line in self.env.read_text().splitlines():
            key, _, value = line.partition("=")
            if key in {"SERVER_ADDRESS", "SERVER_PORT"}:
                settings[key] = value.strip().strip("\"'")
        host = settings.get("SERVER_ADDRESS") or "127.0.0.1"
        if host in {"0.0.0.0", "::"}:
            host = "127.0.0.1"
        if ":" in host:
            host = f"[{host}]"
        try:
            return identity(get_json(f"http://{host}:{settings.get('SERVER_PORT') or '8080'}/api/status", timeout=3))
        except (OSError, ValueError):
            return {}

    def systemctl(self, action):
        subprocess.run(["systemctl", action, SERVICE], check=True, timeout=90)

    def wait_for(self, expected):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.live() == expected:
                return True
            time.sleep(2)
        return False

    def report(self, state, **fields):
        self.state.update(state=state, **fields)
        write_json(self.status, self.state)

    def finish(self, state, detail=None):
        self.report(state, detail=detail, finishedAt=now())

    def rollback(self, transaction, detail):
        try:
            previous = transaction["previous"]
            if not self.jar.exists() or not self.jar.samefile(self.backup) or self.live() != previous:
                self.systemctl("stop")
                restore = self.app / ".github-deploy.restore"
                restore.unlink(missing_ok=True)
                os.link(self.backup, restore)
                os.replace(restore, self.jar)
                sync_dir(self.app)
                self.systemctl("reset-failed")
                self.systemctl("start")
                if not self.wait_for(previous):
                    raise OSError("previous revision did not start")
        except (OSError, subprocess.SubprocessError) as error:
            self.finish("failed", "rollback-failed")
            raise RuntimeError("could not restore the previous JAR") from error
        self.finish("rolled_back", detail)
        self.clear_transaction()

    def clear_transaction(self):
        self.pending.unlink(missing_ok=True)
        sync_dir(self.app)
        self.backup.unlink(missing_ok=True)
        self.downloaded.unlink(missing_ok=True)

    def recover(self):
        transaction = read_json(self.pending)
        if transaction:
            if self.state.get("state") in {"succeeded", "rolled_back"}:
                self.clear_transaction()
            else:
                self.rollback(transaction, "interrupted")
            return True
        return False

    def deploy(self):
        if self.recover():
            return
        if not self.state:
            self.report("idle")
        run = self.latest()
        if run is None:
            return
        order = (run["run_number"], run["run_attempt"])
        recorded = (self.state.get("runNumber", 0), self.state.get("runAttempt", 0))
        retry = self.state.get("state") in IN_PROGRESS or self.state.get("detail") in {
            "download-failed", "service-unavailable"}
        if order < recorded or (order == recorded and not retry):
            return
        self.report("downloading", runNumber=order[0], runAttempt=order[1], commit=run["head_sha"],
                    version=None, startedAt=now(), finishedAt=None, detail=None)
        self.downloaded.unlink(missing_ok=True)
        try:
            version, digest, tag, size = self.artifact(run)
            self.report("downloading", version=version)
            if self.download(tag, size) != digest:
                raise InvalidArtifact("the downloaded JAR does not match the recorded digest")
        except InvalidArtifact as error:
            print(error, flush=True)
            self.finish("failed", "artifact-invalid")
            return
        except (OSError, ValueError, KeyError, TypeError) as error:
            print(f"Could not read the Deploy release: {error}", flush=True)
            self.finish("failed", "download-failed")
            return
        finally:
            if self.state["state"] == "failed":
                self.downloaded.unlink(missing_ok=True)

        previous = self.live()
        if not previous.get("version") or not self.jar.is_file() or self.jar.is_symlink():
            self.downloaded.unlink(missing_ok=True)
            self.finish("failed", "service-unavailable")
            return
        expected = {"version": version, "commit": run["head_sha"], "dirty": False}
        self.backup.unlink(missing_ok=True)
        os.link(self.jar, self.backup)
        transaction = {"previous": previous}
        self.report("starting")
        write_json(self.pending, transaction)
        try:
            self.systemctl("stop")
            os.replace(self.downloaded, self.jar)
            sync_dir(self.app)
            self.systemctl("start")
            if not self.wait_for(expected):
                self.rollback(transaction, "health-check-failed")
                return
            # Publish the backup only once the exact candidate revision has answered.
            previous_jar = self.app / ".github-deploy.prev-link"
            previous_jar.unlink(missing_ok=True)
            os.link(self.backup, previous_jar)
            os.replace(previous_jar, self.app / "app.jar.prev")
            # The legacy updater uses this receipt if the operator switches back to updates from the page.
            installed = self.app / "installed-jars"
            if not installed.is_symlink() and (
                    not installed.exists() or installed.is_file() and installed.stat().st_uid == os.geteuid()):
                with installed.open("a") as receipts:
                    receipts.write(f"{digest} {version}\n")
            finished = now()
            self.report("succeeded", finishedAt=finished, lastSuccessAt=finished, lastSuccessVersion=version)
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Could not start the Deploy revision: {error}", flush=True)
            self.rollback(transaction, "startup-failed")
        finally:
            self.downloaded.unlink(missing_ok=True)
        self.clear_transaction()


def main():
    os.umask(0o022)
    app = Path("/opt/ignite-pr-checker")
    with (app / ".deploy.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        Deployer(app, Path("/etc/ignite-pr-checker/env")).deploy()


if __name__ == "__main__":
    main()

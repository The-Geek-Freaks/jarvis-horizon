#!/usr/bin/env python3
"""Small signed OTA installer. Manifest and detached signature are fetched over HTTP(S).

Manifest contract:
  {"version":"2026.10.5", "url":"http://hdd/releases/2026.10.5.tar.gz",
   "sha256":"<64 lowercase hex>"}
The detached signature is the same URL with `.sig` appended to the manifest URL,
and is an OpenSSL-compatible raw Ed25519 signature (base64 text or raw bytes).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

APP = Path("/opt/jarvis-mirror")
CONFIG = Path("/etc/jarvis-mirror/config.json")
PUBKEY = Path("/etc/jarvis-mirror/ota.pub")
MAX_ARCHIVE = 300 * 1024 * 1024
MAX_FILES = 5000
VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
KIOSK_STARTUP_GRACE_SECONDS = 3
KIOSK_STABILITY_WINDOW_SECONDS = 5
BACKEND_READY_TIMEOUT_SECONDS = 30
BACKEND_HEALTH_ATTEMPT_TIMEOUT_SECONDS = 8
BACKEND_HEALTH_RETRY_SECONDS = 0.5


def fetch(url: str, limit: int) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "jarvis-mirror-ota/1"})
    with urllib.request.urlopen(req, timeout=20) as response:
        length = response.headers.get("Content-Length")
        if length and int(length) > limit:
            raise ValueError("download exceeds size limit")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("download exceeds size limit")
    return data


def verify_manifest(payload: bytes, signature: bytes) -> None:
    if not PUBKEY.is_file() or b"BEGIN PUBLIC KEY" not in PUBKEY.read_bytes():
        raise ValueError("OTA public key is absent or still the example placeholder")
    with tempfile.TemporaryDirectory(prefix="jarvis-ota-verify-") as td:
        data, sig = Path(td, "manifest.json"), Path(td, "manifest.sig")
        data.write_bytes(payload)
        # OpenSSL pkeyutl expects binary signature. Accept base64 .sig for easy hosting.
        try:
            import base64
            decoded = base64.b64decode(signature, validate=True)
            sig.write_bytes(decoded if len(decoded) == 64 else signature)
        except ValueError:
            sig.write_bytes(signature)
        subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(PUBKEY),
                        "-rawin", "-in", str(data), "-sigfile", str(sig)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def safe_extract(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        if len(members) > MAX_FILES:
            raise ValueError("archive has too many files")
        total = 0
        for member in members:
            target = (destination / member.name).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("unsafe archive path")
            if member.issym() or member.islnk() or member.isdev():
                raise ValueError("links and device files are forbidden")
            total += member.size
            if total > MAX_ARCHIVE:
                raise ValueError("expanded archive exceeds size limit")
        tar.extractall(destination, members=members, filter="data")


def health(url: str, timeout: float = BACKEND_HEALTH_ATTEMPT_TIMEOUT_SECONDS) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return 200 <= response.status < 300
    except Exception:
        return False


def wait_for_health(url: str) -> bool:
    """Require a local 2xx health response before the bounded startup deadline."""
    deadline = time.monotonic() + BACKEND_READY_TIMEOUT_SECONDS
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        if health(url, timeout=min(BACKEND_HEALTH_ATTEMPT_TIMEOUT_SECONDS, remaining)):
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(BACKEND_HEALTH_RETRY_SECONDS, remaining))


def kiosk_status() -> tuple[str, str, int, int] | None:
    """Return the kiosk's stable systemd identity, or None when it is not running."""
    result = subprocess.run(
        ["systemctl", "show", "jarvis-kiosk.service", "--property=ActiveState",
         "--property=SubState", "--property=MainPID", "--property=NRestarts"],
        check=False, capture_output=True, text=True,
    )
    if result.returncode:
        return None
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    try:
        main_pid = int(values["MainPID"])
        restarts = int(values["NRestarts"])
    except (KeyError, ValueError):
        return None
    if values.get("ActiveState") != "active" or values.get("SubState") != "running" or main_pid <= 0:
        return None
    return values["ActiveState"], values["SubState"], main_pid, restarts


def kiosk_remains_running() -> bool:
    """Reject a startx/Xorg process that exits or restarts shortly after launch."""
    time.sleep(KIOSK_STARTUP_GRACE_SECONDS)
    initial = kiosk_status()
    if initial is None:
        return False
    for _ in range(KIOSK_STABILITY_WINDOW_SECONDS):
        time.sleep(1)
        if kiosk_status() != initial:
            return False
    return True


def rollback_release(current: Path, old_target: str) -> tuple[bool, bool]:
    """Restore the prior symlink, backend, and kiosk page."""
    if not old_target:
        return False, False
    staged = current.parent / ".current.rollback"
    staged.unlink(missing_ok=True)
    staged.symlink_to(old_target)
    os.replace(staged, current)
    backend = subprocess.run(["systemctl", "restart", "jarvis-mirror.service"], check=False)
    kiosk = subprocess.run(["systemctl", "restart", "jarvis-kiosk.service"], check=False)
    return backend.returncode == 0, kiosk.returncode == 0 and kiosk_remains_running()


def main() -> int:
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    ota = config.get("ota", {})
    manifest_url = ota.get("manifest_url", "")
    if not manifest_url:
        print("OTA disabled: ota.manifest_url is empty")
        return 0
    if not manifest_url.startswith(("http://", "https://")):
        raise ValueError("manifest URL must be HTTP(S)")
    payload = fetch(manifest_url, 64 * 1024)
    verify_manifest(payload, fetch(manifest_url + ".sig", 8 * 1024))
    manifest = json.loads(payload)
    version, url, digest = (manifest.get(k, "") for k in ("version", "url", "sha256"))
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError("invalid manifest version")
    if not isinstance(url, str) or not url.startswith(("http://", "https://")):
        raise ValueError("invalid release URL")
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ValueError("invalid release sha256")
    release = APP / "releases" / version
    if release.is_dir():
        print(f"OTA already installed: {version}")
        return 0
    with tempfile.TemporaryDirectory(prefix="jarvis-ota-", dir=APP) as td:
        archive = Path(td, "release.tar.gz")
        archive.write_bytes(fetch(url, MAX_ARCHIVE))
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError("release sha256 mismatch")
        candidate = Path(td, "release")
        candidate.mkdir()
        safe_extract(archive, candidate)
        if not (candidate / "server.py").is_file():
            raise ValueError("release lacks server.py")
        os.replace(candidate, release)
    current, previous = APP / "current", APP / "previous"
    old_target = os.readlink(current) if current.is_symlink() else ""
    staged = APP / ".current.new"
    staged.unlink(missing_ok=True)
    staged.symlink_to(release)
    os.replace(staged, current)
    restart = subprocess.run(["systemctl", "restart", "jarvis-mirror.service"], check=False)
    healthy = restart.returncode == 0
    failure_cause = "backend service restart failed" if not healthy else ""
    if healthy:
        healthy = wait_for_health(ota.get("health_url", "http://127.0.0.1:8765/healthz"))
        if not healthy:
            failure_cause = "backend did not become healthy within 30 seconds"
    if healthy:
        kiosk = subprocess.run(["systemctl", "restart", "jarvis-kiosk.service"], check=False)
        healthy = kiosk.returncode == 0
        if not healthy:
            failure_cause = "kiosk service restart failed"
        elif not kiosk_remains_running():
            healthy = False
            failure_cause = "kiosk service did not remain active"
    if healthy:
        previous.unlink(missing_ok=True)
        if old_target:
            previous.symlink_to(old_target)
        print(f"OTA installed: {version}")
        return 0
    backend_restored, kiosk_restored = rollback_release(current, old_target)
    # A failed candidate must not suppress a corrected re-publish under the
    # same signed version. It was created by this invocation only.
    shutil.rmtree(release, ignore_errors=True)
    if old_target and backend_restored and kiosk_restored:
        raise RuntimeError(f"new release {failure_cause}; previous backend and kiosk restored")
    if old_target:
        raise RuntimeError(f"new release {failure_cause}; previous symlink restored but backend/kiosk restore failed")
    raise RuntimeError(f"new release {failure_cause}; no previous release was available for rollback")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"OTA not installed: {error}", file=sys.stderr)
        raise SystemExit(1)

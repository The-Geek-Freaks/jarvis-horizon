#!/usr/bin/env python3
"""Reject workstation paths in ZIP/APK/AAR payloads, including nested archives.

This is a narrow build-path leak gate, not a credential scanner or full audit.
It reports member names and marker classes, never matching payload contents.
"""
import argparse
import io
from pathlib import Path
import re
import sys
import zipfile

PATTERNS = {
    "Windows user directory": re.compile(rb"[A-Za-z]:[/\\]Users[/\\][A-Za-z0-9_. -]+[/\\]", re.I),
    "Local Codex workspace": re.compile(rb"Documents[/\\]Codex[/\\]", re.I),
}
MAX_BYTES = 768 * 1024 * 1024
MAX_MEMBER = 256 * 1024 * 1024


def inspect(data, name, budget, hits, depth=0):
    if depth > 4:
        raise ValueError("archive nesting limit reached")
    budget[0] += len(data)
    if budget[0] > MAX_BYTES:
        raise ValueError("expanded archive byte limit reached")
    for label, pattern in PATTERNS.items():
        if pattern.search(data):
            hits.append((name, label))
    stream = io.BytesIO(data)
    if zipfile.is_zipfile(stream):
        with zipfile.ZipFile(stream) as archive:
            for member in archive.infolist():
                if member.is_dir():
                    continue
                if member.file_size > MAX_MEMBER:
                    raise ValueError("archive member byte limit reached")
                inspect(archive.read(member), name + "!" + member.filename, budget, hits, depth + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for artifact in args.artifacts:
        hits = []
        try:
            if artifact.stat().st_size > MAX_MEMBER:
                raise ValueError("input byte limit reached")
            inspect(artifact.read_bytes(), artifact.name, [0], hits)
        except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
            print(f"ERROR: {artifact.name}: {type(error).__name__}", file=sys.stderr)
            failed = True
            continue
        for member, label in hits:
            print(f"FAIL: {member}: {label}")
        if hits:
            failed = True
        else:
            print(f"PASS: {artifact.name}: no workstation path markers in nested payloads")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())

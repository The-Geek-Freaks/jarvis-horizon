#!/usr/bin/env python3
"""Build the offline GitHub Pages demo into the fixed ignored build/site path.

The builder deliberately copies only the public landing files, selected static
showcase media, and the Android Horizon renderer assets.  It never packages the
Pi mirror client, microphone worklet, runtime endpoint, or any configuration.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


REPO = Path(__file__).resolve().parents[1]
SITE = REPO / "site"
HORIZON = REPO / "overlay" / "apps" / "android" / "app" / "src" / "main" / "assets" / "jarvis" / "horizon"
OUTPUT = REPO / "build" / "site"

SITE_FILES = ("index.html", "styles.css", "site.js")
OPTIONAL_SITE_FILES = ("favicon.ico", "favicon.svg", "favicon.png")
SITE_ASSETS = (
    "horizon-motion-poster.png",
    "horizon-motion.mp4",
    "showcase-home.png",
    "showcase-weather.png",
    "showcase-activity.png",
    "showcase-music.png",
    "showcase-agenda.png",
    "showcase-home-control.png",
    "showcase-ambient.png",
    "showcase-portrait.png",
    "how-it-works.png",
)
HORIZON_FILES = (
    "index.html",
    "css/jarvis-horizon.css",
    "css/caption-overrides.css",
    "fonts/space-grotesk.woff2",
    "fonts/jetbrains-mono.woff2",
    "js/jarvis-orb.js",
    "js/app.js",
    "js/galactic-saver.js",
)
CSP = (
    "default-src 'self'; script-src 'self' 'unsafe-inline'; connect-src 'none'; "
    "img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; "
    "object-src 'none'; base-uri 'none'"
)
FORBIDDEN = (
    "mirror-client",
    "mic-worklet",
    "/runtime.json",
    "new websocket",
    "navigator.mediadevices",
    "getusermedia",
    "eventsource",
    "credentials",
)
HTML_REF = re.compile(
    r"<(?:script|img|link|video|source|iframe)\b[^>]*?\b(?:src|href|poster)\s*=\s*(['\"])(.*?)\1",
    re.I,
)
CSS_REF = re.compile(r"url\(\s*(['\"]?)(.*?)\1\s*\)", re.I)


def fail(message: str) -> None:
    raise RuntimeError(message)


def ensure_regular(path: Path) -> None:
    if path.is_symlink():
        fail(f"symlinks are not allowed: {path.relative_to(REPO)}")
    if not path.is_file():
        fail(f"expected regular file: {path.relative_to(REPO)}")


def is_reparse_point(path: Path) -> bool:
    is_junction = getattr(path, "is_junction", None)
    return path.is_symlink() or bool(is_junction and is_junction())


def reject_reparse_point(path: Path, description: str) -> None:
    if is_reparse_point(path):
        fail(f"refusing {description} at symlink or junction: {path}")


def prepare_output() -> None:
    repo_resolved = REPO.resolve(strict=True)
    build_dir = REPO / "build"
    for path in (REPO, build_dir, OUTPUT):
        if path.exists() or path.is_symlink():
            reject_reparse_point(path, "generated-output cleanup")
    expected = (repo_resolved / "build" / "site").resolve(strict=False)
    resolved = OUTPUT.resolve(strict=False)
    try:
        resolved.relative_to(repo_resolved)
    except ValueError as error:
        raise RuntimeError("refusing to clean generated output outside repository") from error
    if resolved != expected or OUTPUT.name != "site" or OUTPUT.parent.name != "build":
        fail("refusing to clean a path other than repository build/site")
    if OUTPUT.exists():
        if not OUTPUT.is_dir():
            fail("build/site exists but is not a directory")
        for entry in OUTPUT.rglob("*"):
            if is_reparse_point(entry):
                fail(f"refusing to clean build/site containing symlink or junction: {entry.relative_to(REPO)}")
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True, exist_ok=False)


def copy_file(source: Path, destination: Path) -> None:
    ensure_regular(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def inject_html(html: str, *, driver: bool) -> str:
    csp = f'<meta http-equiv="Content-Security-Policy" content="{CSP}">'
    if "Content-Security-Policy" not in html:
        if "</head>" not in html:
            fail("HTML has no closing head tag for CSP injection")
        html = html.replace("</head>", csp + "</head>", 1)
    if driver:
        tag = '<script src="demo-driver.js"></script>'
        if tag not in html:
            if "</body>" not in html:
                fail("Horizon HTML has no closing body tag for demo driver injection")
            html = html.replace("</body>", tag + "</body>", 1)
    return html


def local_reference(raw: str, source: Path) -> Path | None:
    value = raw.strip()
    if not value or value.startswith(("#", "data:", "mailto:", "tel:")):
        return None
    parsed = urlsplit(value)
    if parsed.scheme or parsed.netloc or value.startswith("/"):
        fail(f"external or root-relative reference in {source.relative_to(OUTPUT)}: {value}")
    target = (source.parent / unquote(parsed.path)).resolve()
    try:
        target.relative_to(OUTPUT.resolve())
    except ValueError as error:
        raise RuntimeError(f"reference escapes generated site in {source.relative_to(OUTPUT)}: {value}") from error
    return target


def validate_references() -> None:
    for html in OUTPUT.rglob("*.html"):
        content = html.read_text(encoding="utf-8")
        for match in HTML_REF.finditer(content):
            if match.group(0).lower().startswith("<link") and re.search(r"\brel\s*=\s*(['\"]?)canonical\1", match.group(0), re.I):
                continue
            value = match.group(2)
            target = local_reference(value, html)
            if target is not None and not target.is_file():
                fail(f"missing HTML asset in {html.relative_to(OUTPUT)}: {value}")
    for css in OUTPUT.rglob("*.css"):
        content = css.read_text(encoding="utf-8")
        for _, value in CSS_REF.findall(content):
            target = local_reference(value, css)
            if target is not None and not target.is_file():
                fail(f"missing CSS asset in {css.relative_to(OUTPUT)}: {value}")


def validate_content() -> None:
    for path in OUTPUT.rglob("*"):
        if is_reparse_point(path):
            fail(f"generated symlink or junction is not allowed: {path.relative_to(OUTPUT)}")
        if not path.is_file() or path.suffix.lower() not in {".html", ".css", ".js"}:
            continue
        content = path.read_text(encoding="utf-8").lower()
        for forbidden in FORBIDDEN:
            if forbidden in content:
                fail(f"forbidden runtime reference {forbidden!r} in {path.relative_to(OUTPUT)}")
    for script in OUTPUT.rglob("*.js"):
        result = subprocess.run(["node", "--check", str(script)], capture_output=True, text=True)
        if result.returncode:
            fail(f"JavaScript syntax check failed for {script.relative_to(OUTPUT)}:\n{result.stderr.strip()}")


def build() -> None:
    for name in SITE_FILES:
        ensure_regular(SITE / name)
    ensure_regular(SITE / "demo-driver.js")
    for name in SITE_ASSETS:
        ensure_regular(REPO / "docs" / "assets" / name)
    for relative in HORIZON_FILES:
        ensure_regular(HORIZON / relative)

    prepare_output()
    for name in SITE_FILES:
        source = SITE / name
        target = OUTPUT / name
        if name == "index.html":
            target.write_text(inject_html(source.read_text(encoding="utf-8"), driver=False), encoding="utf-8", newline="\n")
        else:
            copy_file(source, target)
    for name in OPTIONAL_SITE_FILES:
        source = SITE / name
        if source.exists():
            copy_file(source, OUTPUT / name)

    for name in SITE_ASSETS:
        copy_file(REPO / "docs" / "assets" / name, OUTPUT / "assets" / name)
    for font in ("space-grotesk.woff2", "jetbrains-mono.woff2"):
        copy_file(HORIZON / "fonts" / font, OUTPUT / "fonts" / font)
    for relative in HORIZON_FILES:
        source = HORIZON / relative
        target = OUTPUT / "horizon" / relative
        if relative == "index.html":
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(inject_html(source.read_text(encoding="utf-8"), driver=True), encoding="utf-8", newline="\n")
        else:
            copy_file(source, target)
    copy_file(SITE / "demo-driver.js", OUTPUT / "horizon" / "demo-driver.js")

    validate_references()
    validate_content()
    files = [path for path in OUTPUT.rglob("*") if path.is_file()]
    total = sum(path.stat().st_size for path in files)
    print(f"Built {len(files)} files ({total:,} bytes) in {OUTPUT.relative_to(REPO)}")


if __name__ == "__main__":
    try:
        build()
    except (OSError, RuntimeError) as error:
        print(f"build-demo: {error}", file=sys.stderr)
        raise SystemExit(1)

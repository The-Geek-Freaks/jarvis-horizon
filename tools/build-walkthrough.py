#!/usr/bin/env python3
"""Build the lightweight, captioned Jarvis Horizon product walkthrough.

The film deliberately contains only renderer media checked into this public
repository.  It is a visual overview with sample data, not a recording of a
connected home or an assertion that any integration is live.

Usage (from the repository root):
  python -B tools/build-walkthrough.py
  python -B tools/build-walkthrough.py --ffmpeg C:\\path\\to\\ffmpeg.exe
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "docs" / "assets"
BUILD = ROOT / "build" / "walkthrough"
OUTPUT = ASSETS / "horizon-walkthrough.mp4"

FPS = 24
WIDTH = 960
HEIGHT = 540
BACKGROUND = "0x050c17"

# Only public, committed renderer media belongs in this film.  Durations are
# intentionally short enough for a README/post preview while still letting a
# visitor register each view.
SCENES = (
    {
        "id": "orb-intro",
        "source": "horizon-motion.mp4",
        "duration": 4.0,
        "kind": "video",
        "caption_en": "Jarvis Horizon — an open, voice-first home surface for OpenClaw. Sample data.",
        "caption_de": "Jarvis Horizon — eine offene, sprachzentrierte Wohnoberfläche für OpenClaw. Beispieldaten.",
    },
    {
        "id": "home",
        "source": "showcase-home.png",
        "duration": 3.2,
        "kind": "image",
        "caption_en": "A living home view: day, schedule, weather, and system presence. Sample data.",
        "caption_de": "Eine lebendige Startansicht: Tag, Termine, Wetter und Systemstatus. Beispieldaten.",
    },
    {
        "id": "weather",
        "source": "showcase-weather.png",
        "duration": 3.0,
        "kind": "image",
        "caption_en": "A dedicated weather view with forecast details. Sample data.",
        "caption_de": "Eine eigene Wetteransicht mit Prognosedetails. Beispieldaten.",
    },
    {
        "id": "activity",
        "source": "showcase-activity.png",
        "duration": 3.0,
        "kind": "image",
        "caption_en": "Visible activity keeps longer work legible. Sample data.",
        "caption_de": "Sichtbare Aktivität macht längere Aufgaben verständlich. Beispieldaten.",
    },
    {
        "id": "music",
        "source": "showcase-music.png",
        "duration": 3.0,
        "kind": "image",
        "caption_en": "Track information and playback context, at a glance. Sample data.",
        "caption_de": "Titelinformationen und Wiedergabekontext auf einen Blick. Beispieldaten.",
    },
    {
        "id": "portrait",
        "source": "showcase-portrait.png",
        "duration": 3.4,
        "kind": "image",
        "caption_en": "Portrait and landscape layouts stay readable. Sample data.",
        "caption_de": "Hoch- und Querformat bleiben gut lesbar. Beispieldaten.",
    },
    {
        "id": "ambient",
        "source": "showcase-ambient.png",
        "duration": 3.0,
        "kind": "image",
        "caption_en": "A calm ambient surface for the moments between. Open source. Sample data.",
        "caption_de": "Eine ruhige Ambient-Ansicht für die Zwischenmomente. Open Source. Beispieldaten.",
    },
)


def run(command: list[str]) -> None:
    print("+", subprocess.list2cmdline(command))
    subprocess.run(command, check=True)


def timestamp(seconds: float) -> str:
    milliseconds = round(seconds * 1000)
    hours, remainder = divmod(milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    whole_seconds, milliseconds = divmod(remainder, 1000)
    return f"{hours:02}:{minutes:02}:{whole_seconds:02}.{milliseconds:03}"


def write_vtt(path: Path, language: str) -> None:
    lines = ["WEBVTT", ""]
    start = 0.0
    key = f"caption_{language}"
    for number, scene in enumerate(SCENES, start=1):
        end = start + scene["duration"]
        lines.extend((str(number), f"{timestamp(start)} --> {timestamp(end)}", scene[key], ""))
        start = end
    path.write_text("\n".join(lines), encoding="utf-8")


def segment_filter(scene: dict[str, object]) -> str:
    duration = float(scene["duration"])
    base = (
        f"fps={FPS},scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
        f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:color={BACKGROUND},"
        f"setsar=1,trim=duration={duration:.3f},"
        f"fade=t=in:st=0:d=0.22,fade=t=out:st={duration - 0.22:.3f}:d=0.22"
    )
    return base


def build(ffmpeg: str) -> None:
    missing = [ASSETS / str(scene["source"]) for scene in SCENES if not (ASSETS / str(scene["source"])).is_file()]
    if missing:
        raise FileNotFoundError("Missing public walkthrough media:\n" + "\n".join(map(str, missing)))

    BUILD.mkdir(parents=True, exist_ok=True)
    parts: list[Path] = []
    for index, scene in enumerate(SCENES):
        source = ASSETS / str(scene["source"])
        part = BUILD / f"{index:02d}-{scene['id']}.mp4"
        parts.append(part)
        if scene["kind"] == "image":
            command = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-loop", "1", "-i", str(source)]
        else:
            command = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(source)]
        command.extend(
            [
                "-t",
                f"{float(scene['duration']):.3f}",
                "-vf",
                segment_filter(scene),
                "-an",
                "-c:v",
                "libx264",
                "-preset",
                "slow",
                "-crf",
                "25",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                "-threads",
                "4",
                str(part),
            ]
        )
        run(command)

    concat_file = BUILD / "concat.txt"
    concat_file.write_text("\n".join(f"file '{part.name}'" for part in parts) + "\n", encoding="utf-8")
    run(
        [
            ffmpeg,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_file),
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "slow",
            "-crf",
            "25",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-threads",
            "4",
            str(OUTPUT),
        ]
    )
    write_vtt(ASSETS / "horizon-walkthrough.vtt", "en")
    write_vtt(ASSETS / "horizon-walkthrough.de.vtt", "de")

    max_bytes = 8 * 1024 * 1024
    if OUTPUT.stat().st_size > max_bytes:
        raise RuntimeError(f"Walkthrough is {OUTPUT.stat().st_size} bytes; limit is {max_bytes} bytes")

    manifest = {
        "output": OUTPUT.relative_to(ROOT).as_posix(),
        "resolution": f"{WIDTH}x{HEIGHT}",
        "fps": FPS,
        "audio": False,
        "sample_data_only": True,
        "duration_seconds": round(sum(float(scene["duration"]) for scene in SCENES), 3),
        "size_bytes": OUTPUT.stat().st_size,
        "captions": [
            (ASSETS / "horizon-walkthrough.vtt").relative_to(ROOT).as_posix(),
            (ASSETS / "horizon-walkthrough.de.vtt").relative_to(ROOT).as_posix(),
        ],
        "scenes": [{key: scene[key] for key in ("id", "source", "duration", "caption_en", "caption_de")} for scene in SCENES],
    }
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--ffmpeg",
        default=shutil.which("ffmpeg") or r"C:\\Users\\Shadow-PC\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe",
        help="ffmpeg executable (defaults to PATH, then this workstation's installed executable)",
    )
    parser.add_argument(
        "--captions-only",
        action="store_true",
        help="regenerate the English and German WebVTT tracks without encoding video",
    )
    args = parser.parse_args()
    if args.captions_only:
        write_vtt(ASSETS / "horizon-walkthrough.vtt", "en")
        write_vtt(ASSETS / "horizon-walkthrough.de.vtt", "de")
        return
    if not Path(args.ffmpeg).is_file() and not shutil.which(args.ffmpeg):
        raise SystemExit(f"ffmpeg was not found: {args.ffmpeg}")
    build(args.ffmpeg)


if __name__ == "__main__":
    main()

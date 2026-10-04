#!/usr/bin/env python3
"""Validation longue durée d'un serveur Pluxy sur téléphone réel.

Le script reste volontairement en bibliothèque standard. Il vérifie la disponibilité,
les réponses HTTP Range et, si ADB est fourni, capture batterie/thermique/veille sans
extraire aucun nom de fichier ni contenu multimédia dans le rapport.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import random
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


RANGE_RE = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")


@dataclass
class Sample:
    at_seconds: float
    health_ms: float | None
    range_ms: float | None
    range_bytes: int
    error: str | None
    adb: dict[str, str]


def private_server_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value.rstrip("/"))
    if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password:
        raise argparse.ArgumentTypeError("Utilisez une URL HTTP locale, par exemple http://192.168.1.20:8420")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("L'adresse doit être une IP locale explicite") from exc
    if not (address.is_private or address.is_link_local or address.is_loopback):
        raise argparse.ArgumentTypeError("Le diagnostic refuse toute adresse hors LAN privé")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise argparse.ArgumentTypeError("Indiquez uniquement l'adresse de base du serveur")
    return value.rstrip("/")


def request_json(url: str, timeout: float = 8.0) -> Any:
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Pluxy-Soak/1"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        body = response.read(8 * 1024 * 1024 + 1)
        if len(body) > 8 * 1024 * 1024:
            raise RuntimeError("Réponse JSON trop volumineuse")
        return json.loads(body.decode("utf-8"))


def select_item(items: list[dict[str, Any]], requested: str | None) -> dict[str, Any]:
    videos = [item for item in items if item.get("kind") in {"movie", "series"}]
    if requested:
        match = next((item for item in videos if item.get("id") == requested), None)
        if match is None:
            raise RuntimeError("Identifiant vidéo introuvable")
        return match
    if not videos:
        raise RuntimeError("Aucune vidéo disponible pour le contrôle Range")
    return max(videos, key=lambda item: int(item.get("size") or 0))


def range_probe(base_url: str, item: dict[str, Any], rng: random.Random) -> tuple[float, int]:
    total = int(item.get("size") or 0)
    if total <= 0:
        raise RuntimeError("Taille du média inconnue")
    length = min(256 * 1024, total)
    start = rng.randrange(0, max(1, total - length + 1))
    end = start + length - 1
    request = urllib.request.Request(
        f"{base_url}/stream/direct/{item['id']}",
        headers={"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity", "User-Agent": "Pluxy-Soak/1"},
    )
    before = time.monotonic()
    with urllib.request.urlopen(request, timeout=20.0) as response:
        body = response.read(length + 1)
        elapsed = (time.monotonic() - before) * 1000
        if response.status != 206 or len(body) != length:
            raise RuntimeError(f"Range invalide : HTTP {response.status}, {len(body)} octets")
        header = response.headers.get("Content-Range", "")
        match = RANGE_RE.fullmatch(header)
        if not match or tuple(map(int, match.groups())) != (start, end, total):
            raise RuntimeError(f"Content-Range incohérent : {header!r}")
        return elapsed, len(body)


def adb_snapshot(serial: str | None) -> dict[str, str]:
    if not serial:
        return {}
    prefix = ["adb", "-s", serial, "shell"]
    commands = {
        "battery": ["dumpsys", "battery"],
        "power": ["dumpsys", "power"],
        "thermal": ["dumpsys", "thermalservice"],
    }
    result: dict[str, str] = {}
    for name, command in commands.items():
        try:
            completed = subprocess.run(
                prefix + command,
                capture_output=True,
                text=True,
                timeout=12,
                check=False,
            )
            text = completed.stdout.replace("\r", "").strip()
            # Rapports bornés : dumpsys peut dépasser plusieurs Mo selon l'OEM.
            if completed.returncode != 0 or not text:
                result[name] = f"indisponible: adb {completed.returncode}"
            else:
                result[name] = text[:12_000]
        except (OSError, subprocess.SubprocessError) as exc:
            result[name] = f"indisponible: {type(exc).__name__}"
    return result


def run(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    items = request_json(f"{args.server}/api/library/items")
    if not isinstance(items, list):
        raise RuntimeError("Catalogue serveur invalide")
    item = select_item(items, args.item_id)
    deadline = started + args.duration_minutes * 60
    samples: list[Sample] = []
    rng = random.Random(args.seed)
    cycles = 0
    while cycles < args.cycles and (cycles == 0 or time.monotonic() < deadline):
        health_ms: float | None = None
        range_ms: float | None = None
        transferred = 0
        error: str | None = None
        try:
            before = time.monotonic()
            health = request_json(f"{args.server}/health")
            health_ms = (time.monotonic() - before) * 1000
            if not isinstance(health, dict) or health.get("status") != "ok":
                raise RuntimeError("Réponse /health inattendue")
            range_ms, transferred = range_probe(args.server, item, rng)
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
            error = f"{type(exc).__name__}: {exc}"
        adb = adb_snapshot(args.adb_serial) if cycles % args.adb_every == 0 else {}
        if args.adb_serial and adb:
            missing_required = [
                name for name in ("battery", "power")
                if adb.get(name, "").startswith("indisponible:")
            ]
            if missing_required:
                adb_error = "ADB indisponible pour " + ", ".join(missing_required)
                error = f"{error}; {adb_error}" if error else adb_error
        samples.append(
            Sample(
                at_seconds=round(time.monotonic() - started, 3),
                health_ms=round(health_ms, 2) if health_ms is not None else None,
                range_ms=round(range_ms, 2) if range_ms is not None else None,
                range_bytes=transferred,
                error=error,
                adb=adb,
            )
        )
        cycles += 1
        if cycles < args.cycles and time.monotonic() < deadline:
            time.sleep(min(args.interval_seconds, max(0.0, deadline - time.monotonic())))

    failures = sum(sample.error is not None for sample in samples)
    report = {
        "schema": 1,
        "server": args.server,
        "duration_seconds": round(time.monotonic() - started, 3),
        "cycles": len(samples),
        "failures": failures,
        "success_rate": round((len(samples) - failures) / max(1, len(samples)), 4),
        "tested_media": {
            "id": item.get("id"),
            "container": item.get("container"),
            "video_codec": item.get("video_codec"),
            "height": item.get("height"),
            "size": item.get("size"),
        },
        "samples": [asdict(sample) for sample in samples],
    }
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Test longue durée Pluxy sur appareil réel")
    parser.add_argument("--server", required=True, type=private_server_url)
    parser.add_argument("--duration-minutes", type=float, default=120.0)
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--interval-seconds", type=float, default=72.0)
    parser.add_argument("--item-id")
    parser.add_argument("--adb-serial")
    parser.add_argument("--adb-every", type=int, default=10)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output", type=Path, default=Path("pluxy-soak-report.json"))
    args = parser.parse_args()
    if args.duration_minutes <= 0 or args.cycles <= 0 or args.interval_seconds < 0 or args.adb_every <= 0:
        parser.error("Les durées, cycles et intervalles doivent être valides")
    return args


def main() -> int:
    args = parse_args()
    report = run(args)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"{report['cycles']} cycles · {report['failures']} échec(s) · "
        f"rapport {args.output.resolve()}"
    )
    return 0 if report["failures"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())

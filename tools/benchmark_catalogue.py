"""Mesure les filtres et pages sur un catalogue synthétique, sans réseau ni FFmpeg."""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from pluxy.catalogue import Catalogue, Query  # noqa: E402
from pluxy.models import MediaItem, MovieMetadata  # noqa: E402


def benchmark(count: int, iterations: int) -> dict:
    items = tuple(MediaItem(
        id=str(index), title=f"Film {index:05d}", path="", container="mkv", size=1,
        duration=(30 + index % 120) * 60, width=3840 if index % 4 == 0 else 1920,
        height=2160 if index % 4 == 0 else 1080, is_hdr=index % 8 == 0, added_at=float(index),
    ) for index in range(count))
    metadata = {item.id: MovieMetadata(title=item.title, year=1990 + index % 35,
                                      genres=["Drame" if index % 2 else "Action"])
                for index, item in enumerate(items)}
    watched = {item.id: {"watched": index % 3 == 0, "position_ms": 30_000 if index % 3 == 1 else 0,
                        "duration_ms": int(item.duration * 1000)} for index, item in enumerate(items)}
    catalogue = Catalogue(SimpleNamespace(snapshot=lambda: ("benchmark", items)),
                          SimpleNamespace(snapshot=lambda _item_ids: (1, metadata)),
                          SimpleNamespace(snapshot=lambda: (1, watched)))
    queries = [Query(), Query(watched="in_progress"), Query(genres=("Action",), year=2020),
               Query(duration_max_minutes=90, sort="duration"), Query(resolution="4k", hdr_only=True),
               Query(q="Film 000", sort="added", descending=True)]
    started = time.perf_counter()
    catalogue.page(queries[0], 0, 100)
    cold_ms = (time.perf_counter() - started) * 1000
    first_filters = []
    for query in queries[1:]:
        started = time.perf_counter()
        catalogue.page(query, 0, 100)
        first_filters.append((time.perf_counter() - started) * 1000)
    warmed = []
    for index in range(iterations):
        started = time.perf_counter()
        catalogue.page(queries[index % len(queries)], (index % 5) * 100, 100)
        warmed.append((time.perf_counter() - started) * 1000)
    return {"items": count, "iterations": iterations, "cold_snapshot_ms": round(cold_ms, 3),
            "first_filter_max_ms": round(max(first_filters), 3),
            "cached_page_median_ms": round(statistics.median(warmed), 3),
            "cached_page_p95_ms": round(sorted(warmed)[int(len(warmed) * .95) - 1], 3),
            "scope": "Filtres, tri et découpage en mémoire ; hors réseau, scan et rendu Android."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--items", type=int, default=10_000)
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 100 <= args.items <= 100_000 or not 20 <= args.iterations <= 100_000:
        parser.error("items : 100–100 000 ; iterations : 20–100 000")
    result = benchmark(args.items, args.iterations)
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)


if __name__ == "__main__":
    main()

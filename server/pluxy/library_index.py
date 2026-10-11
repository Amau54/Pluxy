"""Index transactionnel : les scans réécrivent uniquement les médias modifiés."""
from __future__ import annotations

import json
import logging
from contextlib import closing
import sqlite3
from pathlib import Path


class LibraryIndex:
    def __init__(self, path: Path):
        self.path = path

    def load(self) -> list[dict] | None:
        if not self.path.exists():
            return None
        with closing(sqlite3.connect(self.path)) as db:
            try:
                if db.execute("SELECT ready FROM state").fetchone() is None:
                    return None
            except sqlite3.OperationalError as error:
                if str(error) != "no such table: state":
                    raise
            try:
                rows = db.execute("SELECT payload FROM media ORDER BY id")
            except sqlite3.OperationalError as error:
                if str(error) == "no such table: media":
                    return None
                raise
            values = []
            for row in rows:
                try:
                    values.append(json.loads(row[0]))
                except (ValueError, TypeError):
                    logging.getLogger(__name__).warning("Une entrée invalide de l'index a été ignorée.")
            return values

    def save(self, items: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("PRAGMA synchronous=FULL")
            db.execute("CREATE TABLE IF NOT EXISTS media (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS state (ready INTEGER NOT NULL)")
            db.execute("CREATE TEMP TABLE present (id TEXT PRIMARY KEY)")
            for item in items.values():
                payload = json.dumps(item.model_dump(), ensure_ascii=False, sort_keys=True)
                db.execute("INSERT INTO media VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload WHERE media.payload!=excluded.payload", (item.id, payload))
                db.execute("INSERT INTO present VALUES (?)", (item.id,))
            db.execute("DELETE FROM media WHERE id NOT IN (SELECT id FROM present)")
            db.execute("DELETE FROM state")
            db.execute("INSERT INTO state VALUES (1)")


def media_fingerprint(path: Path, size: int) -> str:
    """Empreinte bornée pour reconnaître un déplacement ; pas de lecture complète."""
    import hashlib
    digest = hashlib.sha256(str(size).encode("ascii"))
    try:
        with path.open("rb") as stream:
            for offset in sorted({0, max(0, size // 2 - 32768), max(0, size - 65536)}):
                stream.seek(offset)
                digest.update(stream.read(65536))
        return digest.hexdigest()
    except OSError:
        return ""

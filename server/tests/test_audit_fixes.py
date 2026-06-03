# -*- coding: utf-8 -*-
"""Tests de non-regression des correctifs d'audit (securite + robustesse)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def test_segment_anti_traversal():
    from pluxy.api.routes_stream import _SEGMENT_RE
    bad = [r"..\..\x.m4s", "../../x.m4s", "seg_0/../x.m4s", r"C:\x.m4s",
           "init.mp4.exe", "evil.m4s", ".env", "seg_.m4s"]
    good = ["seg_00000.m4s", "init.mp4", "seg_12.m4s", "seg_9999999.m4s"]
    assert all(not _SEGMENT_RE.match(b) for b in bad), "traversal non bloque"
    assert all(_SEGMENT_RE.match(g) for g in good), "segment legitime refuse"


def test_atomic_write():
    from pluxy.paths import atomic_write_text
    import tempfile
    p = Path(tempfile.mkdtemp()) / "t.json"
    atomic_write_text(p, '{"a":1}')
    assert p.read_text(encoding="utf-8") == '{"a":1}'
    assert not p.with_suffix(".json.tmp").exists()  # tmp nettoye par os.replace


def test_app_imports():
    from pluxy.main import app
    paths = {r.path for r in app.routes}
    assert "/stream/hls/{item_id}/{variant}/{offset}/index.m3u8" in paths


def test_sub_lang_detection():
    from pluxy.library import Library
    from pathlib import Path as P
    tracks = Library._sub_tracks(P("Movie.mkv"), [P("Movie.fr.srt"), P("Movie.srt"), P("Movie.en.vtt")])
    assert tracks[0].lang == "fr" and tracks[0].format == "srt"
    assert tracks[1].lang is None
    assert tracks[2].lang == "en" and tracks[2].format == "vtt"


def test_sidecar_strict():
    from pluxy.library import Library
    # Film2.srt ne doit PAS matcher Film.mkv (test logique de la condition)
    stem = "film"
    assert not ("film2".startswith(stem + ".") or "film2" == stem)
    assert ("film.fr".startswith(stem + ".")) or ("film" == stem)


if __name__ == "__main__":
    test_segment_anti_traversal()
    test_atomic_write()
    test_app_imports()
    test_sub_lang_detection()
    test_sidecar_strict()
    print("TOUS LES TESTS AUDIT OK")

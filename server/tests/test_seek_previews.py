from pathlib import Path
from types import SimpleNamespace
import sys
import threading
import time
import os
import shutil
import subprocess

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pluxy.seek_previews import SeekPreviews, preview_second
from pluxy.tools import NO_WINDOW


def test_long_gop_preview_near_eof_still_produces_an_image(tmp_path):
    ffmpeg = os.environ.get("PLUXY_TEST_FFMPEG") or shutil.which("ffmpeg")
    if not ffmpeg:
        pytest.skip("FFmpeg absent")
    source = tmp_path / "long-gop.mkv"
    subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=2",
                    "-t", "8", "-c:v", "libx264", "-g", "60", "-sc_threshold", "0", "-y", str(source)],
                   check=True, timeout=10, creationflags=NO_WINDOW)
    previews = SeekPreviews(SimpleNamespace(ffmpeg=SimpleNamespace(ffmpeg_path=ffmpeg)), tmp_path)
    target = tmp_path / "near-end.jpg"
    try:
        assert previews._capture(source, target, 6)
        from PIL import Image
        with Image.open(target) as image:
            assert image.width <= 320 and image.height <= 180
            assert image.getbbox() is not None
    finally:
        previews.close()


@pytest.mark.parametrize(
    "position,duration,expected",
    [(0, 60, 0), (19.9, 60, 10), (60, 60, 50), (600, 0, 600), (2, 1, 0)],
)
def test_preview_position_is_bounded_and_shared(position, duration, expected):
    assert preview_second(position, duration) == expected


@pytest.mark.parametrize("position", [-1, float("inf"), float("nan")])
def test_invalid_position_never_reaches_decoder(position):
    with pytest.raises(ValueError):
        preview_second(position, 60)


def item(tmp_path, kind="movie"):
    source = tmp_path / "media.mp4"
    source.write_bytes(b"fixture")
    return SimpleNamespace(id="movie", path=str(source), duration=180, kind=kind)


def wait_for(check):
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.01)
    assert check()


def test_http_returns_before_capture_and_queue_keeps_latest_target(
    tmp_path, monkeypatch
):
    previews = SeekPreviews(SimpleNamespace(), tmp_path)
    media = item(tmp_path)
    started, release = threading.Event(), threading.Event()
    captured = []

    def capture(source, target, second):
        captured.append(second)
        started.set()
        release.wait(2)
        target.write_bytes(b"image")
        return True

    monkeypatch.setattr(previews, "_capture", capture)
    assert previews.request(media, 10) == (None, True)
    assert started.wait(1)
    for second in [20, 30, 40, 40, 40]:
        assert previews.request(media, second) == (None, True)
    assert len(previews._pending) == 1
    release.set()
    wait_for(lambda: previews._worker is None)
    assert captured == [10, 40]
    path, pending = previews.request(media, 49)
    assert path.read_bytes() == b"image" and not pending
    assert captured == [10, 40]
    previews.close()


def test_failure_is_cached_and_shutdown_discards_queued_work(tmp_path, monkeypatch):
    previews = SeekPreviews(SimpleNamespace(), tmp_path)
    media = item(tmp_path)
    calls = []
    monkeypatch.setattr(previews, "_capture", lambda *args: calls.append(args) or False)
    previews.request(media, 30)
    wait_for(lambda: previews._worker is None)
    assert previews.request(media, 30) == (None, False)
    assert len(calls) == 1
    previews.close()
    assert previews.request(media, 60) == (None, False)


def test_changed_source_cannot_reuse_old_preview(tmp_path, monkeypatch):
    previews = SeekPreviews(SimpleNamespace(), tmp_path)
    media = item(tmp_path)

    def capture(source, target, second):
        target.write_bytes(b"image")
        return True

    monkeypatch.setattr(previews, "_capture", capture)
    previews.request(media, 10)
    wait_for(lambda: previews._worker is None)
    old, _ = previews.request(media, 10)
    Path(media.path).write_bytes(b"changed content")
    previews.request(media, 10)
    wait_for(lambda: previews._worker is None)
    new, _ = previews.request(media, 10)
    assert old != new


def test_non_video_media_never_schedule_decode(tmp_path):
    previews = SeekPreviews(SimpleNamespace(), tmp_path)
    assert previews.request(item(tmp_path, "photo"), 0) == (None, False)
    assert previews._worker is None


def test_capture_exception_does_not_block_later_requests(tmp_path, monkeypatch):
    previews = SeekPreviews(SimpleNamespace(), tmp_path)
    media = item(tmp_path)

    def capture(source, target, second):
        if second == 10:
            raise OSError("Decoder unavailable")
        target.write_bytes(b"image")
        return True

    monkeypatch.setattr(previews, "_capture", capture)
    previews.request(media, 10)
    wait_for(lambda: previews._worker is None)
    previews.request(media, 20)
    wait_for(lambda: previews._worker is None)
    assert previews.request(media, 20)[0].read_bytes() == b"image"
    previews.close()


def test_preview_http_pending_ready_and_invalid_positions(tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from pluxy.api.routes_library import router

    media = item(tmp_path)
    result = [None, True]
    calls = []

    def request(item, position):
        calls.append((item, position))
        return tuple(result)

    app = FastAPI()
    app.include_router(router)
    app.state.pluxy = SimpleNamespace(
        library=SimpleNamespace(get=lambda id: media if id == media.id else None),
        seek_previews=SimpleNamespace(request=request),
    )
    with TestClient(app) as client:
        response = client.get(
            "/api/library/items/movie/preview", params={"position": 60}
        )
        assert response.status_code == 202 and response.content == b""
        assert response.headers["retry-after"] == "1"
        assert response.headers["cache-control"] == "no-store"
        target = tmp_path / "preview.jpg"
        target.write_bytes(b"jpeg fixture")
        result[:] = [target, False]
        response = client.get(
            "/api/library/items/movie/preview", params={"position": 60}
        )
        assert response.status_code == 200 and response.content == b"jpeg fixture"
        assert response.headers["content-type"] == "image/jpeg"
        result[:] = [None, False]
        assert (
            client.get("/api/library/items/movie/preview?position=60").status_code
            == 404
        )
        assert (
            client.get("/api/library/items/unknown/preview?position=60").status_code
            == 404
        )
        count = len(calls)
        for position in ["-1", "604801", "inf", "nan"]:
            assert (
                client.get(
                    "/api/library/items/movie/preview", params={"position": position}
                ).status_code
                == 422
            )
        assert len(calls) == count

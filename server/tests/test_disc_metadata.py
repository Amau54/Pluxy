import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pluxy.disc_metadata import parse_playlist

SAMPLE = bytes.fromhex("4d504c53303230300000003a000000a60000000000000000000000000000000000000000000000000000000e000100000000000000000000400000000068000000010000006030303030304d325453000100019bfcc0021e7330000000000000000000000000003e000001020000000000000000000009011011000000000000051b3200000009011100000000000000058111656e670901110100000000000005811166726100000010000100010000019bfcc0ffff00000000")


def test_languages_use_stream_pid_not_track_order():
    languages, chapters = parse_playlist(SAMPLE)
    assert languages == {0x1100: "eng", 0x1101: "fra"}
    assert chapters == [{"start_time": 0.0, "end_time": 190.0, "tags": {"title": "Chapitre 1"}}]


def test_truncated_or_malicious_playlists_are_ignored():
    for length in range(len(SAMPLE)):
        assert parse_playlist(SAMPLE[:length]) == ({}, [])
    assert parse_playlist(b"MPLS" + b"\xff" * 200) == ({}, [])
    assert parse_playlist(b"MPLS" + b"0" * (1024 * 1024)) == ({}, [])


def test_remux_carries_languages_without_changing_audio_payload():
    from pluxy.discs import remux_command
    cmd = remux_command("ffmpeg", "film.iso", "bd00000", 0, audio_languages=["eng", "fra"])
    assert cmd[cmd.index("-metadata:s:a:1") + 1] == "language=fra"
    assert "-c:a" not in cmd

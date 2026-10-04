# -*- coding: utf-8 -*-
"""Tests du parser de noms de fichiers (16 cas difficiles issus de la recherche).

Lancer :  python -m pytest server/tests/  (ou)  python server/tests/test_filename.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pluxy.filename import parse  # noqa: E402

CASES = [
    ("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv", "The Matrix", 1999),
    ("Blade Runner (1982) {edition-Final Cut} 2160p UHD BluRay REMUX HDR TrueHD Atmos 7.1-FraMeSToR.mkv", "Blade Runner", 1982),
    ("Blade.Runner.2049.2017.MULTi.VFF.2160p.4K.UHD.HDR.x265-GROUP.mkv", "Blade Runner 2049", 2017),
    ("1917.2019.TRUEFRENCH.1080p.WEB-DL.DDP5.1.H264-ABC.mkv", "1917", 2019),
    ("2012.2009.MULTi.1080p.BluRay.x264-ROUGH.mkv", "2012", 2009),
    ("San Andreas 2015 720p BRRip x264 AAC-ETRG.mp4", "San Andreas", 2015),
    ("Le.Fabuleux.Destin.d.Amelie.Poulain.2001.VFF.1080p.BluRay.DTS-HD.MA.5.1.x264.mkv", "Le Fabuleux Destin d Amelie Poulain", 2001),
    ("[YTS.MX] Inception (2010) [2160p] [4K] [BluRay] [5.1].mp4", "Inception", 2010),
    ("www.Torrenting.com - Dune.Part.Two.2024.VOSTFR.1080p.WEBRip.x265-XYZ.mkv", "Dune Part Two", 2024),
    ("Avengers.Endgame.2019.MULTi.VFQ.VFF.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-Group.mkv", "Avengers Endgame", 2019),
    ("The_Lord_of_the_Rings_The_Fellowship_of_the_Ring_2001_EXTENDED_1080p_BluRay_x264-AMIABLE.mkv", "The Lord of the Rings The Fellowship of the Ring", 2001),
    ("Spider-Man.No.Way.Home.2021.REMASTERED.1080p.BluRay.x265.10bit.AC3-RARBG.mkv", "Spider-Man No Way Home", 2021),
    ("300.2006.BrRip.x264.720p.YIFY.mp4", "300", 2006),
    ("Star.Wars.Episode.IV.A.New.Hope.1977.Despecialized.1080p.x264.mkv", "Star Wars Episode IV A New Hope", 1977),
    ("Amelie 2001 TRUEFRENCH DVDRip XviD-NoGRP.avi", "Amelie", 2001),
    ("Blade.Runner.1982.Directors.Cut.Remastered.1080p.HDDVD.DTS.x264-DON.mkv", "Blade Runner", 1982),
]


def test_all_cases():
    for inp, title, year in CASES:
        p = parse(inp)
        assert p.title == title and p.year == year, f"{inp!r} -> {p.title!r}/{p.year}"


if __name__ == "__main__":
    ok = 0
    for inp, et, ey in CASES:
        p = parse(inp)
        good = p.title == et and p.year == ey
        ok += good
        print(("OK " if good else "XX ") + f"{p.title!r} {p.year}")
    print(f"{ok}/{len(CASES)}")

from pathlib import Path
from types import SimpleNamespace
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pluxy.config import PluxyConfig
from pluxy.conversion import PROFILES, conversion_decision
from pluxy.models import MediaInfo, MediaStream, PlaybackDecision, PlaybackMode, StreamKind
from pluxy.transcoder import build_transcode_cmd


def sample(width=3840, height=1608):
    return MediaInfo(path="film.mkv", container="mkv", duration=1000, overall_bitrate=80_000_000,
                     streams=[MediaStream(index=0, kind=StreamKind.VIDEO, codec="hevc", width=width,
                         height=height, is_hdr=True, pix_fmt="yuv420p10le", color_transfer="smpte2084",
                         master_display="G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,1)", max_cll="1000,400")])


def profiles():
    return [dict(id=key, label=label, codec=codec, width=width, height=height, bitrate_mbps=bitrate)
            for key,label,codec,width,height,bitrate in PROFILES]


def test_quality_ladder_keeps_aspect_ratio_and_reduces_bitrate():
    original=PlaybackDecision(mode=PlaybackMode.DIRECT_PLAY, audio_action="copy")
    decisions=[conversion_decision(sample(), original, p, PluxyConfig()) for p in profiles()]
    assert [(d.target_width,d.target_height) for d in decisions] == [(3840,1608),(3840,1608),(1920,804),(1920,804),(1280,536),(854,356)]
    assert all(a.target_bitrate_mbps>b.target_bitrate_mbps for a,b in zip(decisions,decisions[1:]))
    assert [d.tone_map for d in decisions] == [False,False,False,True,True,True]
    assert all(d.audio_action=="copy" for d in decisions)
    assert original.video_action=="copy"


def test_small_source_is_never_enlarged_and_audio_adaptation_is_kept():
    original=PlaybackDecision(mode=PlaybackMode.DIRECT_STREAM,audio_action="transcode",target_audio_codec="eac3",target_audio_channels=6)
    decision=conversion_decision(sample(1280,720),original,profiles()[0],PluxyConfig())
    assert (decision.target_width,decision.target_height)==(1280,720)
    cmd=build_transcode_cmd(sample(1280,720),decision,PluxyConfig(),Path("."),start_time=600,direct_stream=True)
    assert cmd[cmd.index("-ss")+1]=="600.000"
    assert cmd[cmd.index("-c:a")+1]=="eac3"
    assert cmd[-2:]==["matroska","pipe:1"]
    assert cmd[cmd.index("-max_interleave_delta")+1]=="100000"
    assert "-master_display" not in cmd and "-max_cll" not in cmd and "-spatial_aq" not in cmd


def test_software_hdr_uses_x265_metadata_and_h264_can_keep_truehd():
    cfg=PluxyConfig()
    cfg.transcoding.hardware_acceleration=False
    original=PlaybackDecision(mode=PlaybackMode.DIRECT_PLAY,audio_action="copy")
    hdr=conversion_decision(sample(),original,profiles()[0],cfg)
    cmd=build_transcode_cmd(sample(),hdr,cfg,Path("."),direct_stream=True)
    assert "master-display=" in cmd[cmd.index("-x265-params")+1]
    assert cmd[cmd.index("-pix_fmt")+1]=="yuv420p10le"
    sdr=conversion_decision(sample(),original,profiles()[4],cfg)
    cmd=build_transcode_cmd(sample(),sdr,cfg,Path("."),compat=True,direct_stream=True)
    assert cmd[cmd.index("-c:a")+1]=="copy"
    assert "0:a?" in cmd
    assert cmd[cmd.index("-color_trc")+1]=="bt709"


def test_conversion_route_validates_time_profile_and_releases_failed_process(monkeypatch):
    from pluxy.api import routes_stream
    slots=threading.BoundedSemaphore(1)
    original=PlaybackDecision(mode=PlaybackMode.DIRECT_PLAY,audio_action="transcode",target_audio_codec="aac")
    state=SimpleNamespace(library=SimpleNamespace(get=lambda _:SimpleNamespace(path="film.mkv")),
        cfgm=SimpleNamespace(cfg=PluxyConfig()),remux_slots=slots,decision_for=lambda *_:(sample(),original))
    monkeypatch.setattr(routes_stream,"get_state",lambda _:state)
    monkeypatch.setattr(routes_stream,"available_profiles",lambda _:profiles())
    commands=[]
    def fail(cmd,**kwargs):
        commands.append(cmd)
        raise FileNotFoundError()
    monkeypatch.setattr(routes_stream.subprocess,"Popen",fail)
    app=FastAPI()
    app.include_router(routes_stream.router)
    with TestClient(app) as client:
        assert client.get("/stream/convert/film/unknown").status_code==422
        assert client.get("/stream/convert/film/hevc_4k?start_ms=1000000").status_code==416
        assert client.get("/stream/convert/film/hevc_4k?start_ms=600000&copy_audio=true").status_code==503
        assert commands[0][commands[0].index("-c:a")+1]=="copy"
        assert client.get("/stream/convert/film/hevc_4k",headers={"Range":"bytes=500-"}).status_code==416
        assert slots.acquire(False)
        slots.release()


def test_encoder_failure_cannot_look_like_the_end_of_an_episode():
    import asyncio
    import io
    import pytest
    from pluxy.api.routes_stream import _RemuxResponse
    slots=threading.BoundedSemaphore(1)
    slots.acquire()
    proc=SimpleNamespace(stdout=io.BytesIO(b"partial video"),poll=lambda:1,wait=lambda **_:1)
    response=_RemuxResponse(proc,slots,0,strict=True)
    async def read():
        async for _ in response.body_iterator:
            pass
    with pytest.raises(OSError,match="conversion"):
        asyncio.run(read())
    assert slots.acquire(False)
    slots.release()


def test_unavailable_gpu_falls_back_to_software_without_promising_4k(monkeypatch):
    from pluxy import conversion
    monkeypatch.setattr(conversion.shutil,"which",lambda _:str(Path(__file__)))
    monkeypatch.setattr(conversion,"_encoder_works",lambda binary,modified,encoder,gpu: not encoder.endswith("_nvenc"))
    advertised=conversion.available_profiles(PluxyConfig())
    assert advertised
    assert all(p["height"]<=1080 and not p["hardware"] for p in advertised)

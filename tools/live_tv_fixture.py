"""Local-only Android TV UI fixture. Not a production server validation.

Run with --video <short MP4> --output <temporary directory>. Connect the
emulator to 10.0.2.2:18430. Media and watch state are synthetic and isolated.
"""
import argparse
import json
import math
import struct
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    files, items, progress = {}, [], {}
    for number, color in enumerate(('#B95320', '#285B86', '#34795E', '#695083'), 1):
        picture = Image.new('RGB', (1280, 720), color)
        draw = ImageDraw.Draw(picture)
        draw.rectangle((40, 40, 1240, 680), outline='white', width=8)
        draw.text((160, 310), f'PLUXY - PHOTO TEST {number}', fill='white', font_size=58)
        path = args.output / f'photo-{number}.jpg'
        picture.save(path)
        files[f'photo-{number}'] = path
    audio = args.output / 'audio.wav'
    with wave.open(str(audio), 'wb') as output:
        output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        output.writeframes(b''.join(struct.pack('<h', int(500 * math.sin(i * 440 * math.tau / 16000))) for i in range(320000)))
    files.update({'movie': args.video.resolve(), 'episode-1': args.video.resolve(),
                  'episode-2': args.video.resolve(), 'audio': audio, 'audio-2': audio})
    for index, (key, path) in enumerate(files.items()):
        kind = 'photo' if key.startswith('photo') else 'audio' if key.startswith('audio') else 'series' if key.startswith('episode') else 'movie'
        item = dict(id=key, title=f'Pluxy test {key}', path='', container=path.suffix[1:],
                    size=path.stat().st_size, duration=20.0, kind=kind, added_at=1000-index,
                    width=1280, height=720, video_codec='h264')
        if kind == 'photo':
            item.update(folder='Vacances' if index < 2 else 'Famille', thumbnail_url=f'/stream/direct/{key}')
        if kind == 'audio':
            item.update(artist='Pluxy Test', album='Album de contrôle')
        if kind == 'series':
            item.update(series_name='Série de contrôle', series_key='test-series', season=1, episode=int(key[-1]))
        items.append(item)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def json(self, value, status=200):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = urlparse(self.path).path
            if path == '/health':
                return self.json({'app': 'pluxy', 'status': 'ok'})
            if path == '/api/server/info':
                return self.json(dict(app='pluxy', name='Pluxy TEST local', ip='10.0.2.2', port=18430, version='test', platform='android', transcoding_available=False))
            if path == '/api/library/items':
                return self.json(items)
            if path == '/api/library/scan-status':
                return self.json({'scanning': False, 'count': len(items)})
            if path == '/api/settings/client':
                return self.json(dict(buffer=dict(min_buffer_ms=1000, max_buffer_ms=5000, buffer_for_playback_ms=250, buffer_for_playback_after_rebuffer_ms=500, target_buffer_bytes_mb=16, back_buffer_ms=0), subtitles={}, server_name='Pluxy TEST local', server_kind='android', transcoding_available=False))
            if path.startswith('/api/playback/progress/'):
                return self.json(progress.get(path.split('/')[-1], dict(position_ms=0, duration_ms=20000, watched=False)))
            if path.endswith('/metadata'):
                return self.json(dict(title='Média de contrôle', overview='Bibliothèque synthétique réservée au test local.', matched=False))
            if path.startswith('/stream/direct/'):
                source = files.get(path.split('/')[-1])
                if not source:
                    return self.json({'detail': 'Absent'}, 404)
                data = source.read_bytes()
                start, end, status = 0, len(data)-1, 200
                raw = self.headers.get('Range', '')
                if raw.startswith('bytes='):
                    first, last = raw[6:].split('-', 1)
                    start, end, status = int(first or 0), min(int(last or end), end), 206
                self.send_response(status)
                self.send_header('Content-Type', 'image/jpeg' if source.suffix == '.jpg' else 'audio/wav' if source.suffix == '.wav' else 'video/mp4')
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', str(end-start+1))
                if status == 206:
                    self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
                self.end_headers()
                try:
                    self.wfile.write(data[start:end+1])
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            self.json({}, 404)

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            value = json.loads(body or '{}')
            if self.path in ('/api/playback/decide', '/api/playback/diagnose'):
                return self.json(dict(mode='direct_play', delivery='direct', stream_url='/stream/direct/'+value['item_id'], reasons=[]))
            if '/progress/' in self.path:
                progress[self.path.split('/')[-1]] = value
            self.json({})

    ThreadingHTTPServer(('127.0.0.1', 18430), Handler).serve_forever()


if __name__ == '__main__':
    main()

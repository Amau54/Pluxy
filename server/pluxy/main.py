"""Point d'entrée FastAPI : assemble l'API, l'UI de config et la boucle de nettoyage."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from contextlib import suppress
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse

from .api import routes_library, routes_server, routes_settings, routes_stream, routes_profiles
from . import __version__
from .state import AppState
from .security import is_lan_address, is_loopback_request, require_local_admin, trusted_host, validate_browser_origin
from fastapi import HTTPException

BASE_DIR = Path(__file__).resolve().parent.parent      # .../server
WEB_DIR = Path(__file__).resolve().parent / "web"
MAX_REQUEST_BODY = 1_048_576


class _RequestTooLarge(Exception):
    pass


class PreparationActivityMiddleware:
    """Keep background preparation behind active media transfers, including their body."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        state = getattr(getattr(scope.get('app'), 'state', None), 'pluxy', None)
        analyzer = getattr(state, 'segments', None)
        media = (scope.get('type') == 'http' and scope.get('method') == 'GET'
                 and scope.get('path', '').startswith(('/stream/direct/', '/stream/remux/', '/stream/convert/', '/stream/hls/')))
        if not media or not callable(getattr(analyzer, 'playback_started', None)):
            return await self.app(scope, receive, send)
        analyzer.playback_started()
        try:
            await self.app(scope, receive, send)
        finally:
            analyzer.playback_finished()


class RequestBodyLimitMiddleware:
    """Borne aussi les corps HTTP chunked, pas seulement Content-Length."""

    def __init__(self, app, max_bytes: int = MAX_REQUEST_BODY):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: dict, receive: Callable, send: Callable) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        received = 0

        async def limited_receive():
            nonlocal received
            message = await receive()
            if message.get("type") == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _RequestTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except _RequestTooLarge:
            response = JSONResponse(
                status_code=413,
                content={"detail": "Corps de requete trop volumineux."},
                headers={"Connection": "close"},
            )
            await response(scope, receive, send)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.pluxy = AppState(BASE_DIR)

    # Synchronise l'entrée de démarrage Windows avec le réglage persistant.
    try:
        from . import autostart
        autostart.apply(app.state.pluxy.cfgm.cfg.server.autostart)
    except Exception:
        pass

    async def reaper():
        # try/except OBLIGATOIRE : sans lui, une exception (terminate/rmtree) tuerait
        # la boucle -> plus aucune session nettoyée -> fuite GPU permanente.
        while True:
            await asyncio.sleep(30)
            try:
                # terminate/wait et les suppressions de fichiers peuvent prendre
                # plusieurs secondes; les API restent disponibles pendant ce temps.
                await asyncio.to_thread(app.state.pluxy.transcoder.reap_idle)
                await asyncio.to_thread(app.state.pluxy.vod.reap_idle)
            except Exception:
                pass

    task = asyncio.create_task(reaper())
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        await asyncio.to_thread(app.state.pluxy.close)


app = FastAPI(title="Pluxy", version=__version__, lifespan=lifespan)
app.add_middleware(RequestBodyLimitMiddleware)
app.add_middleware(PreparationActivityMiddleware)


@app.middleware("http")
async def trusted_lan_only(request: Request, call_next):
    """Pluxy est ouvert sur le LAN de confiance, jamais sur Internet."""
    peer = request.client.host if request.client else ""
    if not is_lan_address(peer):
        return JSONResponse(status_code=403, content={"detail": "Acces reserve au reseau local."})
    if not trusted_host(request):
        return JSONResponse(status_code=400, content={"detail": "Hote HTTP non autorise"})
    try:
        validate_browser_origin(request)
    except HTTPException as error:
        return JSONResponse(status_code=error.status_code, content={"detail": error.detail})
    raw_length = request.headers.get("content-length")
    if raw_length is not None:
        try:
            content_length = int(raw_length)
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "Content-Length invalide."})
        if content_length < 0:
            return JSONResponse(status_code=400, content={"detail": "Content-Length invalide."})
        if content_length > MAX_REQUEST_BODY:
            return JSONResponse(status_code=413, content={"detail": "Corps de requete trop volumineux."})
    admin_documentation = (
        request.url.path == "/openapi.json"
        or request.url.path.startswith("/docs")
        or request.url.path.startswith("/redoc")
    )
    if admin_documentation and not is_loopback_request(request):
        return JSONResponse(
            status_code=403,
            content={"detail": "Documentation API disponible uniquement sur localhost."},
        )
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    if request.url.path.startswith("/api/settings") or request.url.path in {"/", "/settings.js", "/settings.css", "/desktop-updates.js", "/desktop-updates.css"}:
        response.headers["Cache-Control"] = "no-store"
    content_type = response.headers.get("content-type", "")
    if content_type.startswith("text/html"):
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            "script-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
        )
    return response

app.include_router(routes_settings.router)
app.include_router(routes_library.router)
app.include_router(routes_stream.router)
app.include_router(routes_profiles.router)
app.include_router(routes_server.router)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "app": "pluxy"}


@app.get("/")
def index(request: Request):
    """UI de configuration Web."""
    require_local_admin(request)
    return FileResponse(WEB_DIR / "index.html")


@app.get("/logo.png")
def logo():
    return FileResponse(WEB_DIR / "logo.png", media_type="image/png")


@app.get("/settings.js")
def settings_script():
    return FileResponse(WEB_DIR / "settings.js", media_type="text/javascript")


@app.get("/settings.css")
def settings_style():
    return FileResponse(WEB_DIR / "settings.css", media_type="text/css")


@app.get("/desktop-updates.js")
def desktop_updates_script():
    return FileResponse(WEB_DIR / "desktop-updates.js", media_type="text/javascript")


@app.get("/desktop-updates.css")
def desktop_updates_style():
    return FileResponse(WEB_DIR / "desktop-updates.css", media_type="text/css")


@app.get("/favicon.png")
def favicon():
    return FileResponse(WEB_DIR / "favicon.png", media_type="image/png")


@app.get("/tmdb-logo.svg")
def tmdb_logo():
    return FileResponse(WEB_DIR / "tmdb-logo.svg", media_type="image/svg+xml")

"""Point d'entrée FastAPI : assemble l'API, l'UI de config et la boucle de nettoyage."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from .api import routes_library, routes_server, routes_settings, routes_stream
from .state import AppState

BASE_DIR = Path(__file__).resolve().parent.parent      # .../server
WEB_DIR = Path(__file__).resolve().parent / "web"


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.pluxy = AppState(BASE_DIR)

    async def reaper():
        # try/except OBLIGATOIRE : sans lui, une exception (terminate/rmtree) tuerait
        # la boucle -> plus aucune session nettoyée -> fuite GPU permanente.
        while True:
            await asyncio.sleep(30)
            try:
                app.state.pluxy.transcoder.reap_idle()
                app.state.pluxy.vod.reap_idle()
            except Exception:
                pass

    task = asyncio.create_task(reaper())
    yield
    task.cancel()
    app.state.pluxy.discovery.stop()


app = FastAPI(title="Pluxy", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(routes_settings.router)
app.include_router(routes_library.router)
app.include_router(routes_stream.router)
app.include_router(routes_server.router)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "app": "pluxy"}


@app.get("/")
def index():
    """UI de configuration Web."""
    return FileResponse(WEB_DIR / "index.html")


@app.get("/logo.png")
def logo():
    return FileResponse(WEB_DIR / "logo.png", media_type="image/png")


@app.get("/favicon.png")
def favicon():
    return FileResponse(WEB_DIR / "favicon.png", media_type="image/png")

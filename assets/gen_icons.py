"""
Génère toutes les déclinaisons d'icônes Pluxy À PARTIR du logo source
`assets/pluxy-logo.png` (1024x1024 RGBA, fond transparent).

Sortie :
  assets/pluxy.ico                 (multi-tailles, pour Windows)
  server/pluxy/web/logo.png        (256, UI Web)
  server/pluxy/web/favicon.png     (64)
  client .../res/mipmap-*/ic_launcher(.png/_round.png)  (tuile sombre + logo)
  client .../res/drawable-nodpi/ic_banner.png           (320x180, bannière Android TV)

Dépendances : pillow.  Lancer :  python assets/gen_icons.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
MASTER = ROOT / "assets" / "pluxy-logo.png"


def load_master() -> Image.Image:
    img = Image.open(MASTER).convert("RGBA")
    # Recadre sur le contenu visible puis recentre dans un carré (marge 6 %).
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    side = int(max(img.size) * 1.12)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def logo_at(master: Image.Image, target: int) -> Image.Image:
    return master.resize((target, target), Image.LANCZOS)


def rounded_tile(logo, size, bg=(18, 21, 28, 255), pad_ratio=0.16, radius_ratio=0.22):
    """Tuile sombre arrondie avec le logo centré (style de la planche de marque)."""
    SS = 4
    s = size * SS
    tile = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, s - 1, s - 1], radius=int(s * radius_ratio), fill=255
    )
    base = Image.new("RGBA", (s, s), bg)
    tile.paste(base, (0, 0), mask)
    inner = int(s * (1 - pad_ratio * 2))
    lg = logo.resize((inner, inner), Image.LANCZOS)
    off = (s - inner) // 2
    tile.paste(lg, (off, off), lg)
    return tile.resize((size, size), Image.LANCZOS)


def main():
    assets = ROOT / "assets"
    web = ROOT / "server" / "pluxy" / "web"
    res = ROOT / "client" / "app" / "src" / "main" / "res"

    master = load_master()
    print("master:", master.size)

    # ICO Windows
    logo_at(master, 256).save(
        assets / "pluxy.ico",
        sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    print("OK assets/pluxy.ico")

    # Web
    logo_at(master, 256).save(web / "logo.png")
    logo_at(master, 64).save(web / "favicon.png")
    print("OK web/logo.png + favicon.png")

    # Android launcher icons (tuile sombre arrondie)
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in dens.items():
        folder = res / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        tile = rounded_tile(master, px)
        tile.save(folder / "ic_launcher.png")
        tile.save(folder / "ic_launcher_round.png")
    print("OK mipmap-*/ic_launcher(.png/_round.png)")

    # Bannière Android TV 320x180 (fond sombre + logo + "Pluxy")
    SS = 4
    banner = Image.new("RGBA", (320 * SS, 180 * SS), (13, 15, 20, 255))
    lg = logo_at(master, 150 * SS)
    banner.paste(lg, (24 * SS, 15 * SS), lg)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 64 * SS)
    except Exception:
        font = ImageFont.load_default()
    ImageDraw.Draw(banner).text(
        (182 * SS, 70 * SS), "Pluxy", font=font, fill=(255, 255, 255, 255)
    )
    bdir = res / "drawable-nodpi"
    bdir.mkdir(parents=True, exist_ok=True)
    banner.resize((320, 180), Image.LANCZOS).save(bdir / "ic_banner.png")
    print("OK drawable-nodpi/ic_banner.png")


if __name__ == "__main__":
    main()

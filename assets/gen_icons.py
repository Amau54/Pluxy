"""
Génère toutes les déclinaisons du logo Pluxy à partir d'un tracé vectoriel
(chevron "fast play" + lignes de vitesse, dégradé orange -> magenta).

Sortie :
  assets/pluxy-logo.png            (1024, fond transparent)
  assets/pluxy.ico                 (multi-tailles, pour Windows)
  server/pluxy/web/logo.png        (256, UI Web)
  server/pluxy/web/favicon.png     (64)
  client .../res/mipmap-*/ic_launcher.png   (tuile sombre + logo)
  client .../res/drawable-nodpi/ic_banner.png (320x180, bannière Android TV)

Dépendances : pillow. Lancer :  python assets/gen_icons.py
"""
from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SS = 4                      # supersampling
BASE = 1024                 # canvas logique

# Palette du dégradé (haut -> bas)
C_TOP = (255, 140, 30)      # orange
C_MID = (255, 60, 45)       # rouge
C_BOT = (224, 21, 123)      # magenta


def _lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size):
    """Dégradé diagonal orange->rouge->magenta sur une image RGB."""
    w = h = size
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            # t mêle vertical (dominant) et un peu d'horizontal pour la diagonale.
            t = (0.80 * y + 0.20 * (w - x)) / max(h, w)
            t = min(1.0, max(0.0, t))
            if t < 0.42:
                px[x, y] = _lerp(C_TOP, C_MID, t / 0.42)
            else:
                px[x, y] = _lerp(C_MID, C_BOT, min(1.0, (t - 0.42) / 0.46))
    return img


def capsule(d, x0, x1, y, h):
    """Ligne de vitesse à bouts ronds."""
    d.rounded_rectangle([x0, y - h / 2, x1, y + h / 2], radius=h / 2, fill=255)


def build_mask(size):
    """Masque (L) du logo dessiné à l'échelle `size`."""
    s = size / BASE
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)

    def S(v):
        return v * s

    # --- Chevron "fast play" (pointe à droite, encoche à gauche) ---
    A = (S(470), S(255))
    B = (S(822), S(512))
    C = (S(470), S(770))
    D = (S(575), S(512))
    # Chevron rempli + contour à joints ronds pour adoucir les angles
    # (sans cercles débordants aux sommets).
    d.polygon([A, B, C, D], fill=255)
    d.line([A, B, C, D, A], fill=255, width=int(S(46)), joint="curve")

    # --- Lignes de vitesse (capsules) + points ---
    H = S(44)
    capsule(d, S(360), S(470), S(330), H)
    capsule(d, S(250), S(520), S(405), H)
    capsule(d, S(305), S(565), S(470), H)
    capsule(d, S(250), S(545), S(540), H)
    capsule(d, S(360), S(475), S(615), H)
    # petits points
    rr = S(22)
    for (cx, cy) in ((S(195), S(405)), (S(250), S(330)), (S(195), S(540))):
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=255)

    return m


def render_logo(target):
    """Logo dégradé sur fond transparent, anti-aliasé, à la taille `target`."""
    size = target * SS
    mask = build_mask(size)
    grad = gradient(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out.resize((target, target), Image.LANCZOS)


def rounded_tile(logo, size, bg=(18, 21, 28, 255), pad_ratio=0.16, radius_ratio=0.22):
    """Tuile sombre arrondie avec le logo centré (style de la planche de marque)."""
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255
    )
    base = Image.new("RGBA", (size, size), bg)
    tile.paste(base, (0, 0), mask)
    inner = int(size * (1 - pad_ratio * 2))
    lg = logo.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    tile.paste(lg, (off, off), lg)
    return tile


def main():
    assets = ROOT / "assets"
    web = ROOT / "server" / "pluxy" / "web"
    res = ROOT / "client" / "app" / "src" / "main" / "res"
    assets.mkdir(exist_ok=True)

    master = render_logo(1024)
    master.save(assets / "pluxy-logo.png")
    print("OK assets/pluxy-logo.png")

    # ICO Windows
    master.save(assets / "pluxy.ico",
                sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("OK assets/pluxy.ico")

    # Web
    render_logo(256).save(web / "logo.png")
    render_logo(64).save(web / "favicon.png")
    print("OK web/logo.png + favicon.png")

    # Android launcher icons (tuile sombre arrondie)
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in dens.items():
        folder = res / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        tile = rounded_tile(render_logo(px * SS), px * SS).resize((px, px), Image.LANCZOS)
        tile.save(folder / "ic_launcher.png")
        tile.save(folder / "ic_launcher_round.png")
    print("OK mipmap-*/ic_launcher(.png/_round.png)")

    # Bannière Android TV 320x180 (fond sombre + logo + "Pluxy")
    banner = Image.new("RGBA", (320 * SS, 180 * SS), (13, 15, 20, 255))
    lg = render_logo(150 * SS)
    banner.paste(lg, (24 * SS, 15 * SS), lg)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 64 * SS)
    except Exception:
        font = ImageFont.load_default()
    ImageDraw.Draw(banner).text((176 * SS, 74 * SS), "Pluxy", font=font, fill=(255, 255, 255, 255))
    bdir = res / "drawable-nodpi"
    bdir.mkdir(parents=True, exist_ok=True)
    banner.resize((320, 180), Image.LANCZOS).save(bdir / "ic_banner.png")
    print("OK drawable-nodpi/ic_banner.png")


if __name__ == "__main__":
    main()

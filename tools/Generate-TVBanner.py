"""Compose the TV wordmark inside a centered safe area, preserving the original logo."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "client/app/src/main/res/drawable-nodpi"
SCALE = 4


def main():
    font_path = next(path for path in (
        Path("C:/Windows/Fonts/segoeuib.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    ) if path.is_file())
    canvas = Image.new("RGBA", (320 * SCALE, 180 * SCALE), "#0d1014")
    logo = Image.open(RES / "pluxy_logo.png").convert("RGBA")
    logo = logo.crop(logo.getchannel("R").point(lambda value: 255 if value > 45 else 0).getbbox())
    ratio = 64 * SCALE / max(logo.size)
    logo = logo.resize((round(logo.width * ratio), round(logo.height * ratio)), Image.Resampling.LANCZOS)
    font = ImageFont.truetype(str(font_path), 42 * SCALE)
    draw = ImageDraw.Draw(canvas)
    bounds = draw.textbbox((0, 0), "Pluxy", font=font)
    text_width, text_height = bounds[2] - bounds[0], bounds[3] - bounds[1]
    width = logo.width + 14 * SCALE + text_width
    left = (canvas.width - width) // 2
    assert left >= 32 * SCALE  # Room for launcher rounding, zoom and overscan.
    canvas.alpha_composite(logo, (left, (canvas.height - logo.height) // 2))
    draw.text((left + logo.width + 14 * SCALE - bounds[0],
               (canvas.height - text_height) // 2 - bounds[1]), "Pluxy", font=font, fill="#f4f5fa")
    canvas.convert("RGB").resize((320, 180), Image.Resampling.LANCZOS).save(RES / "ic_banner.png")


if __name__ == "__main__":
    main()

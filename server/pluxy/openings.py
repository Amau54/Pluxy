"""Conservative recognition of opening text cards, independent of chapters."""
from dataclasses import dataclass


@dataclass(frozen=True)
class OpeningFrame:
    text: bool = False
    black: bool = False
    signature: frozenset[int] = frozenset()


def opening_frame(frame: bytes) -> OpeningFrame:
    if len(frame) != 128 * 72:
        return OpeningFrame()
    # Downscaled antialiased lettering is often grey rather than white.
    bright = {i for i, value in enumerate(frame) if value > 80}
    dark = sum(value < 44 for value in frame) / len(frame)
    black = dark >= .98 and len(bright) < 10
    if dark < .80 or not 55 <= len(bright) <= 1658:
        return OpeningFrame(black=black)
    # Subtitles are confined to the bottom; opening cards occupy the picture.
    if sum(i < 128 * 54 for i in bright) < len(bright) * .65:
        return OpeningFrame()
    rows = [sum(y * 128 + x in bright for x in range(128)) >= 3 for y in range(72)]
    if sum(active and (i == 0 or not rows[i - 1]) for i, active in enumerate(rows)) < 2:
        return OpeningFrame()
    # Count small connected glyphs. Landscapes, a logo or a bright rectangle alone
    # must not be enough to identify a credit card.
    remaining = set(bright)
    glyphs = 0
    while remaining:
        seed = remaining.pop()
        stack = [seed]
        pixels = []
        while stack:
            pixel = stack.pop()
            pixels.append(pixel)
            x, y = pixel % 128, pixel // 128
            for neighbor in ((pixel - 1 if x else -1), (pixel + 1 if x < 127 else -1),
                             (pixel - 128 if y else -1), (pixel + 128 if y < 71 else -1)):
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    stack.append(neighbor)
        width = max(p % 128 for p in pixels) - min(p % 128 for p in pixels) + 1
        height = max(p // 128 for p in pixels) - min(p // 128 for p in pixels) + 1
        if len(pixels) >= 2 and width <= 24 and 2 <= height <= 14:
            glyphs += 1
    if glyphs < 12:
        return OpeningFrame()
    return OpeningFrame(text=True, signature=frozenset((i // 128 // 2) * 64 + i % 128 // 2 for i in bright))


def opening_interval(frames: list[OpeningFrame], step: int = 2) -> tuple[int, int] | None:
    """Three sustained, distinct text cards, bounded by black, with no scene inside.

    This intentionally leaves opening credits over live action untouched. The end
    stays on the last recognised text frame, before any following narrative.
    """
    start = -1
    last = -1
    signatures: list[frozenset[int]] = []
    counts: list[int] = []
    text_count = 0
    for index, frame in enumerate(frames):
        if frame.text:
            if start < 0:
                if index == 0 or not frames[index - 1].black:
                    continue
                start = index
            if (index - start) * step > 180:
                start = -1
                signatures, counts, text_count = [], [], 0
                continue
            last = index
            text_count += 1
            match = next((i for i, signature in enumerate(signatures)
                          if len(signature ^ frame.signature) <= 12), None)
            if match is None:
                signatures.append(frame.signature)
                counts.append(1)
            else:
                counts[match] += 1
        elif start >= 0:
            if frame.black and (index - last) * step <= 4:
                # Every accepted card must have been visible in two samples.
                if ((last - start) * step >= 20 and text_count / (last - start + 1) >= .65
                        and sum(count >= 2 for count in counts) >= 3):
                    return start * step, last * step
                continue
            start = -1
            signatures, counts, text_count = [], [], 0
    return None

"""Local overlapping spectral fingerprints; timestamps are in seconds."""
from array import array
from collections import Counter, deque
import cmath
from dataclasses import dataclass
import math

RATE = 8000
SIZE = 512
WINDOW = 16  # 1.024 seconds, averaged rather than four isolated audio fragments.
STRIDE = 4
STEP = SIZE * STRIDE / RATE
SPAN = SIZE * WINDOW / RATE
_REVERSE = tuple(int(f"{i:09b}"[::-1], 2) for i in range(SIZE))
_HANN = tuple(.5 - .5 * math.cos(2 * math.pi * i / (SIZE - 1)) for i in range(SIZE))
_ROOTS = {size: tuple(cmath.exp(-2j * math.pi * i / size) for i in range(size // 2))
          for size in (2, 4, 8, 16, 32, 64, 128, 256, 512)}
_BANDS = tuple((round((100 + i * 100) * SIZE / RATE), round((200 + i * 100) * SIZE / RATE)) for i in range(32))


def fingerprints(raw: bytes) -> list[int]:
    samples = array("h", raw[:len(raw) - len(raw) % 2])
    history = deque()
    totals = [0.0] * 32
    powers = deque()
    total_power = 0.0
    result = []
    for index, offset in enumerate(range(0, len(samples) - SIZE + 1, SIZE)):
        values = [complex(samples[offset + i] * _HANN[i]) for i in _REVERSE]
        for size, roots in _ROOTS.items():
            half = size // 2
            for base in range(0, SIZE, size):
                for j, root in enumerate(roots):
                    even = values[base + j]
                    odd = values[base + j + half] * root
                    values[base + j], values[base + j + half] = even + odd, even - odd
        spectrum = [value.real * value.real + value.imag * value.imag for value in values[:SIZE // 2]]
        energies = [sum(spectrum[a:b]) / (b - a) for a, b in _BANDS]
        power = sum(value * value for value in samples[offset:offset + SIZE]) / SIZE
        history.append(energies)
        powers.append(power)
        total_power += power
        for band, value in enumerate(energies):
            totals[band] += value
        if len(history) > WINDOW:
            total_power -= powers.popleft()
            for band, value in enumerate(history.popleft()):
                totals[band] -= value
        if len(history) == WINDOW and (index + 1 - WINDOW) % STRIDE == 0:
            if total_power / WINDOW < 36:
                result.append(-1)
            else:
                floor = max(totals) * .0001
                result.append(sum(1 << band for band in range(31)
                                  if totals[band + 1] > totals[band] * 1.05 and totals[band + 1] > floor))
    return result


@dataclass(frozen=True)
class AudioMatch:
    start: float
    end: float
    other_start: float
    distance: float
    coverage: float


def match(left: list[int], right: list[int], step: float = STEP, span: float = SPAN) -> AudioMatch | None:
    if not left or not right:
        return None
    # Vote for temporal alignments first, then validate sustained sequences. Small
    # local errors cannot reset an otherwise convincing match to one second.
    votes = Counter()
    for i in range(0, len(left), 3):
        value = left[i]
        if value < 0:
            continue
        for j, other in enumerate(right):
            if other >= 0 and (value ^ other).bit_count() <= 4:
                votes[j - i] += 1
    best = None
    for lag in sorted(votes, key=lambda value: (-votes[value], value))[:16]:
        first, stop = max(0, -lag), min(len(left), len(right) - lag)
        start, last, gaps = -1, -1, 0
        distances = []

        def candidate():
            nonlocal best
            if start < 0 or last < start:
                return
            count = last - start + 1
            duration = count * step
            portion = distances[:count]
            good = sum(distance <= 7 for distance in portion) / count
            mean = sum(portion) / count
            if not 8 <= duration <= 180 or duration >= min(len(left), len(right)) * step * .65:
                return
            if last >= stop - 2 or good < .85 or mean > 5:
                return
            if len(set(left[start:last + 1])) < max(8, min(24, count // 3)):
                return
            result = AudioMatch(start * step + span, last * step, (start + lag) * step + span, mean, good)
            if result.end - result.start < 6:
                return
            if best is None or result.end - result.start > best.end - best.start:
                best = result

        for i in range(first, stop):
            a, b = left[i], right[i + lag]
            distance = (a ^ b).bit_count() if a >= 0 and b >= 0 else 31
            if distance <= 7:
                if start < 0:
                    start = i
                distances.append(distance)
                last, gaps = i, 0
            elif start >= 0:
                gaps += 1
                distances.append(distance)
                if gaps * step > .8:
                    candidate()
                    start, distances, gaps = -1, [], 0
        candidate()
    return best

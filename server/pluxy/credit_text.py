"""Offline OCR decisions shared by the movie opening and animated closing credits."""
from dataclasses import dataclass
import re
import unicodedata

_ROLES = {
    'production': r'\b(produced by|produit par|producer|producers|producteur|producteurs|production)\b',
    'direction': r'\b(directed by|realise par|un film de|director|directeur|realisation|realisateur)\b',
    'writing': r'\b(written by|ecrit par|screenplay|scenario|scenariste)\b',
    'music': r'\b(music|musique|composer|compositeur)\b',
    'editing': r'\b(edited by|editor|editing|montage|monteur)\b',
    'casting': r'\b(cast|casting|distribution|starring)\b',
    'camera': r'\b(photography|camera|cinematography|cinematographer|operateur)\b',
    'design': r'\b(costume|costumes|designer|decors)\b',
    'effects': r'\b(visual effects|special effects|vfx|effets)\b',
    'sound': r'\b(sound|audio|mixing|mixage)\b',
    'crew': r'\b(assistant|assistants|coordinator|supervisor|department|technician|technicians|artist|artists|animation)\b',
}


@dataclass(frozen=True)
class CreditText:
    roles: frozenset[str] = frozenset()
    name_card: bool = False
    dense: bool = False
    signature: str = ''
    blank: bool = False
    roster: bool = False


def summarize(lines: list[str], blank: bool = False) -> CreditText:
    text = ' '.join(lines)
    normalized = ''.join(c for c in unicodedata.normalize('NFD', text) if not unicodedata.combining(c)).lower()
    roles = frozenset(role for role, pattern in _ROLES.items() if re.search(pattern, normalized))
    letters = [c for c in text if c.isalpha()]
    words = re.findall(r'[a-zA-ZÀ-ÿ]+', text)
    capitals = [[c for c in line if c.isalpha()] for line in lines if len(re.findall(r'[a-zA-ZÀ-ÿ]+', line)) >= 2]
    named_line = any(len(row) >= 8 and sum(c.isupper() for c in row) / len(row) >= .85 for row in capitals)
    name = (1 <= len(lines) <= 8 and 2 <= len(words) <= 40 and len(letters) >= 8
            and (sum(c.isupper() for c in letters) / len(letters) >= .65 or named_line))
    roster = (len(lines) >= 8 and len(words) >= 16 and len(letters) >= 30
              and sum(c.isupper() for c in letters) / len(letters) >= .65
              and sum(bool(re.search(r'[.!?,:;]', line)) for line in lines) <= len(lines) * .2)
    return CreditText(roles, name, len(roles) >= 2 and len(lines) >= 4, normalized, blank, roster)


def opening_text_interval(samples: list[tuple[float, CreditText]]) -> tuple[float, float] | None:
    group = []
    gaps = 0
    for timestamp, text in samples + [(float('inf'), CreditText())] * 2:
        if text.name_card or text.dense:
            group.append((timestamp, text))
            gaps = 0
        else:
            gaps += 1
            if gaps < 2:
                continue
            roles = set().union(*(value.roles for _, value in group))
            distinct = {value.signature for _, value in group if value.roles}
            if group and len(roles) >= 3 and len(distinct) >= 3:
                start = group[0][0] + 1
                end = max(time for time, value in group if value.roles)
                if 20 <= end - start <= 240:
                    return start, end
            group = []
    return None

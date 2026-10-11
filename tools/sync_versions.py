"""Synchronise les versions publiées et leur présentation, ou vérifie leur cohérence."""
import argparse
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
values = json.loads((root / "versions.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
replacements = {
    "client/app/build.gradle.kts": [(r'versionCode = \d+', f'versionCode = {values["android"]["code"]}'),
                                    (r'versionName = "[^"]+"', f'versionName = "{values["android"]["name"]}"')],
    "desktop/installer.iss": [(r'#define AppVersion "[^"]+"', f'#define AppVersion "{values["windows"]["name"]}"')],
    "desktop/Program.cs": [(r'AssemblyVersion\("[^"]+"\)', f'AssemblyVersion("{values["windows"]["name"]}.0")')],
    "README.md": [(r'\*\*Android [^\n]+\*\*', f'**Android {values["android"]["name"]} · Windows {values["windows"]["name"]}**')],
}
stale = []
for name, patterns in replacements.items():
    path = root / name
    text = path.read_text(encoding="utf-8")
    updated = text
    for pattern, replacement in patterns:
        updated, count = re.subn(pattern, replacement, updated)
        if count != 1:
            raise SystemExit(f"Version introuvable ou ambiguë : {name}")
    if updated != text:
        stale.append(name)
        if not args.check:
            path.write_text(updated, encoding="utf-8")
if args.check and stale:
    raise SystemExit("Versions incohérentes : " + ", ".join(stale))
print("Versions cohérentes" if args.check else "Versions synchronisées")

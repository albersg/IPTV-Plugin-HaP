#!/usr/bin/env python3
"""Write the CycloneDX SBOM of the binaries committed verbatim to this repository.

HaP ships two kinds of binary it does not build: the embedded kubo executable and a tiny launcher
that runs the AceStream Python engine against the interpreter the device firmware provides. Neither
Gradle nor the native build knows about them, so an APK can change without a source change and
nothing records what went in.

This script hashes those files and writes `docs/supply-chain/vendor-sbom.json`. It deliberately
records no timestamp, so the file only changes when a vendored binary changes, and `--check` can be a
CI gate.

What it does not claim: the AceStream engine and the Python interpreter are not in this repository -
they come from the device firmware - so they cannot be hashed here. `docs/supply-chain/README.md`
states that explicitly instead of implying the APK is fully pinned.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/supply-chain/vendor-sbom.json"

VENDORED_GLOBS = ("app/src/main/jniLibs/*/*.so", "app/src/main/assets/aceserve/*")


def app_version() -> str:
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return match.group(1) if match else "unknown"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def kind_for(path: Path) -> tuple[str, str]:
    """(CycloneDX component type, human note) for one vendored file."""
    name = path.name
    if name == "libipfs.so":
        return "application", "kubo (Go, built as a shared-library-named executable)"
    if name == "libacepython.so":
        return "library", "launcher for the device-provided AceStream Python engine"
    if path.suffix == ".py":
        return "data", "AceStream engine script, run by the device interpreter"
    return "library", "vendored binary"


def collect() -> dict:
    components = []
    for pattern in VENDORED_GLOBS:
        for path in sorted(ROOT.glob(pattern)):
            if not path.is_file():
                continue
            relative = path.relative_to(ROOT).as_posix()
            component_type, note = kind_for(path)
            parts = path.parts
            arch = parts[parts.index("jniLibs") + 1] if "jniLibs" in parts else "any"
            components.append(
                {
                    "type": component_type,
                    "bom-ref": relative,
                    "name": path.name,
                    "version": "unversioned",
                    "hashes": [{"alg": "SHA-256", "content": sha256(path)}],
                    "properties": [
                        {"name": "hap:path", "value": relative},
                        {"name": "hap:architecture", "value": arch},
                        {"name": "hap:size", "value": str(path.stat().st_size)},
                        {"name": "hap:note", "value": note},
                    ],
                }
            )
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "name": "hap",
                "version": app_version(),
            }
        },
        "components": components,
    }


def main(argv: list[str]) -> int:
    document = collect()
    text = json.dumps(document, indent=2, sort_keys=True) + "\n"
    if "--check" in argv:
        if not OUTPUT.exists():
            print(f"missing {OUTPUT.relative_to(ROOT)}; run tools/vendor-sbom.py")
            return 1
        if OUTPUT.read_text(encoding="utf-8") != text:
            print("vendor SBOM is stale: a vendored binary changed without regenerating it.")
            print("Run tools/vendor-sbom.py and commit docs/supply-chain/vendor-sbom.json.")
            return 1
        print(f"vendor SBOM matches {len(document['components'])} vendored files")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(ROOT)} with {len(document['components'])} components")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

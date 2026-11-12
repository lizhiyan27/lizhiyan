#!/usr/bin/env python3
"""Package the signed APK, source, REA evidence and Chinese documentation."""
from __future__ import annotations

import hashlib
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
APK_SOURCE = ROOT / "app" / "app" / "build" / "outputs" / "apk" / "release" / "LocationShadow-1.2.0-release.apk"
APK_TARGET = DIST / "LocationShadow-1.2.0.apk"
SOURCE_ZIP = DIST / "LocationShadow-source-and-analysis.zip"
EXCLUDED_PARTS = {"build", ".gradle", "signing", ".git", "tools", "reference", "dist"}
EXCLUDED_NAMES = {"local.properties", "keystore.properties", "osmdroid-classes.jar"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def included(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    return not (set(relative.parts) & EXCLUDED_PARTS) and path.name not in EXCLUDED_NAMES


def main() -> None:
    if not APK_SOURCE.is_file():
        raise SystemExit(f"Release APK not found: {APK_SOURCE}")
    DIST.mkdir(exist_ok=True)
    shutil.copy2(APK_SOURCE, APK_TARGET)
    for name in ("REVERSE_ANALYSIS.md", "USAGE.md", "FEATURE_MATRIX.md", "VALIDATION.md", "UPDATE_1.1.0.md", "UPDATE_1.2.0.md"):
        source = ROOT / "docs" / name
        if source.is_file():
            shutil.copy2(source, DIST / name)

    with zipfile.ZipFile(SOURCE_ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for base in (ROOT / "app", ROOT / "docs", ROOT / "analysis", ROOT / "scripts"):
            if not base.exists():
                continue
            for path in sorted(base.rglob("*")):
                if path.is_file() and included(path):
                    archive.write(path, Path("LocationShadow") / path.relative_to(ROOT))

    outputs = [APK_TARGET, SOURCE_ZIP]
    outputs.extend(sorted(DIST.glob("*.md")))
    checksums = "\n".join(f"{sha256(path)}  {path.name}" for path in outputs) + "\n"
    (DIST / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    for path in outputs:
        print(f"{path.name}\t{path.stat().st_size}\t{sha256(path)}")


if __name__ == "__main__":
    main()

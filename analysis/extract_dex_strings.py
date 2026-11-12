#!/usr/bin/env python3
"""Extract DEX string_data entries without executing the target APK."""
from __future__ import annotations

import argparse
import json
import struct
import zipfile
from pathlib import Path


def uleb128(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
        if shift > 35:
            raise ValueError("Invalid ULEB128 value")


def extract(data: bytes) -> list[str]:
    if data[:4] != b"dex\n":
        raise ValueError("Not a DEX file")
    string_count, string_ids = struct.unpack_from("<II", data, 0x38)
    result: list[str] = []
    for index in range(string_count):
        (offset,) = struct.unpack_from("<I", data, string_ids + index * 4)
        _, cursor = uleb128(data, offset)
        end = data.find(b"\0", cursor)
        if end < 0:
            raise ValueError(f"Unterminated string at {offset}")
        result.append(data[cursor:end].decode("utf-8", errors="replace"))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    strings: dict[str, list[str]] = {}
    with zipfile.ZipFile(args.apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                strings[name] = extract(archive.read(name))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(strings, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({name: len(items) for name, items in strings.items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()

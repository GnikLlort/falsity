#!/usr/bin/env python3
"""Static checks that catch broken resources without launching Minecraft.

Checks that:
  * every JSON resource parses;
  * fabric.mod.json names entrypoint classes that actually exist;
  * entity textures exist, are PNGs and match the declared atlas size;
  * every UV rectangle in the generated model geometry fits inside the texture;
  * every translation key used in code is present in the English language file.

Run from the repository root: python3 tools/validate_assets.py
"""

from __future__ import annotations

import json
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN_RESOURCES = ROOT / "src" / "main" / "resources"
CLIENT_JAVA = ROOT / "src" / "client" / "java"
MAIN_JAVA = ROOT / "src" / "main" / "java"

errors: list[str] = []
warnings: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def check_json_resources() -> None:
    for path in sorted((ROOT / "src").rglob("*.json")):
        if "build" in path.parts or ".gradle" in path.parts:
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exception:
            fail(f"{path.relative_to(ROOT)}: invalid JSON ({exception})")


def read_png_size(path: Path) -> tuple[int, int] | None:
    try:
        with path.open("rb") as handle:
            header = handle.read(24)
    except OSError as exception:
        fail(f"{path.relative_to(ROOT)}: cannot be read ({exception})")
        return None

    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"{path.relative_to(ROOT)}: not a PNG file")
        return None

    width, height = struct.unpack(">II", header[16:24])
    return width, height


def check_fabric_metadata() -> None:
    metadata_path = MAIN_RESOURCES / "fabric.mod.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

    for key in ("id", "version", "name", "entrypoints", "depends"):
        if key not in metadata:
            fail(f"fabric.mod.json is missing '{key}'")

    if metadata.get("id") != "falsity":
        fail(f"fabric.mod.json id should be 'falsity', found {metadata.get('id')!r}")

    if metadata.get("license") != "MIT":
        warn("fabric.mod.json license does not match the LICENSE file")

    dependencies = metadata.get("depends", {})
    for required in ("minecraft", "fabricloader", "java", "fabric-api"):
        if required not in dependencies:
            fail(f"fabric.mod.json should declare a dependency on '{required}'")

    for environment, entrypoints in metadata.get("entrypoints", {}).items():
        for entrypoint in entrypoints:
            relative = entrypoint.replace(".", "/") + ".java"
            java_roots = [MAIN_JAVA, CLIENT_JAVA]
            if not any((root / relative).exists() for root in java_roots):
                fail(f"entrypoint {entrypoint} ({environment}) has no source file at {relative}")


def check_textures() -> None:
    geometry = (CLIENT_JAVA / "com" / "falsity" / "client" / "model" / "FalsityModelGeometry.java").read_text(
        encoding="utf-8"
    )
    texture_width = int(re.search(r"TEXTURE_WIDTH = (\d+);", geometry).group(1))
    texture_height = int(re.search(r"TEXTURE_HEIGHT = (\d+);", geometry).group(1))

    texture = MAIN_RESOURCES / "assets" / "falsity" / "textures" / "entity" / "falsity" / "falsity.png"
    size = read_png_size(texture)
    if size is None:
        return

    if size != (texture_width, texture_height):
        fail(
            f"entity texture is {size[0]}x{size[1]} but the model expects "
            f"{texture_width}x{texture_height}"
        )

    # Every box strip must fit inside the atlas.
    for match in re.finditer(
        r"texOffs\((\d+), (\d+)\)\.addBox\((-?[\d.]+)F, (-?[\d.]+)F, (-?[\d.]+)F, (\d+), (\d+), (\d+)\)",
        geometry,
    ):
        u, v, _x, _y, _z, width, height, depth = (int(group) if index < 2 or index >= 5 else float(group)
                                                  for index, group in enumerate(match.groups()))
        strip_width = 2 * (width + depth)
        strip_height = depth + height
        if u + strip_width > texture_width or v + strip_height > texture_height:
            fail(
                f"UV rectangle at ({u},{v}) size {strip_width}x{strip_height} overflows the "
                f"{texture_width}x{texture_height} texture"
            )

    for relative, expected in (
        ("assets/falsity/textures/item/falsity_spawn_egg.png", (16, 16)),
        ("assets/falsity/icon.png", (128, 128)),
    ):
        path = MAIN_RESOURCES / relative
        if not path.exists():
            fail(f"missing texture {relative}")
            continue
        actual = read_png_size(path)
        if actual is not None and actual != expected:
            warn(f"{relative} is {actual[0]}x{actual[1]}, expected {expected[0]}x{expected[1]}")


def check_item_models() -> None:
    egg_model = MAIN_RESOURCES / "assets" / "falsity" / "models" / "item" / "falsity_spawn_egg.json"
    egg_item = MAIN_RESOURCES / "assets" / "falsity" / "items" / "falsity_spawn_egg.json"

    for path in (egg_model, egg_item):
        if not path.exists():
            fail(f"missing item resource {path.relative_to(ROOT)}")

    if egg_model.exists():
        model = json.loads(egg_model.read_text(encoding="utf-8"))
        layer = model.get("textures", {}).get("layer0", "")
        texture_path = MAIN_RESOURCES / "assets" / layer.split(":", 1)[0] / "textures" / (
            layer.split(":", 1)[1] + ".png"
        )
        if layer and not texture_path.exists():
            fail(f"spawn egg model points at a missing texture: {layer}")


def check_translations() -> None:
    lang_path = MAIN_RESOURCES / "assets" / "falsity" / "lang" / "en_us.json"
    lang = json.loads(lang_path.read_text(encoding="utf-8"))

    for java_root in (MAIN_JAVA, CLIENT_JAVA):
        for path in java_root.rglob("*.java"):
            source = path.read_text(encoding="utf-8")
            for key in re.findall(r'Component\.translatable\(\s*"([^"]+)"', source):
                if key not in lang:
                    fail(f"{path.relative_to(ROOT)}: translation key '{key}' is missing from en_us.json")
            for key in re.findall(r'"message\.falsity\.[a-z_]+"', source):
                unused = key.strip('"')
                if unused not in lang:
                    fail(f"{path.relative_to(ROOT)}: translation key '{unused}' is missing from en_us.json")

    # The entity and spawn egg names are looked up by the game, not by our code.
    for required in ("entity.falsity.falsity", "item.falsity.falsity_spawn_egg"):
        if required not in lang:
            fail(f"en_us.json is missing '{required}'")


def main() -> int:
    check_json_resources()
    check_fabric_metadata()
    check_textures()
    check_item_models()
    check_translations()

    for message in warnings:
        print(f"warning: {message}")

    if errors:
        for message in errors:
            print(f"error: {message}")
        print(f"\n{len(errors)} asset problem(s) found.")
        return 1

    print("All asset checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

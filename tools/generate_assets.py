#!/usr/bin/env python3
"""Generate Falsity's entity geometry and textures from one design file.

`tools/model/falsity_model.json` is the single source of truth for the first
form's boxes. This script packs those boxes into a texture atlas, paints the
texture, writes the matching Java model geometry, and produces the mod's icon
and spawn-egg sprite.

Because UV coordinates are computed here and then baked into both the Java
geometry and the painted texture, the model and its art cannot drift apart.

Usage:
    python3 tools/generate_assets.py            # write generated files
    python3 tools/generate_assets.py --check     # fail if files are stale
    python3 tools/generate_assets.py --preview   # also write a PNG preview

Requires Pillow (`pip install pillow`).
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from dataclasses import dataclass, field
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - developer convenience only
    sys.exit("Pillow is required: python3 -m pip install pillow")

ROOT = Path(__file__).resolve().parent.parent
MODEL_JSON = ROOT / "tools" / "model" / "falsity_model.json"
JAVA_OUT = ROOT / "src" / "client" / "java"
TEXTURE_OUT = ROOT / "src" / "main" / "resources" / "assets" / "falsity" / "textures"
ICON_OUT = ROOT / "src" / "main" / "resources" / "assets" / "falsity" / "icon.png"
PREVIEW_OUT = ROOT / "docs" / "images" / "falsity_model_preview.png"

PADDING = 1


# --------------------------------------------------------------------------
# Design model
# --------------------------------------------------------------------------


@dataclass
class Box:
    name: str
    origin: tuple[float, float, float]
    size: tuple[int, int, int]
    material: str
    tip_material: str | None = None
    front_decal: str | None = None
    uv: tuple[int, int] = (0, 0)

    @property
    def strip_size(self) -> tuple[int, int]:
        w, h, d = self.size
        return 2 * (w + d), d + h


@dataclass
class Part:
    name: str
    parent: str | None
    pivot: tuple[float, float, float]
    rotation: tuple[float, float, float]
    role: str = ""
    boxes: list[Box] = field(default_factory=list)


def load_design() -> tuple[dict, list[Part]]:
    design = json.loads(MODEL_JSON.read_text(encoding="utf-8"))
    parts: list[Part] = []
    for raw_part in design["parts"]:
        part = Part(
            name=raw_part["name"],
            parent=raw_part["parent"],
            pivot=tuple(raw_part["pivot"]),
            rotation=tuple(raw_part.get("rotation", [0, 0, 0])),
            role=raw_part.get("role", ""),
        )
        for raw_box in raw_part["boxes"]:
            part.boxes.append(
                Box(
                    name=raw_box["name"],
                    origin=tuple(raw_box["origin"]),
                    size=tuple(raw_box["size"]),
                    material=raw_box["material"],
                    tip_material=raw_box.get("tip_material"),
                    front_decal=raw_box.get("front_decal"),
                )
            )
        parts.append(part)
    return design, parts


# --------------------------------------------------------------------------
# Texture packing
# --------------------------------------------------------------------------


def pack_uvs(parts: list[Part], width: int, height: int) -> None:
    """Shelf-pack every box into the atlas, writing each box's UV offset.

    Boxes are packed tallest first, which keeps the shelves short and tight.
    The order is fully determined by box size and name, so packing is stable
    across runs and machines.
    """
    boxes = [box for part in parts for box in part.boxes]
    boxes.sort(key=lambda box: (-box.strip_size[1], -box.strip_size[0], box.name))

    x = y = 0
    shelf_height = 0
    for box in boxes:
        w, h = box.strip_size
        if x + w + PADDING > width:
            x = 0
            y += shelf_height + PADDING
            shelf_height = 0
        if y + h + PADDING > height:
            raise SystemExit(
                f"Texture atlas {width}x{height} is too small for box {box.name!r}"
            )
        box.uv = (x, y)
        x += w + PADDING
        shelf_height = max(shelf_height, h)


def face_rects(box: Box) -> dict[str, tuple[int, int, int, int]]:
    """The atlas rectangle of each of a box's six faces (vanilla unwrapping)."""
    u, v = box.uv
    w, h, d = box.size
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


# --------------------------------------------------------------------------
# Texture painting
# --------------------------------------------------------------------------


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))


def shade(rgb: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, round(channel * factor))) for channel in rgb)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


FACE_SHADE = {
    "top": 1.06,
    "bottom": 0.86,
    "front": 1.0,
    "back": 0.94,
    "left": 0.92,
    "right": 0.90,
}


def paint_texture(design: dict, parts: list[Part]) -> Image.Image:
    palette = {name: hex_rgb(value) for name, value in design["palette"].items()}
    width = design["texture"]["width"]
    height = design["texture"]["height"]
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    pixels = image.load()
    rng = random.Random(0xFA15)

    for part in parts:
        for box in part.boxes:
            base = palette[box.material]
            tip = palette[box.tip_material] if box.tip_material else base
            rects = face_rects(box)
            for face, (rx, ry, rw, rh) in rects.items():
                factor = FACE_SHADE[face]
                face_base = shade(base, factor)
                face_tip = shade(tip, factor)
                # Is this face part of the box's long axis (where the tip fades in)?
                long_axis = face in {"front", "back", "left", "right", "bottom"}
                for local_y in range(rh):
                    for local_x in range(rw):
                        t = 0.0
                        if long_axis and box.tip_material:
                            # Blend into the tip colour over the lower half only.
                            t = max(0.0, (local_y / max(1, rh - 1) - 0.5) * 2.0)
                        color = mix(face_base, face_tip, t)
                        # Faint woven-plush noise so flat areas do not look like plastic.
                        jitter = rng.randint(-4, 4)
                        color = tuple(max(0, min(255, c + jitter)) for c in color)
                        pixels[rx + local_x, ry + local_y] = color + (255,)

            if box.front_decal == "eye":
                paint_eye(pixels, rects["front"], palette)
            elif box.front_decal == "face":
                paint_face(pixels, rects["front"], palette)
            elif box.front_decal == "muzzle":
                paint_muzzle(pixels, rects["front"], palette)
            elif box.front_decal == "belly":
                paint_belly(pixels, rects["front"], palette)
            elif box.front_decal == "stitch_ear":
                paint_ear_stitch(pixels, rects["front"], palette)

    return image


def paint_eye(pixels, rect, palette) -> None:
    """A dark, glossy eye with a pale iris pool and one highlight."""
    x, y, w, h = rect
    dark = palette["eye"]
    iris = palette["eye_iris"]
    glint = palette["eye_glint"]
    for local_y in range(h):
        for local_x in range(w):
            pixels[x + local_x, y + local_y] = dark + (255,)
    if w >= 2 and h >= 2:
        pixels[x + 1, y + 1] = glint + (255,)
    if w >= 3 and h >= 3:
        pixels[x + 2, y + 2] = iris + (255,)


def paint_face(pixels, rect, palette) -> None:
    """Painted skin details for the front of the head.

    The eyes and the muzzle are separate parts, so this face carries only the
    soft details around them: blush low on each cheek and a few freckles.
    """
    x, y, w, h = rect
    blush = palette["blush"]
    if w >= 6 and h >= 5:
        for local_x in (0, 1, w - 2, w - 1):
            pixels[x + local_x, y + h - 2] = blush + (255,)
        for local_x in (0, w - 1):
            pixels[x + local_x, y + h - 3] = shade(blush, 0.94) + (255,)


def paint_muzzle(pixels, rect, palette) -> None:
    """A small stitched smile on the muzzle."""
    x, y, w, h = rect
    mouth = palette["mouth"]
    if w >= 4:
        for local_x in range(1, w - 1):
            pixels[x + local_x, y] = mouth + (255,)
        if h >= 2:
            pixels[x, y + 1] = mouth + (255,)
            pixels[x + w - 1, y + 1] = mouth + (255,)


def paint_belly(pixels, rect, palette) -> None:
    """A dashed seam down the middle of the belly patch."""
    x, y, w, h = rect
    thread = palette["thread"]
    for local_y in range(0, h, 2):
        pixels[x + w // 2, y + local_y] = thread + (255,)


def paint_ear_stitch(pixels, rect, palette) -> None:
    """One red stitch on the left ear: the only wrong thing on a harmless face."""
    x, y, w, h = rect
    stitch = palette["stitch"]
    thread = palette["thread"]
    if w >= 2 and h >= 4:
        pixels[x + 1, y + 2] = thread + (255,)
        pixels[x, y + 3] = stitch + (255,)
        pixels[x + 1, y + 3] = stitch + (255,)
        pixels[x, y + 4] = thread + (255,)


# --------------------------------------------------------------------------
# Java geometry
# --------------------------------------------------------------------------


def java_float(value: float) -> str:
    if float(value).is_integer():
        return f"{int(value)}.0F"
    return f"{value}F"


def constant_name(part_name: str) -> str:
    return part_name.upper()


def generate_java(design: dict, parts: list[Part]) -> str:
    width = design["texture"]["width"]
    height = design["texture"]["height"]
    package = design["java"]["package"]
    class_name = design["java"]["class"]

    lines: list[str] = []
    add = lines.append
    add("// Generated by tools/generate_assets.py from tools/model/falsity_model.json.")
    add("// Do not edit this file by hand: change the design file and re-run the generator.")
    add(f"package {package};")
    add("")
    add("import net.minecraft.client.model.geom.PartPose;")
    add("import net.minecraft.client.model.geom.builders.CubeListBuilder;")
    add("import net.minecraft.client.model.geom.builders.LayerDefinition;")
    add("import net.minecraft.client.model.geom.builders.MeshDefinition;")
    add("import net.minecraft.client.model.geom.builders.PartDefinition;")
    add("")
    add("/**")
    add(" * Geometry of Falsity's first form: a small, round, plush creature with an")
    add(" * oversized head, drooping ears, stubby limbs and a teal cap and trim.")
    add(" *")
    add(" * <p>Every part is exposed by name so the model and its animations refer to")
    add(" * parts symbolically instead of by loose strings.")
    add(" */")
    add(f"public final class {class_name} {{")
    add(f"\tpublic static final int TEXTURE_WIDTH = {width};")
    add(f"\tpublic static final int TEXTURE_HEIGHT = {height};")
    add("")
    for part in parts:
        add(f"\t/** {part.role} */" if part.role else f"\t/** {part.name} */")
        add(f'\tpublic static final String {constant_name(part.name)} = "{part.name}";')
    add("")
    add("\tpublic static LayerDefinition create() {")
    add("\t\tMeshDefinition mesh = new MeshDefinition();")
    add("\t\tPartDefinition root = mesh.getRoot();")
    add("")

    declared: dict[str, str] = {}
    for part in parts:
        var = f"{part.name}Part"
        builder_lines: list[str] = []
        for box in part.boxes:
            ox, oy, oz = box.origin
            w, h, d = box.size
            builder_lines.append(
                f"\t\t\t.texOffs({box.uv[0]}, {box.uv[1]})"
                f".addBox({java_float(ox)}, {java_float(oy)}, {java_float(oz)}, {w}, {h}, {d})"
                f" // {box.name}"
            )
        rotation = part.rotation
        if any(abs(r) > 1e-6 for r in rotation):
            pose = (
                f"PartPose.offsetAndRotation({java_float(part.pivot[0])}, {java_float(part.pivot[1])}, "
                f"{java_float(part.pivot[2])}, (float) Math.toRadians({rotation[0]:g}), "
                f"(float) Math.toRadians({rotation[1]:g}), (float) Math.toRadians({rotation[2]:g}))"
            )
        else:
            pose = (
                f"PartPose.offset({java_float(part.pivot[0])}, {java_float(part.pivot[1])}, "
                f"{java_float(part.pivot[2])})"
            )

        if part.parent is None:
            add(f"\t\tPartDefinition {var} = root.addOrReplaceChild(")
            add(f"\t\t\t{constant_name(part.name)},")
            add("\t\t\tCubeListBuilder.create()")
            lines.extend(builder_lines)
            add(f"\t\t\t, {pose}")
            add("\t\t);")
        else:
            add(f"\t\tPartDefinition {var} = {declared[part.parent]}.addOrReplaceChild(")
            add(f"\t\t\t{constant_name(part.name)},")
            add("\t\t\tCubeListBuilder.create()")
            lines.extend(builder_lines)
            add(f"\t\t\t, {pose}")
            add("\t\t);")
        declared[part.name] = var
        add("")

    add(f"\t\treturn LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);")
    add("\t}")
    add("")
    add(f"\tprivate {class_name}() {{")
    add("\t}")
    add("}")
    add("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Item and mod art
# --------------------------------------------------------------------------


def generate_spawn_egg(design: dict) -> Image.Image:
    palette = {name: hex_rgb(value) for name, value in design["palette"].items()}
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    outline = palette["outline"]
    shell = palette["ivory"]
    spot = palette["teal"]

    # Classic spawn-egg silhouette: a narrow top widening into a round base.
    rows = {
        3: (6, 9),
        4: (5, 10),
        5: (4, 11),
        6: (3, 12),
        7: (3, 12),
        8: (3, 12),
        9: (3, 12),
        10: (3, 12),
        11: (4, 11),
        12: (5, 10),
        13: (6, 9),
    }
    for y, (x0, x1) in rows.items():
        draw.line([(x0, y), (x1, y)], fill=outline + (255,))
    for y, (x0, x1) in rows.items():
        draw.line([(x0 + 1, y), (x1 - 1, y)], fill=shell + (255,))

    # Teal speckles, plus one dark speck to hint at the mod's tone.
    for x, y in [(6, 6), (7, 6), (9, 8), (5, 9), (8, 10), (10, 9), (6, 11), (9, 11)]:
        draw.point((x, y), fill=spot + (255,))
    draw.point((7, 8), fill=palette["stitch"] + (255,))
    return image


def generate_icon(design: dict, parts: list[Part], texture: Image.Image) -> Image.Image:
    """A large portrait of Falsity's face for the mod list."""
    palette = {name: hex_rgb(value) for name, value in design["palette"].items()}
    size = 128
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    ivory = palette["ivory"]
    teal = palette["teal"]
    outline = palette["outline"]

    # Background: the deep teal of Falsity's trim, rounded.
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=18, fill=palette["teal_shadow"] + (255,))

    # Drooping ears behind the head.
    draw.polygon([(24, 44), (44, 40), (40, 108), (26, 104)], fill=shade(ivory, 0.9) + (255,))
    draw.polygon([(104, 44), (84, 40), (88, 108), (102, 104)], fill=shade(ivory, 0.9) + (255,))

    # Head.
    draw.rounded_rectangle([30, 28, 98, 100], radius=26, fill=ivory + (255,), outline=outline + (255,))
    # Teal cap.
    draw.rounded_rectangle([32, 26, 96, 46], radius=16, fill=teal + (255,))

    # Eyes.
    for cx in (49, 79):
        draw.ellipse([cx - 11, 52, cx + 11, 78], fill=palette["eye"] + (255,))
        draw.ellipse([cx - 7, 68, cx + 5, 76], fill=palette["eye_iris"] + (255,))
        draw.ellipse([cx - 8, 54, cx - 3, 59], fill=palette["eye_glint"] + (255,))

    # Blush and a small stitched mouth.
    draw.ellipse([33, 78, 43, 86], fill=palette["blush"] + (255,))
    draw.ellipse([85, 78, 95, 86], fill=palette["blush"] + (255,))
    draw.line([(58, 88), (70, 88)], fill=palette["mouth"] + (255,), width=2)
    # The single red stitch.
    draw.line([(38, 70), (42, 74)], fill=palette["stitch"] + (255,))
    return image


# --------------------------------------------------------------------------
# Preview rendering (for humans, not for the game)
# --------------------------------------------------------------------------

VIEWS = {
    "front": (0.0, 0.0),
    "three-quarter": (math.radians(35), math.radians(18)),
    "side": (math.radians(90), 0.0),
}


def iter_boxes(parts: list[Part]):
    """Yield (world_origin, size, box) applying each part's pivot and rotation."""
    by_name = {part.name: part for part in parts}

    def walk(part: Part, parent_matrix):
        pivot = part.pivot
        rot = [math.radians(a) for a in part.rotation]
        matrix = matmul(matmul(parent_matrix, translate(*pivot)), rotate_xyz(*rot))
        for box in part.boxes:
            yield matrix, box
        for child in parts:
            if child.parent == part.name:
                yield from walk(child, matrix)

    for part in parts:
        if part.parent is None:
            yield from walk(part, identity())


def identity():
    return [
        [1.0, 0.0, 0.0, 0.0],
        [0.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 1.0, 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ]


def translate(x, y, z):
    m = identity()
    m[0][3], m[1][3], m[2][3] = x, y, z
    return m


def rotate_xyz(rx, ry, rz):
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    rx_m = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    ry_m = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    rz_m = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return matmul(matmul(rz_m, ry_m), rx_m)


def bilinear(p00, p10, p11, p01, u, v):
    """Interpolate a point inside a quad given its four corners."""
    top = tuple(p00[i] + (p10[i] - p00[i]) * u for i in range(3))
    bottom = tuple(p01[i] + (p11[i] - p01[i]) * u for i in range(3))
    return tuple(top[i] + (bottom[i] - top[i]) * v for i in range(3))


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def apply(matrix, point):
    x, y, z = point
    return (
        matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z + matrix[0][3],
        matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z + matrix[1][3],
        matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z + matrix[2][3],
    )


BOX_FACES = {
    "north": [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)],
    "south": [(0, 0, 1), (0, 1, 1), (1, 1, 1), (1, 0, 1)],
    "west": [(0, 0, 0), (0, 1, 0), (0, 1, 1), (0, 0, 1)],
    "east": [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],
    "up": [(0, 0, 0), (0, 0, 1), (1, 0, 1), (1, 0, 0)],
    "down": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
}
FACE_TO_RECT = {
    "up": "top",
    "down": "bottom",
    "west": "right",
    "north": "front",
    "east": "left",
    "south": "back",
}


def render_preview(design: dict, parts: list[Part], texture: Image.Image) -> Image.Image:
    """Render the model with a z-buffered rasteriser, for human review.

    This is QA tooling, not something the mod uses at runtime: it shows what the
    geometry and texture will look like together, so proportions and UV mapping
    can be checked without launching Minecraft.
    """
    final_scale = 16
    supersample = 2
    scale = final_scale * supersample
    view_w, view_h = 110, 130
    tex = texture.load()

    sheet = Image.new("RGBA", (view_w * final_scale * len(VIEWS), view_h * final_scale), (22, 26, 28, 255))

    for index, (view_name, (yaw, pitch)) in enumerate(VIEWS.items()):
        width, height = view_w * scale, view_h * scale
        color_buffer = [[(22, 26, 28)] * width for _ in range(height)]
        depth_buffer = [[float("inf")] * width for _ in range(height)]

        cos_y, sin_y = math.cos(yaw), math.sin(yaw)
        cos_p, sin_p = math.cos(pitch), math.sin(pitch)
        centre_x = width / 2
        centre_y = height / 2

        def project(point):
            x, y, z = point
            # Model space is +Y down, like screen space, so no Y flip is needed.
            y -= 13.5
            xr = x * cos_y - z * sin_y
            zr = x * sin_y + z * cos_y
            yr = y * cos_p - zr * sin_p
            depth = y * sin_p + zr * cos_p
            return (centre_x + xr * scale, centre_y + yr * scale, depth)

        triangles = []
        for matrix, box in iter_boxes(parts):
            ox, oy, oz = box.origin
            w, h, d = box.size
            rects = face_rects(box)
            for key, corners_local in BOX_FACES.items():
                rx, ry, rw, rh = rects[FACE_TO_RECT[key]]
                uvs = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
                if key == "south":
                    uvs = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)]
                if key == "west":
                    uvs = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)]
                if key == "east":
                    uvs = [(1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0)]
                if key == "up":
                    uvs = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)]
                if key == "down":
                    uvs = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
                vertices = []
                for (cx, cy, cz), (u, v) in zip(corners_local, uvs):
                    local = (ox + cx * w, oy + cy * h, oz + cz * d)
                    sx, sy, depth = project(apply(matrix, local))
                    vertices.append((sx, sy, depth, rx + u * rw, ry + v * rh))
                triangles.append((vertices[0], vertices[1], vertices[2]))
                triangles.append((vertices[0], vertices[2], vertices[3]))

        for v0, v1, v2 in triangles:
            rasterise_triangle(v0, v1, v2, color_buffer, depth_buffer, tex, width, height)

        canvas = Image.new("RGBA", (width, height))
        canvas_pixels = canvas.load()
        for y in range(height):
            row = color_buffer[y]
            for x in range(width):
                r, g, b = row[x]
                canvas_pixels[x, y] = (r, g, b, 255)
        canvas = canvas.resize((view_w * final_scale, view_h * final_scale), Image.LANCZOS)
        sheet.paste(canvas, (index * view_w * final_scale, 0))
        ImageDraw.Draw(sheet).text(
            (index * view_w * final_scale + 10, 8), view_name.upper(), fill=(228, 224, 212, 255)
        )

    return sheet


def rasterise_triangle(v0, v1, v2, color_buffer, depth_buffer, tex, width, height) -> None:
    (x0, y0, z0, u0, vv0), (x1, y1, z1, u1, vv1), (x2, y2, z2, u2, vv2) = v0, v1, v2
    area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if abs(area) < 1e-9:
        return
    min_x = max(0, int(math.floor(min(x0, x1, x2))))
    max_x = min(width - 1, int(math.ceil(max(x0, x1, x2))))
    min_y = max(0, int(math.floor(min(y0, y1, y2))))
    max_y = min(height - 1, int(math.ceil(max(y0, y1, y2))))
    inv_area = 1.0 / area
    tex_width, tex_height = tex.size if hasattr(tex, "size") else (64, 64)

    for py in range(min_y, max_y + 1):
        pyc = py + 0.5
        for px in range(min_x, max_x + 1):
            pxc = px + 0.5
            w0 = ((x1 - pxc) * (y2 - pyc) - (x2 - pxc) * (y1 - pyc)) * inv_area
            w1 = ((x2 - pxc) * (y0 - pyc) - (x0 - pxc) * (y2 - pyc)) * inv_area
            w2 = 1.0 - w0 - w1
            if w0 < -1e-6 or w1 < -1e-6 or w2 < -1e-6:
                continue
            depth = w0 * z0 + w1 * z1 + w2 * z2
            if depth >= depth_buffer[py][px]:
                continue
            u = w0 * u0 + w1 * u1 + w2 * u2
            v = w0 * vv0 + w1 * vv1 + w2 * vv2
            tx = min(tex_width - 1, max(0, int(u)))
            ty = min(tex_height - 1, max(0, int(v)))
            color = tex[tx, ty]
            if color[3] == 0:
                continue
            depth_buffer[py][px] = depth
            color_buffer[py][px] = color[:3]


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


def write_or_check(
    path: Path, data: bytes, check: bool, stale: list[str], image: Image.Image | None = None
) -> None:
    """Write a generated file, or in check mode report whether it is stale.

    Images are compared by decoded pixels rather than by PNG bytes: two Pillow
    versions can encode identical art differently, and that must not fail CI.
    """
    if check:
        if not path.exists():
            stale.append(str(path.relative_to(ROOT)))
        elif image is not None:
            existing = Image.open(path).convert("RGBA")
            if existing.size != image.size or existing.tobytes() != image.convert("RGBA").tobytes():
                stale.append(str(path.relative_to(ROOT)))
        elif path.read_bytes() != data:
            stale.append(str(path.relative_to(ROOT)))
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def png_bytes(image: Image.Image) -> bytes:
    from io import BytesIO

    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify generated files are current")
    parser.add_argument("--preview", action="store_true", help="also write the model preview sheet")
    args = parser.parse_args()

    design, parts = load_design()
    width = design["texture"]["width"]
    height = design["texture"]["height"]
    pack_uvs(parts, width, height)

    texture = paint_texture(design, parts)
    spawn_egg = generate_spawn_egg(design)
    icon = generate_icon(design, parts, texture)
    java_source = generate_java(design, parts).encode("utf-8")

    java_path = JAVA_OUT.joinpath(*design["java"]["package"].split("."), f"{design['java']['class']}.java")
    texture_path = TEXTURE_OUT / "entity" / "falsity" / "falsity.png"
    spawn_egg_path = TEXTURE_OUT / "item" / "falsity_spawn_egg.png"

    stale: list[str] = []
    write_or_check(java_path, java_source, args.check, stale)
    write_or_check(texture_path, png_bytes(texture), args.check, stale, image=texture)
    write_or_check(spawn_egg_path, png_bytes(spawn_egg), args.check, stale, image=spawn_egg)
    write_or_check(ICON_OUT, png_bytes(icon), args.check, stale, image=icon)

    if args.preview:
        PREVIEW_OUT.parent.mkdir(parents=True, exist_ok=True)
        render_preview(design, parts, texture).save(PREVIEW_OUT)
        print(f"wrote {PREVIEW_OUT.relative_to(ROOT)}")

    if args.check:
        if stale:
            print("Generated files are out of date; run python3 tools/generate_assets.py")
            for path in stale:
                print(f"  stale: {path}")
            return 1
        print("Generated assets are up to date.")
        return 0

    print(f"Generated {java_path.relative_to(ROOT)}")
    print(f"Generated {texture_path.relative_to(ROOT)}")
    print(f"Generated {spawn_egg_path.relative_to(ROOT)}")
    print(f"Generated {ICON_OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

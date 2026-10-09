"""程序化生成 Web Screen 模组所需的所有 PNG 贴图（不依赖第三方库）。"""
import os
import struct
import zlib

ROOT = r"C:\Users\Administrator\Desktop\web\webscreen\src\main\resources"


def write_png(path, width, height, pixel_fn):
    """pixel_fn(x, y) -> (r, g, b, a)"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        for x in range(width):
            r, g, b, a = pixel_fn(x, y)
            raw += bytes((r & 255, g & 255, b & 255, a & 255))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    print("wrote", path, len(png), "bytes")


def mix(c1, c2, t):
    return tuple(int(round(a + (b - a) * t)) for a, b in zip(c1, c2))


# --------------------------------------------------------------------- 边框
def bezel(x, y):
    d = min(x, y, 15 - x, 15 - y)
    if d < 2:
        return (26, 29, 35, 255)        # 外框：深灰
    if d < 3:
        return (58, 65, 79, 255)        # 内缘高光
    return (9, 11, 15, 255)             # 屏幕内侧（会被画面盖住）


write_png(os.path.join(ROOT, "assets", "webscreen", "textures", "block", "web_screen_bezel.png"),
          16, 16, bezel)


# --------------------------------------------------------------------- 侧面
def side(x, y):
    base = (36, 39, 46)
    if x % 4 == 0:
        base = (46, 50, 59)
    if y in (0, 15):
        base = (24, 26, 31)
    return base + (255,)


write_png(os.path.join(ROOT, "assets", "webscreen", "textures", "block", "web_screen_side.png"),
          16, 16, side)


# --------------------------------------------------------------------- 物品图标
def item_icon(x, y):
    # 外壳
    if 2 <= x <= 13 and 2 <= y <= 11:
        # 屏幕区域，带一点渐变，模拟"亮着的屏幕"
        if 3 <= x <= 12 and 3 <= y <= 10:
            t = ((x - 3) + (y - 3)) / 18.0
            return mix((26, 92, 140), (86, 214, 226), t) + (255,)
        return (30, 33, 39, 255)
    if 6 <= x <= 9 and 12 <= y <= 13:
        return (44, 48, 56, 255)     # 支架
    if 4 <= x <= 11 and y == 14:
        return (44, 48, 56, 255)     # 底座
    return (0, 0, 0, 0)


write_png(os.path.join(ROOT, "assets", "webscreen", "textures", "item", "web_screen.png"),
          16, 16, item_icon)


# --------------------------------------------------------------------- 模组图标
def logo(x, y):
    size = 128
    # 圆角矩形外框
    margin = 10
    radius = 18
    inside = margin <= x <= size - 1 - margin and margin <= y <= size - 1 - margin
    cx = min(max(x, margin + radius), size - 1 - margin - radius)
    cy = min(max(y, margin + radius), size - 1 - margin - radius)
    dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
    if not inside or dist > radius:
        return (0, 0, 0, 0)

    border = 8
    if x < margin + border or x > size - 1 - margin - border or y < margin + border or y > size - 1 - margin - border:
        return (26, 29, 35, 255)

    # 屏幕区域：三角渐变
    t = ((x - margin) / float(size - 2 * margin) + (y - margin) / float(size - 2 * margin)) / 2.0
    r, g, b = mix((18, 64, 108), (96, 226, 240), t)

    # 画一个"播放三角"
    px, py = x - 52, y - 44
    if 0 <= px <= 30 and 0 <= py <= 40:
        half = int(py * 0.75)
        if half <= px <= 30 - half:
            r, g, b = (255, 255, 255)
    return (r, g, b, 255)


write_png(os.path.join(ROOT, "icon.png"), 128, 128, logo)
print("全部贴图生成完毕")

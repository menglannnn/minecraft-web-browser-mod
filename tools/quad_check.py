"""把 WebScreenRenderer.buildQuad 的几何照搬过来做仿真，验证拼接后画面是否严丝合缝。

重点验证四件事：
  1) 相邻单元的公共边：世界坐标完全重合，UV 也完全重合（不会有黑边/错位）；
  2) 留下的黑框只出现在整块屏幕的最外圈，内部接缝一根线都不留；
  3) 所有单元的 UV 都在 [0,1] 内，且最外圈正好贴到 0 / 1（画面不被裁切、不拉伸）；
  4) 浏览器视口比例 == 可见区域比例（单格必须是 1:2）。
"""

import contextlib
import io
import json
import os
import re

with contextlib.redirect_stdout(io.StringIO()):
    import layout_sim as L

EPS = 1e-6

MARGIN_X = 1.0 / 16.0
MARGIN_Y = MARGIN_X * 2.0
NUDGE = 0.0015

# facing -> 屏幕"右"在世界里的 (dx, dz)
RIGHT_STEP = {
    "SOUTH": (1, 0),    # screenRight = EAST
    "NORTH": (-1, 0),   # screenRight = WEST
    "EAST": (0, -1),    # screenRight = NORTH
    "WEST": (0, 1),     # screenRight = SOUTH
}

MAX_VIEWPORT = 2048
MIN_BLOCK_PIXELS = 48
MAX_BLOCK_PIXELS = 512

# ---- 与 WebScreenRenderer.java 保持一致的常量 ----
PANEL_THICKNESS = 4.0 / 16.0
PANEL_BACK_GAP = 0.5 / 16.0
# facing -> 方块模型在 blockstates 里的 y 旋转
MODEL_Y_ROTATION = {"NORTH": 0, "EAST": 90, "SOUTH": 180, "WEST": 270}


def back_plane(facing):
    """面板背面在朝向轴上的位置（相对本格最小角，单位：格）。"""
    return PANEL_BACK_GAP if facing in ("SOUTH", "EAST") else 1.0 - PANEL_BACK_GAP


def front_plane(facing):
    """面板正面在朝向轴上的位置（相对本格最小角，单位：格）。"""
    back = back_plane(facing)
    return back + PANEL_THICKNESS if facing in ("SOUTH", "EAST") else back - PANEL_THICKNESS


def visible_width(layout):
    return layout[3] - 2.0 * MARGIN_X


def visible_height(layout):
    return 2.0 * layout[4] - 2.0 * MARGIN_Y


def block_pixels(layout):
    longest = max(visible_width(layout), visible_height(layout))
    by_limit = int(MAX_VIEWPORT // max(0.5, longest))
    return max(MIN_BLOCK_PIXELS, min(by_limit, MAX_BLOCK_PIXELS))


def viewport(layout):
    bp = block_pixels(layout)
    return (max(32, round(visible_width(layout) * bp)),
            max(32, round(visible_height(layout) * bp)))


def unit_world_positions(layout, facing):
    """(col,row) -> 该单元下半格的世界坐标 (x, y, z)。"""
    controller, _, _, w, h = layout
    dx, dz = RIGHT_STEP[facing]
    out = {}
    for row in range(h):
        for col in range(w):
            out[(col, row)] = (controller[0] + dx * col,
                               controller[2] - 2 * row,
                               controller[1] + dz * col)
    return out


def build_quad(facing, layout, unit_pos):
    """返回 4 个顶点，每个是 (世界坐标 (x,y,z), 屏幕坐标 (s, sv), UV (u, v))。

    顶点顺序：左下、右下、右上、左上（站在屏幕正前方看）。
    """
    _, col, row, width, height = layout
    px, py, pz = unit_pos
    dx, dz = RIGHT_STEP[facing]
    right_positive = (dx + dz) > 0
    along_x = facing in ("SOUTH", "NORTH")

    vw = visible_width(layout)
    vh = visible_height(layout)
    unit_base = 2.0 * (height - 1 - row)

    # 正面平面：面板贴着方块背面，所以四个朝向的正面位置不一样
    nudge = NUDGE if facing in ("SOUTH", "EAST") else -NUDGE
    front = (pz if along_x else px) + front_plane(facing) + nudge

    verts = []
    for h, v in ((0.0, 0.0), (1.0, 0.0), (1.0, 2.0), (0.0, 2.0)):
        s = col + h
        if col == 0 and h == 0.0:
            s = MARGIN_X
        elif col == width - 1 and h == 1.0:
            s = width - MARGIN_X

        sv = unit_base + v
        if row == height - 1 and v == 0.0:
            sv = MARGIN_Y
        elif row == 0 and v == 2.0:
            sv = 2.0 * height - MARGIN_Y

        along = (s - col) if right_positive else 1.0 - (s - col)
        world = (px + along, py + (sv - unit_base), front) if along_x \
            else (front, py + (sv - unit_base), pz + along)

        verts.append((world, (s, sv),
                      ((s - MARGIN_X) / vw, 1.0 - (sv - MARGIN_Y) / vh)))
    return verts


def close(a, b, eps=1e-9):
    return abs(a - b) <= eps


def check(name, cells, facing):
    problems = []
    layouts = {}
    for c in sorted(cells):
        lay = L.compute(cells, c, facing)
        layouts[c] = lay
        # 同一块屏幕算出来的 controller / 尺寸必须完全一致（col,row 本来就该不同）
        if L.compute(cells, lay[0], facing)[:1] + L.compute(cells, lay[0], facing)[3:] != lay[:1] + lay[3:]:
            problems.append(f"{c} 的布局与 controller 算出来的不一致")

    # 同一块屏幕分组
    screens = {}
    for c, lay in layouts.items():
        screens.setdefault((lay[0], lay[3], lay[4]), set()).update(
            L.unit_positions(lay, facing))

    for (controller, w, h), members in screens.items():
        layout = (controller, 0, 0, w, h)
        positions = unit_world_positions(layout, facing)
        # 每个单元用"自己的" col/row 去算（模拟渲染器里每格独立调用）
        quads = {cr: build_quad(facing, (controller, cr[0], cr[1], w, h), p)
                 for cr, p in positions.items()}

        # ---- 1) 几何：每个单元在屏幕坐标里的范围必须正好是 [col,col+1] x [base,base+2]
        for (col, row), verts in quads.items():
            ss = [v[1][0] for v in verts]
            svs = [v[1][1] for v in verts]
            lo = MARGIN_X if col == 0 else col
            hi = w - MARGIN_X if col == w - 1 else col + 1
            # 底边 / 顶边：只有落在整块屏幕最下/最上时才会被留白内缩
            span_lo = 2.0 * (h - 1 - row)
            vb = MARGIN_Y if row == h - 1 else span_lo
            vt = 2.0 * h - MARGIN_Y if row == 0 else span_lo + 2.0
            if not (close(min(ss), lo) and close(max(ss), hi)):
                problems.append(f"@{(col, row)} 横向范围 {min(ss)}~{max(ss)} != {lo}~{hi}")
            if not (close(min(svs), vb) and close(max(svs), vt)):
                problems.append(f"@{(col, row)} 纵向范围 {min(svs)}~{max(svs)} != {vb}~{vt}")

        # ---- 2) 水平相邻：公共边世界坐标 + UV 必须重合
        for row in range(h):
            for col in range(w - 1):
                a = quads[(col, row)]      # 右边缘 = 顶点 1,2
                b = quads[(col + 1, row)]  # 左边缘 = 顶点 0,3
                for ia, ib in ((1, 0), (2, 3)):
                    if not all(close(x, y, EPS) for x, y in zip(a[ia][0], b[ib][0])):
                        problems.append(f"横向接缝 @({col},{row})-({col+1},{row}) 几何不重合"
                                        f" {a[ia][0]} vs {b[ib][0]}")
                    if not all(close(x, y, EPS) for x, y in zip(a[ia][2], b[ib][2])):
                        problems.append(f"横向接缝 @({col},{row})-({col+1},{row}) UV 不重合"
                                        f" {a[ia][2]} vs {b[ib][2]}")

        # ---- 3) 垂直相邻：公共边世界坐标 + UV 必须重合
        for row in range(h - 1):
            for col in range(w):
                a = quads[(col, row)]       # 底边 = 顶点 0,1
                b = quads[(col, row + 1)]   # 顶边 = 顶点 3,2
                for ia, ib in ((0, 3), (1, 2)):
                    if not all(close(x, y, EPS) for x, y in zip(a[ia][0], b[ib][0])):
                        problems.append(f"纵向接缝 @({col},{row})-({col},{row+1}) 几何不重合"
                                        f" {a[ia][0]} vs {b[ib][0]}")
                    if not all(close(x, y, EPS) for x, y in zip(a[ia][2], b[ib][2])):
                        problems.append(f"纵向接缝 @({col},{row})-({col},{row+1}) UV 不重合"
                                        f" {a[ia][2]} vs {b[ib][2]}")

        # ---- 4) UV 覆盖：全体 UV 落在 [0,1]，且最外圈正好压到 0 / 1
        us = [v[2][0] for q in quads.values() for v in q]
        vs = [v[2][1] for q in quads.values() for v in q]
        if min(us) < -EPS or max(us) > 1 + EPS or min(vs) < -EPS or max(vs) > 1 + EPS:
            problems.append(f"UV 越界 u[{min(us):.4f},{max(us):.4f}] v[{min(vs):.4f},{max(vs):.4f}]")
        if not (close(min(us), 0.0) and close(max(us), 1.0)
                and close(min(vs), 0.0) and close(max(vs), 1.0)):
            problems.append(f"UV 没有铺满：u[{min(us):.4f},{max(us):.4f}] v[{min(vs):.4f},{max(vs):.4f}]")

        # ---- 5) 外框只留在最外圈：所有顶点中，被留白内缩的必须都在屏幕边界上
        inset_count = 0
        for (col, row), verts in quads.items():
            for _, (s, sv), _ in verts:
                if close(s, MARGIN_X) or close(s, w - MARGIN_X):
                    inset_count += 1
                    if not (col == 0 or col == w - 1):
                        problems.append(f"@{(col, row)} 内部单元却动了横向外框")
                if close(sv, MARGIN_Y) or close(sv, 2.0 * h - MARGIN_Y):
                    if not (row == 0 or row == h - 1):
                        problems.append(f"@{(col, row)} 内部单元却动了纵向外框")

        # ---- 6) 视口比例必须等于可见区域比例
        vpw, vph = viewport(layout)
        got = vpw / vph
        want = visible_width(layout) / visible_height(layout)
        if abs(got - want) > 0.02:
            problems.append(f"视口比例 {got:.4f} != 可见区域比例 {want:.4f}")
        if w == 1 and h == 1 and abs(want - 0.5) > 1e-9:
            problems.append(f"单格可见比例不是 1:2（{want:.6f}）")

    tag = "OK  " if not problems else "FAIL"
    print(f"{tag} {name:16s} facing={facing:5s} 屏幕: "
          + ", ".join(f"{w}x{h}@{ctl}" for (ctl, w, h) in sorted(screens, key=str)))
    for p in problems:
        print("        - " + p)
    return not problems


def java_constant(text, name):
    """从 Java 源码里抠出 `NAME = a / b` 或 `NAME = a` 形式的数值常量。"""
    m = re.search(rf"{name}\s*=\s*([0-9.]+)F?\s*/\s*([0-9.]+)F?\s*;", text)
    if m:
        return float(m.group(1)) / float(m.group(2))
    m = re.search(rf"{name}\s*=\s*([0-9.]+)F?\s*;", text)
    return float(m.group(1)) if m else None


def rotate_y(rot, x, z):
    """MC 方块模型的 y 旋转，输入输出都用 0..16 的像素坐标。"""
    if rot == 0:
        return x, z
    if rot == 90:
        return 16.0 - z, x
    if rot == 180:
        return 16.0 - x, 16.0 - z
    if rot == 270:
        return z, 16.0 - x
    raise ValueError(f"不支持的 y 旋转：{rot}")


# 各朝向下面板"厚度轴"落在哪里（与 WebScreenRenderer 的 alongX 对应）
PERP_AXIS = {"NORTH": "z", "SOUTH": "z", "EAST": "x", "WEST": "x"}


def check_assets():
    """方块模型 / blockstates 旋转 / 碰撞形状 / 渲染常量，四边必须互相对得上。

    这一条是为了防止"改了模型忘了改碰撞"或"改了碰撞忘了改渲染"这类漂移。
    任何一处对不上都会打印具体哪一个朝向、差了多少。
    """
    problems = []

    root = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(root)
    res = os.path.join(root, "src", "main", "resources", "assets", "webscreen")
    src = os.path.join(root, "src", "main", "java", "com", "webscreen")

    model_path = os.path.join(res, "models", "block", "web_screen.json")
    states_path = os.path.join(res, "blockstates", "web_screen.json")
    renderer_path = os.path.join(src, "client", "WebScreenRenderer.java")
    block_path = os.path.join(src, "block", "WebScreenBlock.java")

    with open(model_path, encoding="utf-8") as f:
        model = json.load(f)
    with open(states_path, encoding="utf-8") as f:
        variants = json.load(f)["variants"]
    with open(renderer_path, encoding="utf-8") as f:
        renderer_src = f.read()
    with open(block_path, encoding="utf-8") as f:
        block_src = f.read()

    # ---- (a) Java 常量必须和本脚本一致 ----
    jt = java_constant(renderer_src, "PANEL_THICKNESS")
    jb = java_constant(renderer_src, "PANEL_BACK_GAP")
    if jt is None or abs(jt - PANEL_THICKNESS) > 1e-9:
        problems.append(f"WebScreenRenderer.PANEL_THICKNESS={jt} 与脚本 {PANEL_THICKNESS} 不一致")
    if jb is None or abs(jb - PANEL_BACK_GAP) > 1e-9:
        problems.append(f"WebScreenRenderer.PANEL_BACK_GAP={jb} 与脚本 {PANEL_BACK_GAP} 不一致")
    bt = java_constant(block_src, "PANEL_BACK_GAP")
    if bt is None or abs(bt - PANEL_BACK_GAP * 16.0) > 1e-9:
        problems.append(f"WebScreenBlock.PANEL_BACK_GAP={bt} 与脚本 {PANEL_BACK_GAP * 16.0}(像素) 不一致")

    # ---- (b) 模型元素：横向纵向必须铺满整格，只在厚度轴上有范围 ----
    elems = model.get("elements", [])
    if len(elems) != 1:
        problems.append(f"模型元素个数是 {len(elems)}，期望恰好 1 个")
    else:
        frm, to = elems[0]["from"], elems[0]["to"]
        if (frm[0], to[0], frm[1], to[1]) != (0, 16, 0, 16):
            problems.append(f"模型元素没有铺满整格：from={frm} to={to}")
        model_z = (float(frm[2]), float(to[2]))

        # ---- (c) 每个朝向：blockstates 旋转后的模型范围 == 渲染常量推导的范围 ----
        for facing in ("NORTH", "SOUTH", "EAST", "WEST"):
            variant = variants.get(f"facing={facing.lower()},half=lower")
            if variant is None:
                problems.append(f"blockstates 缺少 facing={facing.lower()} 的变体")
                continue
            rot = variant.get("y", 0)

            # 模型立方体 8 个角旋转后，落在厚度轴上的范围
            corners = [(x, z) for x in (0.0, 16.0) for z in model_z]
            rotated = [rotate_y(rot, x, z) for x, z in corners]
            axis = PERP_AXIS[facing]
            idx = 0 if axis == "x" else 1
            vals = sorted(p[idx] for p in rotated)
            model_lo, model_hi = vals[0], vals[-1]

            back_px = back_plane(facing) * 16.0
            front_px = front_plane(facing) * 16.0
            want_lo, want_hi = sorted((back_px, front_px))

            if abs(model_lo - want_lo) > 1e-9 or abs(model_hi - want_hi) > 1e-9:
                problems.append(
                    f"{facing}(y={rot}) 模型在 {axis} 轴范围 [{model_lo:g},{model_hi:g}] "
                    f"!= 渲染推导 [{want_lo:g},{want_hi:g}]")

            # ---- (d) 碰撞形状 == 模型范围（视觉和碰撞必须一致）----
            shape_name = f"SHAPE_{facing}"
            m = re.search(rf"VoxelShape\s+{shape_name}\s*=\s*Block\.box\(([^)]*)\);", block_src)
            if not m:
                problems.append(f"WebScreenBlock 里找不到 {shape_name}")
                continue
            box = []
            for raw in m.group(1).split(","):
                raw = raw.strip()
                raw = re.sub(r"PANEL_BACK_GAP", f"({PANEL_BACK_GAP * 16.0})", raw)
                try:
                    box.append(eval(raw, {"__builtins__": {}}, {}))
                except Exception:
                    problems.append(f"{shape_name} 的表达式无法求值：{raw}")
                    box = []
                    break
            if len(box) != 6:
                continue
            lo = 0 if axis == "x" else 2
            shape_lo, shape_hi = sorted((box[lo], box[lo + 3]))
            if abs(shape_lo - want_lo) > 1e-9 or abs(shape_hi - want_hi) > 1e-9:
                problems.append(
                    f"{shape_name} 在 {axis} 轴范围 [{shape_lo:g},{shape_hi:g}] "
                    f"!= 渲染推导 [{want_lo:g},{want_hi:g}]")
            # 另外两个轴必须整格铺满
            for other in ("x", "y", "z"):
                if other == axis:
                    continue
                oi = {"x": 0, "y": 1, "z": 2}[other]
                if abs(box[oi] - 0.0) > 1e-9 or abs(box[oi + 3] - 16.0) > 1e-9:
                    problems.append(f"{shape_name} 在 {other} 轴没有铺满整格：{box[oi]}..{box[oi + 3]}")

    tag = "OK  " if not problems else "FAIL"
    print(f"{tag} {'静态一致性':16s} 模型 JSON / blockstates / 碰撞形状 / 渲染常量")
    for p in problems:
        print("        - " + p)
    return not problems


def main():
    all_ok = True
    shapes = [
        ("单格", [(0, 0)]),
        ("横向2格", [(0, 0), (1, 0)]),
        ("横向8格", [(c, 0) for c in range(8)]),
        ("横向9格", [(c, 0) for c in range(9)]),
        ("纵向2格", [(0, 0), (0, 1)]),
        ("纵向4格", [(0, r) for r in range(4)]),
        ("纵向5格", [(0, r) for r in range(5)]),
        ("2x2", [(c, r) for r in range(2) for c in range(2)]),
        ("3x2", [(c, r) for r in range(2) for c in range(3)]),
        ("4x3", [(c, r) for r in range(3) for c in range(4)]),
        ("8x4满屏", [(c, r) for r in range(4) for c in range(8)]),
        ("L形", [(0, 0), (0, 1), (1, 1)]),
        ("T形", [(0, 0), (1, 0), (2, 0), (1, 1)]),
        ("十字", [(1, 0), (0, 1), (1, 1), (2, 1), (1, 2)]),
        ("两段分开", [(0, 0), (3, 0)]),
        ("各自单格", [(0, 0), (2, 2)]),
    ]

    for facing in ("SOUTH", "NORTH", "EAST", "WEST"):
        for name, pairs in shapes:
            cells = L.shape(pairs, facing)
            all_ok &= check(name, cells, facing)

    all_ok &= check_assets()

    print("\n总结果:", "全部通过" if all_ok else "存在问题")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

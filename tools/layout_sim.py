"""把 ScreenLayout 的算法照搬过来做仿真，验证多格拼接的正确性。"""

MAX_W, MAX_H = 8, 4
GRID_W, GRID_H = 32, 16
BFS_LIMIT = 256

FACINGS = {
    # facing: (axis_is_z, right_is_positive)
    "SOUTH": (True, True),    # along=X, screenRight=EAST(+X)
    "NORTH": (True, False),   # along=X, screenRight=WEST(-X)
    "WEST": (False, True),    # along=Z, screenRight=SOUTH(+Z)
    "EAST": (False, False),   # along=Z, screenRight=NORTH(-Z)
}


def along(p, is_z_axis):
    return p[0] if is_z_axis else p[1]


def compute(cells, start, facing):
    """cells: set of (a, b, y) 其中 (a,b) 是水平坐标对，y 是下半格高度。"""
    is_z_axis, positive = FACINGS[facing]

    if start not in cells:
        return None

    # ---- BFS ----
    seen = {start}
    queue = [start]
    comp = []
    while queue and len(comp) < BFS_LIMIT:
        cur = queue.pop(0)
        comp.append(cur)
        a, b, y = cur
        neighbours = [(a + 1, b, y), (a - 1, b, y), (a, b + 1, y), (a, b - 1, y),
                      (a, b, y + 2), (a, b, y - 2)]
        for n in neighbours:
            if n not in seen:
                seen.add(n)
                if n in cells:
                    queue.append(n)

    if len(comp) <= 1:
        return (start, 0, 0, 1, 1)

    alongs = [along(c, is_z_axis) for c in comp]
    ys = [c[2] for c in comp]
    min_along, max_along = min(alongs), max(alongs)
    min_y, max_y = min(ys), max(ys)

    cols = max_along - min_along + 1
    rows = (max_y - min_y) // 2 + 1
    if cols < 1 or rows < 1 or cols > GRID_W or rows > GRID_H:
        return (start, 0, 0, 1, 1)

    grid = [[False] * cols for _ in range(rows)]
    cell_at = [[None] * cols for _ in range(rows)]
    for c in comp:
        row = (max_y - c[2]) // 2
        raw = along(c, is_z_axis) - min_along
        col = raw if positive else cols - 1 - raw
        if 0 <= row < rows and 0 <= col < cols:
            grid[row][col] = True
            cell_at[row][col] = c

    # ---- 贪心切矩形 ----
    used = [[False] * cols for _ in range(rows)]
    self_row = (max_y - start[2]) // 2
    raw = along(start, is_z_axis) - min_along
    self_col = raw if positive else cols - 1 - raw

    result = None
    for row in range(rows):
        for col in range(cols):
            if not grid[row][col] or used[row][col]:
                continue
            max_w = 0
            while col + max_w < cols and max_w < MAX_W and grid[row][col + max_w] and not used[row][col + max_w]:
                max_w += 1

            best = (1, 1, 1)
            for w in range(max_w, 0, -1):
                h = 0
                while row + h < rows and h < MAX_H and all(
                        grid[row + h][cc] and not used[row + h][cc] for cc in range(col, col + w)):
                    h += 1
                if h > 0 and w * h > best[2]:
                    best = (w, h, w * h)
            bw, bh, _ = best

            for r in range(row, row + bh):
                for cc in range(col, col + bw):
                    used[r][cc] = True

            if result is None and row <= self_row < row + bh and col <= self_col < col + bw:
                result = (cell_at[row][col], self_col - col, self_row - row, bw, bh)

    return result if result is not None else (start, 0, 0, 1, 1)


def unit_positions(layout, facing):
    """把 (col,row) 还原成世界坐标，用来做 round-trip 校验。"""
    is_z_axis, positive = FACINGS[facing]
    controller, _, _, w, h = layout
    a, b, y = controller
    out = []
    for row in range(h):
        for col in range(w):
            # screenRight 沿 +axis 还是 -axis
            step = col if positive else -col
            na = a + step if is_z_axis else a
            nb = b if is_z_axis else b + step
            out.append((na, nb, y - 2 * row))
    return out


def check(name, cells, facing):
    ok = True
    groups = {}
    for c in sorted(cells):
        layout = compute(cells, c, facing)
        controller, col, row, w, h = layout
        # 1) round-trip：controller 按 col/row 展开必须覆盖自己
        positions = unit_positions(layout, facing)
        if c not in positions:
            ok = False
            print(f"  [FAIL] {c} 不在自己算出的矩形里 {layout}")
        # 2) 矩形必须完全落在实际格子里
        for p in positions:
            if p not in cells:
                ok = False
                print(f"  [FAIL] {c} 的矩形包含了不存在的格子 {p}")
        # 3) 同一块屏幕的所有格子必须算出一致的布局
        groups.setdefault((controller, w, h), set()).update(positions)
        # 4) col/row 必须与 controller 的相对位置一致
        dc = positions.index(c)
        if dc != row * w + col:
            ok = False
            print(f"  [FAIL] {c} 的 col/row 与位置不符 {layout}")

    # 5) 每块屏幕的矩形不得互相重叠
    seen = {}
    for (controller, w, h), members in groups.items():
        for p in members:
            if p in seen and seen[p] != controller:
                ok = False
                print(f"  [FAIL] {p} 同时属于 {seen[p]} 和 {controller}")
            seen[p] = controller

    # 6) 所有格子都被覆盖
    if set(seen.keys()) != set(cells):
        ok = False
        print(f"  [FAIL] 覆盖不全: 缺 {set(cells) - set(seen.keys())}")

    print(f"{'OK  ' if ok else 'FAIL'} {name}: {len(groups)} 块屏幕 -> "
          + ", ".join(f"{w}x{h}@{ctl}" for (ctl, w, h) in sorted(groups, key=str)))
    return ok


def world_from_screen(col, row, facing):
    """(屏幕列, 屏幕行) -> 世界坐标。row 0 在最上面。"""
    is_z_axis, positive = FACINGS[facing]
    # screenRight 在世界里是 +axis 还是 -axis
    step = col if positive else -col
    if is_z_axis:
        return (100 + step, 0, 300 - 2 * row)
    return (0, 100 + step, 300 - 2 * row)


def grid(w, h, holes=(), facing="SOUTH"):
    return {world_from_screen(cx, cy, facing)
            for cx in range(w) for cy in range(h) if (cx, cy) not in holes}


def shape(pairs, facing="SOUTH"):
    return {world_from_screen(c, r, facing) for (c, r) in pairs}


all_ok = True
TEST_SHAPES = [
    ("单格", [(0, 0)]),
    ("横向 2 格", [(0, 0), (1, 0)]),
    ("横向 3 格", [(0, 0), (1, 0), (2, 0)]),
    ("纵向 2 格", [(0, 0), (0, 1)]),
    ("纵向 3 格", [(0, 0), (0, 1), (0, 2)]),
    ("2x2 方形", None),
    ("3x2", None),
    ("4x3", None),
    ("L 形 (缺右上)", None),
    ("L 形 (缺左上)", None),
    ("T 形", [(0, 0), (1, 0), (2, 0), (1, 1)]),
    ("十字", [(1, 0), (0, 1), (1, 1), (2, 1), (1, 2)]),
    ("超宽 9 格", None),
    ("超高 5 格", None),
    ("两段分开", [(0, 0), (3, 0)]),
    ("斜对角不相连", [(0, 0), (1, 1)]),
]

for facing in ("SOUTH", "NORTH", "EAST", "WEST"):
    print(f"\n=== facing={facing} ===")
    for name, pairs in TEST_SHAPES:
        if pairs is not None:
            cells = shape(pairs, facing)
        elif name == "2x2 方形":
            cells = grid(2, 2, facing=facing)
        elif name == "3x2":
            cells = grid(3, 2, facing=facing)
        elif name == "4x3":
            cells = grid(4, 3, facing=facing)
        elif name == "L 形 (缺右上)":
            cells = grid(2, 2, holes={(1, 0)}, facing=facing)
        elif name == "L 形 (缺左上)":
            cells = grid(2, 2, holes={(0, 0)}, facing=facing)
        elif name == "超宽 9 格":
            cells = grid(9, 1, facing=facing)
        else:
            cells = grid(1, 5, facing=facing)
        all_ok &= check(name, cells, facing)

print("\n总结果:", "全部通过" if all_ok else "存在问题")

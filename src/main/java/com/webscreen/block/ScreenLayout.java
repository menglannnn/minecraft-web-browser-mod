package com.webscreen.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网页方块的屏幕布局。
 *
 * <p>一个"单元"= 竖直方向相邻的两格（宽 1 x 高 2）。相邻的单元会自动合成一块矩形大屏，
 * 中间不额外加方块的话，单格自己就是 1x1 的屏幕。</p>
 *
 * <p><b>关键设计：完全无状态。</b>任何一格都能独立算出自己属于哪块屏幕：
 * 先 BFS 找出所在连通块，再把连通块按"从上到下、从左到右"的顺序贪心切成矩形，
 * 每格落在哪个矩形是确定的。因为同一连通块里的每一格跑出来的结果必然一致，
 * 所以不需要任何额外的布局同步。</p>
 */
public final class ScreenLayout {

    /** 单块屏幕的最大尺寸（单元数）。8 单元 = 8 格宽；4 单元 = 8 格高。 */
    public static final int MAX_WIDTH = 8;
    public static final int MAX_HEIGHT = 4;

    /**
     * 屏幕四周的外框留白（单位：格）。竖直留白取水平的两倍，
     * 这样 1x1 的单元可见区域正好是 (1-2m) : (2-4m) = 1 : 2。
     *
     * <p>注意：留白**只在整块屏幕的外围**生效，内部接缝处两个单元的画面是严丝合缝的，
     * 否则拼起来会看到一条条黑边。</p>
     */
    public static final float MARGIN_X = 1.0F / 16.0F;
    public static final float MARGIN_Y = MARGIN_X * 2.0F;

    /** 连通块栅格的安全上限；超出这个规模就不再尝试切分。 */
    private static final int GRID_MAX_COLS = 32;
    private static final int GRID_MAX_ROWS = 16;

    /** 浏览器视口的上限像素数（防止显存爆炸），以及每世界格分配的像素区间。 */
    public static final int MAX_VIEWPORT = 2048;
    private static final int MIN_BLOCK_PIXELS = 48;
    private static final int MAX_BLOCK_PIXELS = 512;

    /** 连通块 BFS 的安全上限。 */
    private static final int BFS_LIMIT = 256;

    private ScreenLayout() {
    }

    /** 某一格所属屏幕的位置。col/row 以该屏幕的左上角为原点。 */
    public record Layout(BlockPos controller, int col, int row, int width, int height) {

        public boolean isMulti() {
            return width > 1 || height > 1;
        }

        public int unitCount() {
            return width * height;
        }
    }

    // ------------------------------------------------------------------ 方向

    /** 屏幕的"右"对应世界的哪个方向（站在屏幕正前方看）。 */
    public static Direction screenRight(Direction facing) {
        return switch (facing) {
            case SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.EAST;
        };
    }

    /** 屏幕的"上"在世界里永远是 +Y。 */
    public static Direction screenUp() {
        return Direction.UP;
    }

    /** 屏幕上横向排列所沿的世界轴。南北朝向的屏沿 X 排列，东西朝向的沿 Z 排列。 */
    private static Direction horizontalAxis(Direction facing) {
        return facing.getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
    }

    private static int along(BlockPos pos, Direction facing) {
        return facing.getAxis() == Direction.Axis.Z ? pos.getX() : pos.getZ();
    }

    private static boolean rightIsPositive(Direction facing) {
        return screenRight(facing).getAxisDirection() == Direction.AxisDirection.POSITIVE;
    }

    // ------------------------------------------------------------------ 单元判定

    /** 该位置是否是一个完整单元的下半格。 */
    public static boolean isUnit(BlockGetter level, BlockPos lowerPos, Direction facing) {
        BlockState lower = level.getBlockState(lowerPos);
        if (!(lower.getBlock() instanceof WebScreenBlock)) {
            return false;
        }
        if (lower.getValue(WebScreenBlock.HALF) != DoubleBlockHalf.LOWER) {
            return false;
        }
        if (lower.getValue(WebScreenBlock.FACING) != facing) {
            return false;
        }
        BlockState upper = level.getBlockState(lowerPos.above());
        return upper.getBlock() instanceof WebScreenBlock
                && upper.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.UPPER
                && upper.getValue(WebScreenBlock.FACING) == facing;
    }

    /** 单元的下半格往上盖了一层。 */
    public static boolean hasUpperHalf(BlockGetter level, BlockPos lowerPos, Direction facing) {
        BlockState upper = level.getBlockState(lowerPos.above());
        return upper.getBlock() instanceof WebScreenBlock
                && upper.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.UPPER
                && upper.getValue(WebScreenBlock.FACING) == facing;
    }

    /** 从某个单元的下半格出发，求它在第几个"单元格"。 */
    public static BlockPos toLowerPos(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof WebScreenBlock
                && state.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    // ------------------------------------------------------------------ 布局计算

    /** 单格屏幕。 */
    public static Layout single(BlockPos lowerPos) {
        return new Layout(lowerPos.immutable(), 0, 0, 1, 1);
    }

    public static Layout compute(BlockGetter level, BlockPos lowerPos, Direction facing) {
        BlockPos start = lowerPos.immutable();
        if (!isUnit(level, start, facing)) {
            return single(start);
        }

        List<BlockPos> cells = collect(level, start, facing);
        if (cells.size() <= 1) {
            return single(start);
        }

        int minAlong = Integer.MAX_VALUE;
        int maxAlong = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            int a = along(cell, facing);
            minAlong = Math.min(minAlong, a);
            maxAlong = Math.max(maxAlong, a);
            minY = Math.min(minY, cell.getY());
            maxY = Math.max(maxY, cell.getY());
        }

        int cols = maxAlong - minAlong + 1;
        // 一个单元竖直方向占两格，所以行数是高度差的一半
        int rows = (maxY - minY) / 2 + 1;
        if (cols < 1 || rows < 1 || cols > GRID_MAX_COLS || rows > GRID_MAX_ROWS) {
            return single(start);
        }

        boolean positive = rightIsPositive(facing);
        boolean[][] grid = new boolean[rows][cols];
        BlockPos[][] cellAt = new BlockPos[rows][cols];
        for (BlockPos cell : cells) {
            int row = (maxY - cell.getY()) / 2;
            int raw = along(cell, facing) - minAlong;
            int col = positive ? raw : cols - 1 - raw;
            if (row < 0 || row >= rows || col < 0 || col >= cols) {
                continue;
            }
            grid[row][col] = true;
            cellAt[row][col] = cell;
        }

        Layout mine = splitIntoRectangles(grid, cellAt, start, facing, maxY, minAlong, cols, positive);
        return mine != null ? mine : single(start);
    }

    private static List<BlockPos> collect(BlockGetter level, BlockPos start, Direction facing) {
        List<BlockPos> cells = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Direction axis = horizontalAxis(facing);
        Direction[] offsets = {axis, axis.getOpposite(), Direction.UP, Direction.DOWN};

        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && cells.size() < BFS_LIMIT) {
            BlockPos current = queue.poll();
            cells.add(current);
            for (Direction offset : offsets) {
                BlockPos next = step(current, offset);
                if (next == null || !seen.add(next)) {
                    continue;
                }
                if (isUnit(level, next, facing)) {
                    queue.add(next);
                }
            }
        }
        return cells;
    }

    /** 相邻单元：水平方向移动一格；竖直方向一个单元占两格，所以跳两格。 */
    private static BlockPos step(BlockPos pos, Direction offset) {
        if (offset == Direction.UP) {
            return pos.above(2);
        }
        if (offset == Direction.DOWN) {
            return pos.below(2);
        }
        return pos.relative(offset);
    }

    /**
     * 贪心切矩形：按行优先顺序，每次从最靠前的未分配格子出发取面积最大的矩形。
     * 得到的是一组互不重叠、覆盖全部格子的矩形。
     */
    private static Layout splitIntoRectangles(boolean[][] grid, BlockPos[][] cellAt, BlockPos self,
                                              Direction facing, int maxY, int minAlong, int cols, boolean positive) {
        int rows = grid.length;
        boolean[][] used = new boolean[rows][cols];
        Layout result = null;

        int selfRow = (maxY - self.getY()) / 2;
        int selfRaw = along(self, facing) - minAlong;
        int selfCol = positive ? selfRaw : cols - 1 - selfRaw;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (!grid[row][col] || used[row][col]) {
                    continue;
                }
                int maxW = 0;
                while (col + maxW < cols && maxW < MAX_WIDTH
                        && grid[row][col + maxW] && !used[row][col + maxW]) {
                    maxW++;
                }

                int bestW = 1;
                int bestH = 1;
                int bestArea = 1;
                for (int w = maxW; w >= 1; w--) {
                    int h = 0;
                    while (row + h < rows && h < MAX_HEIGHT && rowFits(grid, used, row + h, col, w)) {
                        h++;
                    }
                    if (h > 0 && w * h > bestArea) {
                        bestArea = w * h;
                        bestW = w;
                        bestH = h;
                    }
                }

                BlockPos controller = cellAt[row][col];
                for (int r = row; r < row + bestH; r++) {
                    for (int c = col; c < col + bestW; c++) {
                        used[r][c] = true;
                    }
                }

                if (result == null && selfRow >= row && selfRow < row + bestH && selfCol >= col && selfCol < col + bestW) {
                    result = new Layout(controller.immutable(), selfCol - col, selfRow - row, bestW, bestH);
                }
            }
        }
        return result;
    }

    private static boolean rowFits(boolean[][] grid, boolean[][] used, int row, int col, int width) {
        for (int c = col; c < col + width; c++) {
            if (!grid[row][c] || used[row][c]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 派生信息

    /** 该屏幕所有单元的下半格位置。 */
    public static List<BlockPos> unitPositions(Layout layout, Direction facing) {
        Direction right = screenRight(facing);
        List<BlockPos> positions = new ArrayList<>(layout.unitCount());
        for (int row = 0; row < layout.height(); row++) {
            for (int col = 0; col < layout.width(); col++) {
                positions.add(layout.controller().relative(right, col).below(2 * row));
            }
        }
        return positions;
    }

    /** 可视区域的宽度（单位：格），已扣掉左右外框留白。 */
    public static double visibleWidth(Layout layout) {
        return layout.width() - 2.0 * MARGIN_X;
    }

    /** 可视区域的高度（单位：格），已扣掉上下外框留白。 */
    public static double visibleHeight(Layout layout) {
        return 2.0 * layout.height() - 2.0 * MARGIN_Y;
    }

    /** 每个世界格分配多少像素；保证整个视口不超过 {@link #MAX_VIEWPORT}。 */
    public static int blockPixels(Layout layout) {
        double longest = Math.max(visibleWidth(layout), visibleHeight(layout));
        int byLimit = (int) Math.floor(MAX_VIEWPORT / Math.max(0.5, longest));
        return Mth.clamp(Math.min(byLimit, MAX_BLOCK_PIXELS), MIN_BLOCK_PIXELS, MAX_BLOCK_PIXELS);
    }

    public static int viewportWidth(Layout layout) {
        return Math.max(32, (int) Math.round(visibleWidth(layout) * blockPixels(layout)));
    }

    public static int viewportHeight(Layout layout) {
        return Math.max(32, (int) Math.round(visibleHeight(layout) * blockPixels(layout)));
    }

    // ------------------------------------------------------------------ 合并时的继承

    /**
     * 合并后让新拼出来的屏幕继承已有 URL。
     * 只在服务端调用。
     */
    public static void inheritUrl(net.minecraft.world.level.Level level, BlockPos anyUnitLowerPos, Direction facing) {
        Layout layout = compute(level, anyUnitLowerPos, facing);
        String url = "";
        for (BlockPos pos : unitPositions(layout, facing)) {
            if (level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen && !screen.getUrl().isEmpty()) {
                url = screen.getUrl();
                break;
            }
        }
        if (url.isEmpty()) {
            return;
        }
        for (BlockPos pos : unitPositions(layout, facing)) {
            if (level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen) {
                screen.setUrl(url);
            }
        }
    }

    // ------------------------------------------------------------------ 客户端缓存

    private static final Map<BlockPos, CachedLayout> CACHE = new HashMap<>();

    private record CachedLayout(BlockGetter level, Layout layout) {
    }

    /** 渲染 / 交互路径用，同一 tick 内复用结果。每 tick 由客户端 tick 事件清空。 */
    public static Layout computeCached(BlockGetter level, BlockPos lowerPos, Direction facing) {
        BlockPos key = lowerPos.immutable();
        CachedLayout cached = CACHE.get(key);
        if (cached != null && cached.level() == level) {
            return cached.layout();
        }
        Layout layout = compute(level, key, facing);
        CACHE.put(key, new CachedLayout(level, layout));
        return layout;
    }

    public static void invalidateCache() {
        if (!CACHE.isEmpty()) {
            CACHE.clear();
        }
    }
}

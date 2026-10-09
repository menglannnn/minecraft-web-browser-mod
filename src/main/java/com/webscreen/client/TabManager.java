package com.webscreen.client;

import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebTabBlock;
import com.webscreen.block.WebTabBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端侧的"标签页"管理。
 *
 * <p>一块屏幕可以搭配若干个贴在它顶上的 {@link WebTabBlock}，每个标签方块保存一个网址。
 * 屏幕同一时刻只显示其中一个（"激活"的那个）。</p>
 *
 * <p>"激活"状态刻意<b>只存在客户端内存里</b>，不写世界数据：这样切换标签不需要网络往返，
 * 也不会有"多人时谁抢了标签"的问题。代价是退出世界后激活状态回到"第一个非空标签"，
 * 而网址本身是跟着方块实体持久化的。</p>
 */
public final class TabManager {

    /** 每块屏幕当前激活的标签方块。键是屏幕 controller。 */
    private static final Map<BlockPos, BlockPos> ACTIVE = new HashMap<>();

    private TabManager() {
    }

    /**
     * 找出这块屏幕上方那一排标签方块。
     *
     * <p>位置约定：屏幕最上行的单元，其上两格就是标签方块该在的地方
     * （中间隔着单元的上半格）。横向只扫屏幕宽度范围。</p>
     */
    public static List<BlockPos> tabsFor(BlockGetter level, ScreenLayout.Layout layout, Direction facing) {
        Direction right = ScreenLayout.screenRight(facing);
        List<BlockPos> tabs = new ArrayList<>();
        for (int col = 0; col < layout.width(); col++) {
            BlockPos tabPos = layout.controller().relative(right, col).above(2);
            if (level.getBlockState(tabPos).getBlock() instanceof WebTabBlock) {
                tabs.add(tabPos.immutable());
            }
        }
        return tabs;
    }

    public static String urlAt(BlockGetter level, BlockPos tabPos) {
        return level.getBlockEntity(tabPos) instanceof WebTabBlockEntity tab ? tab.getUrl() : "";
    }

    /**
     * 决定这块屏幕现在该显示哪个网址。
     *
     * <p>优先级：激活的标签 → 第一个非空标签 → 屏幕自己存的网址（没有任何标签方块时）。</p>
     */
    public static String resolveUrl(BlockGetter level, ScreenLayout.Layout layout, Direction facing, String fallback) {
        List<BlockPos> tabs = tabsFor(level, layout, facing);
        if (tabs.isEmpty()) {
            return fallback;
        }
        BlockPos active = ACTIVE.get(layout.controller());
        if (active != null) {
            String url = urlAt(level, active);
            if (!url.isEmpty()) {
                return url;
            }
        }
        for (BlockPos tab : tabs) {
            String url = urlAt(level, tab);
            if (!url.isEmpty()) {
                return url;
            }
        }
        return fallback;
    }

    /** 该屏幕上第一个还没分配网址的标签方块；都满了就返回 null。 */
    public static BlockPos findFreeTab(BlockGetter level, ScreenLayout.Layout layout, Direction facing) {
        for (BlockPos tab : tabsFor(level, layout, facing)) {
            if (urlAt(level, tab).isEmpty()) {
                return tab;
            }
        }
        return null;
    }

    public static void activate(BlockPos controller, BlockPos tabPos) {
        ACTIVE.put(controller.immutable(), tabPos.immutable());
    }

    public static boolean isActive(BlockPos controller, BlockPos tabPos) {
        return tabPos.equals(ACTIVE.get(controller));
    }

    /** 由标签方块反查它服务的那块屏幕的 controller；位置不对就返回 null。 */
    public static BlockPos controllerOf(BlockGetter level, BlockPos tabPos) {
        BlockState state = level.getBlockState(tabPos);
        if (!(state.getBlock() instanceof WebTabBlock)) {
            return null;
        }
        Direction facing = state.getValue(WebTabBlock.FACING);
        // 标签方块的下方依次是：屏幕单元的上半格、最上行单元的下半格
        BlockPos lower = tabPos.below(2);
        BlockState lowerState = level.getBlockState(lower);
        if (!(lowerState.getBlock() instanceof WebScreenBlock)
                || lowerState.getValue(WebScreenBlock.HALF) != DoubleBlockHalf.LOWER
                || lowerState.getValue(WebScreenBlock.FACING) != facing) {
            return null;
        }
        return ScreenLayout.computeCached(level, lower, facing).controller();
    }

    /** 标签上显示的短标题：优先取域名。 */
    public static String labelOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            if (host != null && !host.isEmpty()) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Throwable ignored) {
            // 网址不合法就退化成原文
        }
        return url.length() > 22 ? url.substring(0, 21) + "…" : url;
    }

    /** 退出世界时清空（激活状态不跨世界保留）。 */
    public static void clear() {
        ACTIVE.clear();
    }

    /** 某个标签方块没了（被破坏 / 被替换），顺手清掉指向它的激活记录。 */
    public static void forget(BlockPos tabPos) {
        ACTIVE.values().removeIf(tabPos::equals);
    }
}

package com.webscreen.client;

import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebScreenBlockEntity;
import com.webscreen.client.gui.RemoteControlScreen;
import com.webscreen.client.gui.UrlInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 从公共代码（方块类）里调用的客户端入口。
 * 只有在 {@code level.isClientSide()} 为真时才会被加载。
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openUrlScreen(BlockPos clickedPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        // 拼成大屏时，任何一格打开的都是同一份网址（存在屏幕左上角那一格）
        String initial = "";
        BlockState state = minecraft.level.getBlockState(clickedPos);
        if (state.getBlock() instanceof com.webscreen.block.WebTabBlock) {
            // 标签方块只存自己那一个网址
            if (minecraft.level.getBlockEntity(clickedPos) instanceof com.webscreen.block.WebTabBlockEntity tab) {
                initial = tab.getUrl();
            }
        } else if (state.getBlock() instanceof WebScreenBlock) {
            Direction facing = state.getValue(WebScreenBlock.FACING);
            BlockPos base = state.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.LOWER
                    ? clickedPos
                    : clickedPos.below();
            ScreenLayout.Layout layout = ScreenLayout.compute(minecraft.level, base, facing);
            if (minecraft.level.getBlockEntity(layout.controller()) instanceof WebScreenBlockEntity screen) {
                initial = screen.getUrl();
            }
        }

        minecraft.setScreen(new UrlInputScreen(clickedPos.immutable(), initial));
    }

    /**
     * 右键标签方块：把它切到屏幕上显示。
     *
     * <p>激活状态只记在客户端内存里（见 {@link TabManager}），所以这里不需要发包。</p>
     */
    public static void activateTab(BlockPos tabPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockPos controller = TabManager.controllerOf(minecraft.level, tabPos);
        if (controller == null) {
            return;
        }
        TabManager.activate(controller, tabPos);
    }

    /**
     * 每 tick 检查一次"网页输入模式"的快捷键。
     *
     * <p>只在没有其它界面打开时才响应 —— 界面已经打开时，原版不会去轮询 KeyMapping，
     * 那种情况由 {@link RemoteControlScreen} 自己处理。</p>
     */
    public static void pollRemoteControlKey() {
        Minecraft minecraft = Minecraft.getInstance();
        while (KeyMappings.REMOTE_CONTROL.consumeClick()) {
            if (minecraft.screen != null || minecraft.player == null) {
                continue;
            }
            if (ClientInteractions.resolveTarget() != null) {
                minecraft.setScreen(new RemoteControlScreen());
            }
        }
    }
}

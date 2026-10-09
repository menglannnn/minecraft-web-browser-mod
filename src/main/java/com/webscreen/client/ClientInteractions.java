package com.webscreen.client;

import com.cinemamod.mcef.MCEFBrowser;
import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 鼠标操作分发。
 *
 * <p>准星指着屏幕时的按键分配：</p>
 *
 * <ul>
 *     <li><b>不按 Shift</b>：左键 / 右键 =&gt; 网页上的左键 / 右键点击</li>
 *     <li><b>Shift + 左键</b>：走原版挖掘，把方块挖掉</li>
 *     <li><b>Shift + 右键</b>：打开「输入网址」界面</li>
 *     <li>滚轮 =&gt; 网页上下滚动</li>
 * </ul>
 *
 * <p>也就是说：<b>不按 Shift 时左右键完全交给网页，按住 Shift 才是"操作方块"</b>。
 * 另外准星指着屏幕时还会每 tick 转发鼠标位置，页面的 hover 效果和视频控件才跟得上。</p>
 *
 * <p>屏幕还没加载网页（或者 Chromium 还在初始化）时不算"屏幕"，这时左右键就是普通方块操作，
 * 不按 Shift 的右键也会直接弹出输入网址界面 —— 免得一块空屏幕反而既点不动也挖不掉。</p>
 *
 * <p>键盘输入（登录网页要用）走 {@link com.webscreen.client.gui.RemoteControlScreen}，
 * 那边会锁住鼠标并把按键全部转发给网页。</p>
 */
public class ClientInteractions {

    private static final double MAX_INTERACT_DISTANCE_SQR = 16.0D * 16.0D;

    /** 与浏览器交互后，继续盯 N tick 的鼠标锁定状态，万一被外部改坏就恢复。 */
    private static final int MOUSE_WATCHDOG_TICKS = 40;

    private int tickCounter;
    private int mouseWatchdog;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        // 屏幕布局每 tick 重算一次，熔接/拆分最迟 1 tick 生效
        ScreenLayout.invalidateCache();

        Minecraft minecraft = Minecraft.getInstance();

        if (++tickCounter % 20 == 0) {
            ScreenBrowserManager.tickCleanup();
        }
        ClientHooks.pollRemoteControlKey();
        CookieVault.onClientTick();

        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            mouseWatchdog = 0;
            return;
        }
        BrowserTarget target = resolveTarget();
        if (target != null) {
            target.browser().sendMouseMove(target.pixelX(), target.pixelY());
            mouseWatchdog = MOUSE_WATCHDOG_TICKS;
        }
        if (mouseWatchdog > 0) {
            mouseWatchdog--;
            restoreMouseLock(minecraft);
        }
    }

    /**
     * 兜底：如果鼠标锁定被外部（比如浏览器）解开了，就恢复成游戏内状态。
     * 正常路径下 {@link ScreenBrowserManager} 已经禁用了光标接管，这里只是保险。
     */
    private static void restoreMouseLock(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_DISABLED) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            minecraft.mouseHandler.grabMouse();
        }
    }

    /**
     * 左右键的重新分配：不按 Shift 转发给网页，按住 Shift 才是操作方块。
     *
     * <p>取消这个事件能同时挡住原版的三条路径：{@code startAttack()}（单击挖掘）、
     * {@code continueAttack()}（按住持续挖掘）和 {@code startUseItem()}（右键使用），
     * 所以只要在"要转发给网页"时取消掉，方块就既挖不掉也不会被右键触发。</p>
     *
     * <p><b>Shift + 右键打开界面必须在这里自己处理</b>，不能指望原版的方块交互：
     * 玩家潜行、手上又拿着东西时，原版会跳过方块交互改成"放置手里的方块"，
     * 那样永远弹不出输入网址界面。</p>
     */
    @SubscribeEvent
    public void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        // 原版对"使用物品"会两只手各发一次事件，只认主手，免得弹出两个界面 / 点两次
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        if (minecraft.player.isShiftKeyDown()) {
            // ---- 按着 Shift：操作方块，不碰网页 ----
            if (event.isUseItem()) {
                BlockPos screenPos = screenBlockAt();
                if (screenPos != null) {
                    event.setSwingHand(false);
                    event.setCanceled(true);
                    ClientHooks.openUrlScreen(screenPos);
                }
            }
            // Shift + 左键不用管：不取消就自然走原版挖掘
            return;
        }

        // ---- 不按 Shift：左右键都转发给网页 ----
        BrowserTarget target = resolveTarget();
        if (target == null) {
            return;
        }
        event.setSwingHand(false);
        event.setCanceled(true);
        click(target, event.isAttack() ? GLFW.GLFW_MOUSE_BUTTON_LEFT : GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    /**
     * 给网页发一次完整的"按下 + 抬起"。
     *
     * <p>参数是 <b>GLFW 的按钮码</b>（0 = 左、1 = 右、2 = 中）：
     * MCEF 的 {@code sendMousePress / sendMouseRelease} 内部会把 1 / 2 对调成 CEF 的约定，
     * 传成 CEF 那套的话右键会变成中键。</p>
     */
    public static void click(BrowserTarget target, int glfwButton) {
        MCEFBrowser browser = target.browser();
        browser.setFocus(true);
        browser.sendMousePress(target.pixelX(), target.pixelY(), glfwButton);
        browser.sendMouseRelease(target.pixelX(), target.pixelY(), glfwButton);
    }

    @SubscribeEvent
    public void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        BrowserTarget target = resolveTarget();
        if (target == null) {
            return;
        }
        event.setCanceled(true);
        target.browser().sendMouseWheel(target.pixelX(), target.pixelY(), event.getScrollDeltaY(), 0);
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ScreenBrowserManager.closeAll();
        ScreenLayout.invalidateCache();
    }

    // ---------------------------------------------------------------- 准星 -> 网页坐标

    /** 准星当前指着的屏幕，以及换算好的浏览器像素坐标。 */
    public record BrowserTarget(MCEFBrowser browser, BlockPos controller, int pixelX, int pixelY) {
    }

    /**
     * 准星命中的网页方块坐标（<b>不</b>要求它已经加载了网页）。没命中返回 null。
     *
     * <p>用于「Shift + 右键打开输入网址界面」—— 那时屏幕可能本来就没网址。</p>
     */
    public static BlockPos screenBlockAt() {
        BlockHitResult hit = screenHit();
        return hit == null ? null : hit.getBlockPos();
    }

    /** 准星指着网页方块时的命中信息；带了距离和方块类型校验。 */
    private static BlockHitResult screenHit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult blockHit = (BlockHitResult) hitResult;
        if (minecraft.player.distanceToSqr(blockHit.getLocation()) > MAX_INTERACT_DISTANCE_SQR) {
            return null;
        }
        if (!(minecraft.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof WebScreenBlock)) {
            return null;
        }
        return blockHit;
    }

    /**
     * 准星是否指着一个<b>有画面</b>的网页方块；是的话顺便算出对应的浏览器像素坐标。
     *
     * <p>返回 null 表示准星没指着屏幕、或者这块屏幕还没有浏览器实例
     * （没输入网址，或者 Chromium 还在初始化）。</p>
     */
    public static BrowserTarget resolveTarget() {
        BlockHitResult blockHit = screenHit();
        if (blockHit == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        Direction facing = state.getValue(WebScreenBlock.FACING);
        BlockPos base = state.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();

        ScreenLayout.Layout layout = ScreenLayout.computeCached(minecraft.level, base, facing);
        MCEFBrowser browser = ScreenBrowserManager.browserAt(layout.controller());
        if (browser == null) {
            return null;
        }

        Vec3 location = blockHit.getLocation();
        double dx = location.x - base.getX();
        double dz = location.z - base.getZ();
        double dy = location.y - base.getY();

        // 指针落在本单元内的横向比例（0 = 单元的屏幕左侧）
        double across = switch (facing) {
            case NORTH -> 1.0D - dx;
            case EAST -> 1.0D - dz;
            case WEST -> dz;
            default -> dx; // SOUTH
        };
        across = Mth.clamp(across, 0.0D, 0.999999D);
        // 本单元内的纵向位置（单位：格，0 = 单元底边，2 = 单元顶边）
        double up = Mth.clamp(dy, 0.0D, 1.999999D);

        // 换算到整块屏幕：s 从屏幕左边缘量起，sv 从屏幕底边量起
        double s = layout.col() + across;
        double sv = 2.0D * (layout.height() - 1 - layout.row()) + up;

        // 与渲染端保持完全一致的映射（先扣掉屏幕外框留白，再归一化）
        double u = Mth.clamp((s - ScreenLayout.MARGIN_X) / ScreenLayout.visibleWidth(layout),
                0.0D, 0.999999D);
        double v = Mth.clamp(1.0D - (sv - ScreenLayout.MARGIN_Y) / ScreenLayout.visibleHeight(layout),
                0.0D, 0.999999D);

        int pixelX = (int) (u * ScreenLayout.viewportWidth(layout));
        int pixelY = (int) (v * ScreenLayout.viewportHeight(layout));

        return new BrowserTarget(browser, layout.controller(), pixelX, pixelY);
    }
}

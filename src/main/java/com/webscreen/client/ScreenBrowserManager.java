package com.webscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.webscreen.WebScreenMod;
import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 管理每一块"屏幕"的 Chromium 实例。
 *
 * <p>键是屏幕左上角那个单元的坐标（controller）。整块大屏只跑一个浏览器，
 * 视口分辨率按屏幕格数缩放，所有单元共用同一张贴图。</p>
 *
 * <p>Chromium 很吃内存，所以同时存在的浏览器数量有上限，超出时按最久未使用淘汰。</p>
 */
public final class ScreenBrowserManager {

    /** 同时存在的浏览器上限（每个都会占用一份显存和内存）。 */
    private static final int MAX_BROWSERS = 8;

    private static final Map<BlockPos, Entry> ENTRIES = new HashMap<>();
    private static final Object LOCK = new Object();

    private ScreenBrowserManager() {
    }

    private static final class Entry {
        String url = "";
        MCEFBrowser browser;
        boolean creating;
        long lastRequest;
        int viewportWidth;
        int viewportHeight;
    }

    /**
     * 渲染器每帧调用：拿到该屏幕当前该显示的贴图 id（0 表示还没准备好）。
     *
     * @param controller     屏幕左上角单元的下半格坐标
     * @param url            该屏幕要播放的网址
     * @param viewportWidth  浏览器视口宽（像素）
     * @param viewportHeight 浏览器视口高（像素）
     */
    public static int textureFor(BlockPos controller, String url, int viewportWidth, int viewportHeight) {
        if (url.isEmpty()) {
            // 网址被清空了，顺手把资源还回去
            synchronized (LOCK) {
                Entry stale = ENTRIES.remove(controller);
                if (stale != null) {
                    closeEntry(stale);
                }
            }
            return 0;
        }

        BlockPos key = controller.immutable();
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(key);
            if (entry == null) {
                entry = new Entry();
                ENTRIES.put(key, entry);
            }
            entry.lastRequest = System.currentTimeMillis();

            if (!entry.url.equals(url)) {
                closeEntry(entry);
                entry.url = url;
            }
            if (entry.browser == null && !entry.creating) {
                evictIfNeeded(key);
                create(entry, entry.url, viewportWidth, viewportHeight);
            } else if (entry.browser != null
                    && (entry.viewportWidth != viewportWidth || entry.viewportHeight != viewportHeight)) {
                // 屏幕被拼大 / 拆小了，跟着调整视口
                entry.viewportWidth = viewportWidth;
                entry.viewportHeight = viewportHeight;
                try {
                    entry.browser.resize(viewportWidth, viewportHeight);
                } catch (Throwable throwable) {
                    WebScreenMod.LOGGER.warn("[WebScreen] 调整浏览器视口失败", throwable);
                }
            }
        }

        MCEFBrowser browser = entry.browser;
        if (browser == null) {
            return 0;
        }
        try {
            int textureId = browser.getRenderer().getTextureID();
            return textureId > 0 ? textureId : 0;
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /**
     * 由浏览器实例反查它服务的屏幕 controller。
     * 网页弹窗时只知道是哪个浏览器发起的，要靠这个找回它对应的屏幕。
     */
    public static BlockPos controllerOf(MCEFBrowser browser) {
        if (browser == null) {
            return null;
        }
        synchronized (LOCK) {
            for (Map.Entry<BlockPos, Entry> candidate : ENTRIES.entrySet()) {
                if (candidate.getValue().browser == browser) {
                    return candidate.getKey();
                }
            }
        }
        return null;
    }

    /** 供鼠标交互使用。 */
    public static MCEFBrowser browserAt(BlockPos controller) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(controller);
        }
        return entry == null ? null : entry.browser;
    }

    private static void create(Entry entry, String url, int viewportWidth, int viewportHeight) {
        if (!MCEF.isInitialized()) {
            // CEF 还没初始化完（首次启动还要下载 Chromium 二进制），等它就绪后再试
            if (!entry.creating) {
                entry.creating = true;
                MCEF.scheduleForInit(success -> {
                    synchronized (LOCK) {
                        entry.creating = false;
                    }
                    if (!success) {
                        WebScreenMod.LOGGER.warn("[WebScreen] MCEF 初始化失败，网页方块无法显示画面。");
                    }
                });
            }
            return;
        }
        // CEF 就绪了：顺手接管"新窗口"弹窗（幂等）。
        // 否则网页里 target="_blank" 的链接点了会毫无反应 —— 因为 MCEF 没有注册 lifespan handler。
        PopupHandler.install();
        try {
            entry.browser = MCEF.createBrowser(url, false, viewportWidth, viewportHeight);
            entry.viewportWidth = viewportWidth;
            entry.viewportHeight = viewportHeight;
            // 关键：不要让网页接管系统鼠标指针。
            // MCEF 默认的监听器会在网页光标类型变化时执行
            // glfwSetInputMode(GLFW_CURSOR, GLFW_CURSOR_NORMAL)，
            // 那会把鼠标从游戏里"解锁"出来 —— 表现就是准星一指向方块就冒出系统鼠标，
            // 而且视角也转不动。这里换成空实现，光标始终由游戏自己管。
            entry.browser.setCursorChangeListener(cursorId -> {
            });
            WebScreenMod.LOGGER.info("[WebScreen] 打开网页: {} (视口 {}x{})", url, viewportWidth, viewportHeight);
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.error("[WebScreen] 创建浏览器失败: " + url, throwable);
        }
    }

    private static void evictIfNeeded(BlockPos keep) {
        int alive = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.browser != null) {
                alive++;
            }
        }
        while (alive >= MAX_BROWSERS) {
            BlockPos oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<BlockPos, Entry> candidate : ENTRIES.entrySet()) {
                if (candidate.getKey().equals(keep) || candidate.getValue().browser == null) {
                    continue;
                }
                if (candidate.getValue().lastRequest < oldestTime) {
                    oldestTime = candidate.getValue().lastRequest;
                    oldest = candidate.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            closeEntry(ENTRIES.get(oldest));
            alive--;
        }
    }

    private static void closeEntry(Entry entry) {
        MCEFBrowser browser = entry.browser;
        entry.browser = null;
        if (browser != null) {
            try {
                browser.close();
            } catch (Throwable throwable) {
                WebScreenMod.LOGGER.warn("[WebScreen] 关闭浏览器时出错", throwable);
            }
        }
    }

    /**
     * 每 20 tick 清一次：方块被破坏、区块卸载、或者某格不再是屏幕左上角时，
     * 就把对应的浏览器关掉。
     */
    public static void tickCleanup() {
        Level level = Minecraft.getInstance().level;
        synchronized (LOCK) {
            Iterator<Map.Entry<BlockPos, Entry>> iterator = ENTRIES.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Entry> next = iterator.next();
                if (!isStillController(level, next.getKey())) {
                    closeEntry(next.getValue());
                    iterator.remove();
                }
            }
        }
    }

    private static boolean isStillController(Level level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen)) {
            return false;
        }
        BlockState state = screen.getBlockState();
        if (!(state.getBlock() instanceof WebScreenBlock)
                || state.getValue(WebScreenBlock.HALF) != DoubleBlockHalf.LOWER) {
            return false;
        }
        ScreenLayout.Layout layout = ScreenLayout.compute(level, pos, state.getValue(WebScreenBlock.FACING));
        return layout.controller().equals(pos);
    }

    /** 退出世界时全部关掉，释放 Chromium 资源。 */
    public static void closeAll() {
        synchronized (LOCK) {
            for (Entry entry : ENTRIES.values()) {
                closeEntry(entry);
            }
            ENTRIES.clear();
        }
        // 激活状态不跨世界保留
        TabManager.clear();
    }
}

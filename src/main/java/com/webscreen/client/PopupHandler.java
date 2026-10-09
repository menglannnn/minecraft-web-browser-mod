package com.webscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.webscreen.WebScreenMod;
import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.network.SetUrlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;

/**
 * 接管网页里的"新窗口"（{@code <a target="_blank">} / {@code window.open} 等）。
 *
 * <p><b>问题</b>：MCEF 把 Chromium 跑在离屏（OSR）模式，没有真正的浏览器窗口可以弹出。
 * 哔哩哔哩点用户头像 / 个人主页、点顶部"消息 / 动态 / 历史"这些跨子域链接时，
 * 网页要求开新窗口，CEF 却无处可开 —— 表现就是<b>点了毫无反应</b>。</p>
 *
 * <p><b>为什么不能只靠 {@code CefLifeSpanHandler}</b>：实测（日志见 README）在 OSR 模式下
 * CEF <b>根本不会回调</b> {@code onBeforePopup} —— handler 注册成功了，但一次都没触发。
 * 所以主路径改成"页面内 JS 注入"：页面里所有 {@code target="_blank"} 的点击、
 * {@code window.open} 调用都被 JS 拦下来，通过 {@code cefQuery}
 * （jcef 的 {@code CefMessageRouter}）把网址回传给 Java，由 Java 决定开新标签还是同窗口跳转。</p>
 *
 * <p>另外仍然注册 lifespan / request handler 作为兜底 —— 万一某些站点绕过了 DOM 事件，
 * 还能在 CEF 层接到（不会和 JS 重复，因为 JS 里 {@code preventDefault()} 之后
 * CEF 就不会再触发弹窗回调）。</p>
 *
 * <p>全部注册在 MCEF 的全局 client 上（所有浏览器共用一个），装一次即可。
 * {@link #install()} 幂等，且必须在任何浏览器创建<b>之前</b>调用（{@code addMessageRouter}
 * 有"先于 browser 创建"的硬性要求）。</p>
 */
public final class PopupHandler {

    /** 注入到页面里的查询函数名，和 {@link CefMessageRouter.CefMessageRouterConfig} 对应。 */
    private static final String QUERY_FUNCTION = "webscreenQuery";

    /** 注入脚本发回来的请求前缀。 */
    private static final String OPEN_PREFIX = "open:";

    /** 同一个"新窗口"请求的去重窗口（毫秒）。 */
    private static final long DEDUPLICATE_WINDOW_MS = 400L;

    private static boolean installed;

    /** 必须持有强引用，否则 router 会被 GC 掉、注入到页面的函数随之失效。 */
    private static CefMessageRouter router;

    /** 去重用的"上一次请求"。见 {@link #isDuplicate}。 */
    private static int lastBrowserId = -1;
    private static String lastUrl = "";
    private static long lastRequestTime;

    private PopupHandler() {
    }

    /**
     * 注入到每个页面里的脚本：把"要开新窗口"的意图转成 {@code webscreenQuery} 调用。
     *
     * <p>覆盖三条路径：{@code target} 不为 _self 的链接点击、表单提交、以及 {@code window.open}。
     * 万一 {@code webscreenQuery} 不可用（router 没装上），退化成同窗口导航 —— 至少能跳过去。</p>
     */
    private static final String INJECT_SCRIPT = """
            (function () {
              if (window.__webscreenHooked) { return; }
              window.__webscreenHooked = true;

              function absolute(raw) {
                try { return new URL(raw, location.href).href; } catch (e) { return raw; }
              }

              function requestOpen(raw) {
                if (!raw) { return; }
                var url = absolute(raw);
                try {
                  if (typeof window.webscreenQuery === 'function') {
                    window.webscreenQuery({
                      request: 'open:' + url,
                      onFailure: function () { location.href = url; }
                    });
                    return;
                  }
                } catch (e) { }
                location.href = url;
              }

              document.addEventListener('click', function (event) {
                var node = event.target;
                while (node && node.nodeType !== 1) { node = node.parentNode; }
                while (node && node.tagName !== 'A') { node = node.parentElement; }
                if (!node) { return; }
                var target = (node.getAttribute('target') || '').toLowerCase();
                if (target === '' || target === '_self' || target === '_top' || target === '_parent') { return; }
                event.preventDefault();
                event.stopImmediatePropagation();
                requestOpen(node.href);
              }, true);

              document.addEventListener('submit', function (event) {
                var form = event.target;
                if (!form || !form.getAttribute) { return; }
                var target = (form.getAttribute('target') || '').toLowerCase();
                if (target !== '' && target !== '_self') {
                  event.preventDefault();
                  requestOpen(form.action);
                }
              }, true);

              window.open = function (url) {
                if (url) { requestOpen(String(url)); }
                return null;
              };
            })();
            """;

    /** 幂等安装。必须在 CEF 初始化完成、且任何浏览器创建之前调用。 */
    public static synchronized void install() {
        if (installed || !MCEF.isInitialized() || MCEF.getClient() == null) {
            return;
        }
        try {
            org.cef.CefClient client = MCEF.getClient().getHandle();

            // ---- 1) 消息路由：页面里的 JS 通过 window.webscreenQuery 把网址回传给我们 ----
            router = CefMessageRouter.create(
                    new CefMessageRouter.CefMessageRouterConfig(QUERY_FUNCTION, QUERY_FUNCTION + "Cancel"));
            router.addHandler(new CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                                       String request, boolean persistent, CefQueryCallback callback) {
                    if (request == null || !request.startsWith(OPEN_PREFIX)) {
                        return false;
                    }
                    String url = request.substring(OPEN_PREFIX.length()).trim();
                    WebScreenMod.LOGGER.info("[WebScreen] 网页请求打开新窗口 -> {}", url);
                    if (!url.isEmpty()) {
                        handle(browser, url);
                    }
                    if (callback != null) {
                        callback.success("");
                    }
                    return true;
                }
            }, true);
            client.addMessageRouter(router);

            // ---- 2) 每次页面加载完把拦截脚本注进去 ----
            MCEF.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    if (frame == null || !frame.isMain()) {
                        return;
                    }
                    try {
                        frame.executeJavaScript(INJECT_SCRIPT, frame.getURL(), 0);
                    } catch (Throwable throwable) {
                        WebScreenMod.LOGGER.warn("[WebScreen] 注入弹窗拦截脚本失败", throwable);
                    }
                }
            });

            // ---- 3) 兜底：万一有站点绕过 DOM 事件，在 CEF 层接住 ----
            client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override
                public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                             String targetUrl, String targetFrameName) {
                    WebScreenMod.LOGGER.info("[WebScreen] onBeforePopup -> {}", targetUrl);
                    if (targetUrl != null && !targetUrl.isEmpty()) {
                        handle(browser, targetUrl);
                    }
                    return true;
                }
            });
            client.addRequestHandler(new CefRequestHandlerAdapter() {
                @Override
                public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame,
                                                String targetUrl, boolean userGesture) {
                    WebScreenMod.LOGGER.info("[WebScreen] onOpenURLFromTab -> {}", targetUrl);
                    if (targetUrl != null && !targetUrl.isEmpty()) {
                        handle(browser, targetUrl);
                    }
                    return true;
                }
            });

            installed = true;
            WebScreenMod.LOGGER.info("[WebScreen] 已接管网页弹窗（JS 注入 + 消息路由）。");
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.error("[WebScreen] 注册弹窗处理器失败", throwable);
        }
    }

    /**
     * 分配一个"新窗口"请求。
     *
     * <p>回调来自 CEF 线程，统一切回游戏线程再动世界数据。</p>
     */
    private static void handle(CefBrowser browser, String targetUrl) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> dispatch(browser, targetUrl));
        } else {
            dispatch(browser, targetUrl);
        }
    }

    /**
     * 同一次点击是否会从两条路各来一次？
     *
     * <p>会。像哔哩哔哩这种站点，按钮往往既是 {@code <a target="_blank">}，
     * 又在自己的 click 处理里调了一次 {@code window.open} —— 于是我们注入的
     * 链接拦截和 {@code window.open} 覆盖会各报一次（实测两次日志只差 1 毫秒）。
     * 这里按"同一个浏览器 + 同一个网址 + 400 毫秒内"去重，只认第一次。</p>
     */
    private static synchronized boolean isDuplicate(CefBrowser browser, String url) {
        long now = System.currentTimeMillis();
        // 用 identityHashCode 而不是 browser.getIdentifier()：后者要过 JNI，
        // 没必要为了去重去碰 native。
        int browserId = System.identityHashCode(browser);
        if (browserId == lastBrowserId && url.equals(lastUrl)
                && now - lastRequestTime < DEDUPLICATE_WINDOW_MS) {
            return true;
        }
        lastBrowserId = browserId;
        lastUrl = url;
        lastRequestTime = now;
        return false;
    }

    /** 先试着开到新标签；没有标签方块可用就退化成"当前屏幕直接跳过去"。 */
    private static void dispatch(CefBrowser browser, String url) {
        if (isDuplicate(browser, url)) {
            return;
        }
        if (browser instanceof MCEFBrowser mcef) {
            BlockPos controller = ScreenBrowserManager.controllerOf(mcef);
            if (controller != null && openInNewTab(controller, url)) {
                return;
            }
        }
        navigateInPlace(browser, url);
    }

    /**
     * 占用屏幕顶上的一个空闲标签方块。
     *
     * @return 是否成功开了新标签；false 表示调用方应该走同窗口跳转
     */
    private static boolean openInNewTab(BlockPos controller, String url) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(controller);
        if (!(state.getBlock() instanceof WebScreenBlock)) {
            return false;
        }
        Direction facing = state.getValue(WebScreenBlock.FACING);
        ScreenLayout.Layout layout = ScreenLayout.computeCached(level, controller, facing);
        BlockPos free = TabManager.findFreeTab(level, layout, facing);
        if (free == null) {
            // 标签都占满了（或者屏幕顶上压根没放标签方块）-> 交给调用方同窗口跳转
            return false;
        }
        // 网址要落进世界数据，交给服务端写；客户端这边先把激活状态切过去，
        // 等 SyncUrlPayload 回来画面就会跟着变。
        PacketDistributor.sendToServer(new SetUrlPayload(free, url));
        TabManager.activate(controller, free);
        WebScreenMod.LOGGER.info("[WebScreen] 新窗口链接 -> 新标签 {}: {}", free.toShortString(), url);
        return true;
    }

    private static void navigateInPlace(CefBrowser browser, String url) {
        try {
            browser.loadURL(url);
            WebScreenMod.LOGGER.info("[WebScreen] 新窗口链接 -> 当前屏幕跳转: {}", url);
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.warn("[WebScreen] 跳转新窗口链接失败: " + url, throwable);
        }
    }
}

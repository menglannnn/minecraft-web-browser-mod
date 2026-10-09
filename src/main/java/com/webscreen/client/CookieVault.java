package com.webscreen.client;

import com.cinemamod.mcef.MCEF;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webscreen.WebScreenMod;
import net.neoforged.fml.loading.FMLPaths;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/**
 * 把本机浏览器的登录信息（Cookie）灌进内嵌的 Chromium。
 *
 * <p>MCEF 里的 Chromium 是一个**独立 profile**（缓存目录在 {@code mods/mcef-cache}），
 * 所以它默认不认识你系统浏览器里的登录状态 —— 表现就是 B 站看着看着弹登录框。
 * 这里读取 {@code config/webscreen-cookies.json}，通过 CEF 的
 * {@link CefCookieManager#getGlobalManager()} <b>把 Cookie 直接写进浏览器</b>
 * （注意不是用 JS 的 {@code document.cookie}，HttpOnly 的登录票据那样写不进去）。</p>
 *
 * <p>JSON 支持两种格式，随便哪种都能直接用：</p>
 * <ul>
 *     <li>本模组 {@code tools/import_cookies.py} 导出的 <code>{"cookies":[...]}</code>；</li>
 *     <li>浏览器扩展 Cookie-Editor / EditThisCookie 导出的顶层数组。</li>
 * </ul>
 *
 * <p>注入的 Cookie 会落在 MCEF 的持久化缓存里，所以只需要导一次；
 * 之后就算把这个文件删掉，登录状态也还在。</p>
 */
public final class CookieVault {

    /** 放在 {@code .minecraft/config/} 下。 */
    private static final String FILE_NAME = "webscreen-cookies.json";

    /** 每 40 tick（2 秒）看一次文件有没有变，变了就重新注入。 */
    private static final int CHECK_INTERVAL = 40;

    private static long lastModified = Long.MIN_VALUE;
    private static int tickCounter;
    private static boolean loggedMissing;

    private CookieVault() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** 客户端每 tick 调一次。 */
    public static void onClientTick() {
        if (++tickCounter % CHECK_INTERVAL != 0) {
            return;
        }
        if (!isCefReady()) {
            return;
        }
        try {
            Path file = file();
            if (!Files.isRegularFile(file)) {
                if (!loggedMissing) {
                    loggedMissing = true;
                    WebScreenMod.LOGGER.info(
                            "[WebScreen] 没找到 {} —— 网页方块里的浏览器将使用自己的登录状态。"
                                    + "想复用系统浏览器的登录，跑一下 tools/import_cookies.py。",
                            file);
                }
                return;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == lastModified) {
                return;
            }
            lastModified = modified;
            inject(file);
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.warn("[WebScreen] 注入 Cookie 时出错", throwable);
        }
    }

    private static boolean isCefReady() {
        try {
            return MCEF.isInitialized();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static void inject(Path file) throws Exception {
        String text = Files.readString(file);
        JsonElement root = JsonParser.parseString(text);

        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("cookies")) {
            array = root.getAsJsonObject().getAsJsonArray("cookies");
        } else {
            WebScreenMod.LOGGER.warn("[WebScreen] {} 格式不认识：应该是数组，或者带 cookies 字段的对象", file);
            return;
        }

        CefCookieManager manager = CefCookieManager.getGlobalManager();
        int ok = 0;
        int skipped = 0;

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String domain = string(entry, "domain");
            String name = string(entry, "name");
            String value = string(entry, "value");
            if (domain.isEmpty() || name.isEmpty() || value.isEmpty()) {
                skipped++;
                continue;
            }
            String path = stringOr(entry, "path", "/");
            boolean secure = boolOr(entry, "secure", true);
            boolean httpOnly = boolOr(entry, "httpOnly", false);
            Date expires = expiresOf(entry);

            String host = domain.startsWith(".") ? domain.substring(1) : domain;
            String url = (secure ? "https://" : "http://") + host + (path.startsWith("/") ? path : "/" + path);

            try {
                CefCookie cookie = new CefCookie(name, value, domain, path, secure, httpOnly,
                        null, null, expires != null, expires);
                if (manager.setCookie(url, cookie)) {
                    ok++;
                } else {
                    skipped++;
                }
            } catch (Throwable throwable) {
                skipped++;
            }
        }

        manager.flushStore(() -> {
        });
        WebScreenMod.LOGGER.info("[WebScreen] 已从 {} 注入 {} 条 Cookie（跳过 {} 条）", file.getFileName(), ok, skipped);
    }

    // ---------------------------------------------------------------- JSON 取值

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value.isEmpty() ? fallback : value;
    }

    private static boolean boolOr(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (Throwable throwable) {
            return fallback;
        }
    }

    /** 兼容两套字段名：本模组的 {@code expires}（毫秒）与 Cookie-Editor 的 {@code expirationDate}（秒）。 */
    private static Date expiresOf(JsonObject object) {
        JsonElement seconds = object.get("expirationDate");
        if (seconds != null && seconds.isJsonPrimitive()) {
            try {
                double value = seconds.getAsDouble();
                if (value > 0.0D) {
                    return new Date((long) (value * 1000.0D));
                }
            } catch (Throwable ignored) {
                // 落到下面继续试
            }
        }
        JsonElement millis = object.get("expires");
        if (millis != null && millis.isJsonPrimitive()) {
            try {
                long value = millis.getAsLong();
                if (value > 0L) {
                    return new Date(value);
                }
            } catch (Throwable ignored) {
                // 没写过期时间 = 会话 Cookie
            }
        }
        return null;
    }
}

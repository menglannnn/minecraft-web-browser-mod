package com.webscreen.util;

import java.util.Locale;

/**
 * 网址清洗 / 规范化，服务端与客户端都使用同一套规则。
 */
public final class UrlUtil {

    private UrlUtil() {
    }

    public static final int MAX_LENGTH = 512;

    /** 明确禁止的协议，防止 javascript: 之类的东西被塞进浏览器。 */
    private static final String[] BLOCKED_SCHEMES = {
            "javascript:", "data:", "file:", "vbscript:", "blob:", "about:", "chrome:", "chrome-extension:"
    };

    /**
     * 把用户输入变成一个可用的 URL。
     *
     * @return 规范化后的 URL；输入为空或包含被禁止的协议时返回空串。
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String value = input.trim();
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH);
        }
        if (value.isEmpty()) {
            return "";
        }

        String lower = value.toLowerCase(Locale.ROOT);
        for (String scheme : BLOCKED_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return "";
            }
        }

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "https://" + value;
        }
        return value;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

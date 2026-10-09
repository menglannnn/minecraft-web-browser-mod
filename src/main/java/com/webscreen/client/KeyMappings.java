package com.webscreen.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 本模组的按键绑定。
 *
 * <p>注意：这个按键只在 {@code Minecraft.screen == null} 时才由原版轮询到，
 * 所以在"网页输入模式"界面里是按这个界面自己的 {@code keyPressed} 收的。</p>
 */
public final class KeyMappings {

    /** 打开"网页输入模式"：锁鼠标、把键盘全部转发给网页（登录、搜索框都要用）。 */
    public static final KeyMapping REMOTE_CONTROL = new KeyMapping(
            "key.webscreen.remote_control",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.webscreen");

    private KeyMappings() {
    }
}

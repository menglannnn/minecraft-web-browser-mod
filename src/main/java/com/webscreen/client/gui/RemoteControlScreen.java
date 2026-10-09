package com.webscreen.client.gui;

import com.cinemamod.mcef.MCEFBrowser;
import com.webscreen.client.ClientInteractions;
import com.webscreen.client.KeyMappings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * "网页输入模式"。
 *
 * <p>这个界面存在的唯一理由：Minecraft 平时把键盘全都吃掉了，网页里的输入框
 * 根本收不到字。所以这里做一个不暂停游戏、且**锁住鼠标**的界面：</p>
 *
 * <ul>
 *     <li>鼠标仍然锁在准星上 —— {@code MouseHandler.handleAccumulatedMovement} 里
 *         {@code turnPlayer()} 并不受 {@code screen != null} 影响，所以视角照转，
 *         于是可以用准星瞄准网页上的输入框 / 登录按钮；</li>
 *     <li>键盘的按下、抬起、字符全部转发给 Chromium；</li>
 *     <li>鼠标左/右键、滚轮也一并转发到准星当前指着的像素。</li>
 * </ul>
 *
 * <p>按 Esc 或再按一次 F8 退出。</p>
 */
public class RemoteControlScreen extends Screen {

    private static final Component TITLE = Component.translatable("gui.webscreen.remote.title");
    private static final Component HINT = Component.translatable("gui.webscreen.remote.hint");
    private static final Component NO_TARGET = Component.translatable("gui.webscreen.remote.no_target");

    // CEF 的 EventFlags
    private static final int EVFLAG_SHIFT_DOWN = 1 << 1;
    private static final int EVFLAG_CONTROL_DOWN = 1 << 2;
    private static final int EVFLAG_ALT_DOWN = 1 << 3;

    public RemoteControlScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        grabMouse();
    }

    @Override
    public void tick() {
        // 原版打开界面时会释放鼠标，这里要一直把它按回去
        grabMouse();
    }

    private void grabMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    // ---------------------------------------------------------------- 键盘

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || KeyMappings.REMOTE_CONTROL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        MCEFBrowser browser = currentBrowser();
        if (browser != null) {
            browser.setFocus(true);
            browser.sendKeyPress(toWindowsKeyCode(keyCode), scanCode, toCefModifiers(modifiers));
        }
        // 一律吞掉：不然像 E / Q / 空格 还会触发游戏行为
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return true;
        }
        MCEFBrowser browser = currentBrowser();
        if (browser != null) {
            browser.sendKeyRelease(toWindowsKeyCode(keyCode), scanCode, toCefModifiers(modifiers));
        }
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        MCEFBrowser browser = currentBrowser();
        if (browser != null) {
            browser.sendKeyTyped(codePoint, toCefModifiers(modifiers));
        }
        return true;
    }

    // ---------------------------------------------------------------- 鼠标

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ClientInteractions.BrowserTarget target = ClientInteractions.resolveTarget();
        if (target == null) {
            return false;
        }
        // 这里的 button 本来就是 GLFW 的按钮码（0=左 1=右 2=中），直接透传给网页
        ClientInteractions.click(target, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ClientInteractions.BrowserTarget target = ClientInteractions.resolveTarget();
        if (target == null) {
            return false;
        }
        target.browser().sendMouseWheel(target.pixelX(), target.pixelY(), scrollY, 0);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return true;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        boolean hasTarget = ClientInteractions.resolveTarget() != null;
        Component line = hasTarget ? HINT : NO_TARGET;
        int width = this.font.width(line);

        int x = (this.width - width) / 2;
        int y = 12;
        graphics.fill(x - 6, y - 5, x + width + 6, y + 11, 0x99000000);
        graphics.drawString(this.font, line, x, y, hasTarget ? 0xFF7DE08B : 0xFFE0A24B, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- 辅助

    private MCEFBrowser currentBrowser() {
        ClientInteractions.BrowserTarget target = ClientInteractions.resolveTarget();
        return target == null ? null : target.browser();
    }

    /** GLFW 的修饰键 -> CEF 的 EventFlags。 */
    private static int toCefModifiers(int glfwModifiers) {
        int result = 0;
        if ((glfwModifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            result |= EVFLAG_SHIFT_DOWN;
        }
        if ((glfwModifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            result |= EVFLAG_CONTROL_DOWN;
        }
        if ((glfwModifiers & GLFW.GLFW_MOD_ALT) != 0) {
            result |= EVFLAG_ALT_DOWN;
        }
        return result;
    }

    /**
     * GLFW 键码 -&gt; Windows 虚拟键码。
     *
     * <p>MCEF 是把传进来的键码**原样**塞进 {@code CefKeyEvent.windows_key_code} 的，
     * 而 GLFW 的键码只在「字母 / 数字 / 空格」这几个和 Windows VK 恰好重合，
     * 退格、回车、方向键之类完全对不上 —— 不转换的话网页里连打错字都删不掉。</p>
     */
    private static int toWindowsKeyCode(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xA0;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xA1;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xA2;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xA3;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xA4;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xA5;
            default -> {
                if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F12) {
                    yield 0x70 + (glfwKey - GLFW.GLFW_KEY_F1);
                }
                // 可见 ASCII 区间里 GLFW 与 Windows VK 是一致的
                yield (glfwKey >= 0x20 && glfwKey <= 0x7E) ? glfwKey : 0;
            }
        };
    }
}

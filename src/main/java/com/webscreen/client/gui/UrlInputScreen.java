package com.webscreen.client.gui;

import com.webscreen.network.SetUrlPayload;
import com.webscreen.util.UrlUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 右键网页方块后弹出的"输入网址"界面。
 */
public class UrlInputScreen extends Screen {

    private static final int BOX_WIDTH = 260;

    /** 常见站点的登录页，省得手打。 */
    private static final String BILIBILI_LOGIN = "https://passport.bilibili.com/login";

    private final BlockPos pos;
    private final String initialUrl;
    private EditBox urlBox;

    public UrlInputScreen(BlockPos pos, String initialUrl) {
        super(Component.translatable("webscreen.gui.title"));
        this.pos = pos;
        this.initialUrl = initialUrl == null ? "" : initialUrl;
    }

    @Override
    protected void init() {
        int x = (this.width - BOX_WIDTH) / 2;
        int y = this.height / 2 - 50;

        this.urlBox = new EditBox(this.font, x, y, BOX_WIDTH, 20, Component.translatable("webscreen.gui.hint"));
        this.urlBox.setMaxLength(UrlUtil.MAX_LENGTH);
        this.urlBox.setValue(this.initialUrl);
        this.urlBox.setHint(Component.translatable("webscreen.gui.hint"));
        this.addRenderableWidget(this.urlBox);
        this.setInitialFocus(this.urlBox);

        int halfWidth = (BOX_WIDTH - 6) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("webscreen.gui.play"),
                        button -> submit(this.urlBox.getValue()))
                .bounds(x, y + 30, halfWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("webscreen.gui.stop"),
                        button -> submit(""))
                .bounds(x + halfWidth + 6, y + 30, halfWidth, 20).build());

        // 第二排：登录相关。网页里的输入框需要"输入模式"才收得到键盘。
        this.addRenderableWidget(Button.builder(Component.translatable("webscreen.gui.typing"),
                        button -> {
                            onClose();
                            Minecraft.getInstance().setScreen(new RemoteControlScreen());
                        })
                .bounds(x, y + 54, halfWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("webscreen.gui.bilibili_login"),
                        button -> this.urlBox.setValue(BILIBILI_LOGIN))
                .bounds(x + halfWidth + 6, y + 54, halfWidth, 20).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y + 78, BOX_WIDTH, 20).build());
    }

    private void submit(String raw) {
        String url = UrlUtil.normalize(raw);
        if (!raw.isBlank() && url.isEmpty()) {
            if (this.urlBox != null) {
                this.urlBox.setHint(Component.translatable("webscreen.gui.invalid"));
            }
            return;
        }
        PacketDistributor.sendToServer(new SetUrlPayload(this.pos, url));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 82, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("webscreen.gui.tip"),
                this.width / 2, this.height / 2 + 78, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

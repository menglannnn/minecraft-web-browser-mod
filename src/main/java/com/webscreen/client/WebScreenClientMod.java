package com.webscreen.client;

import com.cinemamod.mcef.MCEF;
import com.webscreen.ModBlockEntities;
import com.webscreen.WebScreenMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口：注册方块实体渲染器、按键绑定、鼠标/键盘交互，以及本地 Cookie 注入。
 */
@Mod(value = WebScreenMod.MODID, dist = Dist.CLIENT)
public class WebScreenClientMod {

    public WebScreenClientMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(WebScreenClientMod::registerRenderers);
        modBus.addListener(WebScreenClientMod::registerKeys);
        NeoForge.EVENT_BUS.register(new ClientInteractions());

        // CEF 一初始化完就把"新窗口"接管装上。
        // 必须赶在任何浏览器创建之前 —— CefMessageRouter 有"先于 browser 创建"的硬性要求。
        MCEF.scheduleForInit(success -> {
            if (success) {
                PopupHandler.install();
            }
        });
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.WEB_SCREEN.get(), WebScreenRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.WEB_TAB.get(), WebTabRenderer::new);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(KeyMappings.REMOTE_CONTROL);
    }
}

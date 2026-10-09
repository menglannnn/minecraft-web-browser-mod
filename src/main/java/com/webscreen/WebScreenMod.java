package com.webscreen;

import com.mojang.logging.LogUtils;
import com.webscreen.network.SetUrlPayload;
import com.webscreen.network.SyncUrlPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Web Screen —— 可以放置的"网页方块"。
 *
 * 方块本身：宽 1 格、高 2 格（1:2），空手右键输入网址后由 MCEF 把网页渲染上去。
 * 服务端只负责保存/同步 URL，真正的浏览器只在客户端创建。
 */
@Mod(WebScreenMod.MODID)
public class WebScreenMod {

    public static final String MODID = "webscreen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WebScreenMod(IEventBus modBus, ModContainer container) {
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModCreativeTabs.register(modBus);

        modBus.addListener(WebScreenMod::registerPayloads);

        LOGGER.info("[WebScreen] 已加载。");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // 客户端 -> 服务端：设置方块上的网址
        registrar.playToServer(SetUrlPayload.TYPE, SetUrlPayload.STREAM_CODEC, SetUrlPayload::handle);
        // 服务端 -> 客户端：广播当前网址
        registrar.playToClient(SyncUrlPayload.TYPE, SyncUrlPayload.STREAM_CODEC, SyncUrlPayload::handle);
    }
}

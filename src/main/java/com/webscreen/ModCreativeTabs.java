package com.webscreen;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WebScreenMod.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.webscreen"))
                    .icon(() -> new ItemStack(ModBlocks.WEB_SCREEN_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.WEB_SCREEN_ITEM.get());
                        output.accept(ModBlocks.WEB_TAB_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        CREATIVE_MODE_TABS.register(modBus);
    }
}

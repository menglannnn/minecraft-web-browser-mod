package com.webscreen;

import com.webscreen.block.WebScreenBlockEntity;
import com.webscreen.block.WebTabBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WebScreenMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WebScreenBlockEntity>> WEB_SCREEN =
            BLOCK_ENTITIES.register("web_screen", () -> BlockEntityType.Builder
                    .of(WebScreenBlockEntity::new, ModBlocks.WEB_SCREEN.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WebTabBlockEntity>> WEB_TAB =
            BLOCK_ENTITIES.register("web_tab", () -> BlockEntityType.Builder
                    .of(WebTabBlockEntity::new, ModBlocks.WEB_TAB.get())
                    .build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}

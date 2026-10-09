package com.webscreen;

import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebTabBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    private ModBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WebScreenMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WebScreenMod.MODID);

    public static final DeferredBlock<WebScreenBlock> WEB_SCREEN = BLOCKS.register("web_screen",
            () -> new WebScreenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .dynamicShape()
                    .lightLevel(state -> 5)));

    public static final DeferredItem<BlockItem> WEB_SCREEN_ITEM =
            ITEMS.registerSimpleBlockItem("web_screen", WEB_SCREEN);

    /** 标签页方块：半格高，贴在屏幕顶上，用来在多个网址之间切换。 */
    public static final DeferredBlock<WebTabBlock> WEB_TAB = BLOCKS.register("web_tab",
            () -> new WebTabBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .dynamicShape()
                    .lightLevel(state -> 3)));

    public static final DeferredItem<BlockItem> WEB_TAB_ITEM =
            ITEMS.registerSimpleBlockItem("web_tab", WEB_TAB);

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}

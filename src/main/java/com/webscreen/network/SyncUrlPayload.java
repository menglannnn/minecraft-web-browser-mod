package com.webscreen.network;

import com.webscreen.WebScreenMod;
import com.webscreen.block.WebScreenBlockEntity;
import com.webscreen.block.WebTabBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 -> 客户端：告诉客户端某个网页方块现在要播哪个网址。
 * 不依赖原版方块实体的同步语义，逻辑完全自控。
 */
public record SyncUrlPayload(BlockPos pos, String url) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncUrlPayload> TYPE =
            new CustomPacketPayload.Type<>(WebScreenMod.id("sync_url"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncUrlPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SyncUrlPayload::pos,
                    ByteBufCodecs.STRING_UTF8, SyncUrlPayload::url,
                    SyncUrlPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            if (minecraft.level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen) {
                screen.applyUrl(url);
            } else if (minecraft.level.getBlockEntity(pos) instanceof WebTabBlockEntity tab) {
                tab.applyUrl(url);
            }
        });
    }
}

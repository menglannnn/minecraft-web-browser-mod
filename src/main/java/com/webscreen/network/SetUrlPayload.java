package com.webscreen.network;

import com.webscreen.WebScreenMod;
import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebScreenBlockEntity;
import com.webscreen.block.WebTabBlock;
import com.webscreen.block.WebTabBlockEntity;
import com.webscreen.util.UrlUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 -&gt; 服务端：把某个网页方块要播放的网址写进去。
 * 如果这个方块是多块拼成的大屏的一部分，则整块屏幕的每一格都会写入同一个网址。
 */
public record SetUrlPayload(BlockPos pos, String url) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetUrlPayload> TYPE =
            new CustomPacketPayload.Type<>(WebScreenMod.id("set_url"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetUrlPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetUrlPayload::pos,
                    ByteBufCodecs.STRING_UTF8, SetUrlPayload::url,
                    SetUrlPayload::new);

    /** 允许操作的距离（16 格），留够余量给多格大屏。 */
    private static final double MAX_DISTANCE_SQR = 256.0D;

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            if (!level.isLoaded(pos)) {
                return;
            }
            // 防止远程改别人的屏幕
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQR) {
                return;
            }

            BlockState state = level.getBlockState(pos);
            // 标签方块：直接写它自己那一个网址即可
            if (state.getBlock() instanceof WebTabBlock) {
                if (level.getBlockEntity(pos) instanceof WebTabBlockEntity tab) {
                    tab.setUrl(UrlUtil.normalize(url));
                }
                return;
            }
            if (!(state.getBlock() instanceof WebScreenBlock)) {
                return;
            }
            Direction facing = state.getValue(WebScreenBlock.FACING);
            BlockPos base = state.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();

            ScreenLayout.Layout layout = ScreenLayout.compute(level, base, facing);
            String normalized = UrlUtil.normalize(url);
            for (BlockPos unit : ScreenLayout.unitPositions(layout, facing)) {
                if (level.getBlockEntity(unit) instanceof WebScreenBlockEntity screen) {
                    screen.setUrl(normalized);
                }
            }
        });
    }
}

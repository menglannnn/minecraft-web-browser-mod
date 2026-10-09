package com.webscreen.block;

import com.webscreen.ModBlockEntities;
import com.webscreen.network.SyncUrlPayload;
import com.webscreen.util.UrlUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 保存该网页方块当前要播放的 URL。
 * 服务端写入后会同步给附近的所有客户端，客户端据此创建/更新 Chromium 实例。
 */
public class WebScreenBlockEntity extends BlockEntity {

    private static final String TAG_URL = "Url";

    private String url = "";

    public WebScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WEB_SCREEN.get(), pos, state);
    }

    public String getUrl() {
        return url;
    }

    /** 服务端侧调用：写入并通知附近客户端。 */
    public void setUrl(String newUrl) {
        String cleaned = UrlUtil.normalize(newUrl);
        if (cleaned.equals(this.url)) {
            return;
        }
        applyUrl(cleaned);
        if (level != null && !level.isClientSide) {
            // 双保险：原版方块实体同步 + 自定义广播包
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
            PacketDistributor.sendToAllPlayers(new SyncUrlPayload(worldPosition, cleaned));
        }
    }

    /** 只改数据，不广播（客户端收到 SyncUrlPayload 时走这里）。 */
    public void applyUrl(String newUrl) {
        String cleaned = UrlUtil.normalize(newUrl);
        if (cleaned.equals(this.url)) {
            return;
        }
        this.url = cleaned;
        setChanged();
    }

    // ---------------------------------------------------------------- 序列化

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!url.isEmpty()) {
            tag.putString(TAG_URL, url);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.url = tag.getString(TAG_URL);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

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
 * 标签页方块要在浏览器里打开的网址。
 *
 * <p>它自己<b>不跑浏览器</b>：真正的 Chromium 仍然挂在屏幕的 controller 上。
 * 标签只是"一组可切换的网址槽位"，屏幕显示其中被激活的那一个。
 * 这么做的好处是切换标签 = 换网址，完全复用 {@link com.webscreen.client.ScreenBrowserManager}
 * 现有的"网址变了就重建浏览器"逻辑，不需要同时维持多个 Chromium 实例。</p>
 */
public class WebTabBlockEntity extends BlockEntity {

    private static final String TAG_URL = "Url";

    private String url = "";

    public WebTabBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WEB_TAB.get(), pos, state);
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
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
            PacketDistributor.sendToAllPlayers(new SyncUrlPayload(worldPosition, cleaned));
        }
    }

    /** 只改数据，不广播。 */
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

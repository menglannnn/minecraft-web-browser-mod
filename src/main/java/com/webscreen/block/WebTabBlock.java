package com.webscreen.block;

import com.webscreen.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 标签页方块：贴在屏幕最上沿上方那一格的"半格高"方块。
 *
 * <p>放在屏幕上方时，它代表这块屏幕的一个<b>标签页</b>。屏幕同时只会显示其中一个被
 * "激活"的标签。于是一块屏幕就能像浏览器一样在若干网址之间来回切，遇上
 * {@code target="_blank"} 的新窗口链接也有地方安放（见 {@link com.webscreen.client.PopupHandler}）。</p>
 *
 * <p>形状刻意做成"与屏幕面板同样的厚度、但只有半格高"，这样它看起来就是屏幕顶上的一条标签条，
 * 而不是一块突兀的砖。朝向在放置时会自动和下方的屏幕对齐。</p>
 */
public class WebTabBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 与 {@link WebScreenBlock} 保持一致的贴背缝隙与面板厚度。 */
    private static final double PANEL_BACK_GAP = 0.5;
    private static final double PANEL_THICKNESS = 4.0;

    /** 标签方块只有半格高。 */
    private static final double TAB_HEIGHT = 8.0;

    private static final VoxelShape SHAPE_NORTH =
            Block.box(0.0, 0.0, 12.0 - PANEL_BACK_GAP, 16.0, TAB_HEIGHT, 16.0 - PANEL_BACK_GAP);
    private static final VoxelShape SHAPE_SOUTH =
            Block.box(0.0, 0.0, PANEL_BACK_GAP, 16.0, TAB_HEIGHT, PANEL_BACK_GAP + PANEL_THICKNESS);
    private static final VoxelShape SHAPE_EAST =
            Block.box(PANEL_BACK_GAP, 0.0, 0.0, PANEL_BACK_GAP + PANEL_THICKNESS, TAB_HEIGHT, 16.0);
    private static final VoxelShape SHAPE_WEST =
            Block.box(12.0 - PANEL_BACK_GAP, 0.0, 0.0, 16.0 - PANEL_BACK_GAP, TAB_HEIGHT, 16.0);

    public WebTabBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    private static final com.mojang.serialization.MapCodec<WebTabBlock> CODEC = simpleCodec(WebTabBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 四个朝向的形状各不相同，因为标签条贴着不同的那一面。 */
    public static VoxelShape tabShape(Direction facing) {
        return switch (facing) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return tabShape(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return tabShape(state.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ---------------------------------------------------------------- 放置

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        // 放在屏幕顶上的话，直接沿用屏幕的朝向，省得玩家自己去对齐
        BlockState below = level.getBlockState(pos.below());
        if (below.getBlock() instanceof WebScreenBlock
                && below.getValue(WebScreenBlock.HALF) == DoubleBlockHalf.UPPER) {
            facing = below.getValue(WebScreenBlock.FACING);
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack(ModBlocks.WEB_TAB_ITEM.get()));
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 右键 = 把这个标签切到屏幕上。
     *
     * <p>按住 Shift 右键 = 改这个标签的网址。</p>
     *
     * <p>注意这里不会被 {@link com.webscreen.client.ClientInteractions} 抢先拦下：
     * 它的命中判定只认 {@link WebScreenBlock}，而标签方块是另一种方块，
     * 所以右键会原原本本地走到这里来。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide()) {
            if (player.isShiftKeyDown()) {
                com.webscreen.client.ClientHooks.openUrlScreen(pos);
            } else {
                com.webscreen.client.ClientHooks.activateTab(pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WebTabBlockEntity(pos, state);
    }
}

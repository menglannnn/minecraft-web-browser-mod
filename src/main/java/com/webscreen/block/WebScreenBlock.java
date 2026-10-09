package com.webscreen.block;

import com.webscreen.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 网页方块。
 *
 * <p>占用竖直方向两格（宽 1 格 x 高 2 格 -> 屏幕比例正好 1:2）。
 * 下半格持有 {@link WebScreenBlockEntity}，上半格只是陪衬。破坏任意一半，另一半也会一起消失。</p>
 */
public class WebScreenBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /**
     * 面板厚度 4/16 格。背面再留 {@link #PANEL_BACK_GAP} 的缝，
     * 这样面板是**贴着方块背面（贴墙那一侧）**的，而不是悬在方块中间 ——
     * 于是把屏幕放在墙边时，它看起来就是"贴"在墙上的。
     */
    private static final double PANEL_BACK_GAP = 0.5;

    private static final VoxelShape SHAPE_NORTH = Block.box(0.0, 0.0, 12.0 - PANEL_BACK_GAP, 16.0, 16.0, 16.0 - PANEL_BACK_GAP);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0, 0.0, PANEL_BACK_GAP, 16.0, 16.0, PANEL_BACK_GAP + 4.0);
    private static final VoxelShape SHAPE_EAST = Block.box(PANEL_BACK_GAP, 0.0, 0.0, PANEL_BACK_GAP + 4.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_WEST = Block.box(12.0 - PANEL_BACK_GAP, 0.0, 0.0, 16.0 - PANEL_BACK_GAP, 16.0, 16.0);

    public WebScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    private static final com.mojang.serialization.MapCodec<WebScreenBlock> CODEC = simpleCodec(WebScreenBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    // ---------------------------------------------------------------- 形状

    /** 面板所在的位置。注意：四个朝向的形状**各不相同**，因为面板贴着不同的那一面。 */
    public static VoxelShape panelShape(Direction facing) {
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
        return panelShape(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return panelShape(state.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ---------------------------------------------------------------- 放置 / 破坏

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        // 贴着墙 / 天花板放的时候，点击位置的上方经常被占住，
        // 这时往下挪一格再试，而不是直接失败。
        if (!canPlaceAt(level, pos, context)) {
            BlockPos lower = pos.below();
            if (level.getBlockState(lower).canBeReplaced(context) && canPlaceAt(level, lower, context)) {
                pos = lower;
            } else {
                return null;
            }
        }

        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    /** 下半格放在 lower 时，上下两格是否都放得下。 */
    private static boolean canPlaceAt(Level level, BlockPos lower, BlockPlaceContext context) {
        if (lower.getY() >= level.getMaxBuildHeight() - 1) {
            return false;
        }
        return level.getBlockState(lower.above()).canBeReplaced(context);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        // flag 3 与 vanilla 门/高花一致：更新客户端 + 通知邻居，但不吞掉落物
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
        if (!level.isClientSide) {
            // 拼到已有屏幕上时，继承正在播放的网址，避免新拼出来的屏幕是黑的
            ScreenLayout.inheritUrl(level, pos, state.getValue(FACING));
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            DoubleBlockHalf half = state.getValue(HALF);
            BlockPos otherPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState other = level.getBlockState(otherPos);
            if (other.is(this) && other.getValue(HALF) != half) {
                // 静默移除另一半，避免重复掉落
                level.removeBlock(otherPos, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 不论破坏哪一半，都只掉一个物品。 */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack(ModBlocks.WEB_SCREEN_ITEM.get()));
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 空手右键。
     *
     * <p>正常路径其实<b>走不到这里</b>：{@link com.webscreen.client.ClientInteractions} 会在更早的
     * 输入事件里就把右键拦下 —— 有画面的屏幕把右键转发给网页，按住 Shift 时则自己弹出输入网址界面。</p>
     *
     * <p>这里兜的是"屏幕还没加载网页、又没按 Shift"的情况：这时右键转发给网页也没意义，
     * 干脆直接弹出输入网址界面，免得一块空屏幕右键毫无反应。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        BlockPos base = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        if (level.isClientSide()) {
            // 仅在客户端执行；专用服务器上这一分支永远不会被走到，
            // 因此客户端专属类不会被加载。
            com.webscreen.client.ClientHooks.openUrlScreen(base);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new WebScreenBlockEntity(pos, state) : null;
    }

    /** 屏幕朝向的前方（用于渲染和鼠标交互）。 */
    public static Direction frontOf(BlockState state) {
        return state.getValue(FACING);
    }
}

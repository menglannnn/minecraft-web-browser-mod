package com.webscreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.webscreen.WebScreenMod;
import com.webscreen.block.ScreenLayout;
import com.webscreen.block.WebScreenBlock;
import com.webscreen.block.WebScreenBlockEntity;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 把 Chromium 的画面贴到方块正面。
 *
 * <p>每个单元只渲染自己那一小块：整张画面被切成 宽 x 高 份，
 * 第 (col,row) 份贴在对应单元上，所以多块拼起来就是一整张连续的图。
 * 所有单元共用同一个浏览器实例（挂在屏幕左上角那一格）。</p>
 *
 * <p>面板是**贴着方块背面（贴墙那一侧）**的，所以正面平面在朝向轴上并不居中：
 * 朝正轴的 SOUTH / EAST 正面在 {@code 0.5/16 + 4/16}，朝负轴的 NORTH / WEST 在 {@code 15.5/16 - 4/16}。
 * 另外还会在每个单元的背面补一块实心背板，保证从背后看过去是实心外壳而不是穿透。</p>
 */
public class WebScreenRenderer implements BlockEntityRenderer<WebScreenBlockEntity> {

    /** 屏幕四周的外框留白，见 {@link ScreenLayout#MARGIN_X}。 */
    private static final double MARGIN_X = ScreenLayout.MARGIN_X;
    private static final double MARGIN_Y = ScreenLayout.MARGIN_Y;

    /** 面板厚度 4/16，与方块模型一致。 */
    private static final float PANEL_THICKNESS = 4.0F / 16.0F;
    /** 面板背面离方块背面的缝隙，与方块模型一致（避免和墙的贴图 z-fighting）。 */
    private static final float PANEL_BACK_GAP = 0.5F / 16.0F;

    /** 轻微外移，避免与方块模型正面 z-fighting。 */
    private static final float NUDGE = 0.0015F;

    /** 背板往方块内部再让一点，好让模型自己的背面（如果画了）盖在它上面。 */
    private static final float SHELL_INSET = 0.002F;

    /** 背板颜色：和 bezel 外框同色，看起来就是一块实心外壳。 */
    private static final float SHELL_R = 26.0F / 255.0F;
    private static final float SHELL_G = 29.0F / 255.0F;
    private static final float SHELL_B = 35.0F / 255.0F;

    public WebScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** 面板背面在朝向轴上的位置（相对本格最小角）。 */
    private static float backPlane(Direction facing) {
        return switch (facing) {
            case SOUTH, EAST -> PANEL_BACK_GAP;                  // 背面贴着方块最小角那一侧
            case NORTH, WEST -> 1.0F - PANEL_BACK_GAP;           // 背面贴着方块最大角那一侧
            default -> PANEL_BACK_GAP;
        };
    }

    /** 面板正面在朝向轴上的位置（相对本格最小角），不含 NUDGE。 */
    private static float frontPlane(Direction facing) {
        float back = backPlane(facing);
        return switch (facing) {
            case SOUTH, EAST -> back + PANEL_THICKNESS;          // 正面朝正轴
            case NORTH, WEST -> back - PANEL_THICKNESS;          // 正面朝负轴
            default -> back + PANEL_THICKNESS;
        };
    }

    /** 正面法线方向上的微小外移。 */
    private static float nudge(Direction facing) {
        return switch (facing) {
            case SOUTH, EAST -> NUDGE;
            case NORTH, WEST -> -NUDGE;
            default -> 0.0F;
        };
    }

    @Override
    public void render(WebScreenBlockEntity screen, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState state = screen.getBlockState();
        if (!(state.getBlock() instanceof WebScreenBlock)) {
            return;
        }
        if (state.getValue(WebScreenBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        Level level = screen.getLevel();
        if (level == null) {
            return;
        }
        Direction facing = state.getValue(WebScreenBlock.FACING);
        BlockPos pos = screen.getBlockPos();
        if (!ScreenLayout.hasUpperHalf(level, pos, facing)) {
            // 上半格被活塞推掉或被指令替换了，这块单元已经不完整
            return;
        }

        ScreenLayout.Layout layout = ScreenLayout.computeCached(level, pos, facing);
        // 屏幕顶上放了标签方块的话，显示的是"当前激活的那个标签"的网址，否则才是屏幕自己存的网址
        int textureId = ScreenBrowserManager.textureFor(
                layout.controller(),
                TabManager.resolveUrl(level, layout, facing, urlAt(level, layout.controller())),
                ScreenLayout.viewportWidth(layout),
                ScreenLayout.viewportHeight(layout));

        float[][] corners = new float[4][3];
        float[] uvs = new float[8];
        buildQuad(facing, layout, corners, uvs);

        float[][] shell = new float[4][3];
        buildBackQuad(facing, layout, shell);

        pose.pushPose();
        try {
            Matrix4f matrix = pose.last().pose();

            // 方块实体这一阶段的环境状态是"上一个 draw call 留下来的"，
            // 这里显式摆正，免得背板/画面谁的深度判定出错。
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(515); // GL_LEQUAL

            // 先铺背板：从背面看过去必须是一块实心外壳，而不是穿透看到正面画面
            drawSolid(matrix, shell, SHELL_R, SHELL_G, SHELL_B, 1.0F);

            if (textureId != 0) {
                drawTextured(matrix, corners, textureId, uvs);
            } else {
                // 还没准备好画面：给一块深色"待机屏"
                drawSolid(matrix, corners, 0.055F, 0.075F, 0.11F, 1.0F);
            }
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.error("[WebScreen] 渲染网页方块时出错", throwable);
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
        pose.popPose();
    }

    private static String urlAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen ? screen.getUrl() : "";
    }

    /**
     * 计算本单元正面那一片四边形的 4 个顶点（相对本格最小角的偏移）和对应的 UV。
     *
     * <p>顶点顺序固定：左下、右下、右上、左上（站在屏幕正前方看）。</p>
     *
     * <p><b>外框留白只作用在整块屏幕的最外圈。</b>某个边算不算"外边缘"，看的是本单元在
     * 布局里的 col / row，跟邻居无关；因此内部接缝处，相邻两个单元各自算出来的坐标必然
     * 完全相同 —— 画面严丝合缝，不会出现一条条黑边。</p>
     */
    private static void buildQuad(Direction facing, ScreenLayout.Layout layout, float[][] corners, float[] uvs) {
        int col = layout.col();
        int row = layout.row();
        int width = layout.width();
        int height = layout.height();

        Direction right = ScreenLayout.screenRight(facing);
        // 屏幕的"右"在世界坐标里是正方向还是负方向
        boolean rightPositive = right.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        // "右"是沿 X 还是沿 Z
        boolean alongX = right.getAxis() == Direction.Axis.X;

        double visibleWidth = ScreenLayout.visibleWidth(layout);
        double visibleHeight = ScreenLayout.visibleHeight(layout);
        // 本单元底边离"屏幕底边"的高度（单位：格）。row 0 在最上面。
        double unitBase = 2.0 * (height - 1 - row);

        float front = frontPlane(facing) + nudge(facing);

        // 局部坐标：h: 0 = 单元左边缘, 1 = 单元右边缘；v: 0 = 单元底边, 2 = 单元顶边
        final double[][] local = {{0.0, 0.0}, {1.0, 0.0}, {1.0, 2.0}, {0.0, 2.0}};

        for (int i = 0; i < 4; i++) {
            double h = local[i][0];
            double v = local[i][1];

            // 换算成"整块屏幕"下的坐标：s 从屏幕左边缘量起，sv 从屏幕底边量起
            double s = col + h;
            if (col == 0 && h == 0.0) {
                s = MARGIN_X;                       // 整块屏幕的左外边缘
            } else if (col == width - 1 && h == 1.0) {
                s = width - MARGIN_X;               // 整块屏幕的右外边缘
            }
            double sv = unitBase + v;
            if (row == height - 1 && v == 0.0) {
                sv = MARGIN_Y;                      // 整块屏幕的下外边缘
            } else if (row == 0 && v == 2.0) {
                sv = 2.0 * height - MARGIN_Y;        // 整块屏幕的上外边缘
            }

            // 相对本格最小角的横向偏移（沿 right 方向推进）
            double along = rightPositive ? (s - col) : 1.0 - (s - col);
            double y = sv - unitBase;

            if (alongX) {
                corners[i] = new float[]{(float) along, (float) y, front};
            } else {
                corners[i] = new float[]{front, (float) y, (float) along};
            }

            // UV：贴图整张对应"屏幕可见区域"，所以要先扣掉留白再归一化
            uvs[i * 2] = (float) ((s - MARGIN_X) / visibleWidth);
            uvs[i * 2 + 1] = (float) (1.0 - (sv - MARGIN_Y) / visibleHeight);
        }
    }

    /**
     * 背板：贴在本单元面板背面的外侧（往方块内部挪一点点），朝向方块背面。
     *
     * <p>顶点顺序是正面那套的**逆序**，这样从背后看过去才是正面朝向观察者、而不是背面。
     * 背板铺满整格（不含外框留白），所以从背后看是一整块实心外壳。</p>
     */
    private static void buildBackQuad(Direction facing, ScreenLayout.Layout layout, float[][] corners) {
        int col = layout.col();
        int row = layout.row();
        int width = layout.width();
        int height = layout.height();

        Direction right = ScreenLayout.screenRight(facing);
        boolean rightPositive = right.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        boolean alongX = right.getAxis() == Direction.Axis.X;

        double unitBase = 2.0 * (height - 1 - row);

        // 背板放在面板背面的内侧（方块内部方向）
        boolean positiveSide = facing == Direction.SOUTH || facing == Direction.EAST;
        float plane = backPlane(facing) + (positiveSide ? SHELL_INSET : -SHELL_INSET);

        // 逆序遍历：右下、左下、左上、右上 -> 从背面看就是左下、右下、右上、左上
        final double[][] local = {{1.0, 0.0}, {0.0, 0.0}, {0.0, 2.0}, {1.0, 2.0}};

        for (int i = 0; i < 4; i++) {
            double h = local[i][0];
            double v = local[i][1];

            double s = col + h;
            double sv = unitBase + v;
            double along = rightPositive ? (s - col) : 1.0 - (s - col);
            double y = sv - unitBase;

            if (alongX) {
                corners[i] = new float[]{(float) along, (float) y, plane};
            } else {
                corners[i] = new float[]{plane, (float) y, (float) along};
            }
        }
    }

    private static void drawTextured(Matrix4f matrix, float[][] corners, int textureId, float[] uvs) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureId);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2])
                    .setUv(uvs[i * 2], uvs[i * 2 + 1])
                    .setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        BufferUploader.drawWithShader(buffer.build());

        RenderSystem.setShaderTexture(0, 0);
    }

    private static void drawSolid(Matrix4f matrix, float[][] corners, float red, float green, float blue, float alpha) {
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2])
                    .setColor(red, green, blue, alpha);
        }
        BufferUploader.drawWithShader(buffer.build());
    }

    @Override
    public AABB getRenderBoundingBox(WebScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        BlockState state = screen.getBlockState();
        Level level = screen.getLevel();
        if (!(state.getBlock() instanceof WebScreenBlock) || level == null) {
            return new AABB(pos).inflate(1.0D, 1.0D, 1.0D);
        }
        Direction facing = state.getValue(WebScreenBlock.FACING);
        ScreenLayout.Layout layout = ScreenLayout.computeCached(level, pos, facing);

        // 围栏必须罩住整块拼出来的屏幕，否则大屏在视野边缘会被整片剔除掉。
        Direction right = ScreenLayout.screenRight(facing);
        BlockPos origin = layout.controller();
        BlockPos far = origin.relative(right, layout.width() - 1);

        double minX = Math.min(origin.getX(), far.getX()) - 0.5D;
        double maxX = Math.max(origin.getX(), far.getX()) + 1.5D;
        double minZ = Math.min(origin.getZ(), far.getZ()) - 0.5D;
        double maxZ = Math.max(origin.getZ(), far.getZ()) + 1.5D;
        double minY = origin.getY() - 2.0D * (layout.height() - 1) - 0.5D;
        double maxY = origin.getY() + 2.5D;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

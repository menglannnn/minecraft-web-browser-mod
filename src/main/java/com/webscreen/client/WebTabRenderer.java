package com.webscreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.webscreen.WebScreenMod;
import com.webscreen.block.WebTabBlock;
import com.webscreen.block.WebTabBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 把标签方块画成"浏览器标签"的样子：一块半格高的色块，激活时是亮蓝色，否则是深灰，
 * 上面压一行域名。
 *
 * <p>标签方块自身<b>不显示网页画面</b>（半格高根本放不下页面），它只是"网址槽位 + 开关"。
 * 网页画面始终由下方那块屏幕呈现。</p>
 */
public class WebTabRenderer implements BlockEntityRenderer<WebTabBlockEntity> {

    /** 与 {@link WebScreenRenderer} / 方块模型保持一致的面板几何。 */
    private static final float PANEL_BACK_GAP = 0.5F / 16.0F;
    private static final float PANEL_THICKNESS = 4.0F / 16.0F;
    private static final float NUDGE = 0.002F;

    /** 标签方块的高度（半格）。 */
    private static final float TAB_HEIGHT = 0.5F;

    private static final float ACTIVE_R = 0.22F;
    private static final float ACTIVE_G = 0.45F;
    private static final float ACTIVE_B = 0.85F;
    private static final float IDLE_R = 0.09F;
    private static final float IDLE_G = 0.10F;
    private static final float IDLE_B = 0.13F;

    public WebTabRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** 正面在朝向轴上的位置（相对本格最小角）。 */
    private static float frontPlane(Direction facing) {
        return switch (facing) {
            case SOUTH, EAST -> PANEL_BACK_GAP + PANEL_THICKNESS;
            case NORTH, WEST -> (1.0F - PANEL_BACK_GAP) - PANEL_THICKNESS;
            default -> PANEL_BACK_GAP + PANEL_THICKNESS;
        };
    }

    private static float nudge(Direction facing) {
        return switch (facing) {
            case SOUTH, EAST -> NUDGE;
            case NORTH, WEST -> -NUDGE;
            default -> 0.0F;
        };
    }

    @Override
    public void render(WebTabBlockEntity tab, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        Level level = tab.getLevel();
        if (level == null || !(tab.getBlockState().getBlock() instanceof WebTabBlock)) {
            return;
        }
        Direction facing = tab.getBlockState().getValue(WebTabBlock.FACING);
        BlockPos pos = tab.getBlockPos();

        BlockPos controller = TabManager.controllerOf(level, pos);
        boolean active = controller != null && TabManager.isActive(controller, pos);
        String label = TabManager.labelOf(tab.getUrl());
        if (label.isEmpty()) {
            label = "新标签";
        }

        float front = frontPlane(facing) + nudge(facing);
        boolean alongX = facing.getAxis() == Direction.Axis.Z;

        float[][] quad = new float[4][3];
        if (alongX) {
            quad[0] = new float[]{0.0F, 0.0F, front};
            quad[1] = new float[]{1.0F, 0.0F, front};
            quad[2] = new float[]{1.0F, TAB_HEIGHT, front};
            quad[3] = new float[]{0.0F, TAB_HEIGHT, front};
        } else {
            quad[0] = new float[]{front, 0.0F, 0.0F};
            quad[1] = new float[]{front, 0.0F, 1.0F};
            quad[2] = new float[]{front, TAB_HEIGHT, 1.0F};
            quad[3] = new float[]{front, TAB_HEIGHT, 0.0F};
        }

        pose.pushPose();
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(515); // GL_LEQUAL
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            Matrix4f matrix = pose.last().pose();
            BufferBuilder buffer = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float red = active ? ACTIVE_R : IDLE_R;
            float green = active ? ACTIVE_G : IDLE_G;
            float blue = active ? ACTIVE_B : IDLE_B;
            for (int i = 0; i < 4; i++) {
                buffer.addVertex(matrix, quad[i][0], quad[i][1], quad[i][2]).setColor(red, green, blue, 1.0F);
            }
            BufferUploader.drawWithShader(buffer.build());

            drawLabel(pose, buffers, facing, label, active, packedLight);
        } catch (Throwable throwable) {
            WebScreenMod.LOGGER.error("[WebScreen] 渲染标签方块时出错", throwable);
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
        pose.popPose();
    }

    /** 在标签正面压一行文字。字号按文字长度自适应，保证塞得进一格宽。 */
    private static void drawLabel(PoseStack pose, MultiBufferSource buffers, Direction facing,
                                  String label, boolean active, int packedLight) {
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        float front = frontPlane(facing) + nudge(facing) * 2.0F;
        boolean alongX = facing.getAxis() == Direction.Axis.Z;
        float x = alongX ? 0.5F : front;
        float z = alongX ? front : 0.5F;

        float width = Math.max(1.0F, font.width(label));
        // 让文字最多占九成格宽
        float scale = Math.min(0.02F, 0.9F / width);

        pose.pushPose();
        pose.translate(x, TAB_HEIGHT * 0.5F, z);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(scale, -scale, scale);
        int color = active ? 0xFFFFFFFF : 0xFF9AA4B2;
        font.drawInBatch(label, -width / 2.0F, -4.0F, color, false,
                pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(WebTabBlockEntity tab) {
        return new AABB(tab.getBlockPos()).inflate(1.0D);
    }
}

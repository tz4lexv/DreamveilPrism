package dev.dreamveil.prism.pack;

import java.util.List;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.*;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

/** CPU-only collector: never feeds auxiliary geometry into Minecraft's main draw storage. */
final class PrismResidentModelCollector implements SubmitNodeCollector {
    private final String domain;
    private final double distanceSquared;
    PrismResidentModelCollector(String domain, double distanceSquared) {
        this.domain = domain; this.distanceSquared = distanceSquared;
    }
    public OrderedSubmitNodeCollector order(int order) { return this; }
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poses, RenderType type,
            int light, int overlay, int tint, TextureAtlasSprite sprite, int outline,
            ModelFeatureRenderer.CrumblingOverlay crumbling) {
        var submit = new ModelFeatureRenderer.Submit<S>(type, poses.last(), model, state,
                light, overlay, tint, sprite, null);
        PrismModelViewCapture.markWorldState(state, domain);
        var capture = PrismModelViewCapture.begin(submit, DISCARD);
        if (capture == null) return;
        capture.residentDistanceSquared = distanceSquared;
        boolean success = false;
        try {
            // Only off-screen states arrive here; main-camera models are never evaluated twice.
            model.setupAnim(state);
            model.renderToBuffer(poses, capture, light, overlay, tint);
            success = true;
        } finally { PrismModelViewCapture.finish(capture, success); }
    }
    public void submitBlockModel(PoseStack p, RenderType t, List<BlockStateModelPart> parts, int[] tint, int l, int o, int c) {}
    public void submitBreakingBlockModel(PoseStack p, List<BlockStateModelPart> parts, int progress) {}
    public void submitCustomGeometry(PoseStack p, RenderType t, CustomGeometryRenderer renderer) {}
    public void submitMovingBlock(PoseStack p, MovingBlockRenderState state, int c) {}
    public void submitItem(PoseStack p, ItemDisplayContext ctx, int l, int o, int c, int[] tint, List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {}
    public void submitText(PoseStack p, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode mode, int l, int c, int bg, int outline) {}
    public void submitNameTag(PoseStack p, Vec3 pos, int offset, Component name, boolean through, int l, CameraRenderState camera) {}
    public void submitShadow(PoseStack p, float radius, List<EntityRenderState.ShadowPiece> pieces) {}
    public void submitFlame(PoseStack p, EntityRenderState state, Quaternionf rotation) {}
    public void submitLeash(PoseStack p, EntityRenderState.LeashState state) {}
    public void submitShapeOutline(PoseStack p, VoxelShape shape, RenderType t, int c, float width, boolean after) {}
    public void submitQuadParticleGroup(QuadParticleRenderState state) {}
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean top) {}
    private static final VertexConsumer DISCARD = new VertexConsumer() {
        public VertexConsumer addVertex(float x, float y, float z) { return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public VertexConsumer setColor(int argb) { return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
        public VertexConsumer setLineWidth(float width) { return this; }
    };
}

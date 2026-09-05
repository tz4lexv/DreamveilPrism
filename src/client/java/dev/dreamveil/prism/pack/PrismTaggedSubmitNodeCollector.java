package dev.dreamveil.prism.pack;

import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Allocation-light world Feature Rendering collector wrapper.
 *
 * <p>Only methods that already carry an explicit RenderType are tagged. Item/text/name-tag/shadow/
 * flame/leash/particle submissions stay vanilla because those feature contracts require dedicated
 * Prism domains rather than an unsafe global pipeline replacement.</p>
 */
public final class PrismTaggedSubmitNodeCollector extends PrismTaggedOrderedSubmitNodeCollector
        implements SubmitNodeCollector {
    private final SubmitNodeCollector root;

    private PrismTaggedSubmitNodeCollector(SubmitNodeCollector delegate, PrismFeatureSource source) {
        super(delegate, source);
        this.root = Objects.requireNonNull(delegate, "delegate");
    }

    public static SubmitNodeCollector entity(SubmitNodeCollector delegate) {
        return (PrismModelMotionCapture.enabled() || PrismModelViewCapture.enabled() || PrismWorldRenderingPipeline.needsFeatureTagging(PrismFeatureSource.ENTITY))
                ? new PrismTaggedSubmitNodeCollector(delegate, PrismFeatureSource.ENTITY)
                : delegate;
    }

    public static SubmitNodeCollector blockEntity(SubmitNodeCollector delegate) {
        return (PrismModelMotionCapture.enabled() || PrismModelViewCapture.enabled() || PrismWorldRenderingPipeline.needsFeatureTagging(PrismFeatureSource.BLOCK_ENTITY))
                ? new PrismTaggedSubmitNodeCollector(delegate, PrismFeatureSource.BLOCK_ENTITY)
                : delegate;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new PrismTaggedOrderedSubmitNodeCollector(root.order(order), source());
    }
}

class PrismTaggedOrderedSubmitNodeCollector implements OrderedSubmitNodeCollector {
    private final OrderedSubmitNodeCollector delegate;
    private final PrismFeatureSource source;

    PrismTaggedOrderedSubmitNodeCollector(OrderedSubmitNodeCollector delegate, PrismFeatureSource source) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.source = Objects.requireNonNull(source, "source");
    }

    final PrismFeatureSource source() {
        return source;
    }

    private RenderType tag(RenderType renderType) {
        return PrismFeatureRenderTypeRegistry.tag(renderType, source);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
            int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        delegate.submitBlockModel(poseStack, tag(renderType), parts, tintLayers, lightCoords, overlayCoords, outlineColor);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
        delegate.submitBreakingBlockModel(poseStack, parts, progress);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        delegate.submitCustomGeometry(poseStack, tag(renderType), customGeometryRenderer);
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        delegate.submitFlame(poseStack, renderState, rotation);
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
        delegate.submitGizmoPrimitives(group, camera, onTop);
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
            int outlineColor, int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
        delegate.submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        delegate.submitLeash(poseStack, leashState);
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        PrismModelMotionCapture.markWorldState(state);
        PrismModelViewCapture.markWorldState(state, source == PrismFeatureSource.ENTITY ? "entity" : "block_entity");
        delegate.submitModel(model, state, poseStack, tag(renderType), lightCoords, overlayCoords, tintedColor,
                sprite, outlineColor, crumblingOverlay);
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
        delegate.submitMovingBlock(poseStack, movingBlockRenderState, outlineColor);
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
            boolean seeThrough, int lightCoords, CameraRenderState camera) {
        delegate.submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough, lightCoords, camera);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        delegate.submitQuadParticleGroup(particles);
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        delegate.submitShadow(poseStack, radius, pieces);
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
            float width, boolean afterTerrain) {
        delegate.submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
            Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
        delegate.submitText(poseStack, x, y, string, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
    }
}

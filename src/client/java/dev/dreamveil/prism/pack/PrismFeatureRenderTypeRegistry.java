package dev.dreamveil.prism.pack;

import java.util.EnumMap;
import java.util.WeakHashMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import dev.dreamveil.prism.mixin.RenderTypeAccessor;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * World-only RenderType tagging bridge.
 *
 * <p>Tags are created only by LevelRenderer entity/block-entity submission wrappers. GUI and item
 * collectors never pass through this registry, so enabling a world pack cannot globally replace
 * inventory/entity-preview RenderTypes.</p>
 */
public final class PrismFeatureRenderTypeRegistry {
    private static final Map<RenderType, EnumMap<PrismFeatureSource, RenderType>> TAGGED_BY_ORIGINAL =
            new WeakHashMap<>();
    private static final Map<RenderType, PrismFeatureSource> SOURCE_BY_TAGGED = new WeakHashMap<>();

    private PrismFeatureRenderTypeRegistry() {}

    static RenderType tag(RenderType original, PrismFeatureSource source) {
        java.util.Objects.requireNonNull(original, "original");
        java.util.Objects.requireNonNull(source, "source");
        // Outlines have dedicated target/order semantics; keep them vanilla until Prism owns an
        // explicit outline domain.
        if (original.isOutline()) return original;

        PrismSceneDomain domain = switch (source) {
            case ENTITY -> original.hasBlending()
                    ? PrismSceneDomain.ENTITY_TRANSLUCENT
                    : PrismSceneDomain.ENTITY_OPAQUE;
            case BLOCK_ENTITY -> PrismSceneDomain.BLOCK_ENTITY;
        };
        // Do not create a cloned RenderType when the active pack has no shader for this exact
        // domain. This preserves vanilla batching/identity for unaffected feature submissions.
        if (!PrismWorldRenderingPipeline.hasFeatureProgram(domain)) return original;

        synchronized (TAGGED_BY_ORIGINAL) {
            EnumMap<PrismFeatureSource, RenderType> variants = TAGGED_BY_ORIGINAL.computeIfAbsent(
                    original, ignored -> new EnumMap<>(PrismFeatureSource.class));
            RenderType existing = variants.get(source);
            if (existing != null) return existing;

            RenderSetup state = ((RenderTypeAccessor) (Object) original).dreamveilPrism$getState();
            String rawName = original.toString().replaceAll("[^A-Za-z0-9._/-]", "_");
            if (rawName.length() > 96) rawName = rawName.substring(0, 96);
            RenderType tagged = RenderType.create(
                    "prism/" + source.name().toLowerCase(Locale.ROOT) + "/" + rawName,
                    state);
            variants.put(source, tagged);
            SOURCE_BY_TAGGED.put(tagged, source);
            return tagged;
        }
    }

    /** Rewrites only PreparedRenderTypes produced from a world-only Prism tag. */
    public static PreparedRenderType resolvePrepared(RenderType renderType, PreparedRenderType prepared) {
        PrismFeatureSource source;
        synchronized (TAGGED_BY_ORIGINAL) {
            source = SOURCE_BY_TAGGED.get(renderType);
        }
        if (source == null) return prepared;

        PrismSceneDomain domain = switch (source) {
            case ENTITY -> renderType.hasBlending()
                    ? PrismSceneDomain.ENTITY_TRANSLUCENT
                    : PrismSceneDomain.ENTITY_OPAQUE;
            case BLOCK_ENTITY -> PrismSceneDomain.BLOCK_ENTITY;
        };
        RenderPipeline replacement = PrismWorldRenderingPipeline.resolveFeature(domain, prepared.pipeline());
        if (replacement == prepared.pipeline()) return prepared;
        return new PreparedRenderType(
                replacement,
                prepared.outputTarget(),
                prepared.dynamicTransforms(),
                prepared.scissorState(),
                prepared.textures());
    }
}

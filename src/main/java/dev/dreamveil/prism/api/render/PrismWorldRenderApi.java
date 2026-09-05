package dev.dreamveil.prism.api.render;

import java.util.Set;

import dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot;

/** Read-only world-pipeline state for creator tooling and diagnostics. */
public interface PrismWorldRenderApi {
    PrismWorldRenderApi EMPTY = new PrismWorldRenderApi() {
        @Override
        public PrismWorldPipelineSnapshot snapshot() {
            return PrismWorldPipelineSnapshot.VANILLA;
        }

        @Override
        public Set<PrismRenderDomain> supportedDomains() {
            return Set.of();
        }

        @Override
        public PrismFeaturePipelineStats featurePipelineStats() {
            return PrismFeaturePipelineStats.EMPTY;
        }
    };

    PrismWorldPipelineSnapshot snapshot();

    /**
     * Domains that the active Minecraft bridge can execute end-to-end. This is intentionally
     * separate from {@link PrismRenderDomain}: enum recognition is a pack-format contract, while
     * execution support depends on verified renderer hooks for the current Minecraft version.
     */
    Set<PrismRenderDomain> supportedDomains();

    /** Dynamic world Feature Rendering pipeline-variant cache statistics. */
    default PrismFeaturePipelineStats featurePipelineStats() {
        return PrismFeaturePipelineStats.EMPTY;
    }

    /** Added in API 1.11. Shadow planning/GPU execution state for the active Minecraft bridge. */
    default PrismShadowExecutionSnapshot shadowExecutionSnapshot() {
        return PrismShadowExecutionSnapshot.UNAVAILABLE;
    }

    default boolean supportsDomain(PrismRenderDomain domain) {
        return supportedDomains().contains(java.util.Objects.requireNonNull(domain, "domain"));
    }
}

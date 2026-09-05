package dev.dreamveil.prism.api.shadow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.dreamveil.prism.api.visibility.PrismFrustum;

/**
 * Backend-neutral CPU visibility planner for a future/active Prism shadow renderer.
 *
 * <p>The planner never derives planes from an implicit projection convention. Callers supply
 * already convention-aware {@link PrismFrustum} instances, so raw reversed-Z and normalized
 * forward-Z paths are both valid.</p>
 */
public final class PrismShadowVisibilityPlanner {
    private PrismShadowVisibilityPlanner() { }

    public static PrismShadowVisibilityPlan plan(
            List<PrismShadowCandidate> candidates,
            List<PrismFrustum> cascadeFrusta) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(cascadeFrusta, "cascadeFrusta");
        if (cascadeFrusta.isEmpty() || cascadeFrusta.size() > 8) {
            throw new IllegalArgumentException("Prism shadow visibility requires 1..8 cascade frusta");
        }
        for (PrismFrustum frustum : cascadeFrusta) Objects.requireNonNull(frustum, "cascade frustum");

        Map<CasterKey, PrismShadowCandidate> unique = new LinkedHashMap<>();
        int duplicatesDropped = 0;
        for (PrismShadowCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            CasterKey key = new CasterKey(candidate.kind(), candidate.stableId());
            PrismShadowCandidate previous = unique.putIfAbsent(key, candidate);
            if (previous != null) {
                if (!previous.bounds().equals(candidate.bounds())) {
                    throw new IllegalArgumentException(
                            "Conflicting shadow-caster bounds for stable key " + candidate.kind() + ":" + candidate.stableId());
                }
                duplicatesDropped++;
            }
        }

        MutableBreakdown candidateCounts = new MutableBreakdown();
        MutableBreakdown acceptedCounts = new MutableBreakdown();
        int[] acceptedPerCascade = new int[cascadeFrusta.size()];
        List<PrismShadowVisibleCandidate> visible = new ArrayList<>();

        for (PrismShadowCandidate candidate : unique.values()) {
            candidateCounts.add(candidate.kind());
            PrismShadowBounds bounds = candidate.bounds();
            int cascadeMask = 0;
            for (int cascade = 0; cascade < cascadeFrusta.size(); cascade++) {
                PrismFrustum frustum = cascadeFrusta.get(cascade);
                if (frustum.intersectsAabb(
                        bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                    cascadeMask |= 1 << cascade;
                    acceptedPerCascade[cascade]++;
                }
            }
            if (cascadeMask != 0) {
                acceptedCounts.add(candidate.kind());
                visible.add(new PrismShadowVisibleCandidate(candidate, cascadeMask));
            }
        }

        return new PrismShadowVisibilityPlan(
                cascadeFrusta.size(),
                candidates.size(),
                unique.size(),
                duplicatesDropped,
                candidateCounts.snapshot(),
                acceptedCounts.snapshot(),
                java.util.Arrays.stream(acceptedPerCascade).boxed().toList(),
                visible);
    }

    private record CasterKey(PrismShadowCasterKind kind, long stableId) { }

    private static final class MutableBreakdown {
        int terrain;
        int entities;
        int blockEntities;

        void add(PrismShadowCasterKind kind) {
            switch (kind) {
                case TERRAIN -> terrain++;
                case ENTITY -> entities++;
                case BLOCK_ENTITY -> blockEntities++;
            }
        }

        PrismShadowCasterBreakdown snapshot() {
            return new PrismShadowCasterBreakdown(terrain, entities, blockEntities);
        }
    }
}

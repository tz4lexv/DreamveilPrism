package dev.dreamveil.prism.api.shadow;

import java.util.List;
import java.util.Objects;

/** Immutable result of CPU shadow-caster classification/cascade culling. */
public record PrismShadowVisibilityPlan(
        int cascadeCount,
        int inputCandidates,
        int uniqueCandidates,
        int duplicatesDropped,
        PrismShadowCasterBreakdown candidateBreakdown,
        PrismShadowCasterBreakdown acceptedBreakdown,
        List<Integer> acceptedPerCascade,
        List<PrismShadowVisibleCandidate> visibleCandidates) {

    public PrismShadowVisibilityPlan {
        if (cascadeCount < 1 || cascadeCount > 8) {
            throw new IllegalArgumentException("Prism shadow visibility supports 1..8 cascades");
        }
        if (inputCandidates < 0 || uniqueCandidates < 0 || duplicatesDropped < 0) {
            throw new IllegalArgumentException("Shadow visibility counts must be >= 0");
        }
        Objects.requireNonNull(candidateBreakdown, "candidateBreakdown");
        Objects.requireNonNull(acceptedBreakdown, "acceptedBreakdown");
        acceptedPerCascade = List.copyOf(Objects.requireNonNull(acceptedPerCascade, "acceptedPerCascade"));
        visibleCandidates = List.copyOf(Objects.requireNonNull(visibleCandidates, "visibleCandidates"));
        if (acceptedPerCascade.size() != cascadeCount) {
            throw new IllegalArgumentException("acceptedPerCascade size must equal cascadeCount");
        }
        for (Integer count : acceptedPerCascade) {
            if (count == null || count < 0 || count > uniqueCandidates) {
                throw new IllegalArgumentException("Per-cascade accepted counts must be in [0, uniqueCandidates]");
            }
        }
        if (uniqueCandidates + duplicatesDropped != inputCandidates) {
            throw new IllegalArgumentException("uniqueCandidates + duplicatesDropped must equal inputCandidates");
        }
        if (candidateBreakdown.total() != uniqueCandidates) {
            throw new IllegalArgumentException("candidateBreakdown total must equal uniqueCandidates");
        }
        if (acceptedBreakdown.total() != visibleCandidates.size()) {
            throw new IllegalArgumentException("acceptedBreakdown total must equal visible candidate count");
        }
        if (acceptedBreakdown.terrain() > candidateBreakdown.terrain()
                || acceptedBreakdown.entities() > candidateBreakdown.entities()
                || acceptedBreakdown.blockEntities() > candidateBreakdown.blockEntities()) {
            throw new IllegalArgumentException("Accepted shadow counts cannot exceed candidate counts");
        }
        int[] recomputedPerCascade = new int[cascadeCount];
        int allowedCascadeMask = (1 << cascadeCount) - 1;
        for (PrismShadowVisibleCandidate candidate : visibleCandidates) {
            if ((candidate.cascadeMask() & ~allowedCascadeMask) != 0) {
                throw new IllegalArgumentException("Visible candidate cascadeMask contains bits outside cascadeCount");
            }
            for (int cascade = 0; cascade < cascadeCount; cascade++) {
                if (candidate.intersectsCascade(cascade)) recomputedPerCascade[cascade]++;
            }
        }
        for (int cascade = 0; cascade < cascadeCount; cascade++) {
            if (recomputedPerCascade[cascade] != acceptedPerCascade.get(cascade)) {
                throw new IllegalArgumentException("acceptedPerCascade does not match visible candidate masks");
            }
        }
    }

    public int acceptedCandidates() {
        return visibleCandidates.size();
    }

    public int culledCandidates() {
        return uniqueCandidates - acceptedCandidates();
    }

    /** Number of potential caster draws across all cascades before batching/instancing. */
    public int cascadeDrawCandidates() {
        int total = 0;
        for (int count : acceptedPerCascade) total = Math.addExact(total, count);
        return total;
    }
}

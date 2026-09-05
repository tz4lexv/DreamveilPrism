package dev.dreamveil.prism.shadow;

import java.util.List;

import dev.dreamveil.prism.api.frame.PrismMatrix4;
import dev.dreamveil.prism.api.shadow.PrismShadowApi;
import dev.dreamveil.prism.api.shadow.PrismShadowBounds;
import dev.dreamveil.prism.api.shadow.PrismShadowCandidate;
import dev.dreamveil.prism.api.shadow.PrismShadowCasterKind;
import dev.dreamveil.prism.api.shadow.PrismShadowVisibilityPlan;
import dev.dreamveil.prism.api.shadow.PrismShadowVisibleCandidate;
import dev.dreamveil.prism.api.visibility.PrismClipConvention;
import dev.dreamveil.prism.api.visibility.PrismDepthDirection;
import dev.dreamveil.prism.api.visibility.PrismDepthRange;
import dev.dreamveil.prism.api.visibility.PrismFrustum;
import dev.dreamveil.prism.api.visibility.PrismProjectionDescriptor;
import dev.dreamveil.prism.api.visibility.PrismProjectionType;

/** Shadow-submission regression: source completeness, de-duplication and reversed-Z invariance. */
public final class PrismShadowVisibilitySmokeTest {
    private static final double NEAR = 0.1;

    private PrismShadowVisibilitySmokeTest() { }

    public static void main(String[] args) {
        List<PrismShadowCandidate> candidates = List.of(
                candidate(1, PrismShadowCasterKind.TERRAIN, -4, -4, -12, 4, 4, -4),
                candidate(2, PrismShadowCasterKind.ENTITY, -1, -1, -64, 1, 2, -60),
                candidate(3, PrismShadowCasterKind.BLOCK_ENTITY, 6, -1, -24, 8, 3, -22),
                candidate(4, PrismShadowCasterKind.TERRAIN, 900, 0, -16, 916, 16, 0),
                // Identical stable source submitted twice must not become a second draw candidate.
                candidate(1, PrismShadowCasterKind.TERRAIN, -4, -4, -12, 4, 4, -4));

        List<PrismFrustum> forward = List.of(
                frustum(32.0, false),
                frustum(128.0, false));
        List<PrismFrustum> reversedRaw = List.of(
                frustum(32.0, true),
                frustum(128.0, true));
        List<PrismFrustum> reversedNormalized = List.of(
                normalizedFrustum(32.0),
                normalizedFrustum(128.0));

        PrismShadowVisibilityPlan reference = PrismShadowApi.DEFAULT.planVisibility(candidates, forward);
        PrismShadowVisibilityPlan reversed = PrismShadowApi.DEFAULT.planVisibility(candidates, reversedRaw);
        PrismShadowVisibilityPlan normalized = PrismShadowApi.DEFAULT.planVisibility(candidates, reversedNormalized);

        require(reference.inputCandidates() == 5, "input candidate count");
        require(reference.uniqueCandidates() == 4, "unique candidate count");
        require(reference.duplicatesDropped() == 1, "duplicate candidate count");
        require(reference.candidateBreakdown().terrain() == 2, "terrain candidate classification");
        require(reference.candidateBreakdown().entities() == 1, "entity candidate classification");
        require(reference.candidateBreakdown().blockEntities() == 1, "block-entity candidate classification");
        require(reference.acceptedBreakdown().terrain() == 1, "terrain accepted classification");
        require(reference.acceptedBreakdown().entities() == 1, "entity accepted classification");
        require(reference.acceptedBreakdown().blockEntities() == 1, "block-entity accepted classification");
        require(reference.acceptedCandidates() == 3 && reference.culledCandidates() == 1,
                "accepted/culled accounting");
        require(reference.acceptedPerCascade().equals(List.of(2, 3)),
                "per-cascade caster accounting must track actual cascade memberships");
        require(reference.cascadeDrawCandidates() == 5,
                "cascade draw candidates must count multi-cascade caster expansion");

        require(signature(reference).equals(signature(reversed)),
                "raw reversed-Z shadow visibility changed the selected caster/cascade set");
        require(signature(reference).equals(signature(normalized)),
                "normalized reversed-Z shadow visibility changed the selected caster/cascade set");

        PrismShadowVisibleCandidate nearTerrain = find(reference, PrismShadowCasterKind.TERRAIN, 1);
        PrismShadowVisibleCandidate farEntity = find(reference, PrismShadowCasterKind.ENTITY, 2);
        require(nearTerrain.cascadeMask() == 0b11, "near terrain should intersect both nested cascade frusta");
        require(farEntity.cascadeMask() == 0b10, "far entity should intersect only the far cascade frustum");

        boolean conflictRejected = false;
        try {
            PrismShadowApi.DEFAULT.planVisibility(List.of(
                    candidate(77, PrismShadowCasterKind.ENTITY, 0, 0, -4, 1, 1, -3),
                    candidate(77, PrismShadowCasterKind.ENTITY, 10, 0, -4, 11, 1, -3)), forward);
        } catch (IllegalArgumentException expected) {
            conflictRejected = expected.getMessage().contains("Conflicting shadow-caster bounds");
        }
        require(conflictRejected, "conflicting duplicate stable IDs must be rejected");

        boolean invalidMaskRejected = false;
        try {
            new PrismShadowVisibilityPlan(
                    2, 1, 1, 0,
                    new dev.dreamveil.prism.api.shadow.PrismShadowCasterBreakdown(1, 0, 0),
                    new dev.dreamveil.prism.api.shadow.PrismShadowCasterBreakdown(1, 0, 0),
                    List.of(1, 0),
                    List.of(new PrismShadowVisibleCandidate(
                            candidate(99, PrismShadowCasterKind.TERRAIN, -1, -1, -4, 1, 1, -3), 0b101)));
        } catch (IllegalArgumentException expected) {
            invalidMaskRejected = expected.getMessage().contains("outside cascadeCount");
        }
        require(invalidMaskRejected, "visibility plan must reject cascade mask bits outside cascadeCount");

        System.out.println("Dreamveil Prism shadow visibility/submission smoke test: PASS");
        System.out.println("casters input=" + reference.inputCandidates()
                + " unique=" + reference.uniqueCandidates()
                + " accepted=" + reference.acceptedCandidates()
                + " culled=" + reference.culledCandidates()
                + " duplicatesDropped=" + reference.duplicatesDropped()
                + " acceptedPerCascade=" + reference.acceptedPerCascade()
                + " cascadeDrawCandidates=" + reference.cascadeDrawCandidates()
                + " breakdown=" + reference.acceptedBreakdown());
    }

    private static PrismShadowCandidate candidate(
            long id, PrismShadowCasterKind kind,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return new PrismShadowCandidate(id, kind,
                new PrismShadowBounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static PrismFrustum frustum(double far, boolean reversed) {
        PrismClipConvention convention = new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE,
                reversed ? PrismDepthDirection.REVERSED_Z : PrismDepthDirection.FORWARD_Z,
                NEAR, far, PrismProjectionType.PERSPECTIVE);
        return PrismFrustum.fromViewProjection(perspective(75.0, 16.0 / 9.0, NEAR, far, reversed), convention);
    }

    private static PrismFrustum normalizedFrustum(double far) {
        PrismClipConvention reversed = new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE, PrismDepthDirection.REVERSED_Z,
                NEAR, far, PrismProjectionType.PERSPECTIVE);
        PrismProjectionDescriptor descriptor = new PrismProjectionDescriptor(
                perspective(75.0, 16.0 / 9.0, NEAR, far, true), reversed);
        return PrismFrustum.fromViewProjection(descriptor.cullingProjection(), descriptor.cullingConvention());
    }

    private static PrismMatrix4 perspective(
            double fovYDegrees, double aspect, double near, double far, boolean reversedZ) {
        double y = 1.0 / Math.tan(Math.toRadians(fovYDegrees) * 0.5);
        double x = y / aspect;
        double a = reversedZ ? near / (far - near) : far / (near - far);
        double b = reversedZ ? near * far / (far - near) : near * far / (near - far);
        return new PrismMatrix4(new float[] {
                (float) x, 0, 0, 0,
                0, (float) y, 0, 0,
                0, 0, (float) a, -1,
                0, 0, (float) b, 0
        });
    }

    private static PrismShadowVisibleCandidate find(
            PrismShadowVisibilityPlan plan, PrismShadowCasterKind kind, long id) {
        return plan.visibleCandidates().stream()
                .filter(candidate -> candidate.candidate().kind() == kind && candidate.candidate().stableId() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing visible candidate " + kind + ":" + id));
    }

    private static String signature(PrismShadowVisibilityPlan plan) {
        return plan.visibleCandidates().stream()
                .map(candidate -> candidate.candidate().kind() + ":" + candidate.candidate().stableId()
                        + "@" + candidate.cascadeMask())
                .sorted()
                .reduce("", (left, right) -> left + "|" + right);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

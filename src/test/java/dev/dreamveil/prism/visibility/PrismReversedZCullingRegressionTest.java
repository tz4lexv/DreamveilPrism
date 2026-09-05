package dev.dreamveil.prism.visibility;

import dev.dreamveil.prism.api.frame.PrismMatrix4;
import dev.dreamveil.prism.api.visibility.PrismClipConvention;
import dev.dreamveil.prism.api.visibility.PrismDepthDirection;
import dev.dreamveil.prism.api.visibility.PrismDepthRange;
import dev.dreamveil.prism.api.visibility.PrismFrustum;
import dev.dreamveil.prism.api.visibility.PrismProjectionDescriptor;
import dev.dreamveil.prism.api.visibility.PrismProjectionType;

/** Regression guard for Minecraft 26.2 reversed-Z shadow/terrain visibility math. */
public final class PrismReversedZCullingRegressionTest {
    private static final double NEAR = 0.1;
    private static final double FAR = 256.0;
    private static final double MAX_SECTION_COUNT_DELTA_RATIO = 0.01;

    private PrismReversedZCullingRegressionTest() { }

    public static void main(String[] args) {
        PrismClipConvention forwardConvention = new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE,
                PrismDepthDirection.FORWARD_Z,
                NEAR, FAR, PrismProjectionType.PERSPECTIVE);
        PrismClipConvention reversedConvention = new PrismClipConvention(
                PrismDepthRange.ZERO_TO_ONE,
                PrismDepthDirection.REVERSED_Z,
                NEAR, FAR, PrismProjectionType.PERSPECTIVE);

        PrismMatrix4 forwardProjection = perspectiveZeroToOne(70.0, 16.0 / 9.0, NEAR, FAR, false);
        PrismMatrix4 reversedProjection = perspectiveZeroToOne(70.0, 16.0 / 9.0, NEAR, FAR, true);

        PrismProjectionDescriptor forward = new PrismProjectionDescriptor(forwardProjection, forwardConvention);
        PrismProjectionDescriptor reversed = new PrismProjectionDescriptor(reversedProjection, reversedConvention);

        require(reversed.cullingProjection().approximatelyEquals(forwardProjection, 2.0e-5f),
                "reversed-Z normalization must reconstruct the forward-Z culling projection");

        PrismFrustum forwardRaw = PrismFrustum.fromViewProjection(forwardProjection, forwardConvention);
        PrismFrustum reversedRaw = PrismFrustum.fromViewProjection(reversedProjection, reversedConvention);
        PrismFrustum reversedNormalized = PrismFrustum.fromViewProjection(
                reversed.cullingProjection(), reversed.cullingConvention());

        int forwardAccepted = countVisibleSections(forwardRaw);
        int reversedAccepted = countVisibleSections(reversedRaw);
        int normalizedAccepted = countVisibleSections(reversedNormalized);

        double rawDelta = deltaRatio(forwardAccepted, reversedAccepted);
        double normalizedDelta = deltaRatio(forwardAccepted, normalizedAccepted);
        require(rawDelta < MAX_SECTION_COUNT_DELTA_RATIO,
                "convention-aware reversed-Z frustum changed visible section count: "
                        + forwardAccepted + " vs " + reversedAccepted + " (delta=" + rawDelta + ")");
        require(normalizedDelta < MAX_SECTION_COUNT_DELTA_RATIO,
                "normalized reversed-Z frustum changed visible section count: "
                        + forwardAccepted + " vs " + normalizedAccepted + " (delta=" + normalizedDelta + ")");
        require(forwardAccepted == reversedAccepted && forwardAccepted == normalizedAccepted,
                "deterministic regression scene must have exactly identical visibility");

        require(!reversedRaw.containsPoint(0.0, 0.0, 1.0), "point behind camera must be culled");
        require(reversedRaw.containsPoint(0.0, 0.0, -1.0), "point inside camera frustum must survive");
        require(!reversedRaw.containsPoint(0.0, 0.0, -(FAR + 1.0)), "point beyond far plane must be culled");

        verifyNegativeOneToOneNormalization(forwardProjection, forwardAccepted);

        System.out.println("Dreamveil Prism reversed-Z culling regression: PASS");
        System.out.println("shadowSectionCount forward=" + forwardAccepted
                + " reversedRaw=" + reversedAccepted
                + " reversedNormalized=" + normalizedAccepted
                + " delta=" + normalizedDelta);
    }

    private static void verifyNegativeOneToOneNormalization(PrismMatrix4 canonicalForward01, int expectedAccepted) {
        PrismClipConvention forwardConvention = new PrismClipConvention(
                PrismDepthRange.NEGATIVE_ONE_TO_ONE, PrismDepthDirection.FORWARD_Z,
                NEAR, FAR, PrismProjectionType.PERSPECTIVE);
        PrismClipConvention reversedConvention = new PrismClipConvention(
                PrismDepthRange.NEGATIVE_ONE_TO_ONE, PrismDepthDirection.REVERSED_Z,
                NEAR, FAR, PrismProjectionType.PERSPECTIVE);
        PrismMatrix4 forwardProjection = perspectiveNegativeOneToOne(70.0, 16.0 / 9.0, NEAR, FAR, false);
        PrismMatrix4 reversedProjection = perspectiveNegativeOneToOne(70.0, 16.0 / 9.0, NEAR, FAR, true);

        PrismProjectionDescriptor forward = new PrismProjectionDescriptor(forwardProjection, forwardConvention);
        PrismProjectionDescriptor reversed = new PrismProjectionDescriptor(reversedProjection, reversedConvention);
        require(forward.cullingProjection().approximatelyEquals(canonicalForward01, 2.0e-5f),
                "negative-one-to-one forward projection did not normalize to canonical zero-to-one");
        require(reversed.cullingProjection().approximatelyEquals(canonicalForward01, 2.0e-5f),
                "negative-one-to-one reversed projection did not normalize to canonical zero-to-one");

        int forwardAccepted = countVisibleSections(PrismFrustum.fromViewProjection(forwardProjection, forwardConvention));
        int reversedAccepted = countVisibleSections(PrismFrustum.fromViewProjection(reversedProjection, reversedConvention));
        require(forwardAccepted == expectedAccepted && reversedAccepted == expectedAccepted,
                "negative-one-to-one convention-aware extraction changed deterministic visibility");
    }

    private static int countVisibleSections(PrismFrustum frustum) {
        int accepted = 0;
        double section = 16.0;
        for (int y = -4; y <= 4; y++) {
            for (int x = -24; x <= 24; x++) {
                for (int z = -20; z <= 4; z++) {
                    double minX = x * section;
                    double minY = y * section;
                    double minZ = z * section;
                    if (frustum.intersectsAabb(
                            minX, minY, minZ,
                            minX + section, minY + section, minZ + section)) {
                        accepted++;
                    }
                }
            }
        }
        return accepted;
    }

    private static double deltaRatio(int reference, int candidate) {
        return reference == 0 ? (candidate == 0 ? 0.0 : 1.0)
                : Math.abs(candidate - reference) / (double) reference;
    }

    /** Right-handed perspective, camera looking down -Z, zero-to-one clip depth. */
    private static PrismMatrix4 perspectiveZeroToOne(
            double fovYDegrees, double aspect, double near, double far, boolean reversedZ) {
        double y = 1.0 / Math.tan(Math.toRadians(fovYDegrees) * 0.5);
        double x = y / aspect;
        double a;
        double b;
        if (reversedZ) {
            a = near / (far - near);
            b = near * far / (far - near);
        } else {
            a = far / (near - far);
            b = near * far / (near - far);
        }
        return new PrismMatrix4(new float[] {
                (float) x, 0, 0, 0,
                0, (float) y, 0, 0,
                0, 0, (float) a, -1,
                0, 0, (float) b, 0
        });
    }

    /** Right-handed perspective, camera looking down -Z, negative-one-to-one clip depth. */
    private static PrismMatrix4 perspectiveNegativeOneToOne(
            double fovYDegrees, double aspect, double near, double far, boolean reversedZ) {
        double y = 1.0 / Math.tan(Math.toRadians(fovYDegrees) * 0.5);
        double x = y / aspect;
        double a;
        double b;
        if (reversedZ) {
            a = (far + near) / (far - near);
            b = 2.0 * near * far / (far - near);
        } else {
            a = (far + near) / (near - far);
            b = 2.0 * near * far / (near - far);
        }
        return new PrismMatrix4(new float[] {
                (float) x, 0, 0, 0,
                0, (float) y, 0, 0,
                0, 0, (float) a, -1,
                0, 0, (float) b, 0
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

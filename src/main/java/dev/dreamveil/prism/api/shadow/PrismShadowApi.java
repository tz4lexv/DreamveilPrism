package dev.dreamveil.prism.api.shadow;

import java.util.ArrayList;
import java.util.List;

/** Optional shadow-math utilities. No visual shadow implementation is imposed by Prism. */
public interface PrismShadowApi {
    PrismShadowApi DEFAULT = new PrismShadowApi() { };

    /**
     * CPU shadow-caster visibility planning across 1..8 convention-aware cascade frusta.
     * Added in API 1.11. This does not issue GPU draws.
     */
    default PrismShadowVisibilityPlan planVisibility(
            java.util.List<PrismShadowCandidate> candidates,
            java.util.List<dev.dreamveil.prism.api.visibility.PrismFrustum> cascadeFrusta) {
        return PrismShadowVisibilityPlanner.plan(candidates, cascadeFrusta);
    }

    /** Practical CSM split scheme: lambda=0 uniform, lambda=1 logarithmic. */
    default PrismCsmLayout computeCascadeSplits(PrismCsmConfig config) {
        List<Double> splits = new ArrayList<>(config.cascades());
        double near = config.nearPlane();
        double far = config.farPlane();
        double ratio = far / near;
        for (int i = 1; i <= config.cascades(); i++) {
            double p = (double) i / config.cascades();
            double logarithmic = near * Math.pow(ratio, p);
            double uniform = near + (far - near) * p;
            splits.add(config.lambda() * logarithmic + (1.0 - config.lambda()) * uniform);
        }
        return new PrismCsmLayout(splits);
    }


    /**
     * Builds cascade intervals plus creator-controlled cross-fade regions.
     * blendFraction=0 disables overlap; values up to 0.5 are accepted.
     */
    default List<PrismCsmCascade> computeCascadeRegions(PrismCsmConfig config, double blendFraction) {
        if (!Double.isFinite(blendFraction) || blendFraction < 0.0 || blendFraction > 0.5) {
            throw new IllegalArgumentException("CSM blendFraction must be within [0, 0.5]");
        }
        PrismCsmLayout layout = computeCascadeSplits(config);
        List<PrismCsmCascade> cascades = new ArrayList<>(layout.cascadeCount());
        double near = config.nearPlane();
        for (int index = 0; index < layout.cascadeCount(); index++) {
            double far = layout.splitFarDistances().get(index);
            double span = far - near;
            double blendStart = far - span * blendFraction;
            cascades.add(new PrismCsmCascade(index, near, far, blendStart));
            near = far;
        }
        return List.copyOf(cascades);
    }

    /**
     * Packs cascades into a compact square-ish atlas. Packs may ignore this and use arrays when supported.
     */
    default PrismShadowAtlasLayout computeAtlasLayout(int cascadeCount, int tileResolution) {
        if (cascadeCount < 1 || cascadeCount > 8 || tileResolution < 1) {
            throw new IllegalArgumentException("cascadeCount must be 1..8 and tileResolution >= 1");
        }
        int columns = (int) Math.ceil(Math.sqrt(cascadeCount));
        int rows = (cascadeCount + columns - 1) / columns;
        List<PrismShadowAtlasTile> tiles = new ArrayList<>(cascadeCount);
        for (int index = 0; index < cascadeCount; index++) {
            int x = (index % columns) * tileResolution;
            int y = (index / columns) * tileResolution;
            tiles.add(new PrismShadowAtlasTile(index, x, y, tileResolution, tileResolution));
        }
        return new PrismShadowAtlasLayout(columns * tileResolution, rows * tileResolution, tiles);
    }

    /** World units represented by one texel for an orthographic shadow extent. */
    default double worldUnitsPerTexel(double orthographicSpan, int shadowResolution) {
        if (!Double.isFinite(orthographicSpan) || orthographicSpan <= 0.0 || shadowResolution < 1) {
            throw new IllegalArgumentException("Shadow span must be finite > 0 and resolution >= 1");
        }
        return orthographicSpan / shadowResolution;
    }

    /** Quantizes a light-space center to the shadow-map texel grid to reduce cascade shimmering. */
    default double snapToTexel(double coordinate, double worldUnitsPerTexel) {
        if (!Double.isFinite(coordinate) || !Double.isFinite(worldUnitsPerTexel) || worldUnitsPerTexel <= 0.0) {
            throw new IllegalArgumentException("Texel snapping values must be finite and scale > 0");
        }
        return Math.rint(coordinate / worldUnitsPerTexel) * worldUnitsPerTexel;
    }
}

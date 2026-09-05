package dev.dreamveil.prism.api.shadow;

import java.util.List;

/** Split distances in view-space units. Rendering the cascades still requires an auxiliary-scene-view capability. */
public record PrismCsmLayout(List<Double> splitFarDistances) {
    public PrismCsmLayout {
        splitFarDistances = List.copyOf(splitFarDistances);
        if (splitFarDistances.isEmpty()) {
            throw new IllegalArgumentException("CSM layout must contain at least one cascade");
        }
        double previous = 0.0;
        for (double value : splitFarDistances) {
            if (!Double.isFinite(value) || value <= previous) {
                throw new IllegalArgumentException("CSM split distances must be finite and strictly increasing");
            }
            previous = value;
        }
    }

    public int cascadeCount() {
        return splitFarDistances.size();
    }
}

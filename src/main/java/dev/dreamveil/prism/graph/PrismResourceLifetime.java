package dev.dreamveil.prism.graph;

/** Inclusive first/last pass indices at which a logical graph resource is used. */
public record PrismResourceLifetime(int firstUsePass, int lastUsePass) {
    public PrismResourceLifetime {
        if (firstUsePass < 0 || lastUsePass < firstUsePass) {
            throw new IllegalArgumentException("Invalid Prism resource lifetime");
        }
    }

    public boolean overlaps(PrismResourceLifetime other) {
        return firstUsePass <= other.lastUsePass && other.firstUsePass <= lastUsePass;
    }
}

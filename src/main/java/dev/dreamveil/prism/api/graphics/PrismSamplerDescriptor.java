package dev.dreamveil.prism.api.graphics;

/** Backend-neutral sampler description. Comparison sampling is separately capability-gated. */
public record PrismSamplerDescriptor(
        PrismSamplerAddressMode addressU,
        PrismSamplerAddressMode addressV,
        PrismSamplerFilter minFilter,
        PrismSamplerFilter magFilter,
        int maxAnisotropy,
        double maxLod) {
    public PrismSamplerDescriptor {
        if (addressU == null || addressV == null || minFilter == null || magFilter == null) {
            throw new IllegalArgumentException("Sampler modes must not be null");
        }
        if (maxAnisotropy < 1) {
            throw new IllegalArgumentException("maxAnisotropy must be >= 1");
        }
        if (!(Double.isNaN(maxLod) || (Double.isFinite(maxLod) && maxLod >= 0.0))) {
            throw new IllegalArgumentException("maxLod must be NaN (unbounded) or finite and >= 0");
        }
    }

    public static PrismSamplerDescriptor linearClamp() {
        return new PrismSamplerDescriptor(
                PrismSamplerAddressMode.CLAMP_TO_EDGE,
                PrismSamplerAddressMode.CLAMP_TO_EDGE,
                PrismSamplerFilter.LINEAR,
                PrismSamplerFilter.LINEAR,
                1,
                Double.NaN);
    }

    public static PrismSamplerDescriptor nearestClamp() {
        return new PrismSamplerDescriptor(
                PrismSamplerAddressMode.CLAMP_TO_EDGE,
                PrismSamplerAddressMode.CLAMP_TO_EDGE,
                PrismSamplerFilter.NEAREST,
                PrismSamplerFilter.NEAREST,
                1,
                Double.NaN);
    }
}

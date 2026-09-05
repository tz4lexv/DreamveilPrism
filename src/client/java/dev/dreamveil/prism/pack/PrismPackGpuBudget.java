package dev.dreamveil.prism.pack;

import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceType;
import dev.dreamveil.prism.graph.PrismTextureDesc;

/** Conservative admission control preventing an untrusted pack from exhausting device memory. */
final class PrismPackGpuBudget {
    static final long DEFAULT_MAX_BYTES = 768L * 1024L * 1024L;
    private static final long MIN_MAX_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_MAX_BYTES = 4096L * 1024L * 1024L;

    private PrismPackGpuBudget() {}

    static long configuredMaxBytes() {
        long mib = Long.getLong("dreamveil.prism.maxPackGpuMiB", DEFAULT_MAX_BYTES / (1024L * 1024L));
        long minimumMiB = MIN_MAX_BYTES / (1024L * 1024L);
        long maximumMiB = MAX_MAX_BYTES / (1024L * 1024L);
        return Math.clamp(mib, minimumMiB, maximumMiB) * 1024L * 1024L;
    }

    static long estimateBytes(PrismCompiledPack pack, int mainWidth, int mainHeight) {
        long total = 0L;
        if (pack.definition().pipelines().stream().anyMatch(p -> p.isSceneView() && p.sceneView().capturedModels())) {
            total = add(total, PrismModelViewReplay.GPU_BYTES);
        }
        if (pack.definition().pipelines().stream().flatMap(p -> p.samplers().stream())
                .anyMatch(b -> PrismPackResources.MODEL_MOTION.equals(b.resource()))) {
            total = add(total, modelMotionBytes(mainWidth, mainHeight));
        }

        // The compiled alias plan is the real number of physical transient allocations, not the
        // larger logical resource count.
        for (PrismResourceDescriptor descriptor
                : pack.graph().transientPlan().representativeBySlot().values()) {
            total = add(total, descriptor.type() == PrismResourceType.TEXTURE
                    ? textureBytes(descriptor.textureDesc(), mainWidth, mainHeight, 1.0, 1)
                    : descriptor.bufferDesc().sizeBytes());
        }

        for (PrismPackTextureDefinition resource : pack.definition().resources()) {
            if (resource.history()) {
                total = add(total, textureBytes(resource.descriptor(), mainWidth, mainHeight, 1.0, 2));
            } else if (resource.scene()) {
                total = add(total, textureBytes(resource.descriptor(), mainWidth, mainHeight, 1.0, 1));
            }
        }

        for (PrismPackTextureAssetDefinition asset : pack.definition().textureAssets()) {
            int layers = asset.sources().size();
            int depth = asset.volume() ? layers : 1;
            int arrayLayers = asset.volume() ? 1 : layers;
            total = add(total, rgba8MipBytes(
                    asset.width(), asset.height(), depth, arrayLayers, asset.mipLevels()));
        }
        return total;
    }

    static long modelMotionBytes(int width, int height) {
        return add(multiply(multiply(width, height), 8L),
                PrismModelMotionCapture.MAX_VERTICES * 3L / 2 * PrismModelMotionCapture.STRIDE);
    }

    static void requireWithinBudget(PrismCompiledPack pack, int mainWidth, int mainHeight)
            throws PrismPackLoadException {
        long estimate = estimateBytes(pack, mainWidth, mainHeight);
        long maximum = configuredMaxBytes();
        if (estimate <= maximum) return;
        throw new PrismPackLoadException(
                "gpu_budget_exceeded",
                "Pack requires an estimated " + toMiB(estimate)
                        + " MiB of Prism-owned GPU memory at " + mainWidth + "x" + mainHeight
                        + "; safety limit is " + toMiB(maximum)
                        + " MiB. Reduce attachment formats, resolution scales, history resources or texture assets.",
                "prism.json");
    }

    private static long textureBytes(
            PrismTextureDesc descriptor,
            int mainWidth,
            int mainHeight,
            double dynamicScale,
            int copies) {
        int width = descriptor.extent().resolveWidth(mainWidth, dynamicScale);
        int height = descriptor.extent().resolveHeight(mainHeight, dynamicScale);
        long texels = 0L;
        for (int mip = 0; mip < descriptor.mipLevels(); mip++) {
            texels = add(texels, multiply(Math.max(1, width >> mip), Math.max(1, height >> mip)));
        }
        return multiply(multiply(texels, descriptor.format().bytesPerTexel()), copies);
    }

    private static long rgba8MipBytes(
            int baseWidth,
            int baseHeight,
            int baseDepth,
            int arrayLayers,
            int mipLevels) {
        long texels = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            long slice = multiply(Math.max(1, baseWidth >> mip), Math.max(1, baseHeight >> mip));
            int depth = Math.max(1, baseDepth >> mip);
            texels = add(texels, multiply(multiply(slice, depth), arrayLayers));
        }
        return multiply(texels, 4L);
    }

    private static long toMiB(long bytes) {
        return (bytes + 1024L * 1024L - 1L) / (1024L * 1024L);
    }

    private static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long add(long left, long right) {
        if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}

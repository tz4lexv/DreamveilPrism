package dev.dreamveil.prism.pack;

/** Portable pack limits enforced before Blaze3D pipeline creation. */
final class PrismResourceBudget {
    /** Vulkan requires at least 16 sampled images/samplers per shader stage; Prism uses that portable floor. */
    static final int PORTABLE_SAMPLERS_PER_PIPELINE = 16;
    /** Prism's modern Vulkan profile: enough for four G-buffer inputs plus working outputs. */
    static final int PORTABLE_STORAGE_IMAGES_PER_COMPUTE = 8;
    /** Vulkan's required per-stage floor for storage buffers. */
    static final int PORTABLE_STORAGE_BUFFERS_PER_COMPUTE = 4;

    private PrismResourceBudget() {
    }

    static void validatePipeline(PrismPipelineDefinition pipeline) throws PrismPackLoadException {
        int samplers = pipeline.samplers().size() + (pipeline.isSceneView() ? 1 : 0);
        if (samplers > PORTABLE_SAMPLERS_PER_PIPELINE) {
            PrismSamplerBinding offending = pipeline.samplers().get(Math.min(pipeline.samplers().size() - 1, PORTABLE_SAMPLERS_PER_PIPELINE));
            throw new PrismPackLoadException(
                    "resource_sampler_limit",
                    "Pipeline '" + pipeline.id() + "' requires " + samplers
                            + " sampler descriptors; Prism portable budget is " + PORTABLE_SAMPLERS_PER_PIPELINE
                            + ". First offending sampler: '" + offending.name() + "' -> '"
                            + offending.resource() + "'",
                    "prism.json");
        }
        if (pipeline.storageImages().size() > PORTABLE_STORAGE_IMAGES_PER_COMPUTE) {
            PrismStorageBinding offending = pipeline.storageImages().get(PORTABLE_STORAGE_IMAGES_PER_COMPUTE);
            throw new PrismPackLoadException(
                    "resource_storage_image_limit",
                    "Compute program '" + pipeline.id() + "' requires " + pipeline.storageImages().size()
                            + " storage-image descriptors; Prism portable Vulkan budget is "
                            + PORTABLE_STORAGE_IMAGES_PER_COMPUTE + ". First offending binding: '"
                            + offending.name() + "'",
                    "prism.json");
        }
        if (pipeline.storageBuffers().size() > PORTABLE_STORAGE_BUFFERS_PER_COMPUTE) {
            PrismStorageBinding offending = pipeline.storageBuffers().get(PORTABLE_STORAGE_BUFFERS_PER_COMPUTE);
            throw new PrismPackLoadException(
                    "resource_storage_buffer_limit",
                    "Compute program '" + pipeline.id() + "' requires " + pipeline.storageBuffers().size()
                            + " storage-buffer descriptors; Prism portable Vulkan budget is "
                            + PORTABLE_STORAGE_BUFFERS_PER_COMPUTE + ". First offending binding: '"
                            + offending.name() + "'",
                    "prism.json");
        }
    }
}

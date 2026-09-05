package dev.dreamveil.prism.bridge;

/** Private usage bits extended into Vulkan usage flags by VulkanConstMixin. */
public final class PrismVulkanUsage {
    public static final int STORAGE_TEXTURE = 1 << 28;
    public static final int STORAGE_BUFFER = 1 << 28;
    public static final int TEXTURE_2D_ARRAY = 1 << 27;
    public static final int TEXTURE_3D = 1 << 26;

    private PrismVulkanUsage() {}
}

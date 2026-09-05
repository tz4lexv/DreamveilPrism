package dev.dreamveil.prism.bridge;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.backend.PrismBackend;
import dev.dreamveil.prism.backend.PrismBackendInfo;
import dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.mixin.GpuDeviceAccessorMixin;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** Minecraft-facing backend for Prism v0.14 beta. Only proven end-to-end capabilities are advertised. */
public final class Blaze3DPrismBackend implements PrismBackend {
    private final PrismBackendInfo info;

    private final Blaze3DGraphExecutor graphExecutor = new Blaze3DGraphExecutor();
    private Blaze3DTransientResourcePool transientPool;
    private long retiredCreatedTextures;
    private long retiredClosedTextures;
    private long retiredCreatedTextureViews;
    private long retiredClosedTextureViews;
    private long retiredCreatedBuffers;
    private long retiredClosedBuffers;
    private long retiredReuseHits;
    private boolean initialized;
    private boolean loggedPoolStats;

    public Blaze3DPrismBackend() {
        GpuDevice currentDevice = RenderSystem.tryGetDevice();
        boolean nativeVulkanBackend = currentDevice != null
                && ((GpuDeviceAccessorMixin) (Object) currentDevice).prism$getBackend() instanceof VulkanDevice;
        this.info = new PrismBackendInfo(
                nativeVulkanBackend ? "Minecraft Blaze3D / Vulkan" : "Minecraft Blaze3D",
                "26.2 Vulkan-first creator runtime / Prism v" + PrismMod.VERSION,
                PrismMinecraft26_2Capabilities.proven(nativeVulkanBackend));
    }

    @Override
    public PrismBackendInfo info() {
        return info;
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }
        resetRetiredPoolStats();
        initialized = true;
        PrismMod.LOGGER.info("Prism Blaze3D backend online; vulkan={}, MRT, generated mipmaps, cubemaps, scene jitter and dynamic pack resolution enabled; nativeCompute={}, multidimensionalTextures={}",
                info.supports(dev.dreamveil.prism.api.PrismCapability.VULKAN_BACKEND),
                info.supports(dev.dreamveil.prism.api.PrismCapability.COMPUTE_PIPELINES),
                info.supports(dev.dreamveil.prism.api.PrismCapability.TEXTURE_3D));
    }

    public void registerPassExecutor(String passName, Blaze3DPassExecutor executor) {
        ensureInitialized();
        graphExecutor.register(passName, executor);
    }

    public void unregisterPassExecutor(String passName) {
        graphExecutor.unregister(passName);
    }

    public void executeGraph(
            PrismCompiledGraph graph,
            Blaze3DFrameResources resources,
            LevelRenderContext levelRenderContext) {
        executeGraph(graph, resources, levelRenderContext, null, 1.0);
    }

    public void executeGraph(
            PrismCompiledGraph graph,
            Blaze3DFrameResources resources,
            LevelRenderContext levelRenderContext,
            CommandEncoder commandEncoder) {
        executeGraph(graph, resources, levelRenderContext, commandEncoder, 1.0);
    }

    public void executeGraph(
            PrismCompiledGraph graph,
            Blaze3DFrameResources resources,
            LevelRenderContext levelRenderContext,
            CommandEncoder commandEncoder,
            double dynamicScale) {
        ensureInitialized();
        ensurePoolForCurrentDevice();
        graphExecutor.execute(graph, resources, levelRenderContext, transientPool, commandEncoder, dynamicScale);

        if (!loggedPoolStats && graph.transientPlan().slotCount() > 0) {
            loggedPoolStats = true;
            PrismMod.LOGGER.info(
                    "Prism transient pool first frame: logical={}, physicalSlots={}, stats={}",
                    graph.transientPlan().slotByResource().size(),
                    graph.transientPlan().slotCount(),
                    transientPool.stats());
        }
    }

    public Blaze3DTransientPoolStats transientPoolStats() {
        ensureInitialized();
        ensurePoolForCurrentDevice();
        Blaze3DTransientPoolStats current = transientPool.stats();
        return new Blaze3DTransientPoolStats(
                retiredCreatedTextures + current.createdTextures(),
                retiredClosedTextures + current.closedTextures(),
                retiredCreatedTextureViews + current.createdTextureViews(),
                retiredClosedTextureViews + current.closedTextureViews(),
                retiredCreatedBuffers + current.createdBuffers(),
                retiredClosedBuffers + current.closedBuffers(),
                retiredReuseHits + current.reuseHits(),
                current.cachedResources(),
                current.activeResources());
    }

    @Override
    public void close() {
        if (!initialized) {
            return;
        }
        initialized = false;
        graphExecutor.clear();
        retireTransientPool();
        PrismMod.LOGGER.info("Prism Blaze3D backend closed and transient GPU resources released");
    }

    private void ensurePoolForCurrentDevice() {
        GpuDevice currentDevice = RenderSystem.getDevice();
        if (transientPool != null && transientPool.device() == currentDevice) {
            return;
        }
        retireTransientPool();
        transientPool = new Blaze3DTransientResourcePool(currentDevice);
        loggedPoolStats = false;
    }

    private void retireTransientPool() {
        if (transientPool == null) {
            return;
        }
        transientPool.close();
        Blaze3DTransientPoolStats retired = transientPool.stats();
        retiredCreatedTextures += retired.createdTextures();
        retiredClosedTextures += retired.closedTextures();
        retiredCreatedTextureViews += retired.createdTextureViews();
        retiredClosedTextureViews += retired.closedTextureViews();
        retiredCreatedBuffers += retired.createdBuffers();
        retiredClosedBuffers += retired.closedBuffers();
        retiredReuseHits += retired.reuseHits();
        transientPool = null;
    }

    private void resetRetiredPoolStats() {
        retiredCreatedTextures = 0L;
        retiredClosedTextures = 0L;
        retiredCreatedTextureViews = 0L;
        retiredClosedTextureViews = 0L;
        retiredCreatedBuffers = 0L;
        retiredClosedBuffers = 0L;
        retiredReuseHits = 0L;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Prism Blaze3D backend is not initialized");
        }
    }
}

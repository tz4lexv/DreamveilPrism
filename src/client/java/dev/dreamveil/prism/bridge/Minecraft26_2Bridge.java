package dev.dreamveil.prism.bridge;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.backend.PrismBackend;
import dev.dreamveil.prism.render.PrismDebugEntry;
import dev.dreamveil.prism.render.PrismFrameTracker;
import dev.dreamveil.prism.render.PrismProbeRenderer;
import dev.dreamveil.prism.pack.PrismShaderPackManager;
import dev.dreamveil.prism.ui.PrismShadersUi;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Minecraft 26.2 adapter. Keep Minecraft-version-specific hooks here. */
public final class Minecraft26_2Bridge implements PrismMinecraftBridge {
    private static final Identifier SHADER_RELOAD_BEFORE =
            Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "before_shader_cache_invalidation");
    private static final Identifier SHADER_RELOAD_AFTER =
            Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "after_shader_cache_invalidation");
    private static boolean reloadListenerRegistered;
    private static volatile PrismShaderPackManager reloadTarget;

    private final Blaze3DPrismBackend backend = new Blaze3DPrismBackend();
    private boolean initialized;
    private PrismShaderPackManager packManager;

    @Override
    public String minecraftVersion() {
        return "26.2";
    }

    @Override
    public PrismBackend backend() {
        return backend;
    }

    @Override
    public void initialize(PrismBridgeContext context) {
        if (initialized) {
            return;
        }
        initialized = true;
        backend.initialize();
        PrismFrameTracker.initialize(context);
        PrismDebugEntry.initialize();
        PrismProbeRenderer.initialize(backend);
        packManager = new PrismShaderPackManager(
                backend,
                context.packApi(),
                context.settingsApi(),
                context.performanceApi(),
                context.worldRenderApi());
        packManager.initialize();
        reloadTarget = packManager;
        registerShaderReloadListener();
        PrismShadersUi.initialize(packManager);
        PrismMod.LOGGER.info("Minecraft {} Prism bridge initialized with creator API {}; execution support is reported by negotiated capabilities",
                minecraftVersion(), dev.dreamveil.prism.api.PrismApiVersion.CURRENT);
    }

    public static synchronized void registerShaderReloadListener() {
        if (reloadListenerRegistered) return;
        reloadListenerRegistered = true;
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(
                SHADER_RELOAD_BEFORE,
                (ResourceManagerReloadListener) resources -> {
                    PrismShaderPackManager current = reloadTarget;
                    if (current != null) current.beforeMinecraftShaderResourcesReload();
                });
        loader.registerReloadListener(
                SHADER_RELOAD_AFTER,
                (ResourceManagerReloadListener) resources -> {
                    PrismShaderPackManager current = reloadTarget;
                    if (current != null) current.afterMinecraftShaderResourcesReload();
                });
        // There must be no renderable frame between native invalidation and Prism's fallback.
        loader.addListenerOrdering(SHADER_RELOAD_BEFORE, ResourceReloaderKeys.Client.SHADERS);
        loader.addListenerOrdering(ResourceReloaderKeys.AFTER_VANILLA, SHADER_RELOAD_AFTER);
    }

    @Override
    public void close() {
        if (!initialized) {
            return;
        }
        initialized = false;
        PrismFrameTracker.close();
        PrismDebugEntry.close();
        PrismShadersUi.close();
        reloadTarget = null;
        if (packManager != null) {
            packManager.close();
            packManager = null;
        }
        PrismProbeRenderer.close(backend);
        backend.close();
    }
}

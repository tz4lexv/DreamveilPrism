package dev.dreamveil.prism.client;

import dev.dreamveil.prism.bridge.Minecraft26_2Bridge;
import dev.dreamveil.prism.runtime.PrismRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class PrismClient implements ClientModInitializer {
    private static PrismRuntime runtime;
    private static boolean lifecycleRegistered;

    @Override
    public synchronized void onInitializeClient() {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        // Resource reloaders are snapshotted before CLIENT_STARTED. Register this boundary now;
        // its runtime target is attached only after the Vulkan device becomes available.
        Minecraft26_2Bridge.registerShaderReloadListener();
        // Minecraft 26.2 invokes Fabric client entrypoints before RenderSystem owns a GpuDevice.
        // Negotiate Vulkan-only capabilities after window/device initialization instead.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> startRuntime());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    private static synchronized void startRuntime() {
        if (runtime != null) return;
        PrismRuntime candidate = new PrismRuntime(new Minecraft26_2Bridge());
        candidate.initialize();
        runtime = candidate;
    }

    public static synchronized void shutdown() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
            PrismChatReporter.clear();
        }
    }
}

package dev.dreamveil.prism.render;

import java.util.ArrayList;
import java.util.List;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.PrismApi;
import dev.dreamveil.prism.api.diagnostics.PrismDiagnosticsSnapshot;
import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.pack.PrismShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Minecraft 26.2 F3 integration. All displayed data is read through the public Prism API. */
public final class PrismDebugEntry implements DebugScreenEntry {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "runtime");

    private static boolean definitionRegistered;
    private static boolean attachFailureLogged;
    private static DebugScreenEntryList attachedList;

    private PrismDebugEntry() {
    }

    /**
     * Registers Prism's debug entry definition. Minecraft creates the per-client
     * DebugScreenEntryList later in its constructor, so attaching to the overlay is
     * intentionally deferred to {@link #tryAttach()}.
     */
    public static synchronized void initialize() {
        if (!definitionRegistered) {
            DebugScreenEntries.register(ID, new PrismDebugEntry());
            definitionRegistered = true;
        }

        // This may legitimately return false during Minecraft's constructor.
        tryAttach();
    }

    /**
     * Attaches the already registered Prism entry to the live F3 overlay once
     * Minecraft has created its DebugScreenEntryList. Safe to call repeatedly.
     *
     * @return true when the entry is attached to the current debug list.
     */
    public static synchronized boolean tryAttach() {
        if (!definitionRegistered) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }

        DebugScreenEntryList debugEntries = minecraft.debugEntries;
        if (debugEntries == null) {
            return false;
        }

        if (attachedList == debugEntries) {
            return true;
        }

        try {
            debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
            attachedList = debugEntries;
            attachFailureLogged = false;
            PrismMod.LOGGER.info("Prism creator diagnostics attached to the Minecraft debug overlay");
            return true;
        } catch (RuntimeException exception) {
            if (!attachFailureLogged) {
                attachFailureLogged = true;
                PrismMod.LOGGER.warn(
                        "Prism creator diagnostics could not attach to the Minecraft 26.2 debug overlay; runtime continues",
                        exception);
            }
            return false;
        }
    }

    /** Runtime shutdown/reset hook. The global Minecraft debug entry registry has no unregister API. */
    public static synchronized void close() {
        attachedList = null;
        attachFailureLogged = false;
    }

    @Override
    public void display(
            DebugScreenDisplayer displayer,
            Level level,
            LevelChunk clientChunk,
            LevelChunk serverChunk) {
        PrismApi api = Prism.tryApi().orElse(null);
        if (api == null) {
            displayer.addLine("Dreamveil Prism: API not initialized");
            return;
        }

        PrismDiagnosticsSnapshot diagnostics = api.diagnostics().snapshot();
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism " + diagnostics.prismVersion() + " | API " + diagnostics.apiVersion());
        lines.add("Backend: " + diagnostics.backendName());
        lines.add("Capabilities: " + diagnostics.capabilities().size());
        var activePack = api.packs().active().orElse(null);
        if (activePack != null) {
            lines.add("Pack: " + activePack.name() + " " + activePack.version()
                    + " | " + activePack.status() + " | gen " + activePack.generation());
            if (!activePack.diagnostics().isEmpty()) {
                var diagnostic = activePack.diagnostics().get(0);
                String message = diagnostic.code() + ": " + diagnostic.message();
                if (message.length() > 96) {
                    message = message.substring(0, 93) + "...";
                }
                lines.add("Pack diag: " + message);
            }
        } else {
            lines.add("Pack: none | pack ids " + api.packs().installed().size()
                    + " | versions " + api.packs().allVariants().size());
        }

        var cache = api.performance().pipelineCacheSnapshot();
        if (cache.totalPipelineDecisions() > 0) {
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Prism cache: %.0f%% pipeline reuse | %d compiled",
                    cache.reuseRatio() * 100.0,
                    cache.compiledPipelines()));
        }

        var lifetime = api.performance().resourceLifetimeSnapshot();
        if (lifetime.liveGenerations() > 0 || lifetime.pendingGenerations() > 0
                || lifetime.sessionCachedPipelines() > 0 || lifetime.transientActiveResources() > 0) {
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Prism life: gen %d+%d pending | bindings %d | transient %d active | cache %d",
                    lifetime.liveGenerations(),
                    lifetime.pendingGenerations(),
                    lifetime.livePipelineBindings(),
                    lifetime.transientActiveResources(),
                    lifetime.sessionCachedPipelines()));
        }

        var performance = api.performance().snapshot();
        if (performance.packActive()) {
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Prism CPU: %.3f ms avg | %.3f ms last",
                    performance.averagePackCpuMilliseconds(),
                    performance.lastPackCpuMilliseconds()));
        }

        var shadowExecution = api.worldRendering().shadowExecutionSnapshot();
        if (shadowExecution.visibilityPlanningAvailable() && !shadowExecution.gpuShadowPassAvailable()) {
            lines.add("Shadow GPU: guarded | " + shadowExecution.blockerCode());
        }

        var shadowCulling = api.performance().shadowCullingSnapshot();
        if (shadowCulling.available()) {
            String gpuSuffix = shadowCulling.gpuTimeAvailable()
                    ? String.format(java.util.Locale.ROOT, " | %.3f ms GPU", shadowCulling.gpuMilliseconds())
                    : "";
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Shadow draws: %d independent%s",
                    shadowCulling.drawCalls(),
                    gpuSuffix));
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Shadow visibility: %d/%d accepted | %d culled",
                    shadowCulling.accepted(),
                    shadowCulling.candidates(),
                    shadowCulling.culled()));
            lines.add(PrismShadowRenderer.celestialDebugLine());
            lines.add(PrismShadowRenderer.celestialCacheDebugLine());
            lines.add(PrismShadowRenderer.receiverCasterDebugLine());
            lines.add(PrismShadowRenderer.receiverSamplingDebugLine());
        }

        var gpuPerformance = api.performance().gpuSnapshot();
        if (gpuPerformance.available()) {
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Prism GPU: %.3f ms avg | delayed timestamps",
                    gpuPerformance.averageTotalMilliseconds()));
        }

        PrismFrameData frame = api.frames().current().orElse(null);
        if (frame != null) {
            lines.add("Frame: " + frame.frameIndex() + " | " + frame.renderWidth() + "x" + frame.renderHeight());
            lines.add(String.format(
                    java.util.Locale.ROOT,
                    "Camera: %.2f %.2f %.2f | far %.1f",
                    frame.cameraPosition().x(),
                    frame.cameraPosition().y(),
                    frame.cameraPosition().z(),
                    frame.depthFar()));
            lines.add("Depth: " + (frame.reversedDepth() ? "reversed-Z" : "forward-Z")
                    + " | render projection kept raw");
        } else {
            lines.add("Frame API: waiting for world render");
        }

        displayer.addToGroup(ID, lines);
    }

    @Override
    public DebugEntryCategory category() {
        return DebugEntryCategory.RENDERER;
    }
}

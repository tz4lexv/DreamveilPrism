package dev.dreamveil.prism.ui;

import java.util.Locale;

import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.performance.PrismGpuPerformanceSnapshot;
import dev.dreamveil.prism.api.performance.PrismPerformanceSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Creator profiler screen with CPU submission timings and asynchronous GPU timestamp timings when available. */
public final class PrismPerformanceScreen extends Screen {
    private final Screen parent;

    public PrismPerformanceScreen(Screen parent) {
        super(Component.literal("Dreamveil Prism Profiler"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var graph = Prism.tryApi()
                .map(api -> api.performance().graphSnapshot())
                .orElse(dev.dreamveil.prism.api.performance.PrismGraphDiagnosticsSnapshot.EMPTY);
        Button graphButton = Button.builder(Component.literal("Render Graph..."), button -> {
                    if (minecraft != null && minecraft.gui != null) {
                        minecraft.gui.setScreen(new PrismRenderGraphScreen(this));
                    }
                })
                .bounds(width / 2 - 104, height - 28, 100, 20)
                .build();
        graphButton.active = graph.available();
        addRenderableWidget(graphButton);
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 4, height - 28, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        PrismPerformanceSnapshot cpu = Prism.tryApi()
                .map(api -> api.performance().snapshot())
                .orElse(PrismPerformanceSnapshot.EMPTY);
        PrismGpuPerformanceSnapshot gpu = Prism.tryApi()
                .map(api -> api.performance().gpuSnapshot())
                .orElse(PrismGpuPerformanceSnapshot.EMPTY);
        var cache = Prism.tryApi()
                .map(api -> api.performance().pipelineCacheSnapshot())
                .orElse(dev.dreamveil.prism.api.performance.PrismPipelineCacheSnapshot.EMPTY);
        var lifetime = Prism.tryApi()
                .map(api -> api.performance().resourceLifetimeSnapshot())
                .orElse(dev.dreamveil.prism.api.performance.PrismResourceLifetimeSnapshot.EMPTY);
        var shadowCulling = Prism.tryApi()
                .map(api -> api.performance().shadowCullingSnapshot())
                .orElse(dev.dreamveil.prism.api.performance.PrismShadowCullingSnapshot.EMPTY);
        var shadowExecution = Prism.tryApi()
                .map(api -> api.worldRendering().shadowExecutionSnapshot())
                .orElse(dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot.UNAVAILABLE);

        graphics.centeredText(font, Component.literal("Dreamveil Prism Profiler"), width / 2, 18, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("CPU submission + asynchronous GPU timestamps"), width / 2, 36, 0xFFFFD37A);

        if (!cpu.packActive()) {
            graphics.centeredText(font, Component.literal("No active Prism shader pack."), width / 2, 68, 0xFFE5E5E5);
            return;
        }

        int y = 58;
        line(graphics, y, "Pack: " + cpu.packId());
        y += 14;
        line(graphics, y, String.format(Locale.ROOT,
                "CPU pack: last %.3f ms | avg %.3f ms | max %.3f ms | samples %d",
                cpu.lastPackCpuMilliseconds(),
                cpu.averagePackCpuMilliseconds(),
                cpu.maxPackCpuMilliseconds(),
                cpu.samples()));
        y += 14;
        if (gpu.available()) {
            line(graphics, y, String.format(Locale.ROOT,
                    "GPU passes: %.3f ms avg total | delayed/non-blocking",
                    gpu.averageTotalMilliseconds()));
        } else {
            line(graphics, y, "GPU passes: waiting for timestamp samples / unavailable on backend");
        }
        y += 14;
        line(graphics, y, "Pipelines: " + cpu.pipelineCount()
                + " | transients: " + cpu.logicalTransientResources()
                + " logical / " + cpu.physicalTransientSlots() + " physical slots");
        y += 14;
        line(graphics, y, String.format(Locale.ROOT,
                "Pipeline cache: %d reused / %d compiled | %.1f%% reuse | %d failed-cache skips",
                cache.reusedPipelines(),
                cache.compiledPipelines(),
                cache.reuseRatio() * 100.0,
                cache.cachedFailuresSkipped()));
        y += 14;
        line(graphics, y, String.format(Locale.ROOT,
                "Lifetime: gen %d live / %d pending | bindings %d | sampler %d | session cache %d",
                lifetime.liveGenerations(),
                lifetime.pendingGenerations(),
                lifetime.livePipelineBindings(),
                lifetime.liveSamplers(),
                lifetime.sessionCachedPipelines()));
        y += 14;
        line(graphics, y, String.format(Locale.ROOT,
                "Transient lifetime: tex %d/%d | views %d/%d | buffers %d/%d | active %d | reuse %d",
                lifetime.transientTexturesCreated(), lifetime.transientTexturesClosed(),
                lifetime.transientTextureViewsCreated(), lifetime.transientTextureViewsClosed(),
                lifetime.transientBuffersCreated(), lifetime.transientBuffersClosed(),
                lifetime.transientActiveResources(), lifetime.transientReuseHits()));
        y += 14;
        if (shadowCulling.available()) {
            String gpuShadow = shadowCulling.gpuTimeAvailable()
                    ? String.format(Locale.ROOT, "%.3f ms GPU", shadowCulling.gpuMilliseconds())
                    : "GPU pending";
            line(graphics, y, String.format(Locale.ROOT,
                    "Shadow visibility: %d/%d accepted | %d culled | %d independent draws | %s",
                    shadowCulling.accepted(), shadowCulling.candidates(), shadowCulling.culled(),
                    shadowCulling.drawCalls(), gpuShadow));
            y += 14;
        }
        if (!shadowExecution.gpuShadowPassAvailable() && shadowExecution.visibilityPlanningAvailable()) {
            line(graphics, y, "Shadow GPU: guarded | " + shadowExecution.blockerCode());
            y += 14;
        }
        y += 6;
        line(graphics, y, "Pass timings   CPU avg / GPU avg");
        y += 14;

        int shown = 0;
        for (var pass : cpu.passes()) {
            if (y > height - 48) break;
            double gpuMs = gpu.passes().stream()
                    .filter(candidate -> candidate.passName().equals(pass.passName()))
                    .findFirst()
                    .map(candidate -> candidate.averageGpuMilliseconds())
                    .orElse(Double.NaN);
            String gpuText = Double.isNaN(gpuMs) ? "pending" : String.format(Locale.ROOT, "%.3f ms", gpuMs);
            line(graphics, y, String.format(Locale.ROOT,
                    "%s   %.3f ms / %s",
                    pass.passName(),
                    pass.averageCpuMilliseconds(),
                    gpuText));
            y += 13;
            shown++;
        }
        if (shown < cpu.passes().size() && y <= height - 48) {
            line(graphics, y, "+ " + (cpu.passes().size() - shown) + " more passes");
        }
    }

    private void line(GuiGraphicsExtractor graphics, int y, String text) {
        graphics.centeredText(font, Component.literal(text), width / 2, y, 0xFFE5E5E5);
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.gui != null) minecraft.gui.setScreen(parent);
    }
}

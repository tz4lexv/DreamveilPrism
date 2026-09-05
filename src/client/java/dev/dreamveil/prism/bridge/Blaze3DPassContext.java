package dev.dreamveil.prism.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismResourceAccess;
import dev.dreamveil.prism.graph.PrismResourceRef;
import dev.dreamveil.prism.graph.PrismResourceTransition;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Resource-scoped context for one executing Blaze3D graph pass.
 *
 * A pass can resolve only resources it declared in the render graph. This is a
 * correctness requirement once transient resources can alias: retaining or
 * reaching into another pass' logical resource would otherwise make lifetime
 * analysis unsound.
 */
public final class Blaze3DPassContext {
    private final PrismPass pass;
    private final Blaze3DFrameResources frameResources;
    private final LevelRenderContext levelRenderContext;
    private final CommandEncoder commandEncoder;
    private final Map<String, PrismResourceAccess> declaredAccess;
    private final List<PrismResourceTransition> incomingTransitions;
    private final List<PrismResourceTransition> outgoingTransitions;

    Blaze3DPassContext(
            PrismPass pass,
            Blaze3DFrameResources frameResources,
            LevelRenderContext levelRenderContext,
            CommandEncoder commandEncoder,
            List<PrismResourceTransition> incomingTransitions,
            List<PrismResourceTransition> outgoingTransitions) {
        this.pass = Objects.requireNonNull(pass, "pass");
        this.frameResources = Objects.requireNonNull(frameResources, "frameResources");
        this.levelRenderContext = Objects.requireNonNull(levelRenderContext, "levelRenderContext");
        this.commandEncoder = commandEncoder;
        this.incomingTransitions = List.copyOf(incomingTransitions);
        this.outgoingTransitions = List.copyOf(outgoingTransitions);

        LinkedHashMap<String, PrismResourceAccess> access = new LinkedHashMap<>();
        for (PrismResourceRef ref : pass.resources()) {
            access.put(ref.name(), ref.access());
        }
        this.declaredAccess = Map.copyOf(access);
    }

    public PrismPass pass() {
        return pass;
    }

    public LevelRenderContext levelRenderContext() {
        return levelRenderContext;
    }

    /**
     * Returns the command encoder owned by the current host frame. Fullscreen presentation passes
     * require this encoder so their writes are ordered before Minecraft's surface blit and are
     * included in the same eventual submission.
     */
    public CommandEncoder requireCommandEncoder() {
        if (commandEncoder == null) {
            throw new IllegalStateException(
                    "Prism pass '" + pass.name() + "' requires a host CommandEncoder but none was supplied");
        }
        return commandEncoder;
    }

    public PrismResourceAccess access(String resourceName) {
        PrismResourceAccess access = declaredAccess.get(resourceName);
        if (access == null) {
            throw new IllegalStateException(
                    "Prism pass '" + pass.name() + "' attempted undeclared resource access to '"
                            + resourceName + "'");
        }
        return access;
    }

    public GpuTextureView requireTexture(String resourceName) {
        access(resourceName);
        return frameResources.requireTexture(resourceName);
    }

    public GpuBuffer requireBuffer(String resourceName) {
        access(resourceName);
        return frameResources.requireBuffer(resourceName);
    }

    public List<PrismResourceTransition> incomingTransitions() {
        return incomingTransitions;
    }

    public List<PrismResourceTransition> outgoingTransitions() {
        return outgoingTransitions;
    }
}

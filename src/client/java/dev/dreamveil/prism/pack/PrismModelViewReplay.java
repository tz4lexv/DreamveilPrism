package dev.dreamveil.prism.pack;

import java.util.List;
import java.util.IdentityHashMap;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.mixin.RenderTypeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

/** One bounded upload per world frame, reused by all creator model views. */
final class PrismModelViewReplay {
    static final long GPU_BYTES = (long)PrismModelViewCapture.MAX_VERTICES * PrismModelViewCapture.STRIDE;
    static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT).addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT).addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("LightUV", GpuFormat.RG32_FLOAT).addAttribute("OverlayUV", GpuFormat.RG32_FLOAT).build();
    private static GpuDevice device;
    private static GpuBuffer buffer;
    private static List<PrismModelViewCapture.Draw> draws = List.of();
    private static final IdentityHashMap<RenderType, PreparedRenderType.Texture> textures = new IdentityHashMap<>();
    private static boolean logged;
    static void prepare(GpuDevice gpu, CommandEncoder encoder) {
        var bytes = PrismModelViewCapture.seal();
        draws = PrismModelViewCapture.draws(); textures.clear();
        if (!bytes.hasRemaining()) return;
        if (gpu != device || buffer == null) {
            close(); device = gpu;
            buffer = gpu.createBuffer(() -> "Prism captured model views", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, GPU_BYTES);
            draws = PrismModelViewCapture.draws();
        }
        int uploadedBytes = bytes.remaining();
        encoder.writeToBuffer(buffer.slice(0, uploadedBytes), bytes);
        if (!logged) {
            logged = true;
            PrismMod.LOGGER.info("Prism model view upload: vertices={}, batches={}, dropped={}, bytes={}",
                    uploadedBytes / PrismModelViewCapture.STRIDE, draws.size(), PrismModelViewCapture.dropped(), uploadedBytes);
        }
    }
    static int draw(RenderPass pass, PrismSceneViewDefinition view) {
        if (buffer == null || draws.isEmpty()) return 0;
        var mc = Minecraft.getInstance();
        var frame = dev.dreamveil.prism.api.Prism.api().frames().current().orElse(null);
        if (view.visibility() != null && frame == null) return 0;
        var origin = frame == null ? null : frame.cameraPosition();
        var volume = view.visibility() == null ? null : view.visibility().resolve(origin.x(),origin.y(),origin.z());
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int recorded = 0;
        pass.setVertexBuffer(0, buffer.slice());
        for (var draw : draws) {
            if (!view.modelDomains().contains(draw.domain())) continue;
            if (volume != null) {
                // Query scopes BOTH main-captured and auxiliary geometry. Bounds precede creator GPU deformation.
                if (draw.bounds() == null || !draw.bounds().intersects(volume,origin.x(),origin.y(),origin.z())) continue;
            } else if (!draw.includedBy(view.modelRadius())) continue;
            if (!textures.containsKey(draw.type())) {
                var state = ((RenderTypeAccessor)(Object)draw.type()).dreamveilPrism$getState();
                // Only Sampler0 is consumed. Overlay/lightmap texture records are intentionally
                // ignored; their raw vertex coordinates are available to creator GLSL.
                var albedo = state.prepareTextures(mc.getTextureManager(), RenderSystem.getSamplerCache(), null, null)
                        .stream().filter(t -> t.name().equals("Sampler0")).findFirst().orElse(null);
                textures.put(draw.type(), albedo);
            }
            var texture = textures.get(draw.type());
            if (texture == null || texture.textureView() == null || texture.sampler() == null) continue;
            pass.bindTexture(view.atlasSampler(), texture.textureView(), texture.sampler());
            int indices = draw.vertices() / 4 * 6;
            pass.setIndexBuffer(sequential.getBuffer(indices), sequential.type());
            pass.drawIndexed(indices, 1, 0, draw.firstVertex(), 0);
            recorded++;
        }
        return recorded;
    }
    static void close() {
        if (buffer != null) buffer.close();
        buffer = null; device = null; draws = List.of(); textures.clear(); logged = false;
    }
    static void endFrame() { draws = List.of(); textures.clear(); }
}

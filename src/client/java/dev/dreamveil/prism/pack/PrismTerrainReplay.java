package dev.dreamveil.prism.pack;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.dreamveil.prism.api.Prism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;

/** Read-only replay of current resident terrain buffers. Never invokes LevelRenderer.render recursively. */
final class PrismTerrainReplay {

    static int draw(RenderPass pass, PrismSceneViewDefinition view, String label) {
        var mc = Minecraft.getInstance();
        var renderer = mc.levelRenderer;
        if (mc.level == null || renderer == null || renderer.viewArea() == null) return 0;
        var area = renderer.viewArea();
        var dispatcher = renderer.sectionRenderDispatcher();
        if (dispatcher == null) return 0;
        var frame = Prism.api().frames().current().orElse(null);
        if (frame == null) return 0;
        int cx = (int)Math.floor(frame.cameraPosition().x() / 16.0);
        int cy = (int)Math.floor(frame.cameraPosition().y() / 16.0);
        int cz = (int)Math.floor(frame.cameraPosition().z() / 16.0);
        int radius = Math.min(view.sectionRadius(), area.getViewDistance());
        int minY = Math.max(area.minSectionY(), cy - radius);
        int maxY = Math.min(area.minSectionY() + area.sectionCount() - 1, cy + radius);
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        pass.bindTexture(view.atlasSampler(), atlas.getTextureView(), atlas.getSampler());
        var origin = new BlockPos.MutableBlockPos();
        int draws = 0;
        // Enumerate a bounded camera-origin volume, NOT the main camera's visible section list.
        // Far/hidden sections outside Minecraft's resident mesh set remain unavailable.
        for (String layerName : view.layers()) {
            ChunkSectionLayer layer = switch (layerName) {
                case "solid" -> ChunkSectionLayer.SOLID;
                case "cutout" -> ChunkSectionLayer.CUTOUT;
                case "translucent" -> ChunkSectionLayer.TRANSLUCENT;
                default -> throw new IllegalStateException("Unknown replay layer " + layerName);
            };
            if (!layer.vertexFormat().equals(ChunkSectionLayer.SOLID.vertexFormat())) {
                throw new IllegalStateException("Terrain replay vertex ABI changed for " + layer);
            }
            var sequential = RenderSystem.getSequentialBuffer(layer.pipeline().getPrimitiveTopology());
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        origin.set(x << 4, y << 4, z << 4);
                        var section = area.getRenderSectionAt(origin);
                        // ViewArea is a ring buffer; recycled sections must not acquire the wrong world origin.
                        if (section == null || !origin.equals(section.getRenderOrigin())) continue;
                        var mesh = section.getSectionMesh();
                        if (mesh == null || mesh.isEmpty(layer)) continue;
                        var draw = mesh.getSectionDraw(layer);
                        if (draw == null || draw.indexCount() <= 0) continue;
                        var slice = dispatcher.getRenderSectionSlice(mesh, layer);
                        if (slice == null || slice.vertexBuffer() == null) continue;
                        GpuBuffer indexBuffer;
                        com.mojang.blaze3d.IndexType indexType;
                        int firstIndex;
                        // The translucent custom index buffer is sorted for the MAIN camera. An
                        // auxiliary projection must not inherit that order. Submit original quads;
                        // creators can capture the nearest surface or accumulate order-independent data.
                        if (draw.hasCustomIndexBuffer() && layer != ChunkSectionLayer.TRANSLUCENT) {
                            indexBuffer = slice.indexBuffer();
                            if (indexBuffer == null) continue;
                            indexType = draw.indexType();
                            firstIndex = PrismShadowDrawEncoding.firstIndex(slice.indexBufferOffset(), indexType.bytes);
                        } else {
                            indexBuffer = sequential.getBuffer(draw.indexCount());
                            indexType = sequential.type();
                            firstIndex = 0;
                        }
                        int baseVertex = PrismShadowDrawEncoding.baseVertex(slice.vertexBufferOffset(), layer.vertexFormat().getVertexSize());
                        pass.setVertexBuffer(0, slice.vertexBuffer().slice());
                        pass.setIndexBuffer(indexBuffer, indexType);
                        pass.drawIndexed(draw.indexCount(), 1, firstIndex, baseVertex,
                                PrismShadowDrawEncoding.packRelativeSection(x - cx, y - cy, z - cz));
                        draws++;
                    }
                }
            }
        }
        return draws;
    }
}

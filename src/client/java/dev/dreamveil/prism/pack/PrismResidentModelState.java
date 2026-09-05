package dev.dreamveil.prism.pack;

import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** Extraction-owned snapshot carried by the corresponding vanilla frame, never a live world. */
public interface PrismResidentModelState {
    record Snapshot(long generation, List<EntityRenderState> entities, List<BlockEntityRenderState> blocks) {
        public static final Snapshot EMPTY = new Snapshot(-1, List.of(), List.of());
        public Snapshot { entities = List.copyOf(entities); blocks = List.copyOf(blocks); }
    }
    Snapshot prism$residentModels();
    void prism$residentModels(Snapshot snapshot);
}

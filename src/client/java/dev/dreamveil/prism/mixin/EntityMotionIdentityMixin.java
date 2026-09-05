package dev.dreamveil.prism.mixin;

import java.util.UUID;
import dev.dreamveil.prism.pack.PrismMotionIdentity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityMotionIdentityMixin implements PrismMotionIdentity {
    @Unique private UUID prism$motionId;
    public UUID prism$motionId() { return prism$motionId; }
    public void prism$motionId(UUID id) { prism$motionId = id; }
}

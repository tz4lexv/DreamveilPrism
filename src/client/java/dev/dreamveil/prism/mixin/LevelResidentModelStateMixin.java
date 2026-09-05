package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.pack.PrismResidentModelState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderState.class)
public abstract class LevelResidentModelStateMixin implements PrismResidentModelState {
    @Unique private Snapshot prism$residentModels = Snapshot.EMPTY;
    public Snapshot prism$residentModels() { return prism$residentModels; }
    public void prism$residentModels(Snapshot snapshot) { prism$residentModels = snapshot; }
    @Inject(method = "reset", at = @At("HEAD"))
    private void prism$clearResidentModels(CallbackInfo ci) { prism$residentModels = Snapshot.EMPTY; }
}

package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.pack.PrismResidentModelViews;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelResidentModelExtractMixin {
    @Shadow private ClientLevel level;
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Inject(method = "extract", at = @At("TAIL"))
    private void prism$extractResidentModels(DeltaTracker delta, Camera camera, float partialTick, CallbackInfo ci) {
        PrismResidentModelViews.extract(level, levelRenderer, levelRenderState, camera, delta, partialTick);
    }
}

package dev.dreamveil.prism.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;

/** Gives the Vulkan-only runtime access to Blaze3D's selected backend without reflection. */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessorMixin {
    @Accessor("backend")
    GpuDeviceBackend prism$getBackend();
}

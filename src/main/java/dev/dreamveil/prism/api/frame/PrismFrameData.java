package dev.dreamveil.prism.api.frame;

/** Immutable creator-facing snapshot of the current rendered world frame. */
public record PrismFrameData(
        long frameIndex,
        long gameTimeTicks,
        float deltaSeconds,
        int renderWidth,
        int renderHeight,
        PrismVec3 cameraPosition,
        float cameraYawDegrees,
        float cameraPitchDegrees,
        float depthFar,
        PrismMatrix4 projectionMatrix,
        PrismMatrix4 viewRotationMatrix,
        boolean reversedDepth) {

    public PrismFrameData {
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be >= 0");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and >= 0");
        }
        if (renderWidth < 1 || renderHeight < 1) {
            throw new IllegalArgumentException("render dimensions must be >= 1");
        }
        if (cameraPosition == null || projectionMatrix == null || viewRotationMatrix == null) {
            throw new IllegalArgumentException("Frame camera and matrices must not be null");
        }
        if (!Float.isFinite(depthFar) || depthFar <= 0.0f) {
            throw new IllegalArgumentException("depthFar must be finite and > 0");
        }
    }

    public float aspectRatio() {
        return (float) renderWidth / (float) renderHeight;
    }

    public dev.dreamveil.prism.api.visibility.PrismDepthDirection depthDirection() {
        return reversedDepth
                ? dev.dreamveil.prism.api.visibility.PrismDepthDirection.REVERSED_Z
                : dev.dreamveil.prism.api.visibility.PrismDepthDirection.FORWARD_Z;
    }

    public dev.dreamveil.prism.api.visibility.PrismClipConvention clipConvention(
            double nearPlane,
            dev.dreamveil.prism.api.visibility.PrismDepthRange depthRange,
            dev.dreamveil.prism.api.visibility.PrismProjectionType projectionType) {
        return new dev.dreamveil.prism.api.visibility.PrismClipConvention(
                depthRange, depthDirection(), nearPlane, depthFar, projectionType);
    }

    /**
     * Couples this frame's renderer-owned projection with an explicit clip convention without
     * changing the FrameData record ABI. The caller supplies the backend's depth range and the
     * geometric near plane; reversed/forward direction comes from the captured frame.
     */
    public dev.dreamveil.prism.api.visibility.PrismProjectionDescriptor projectionDescriptor(
            double nearPlane,
            dev.dreamveil.prism.api.visibility.PrismDepthRange depthRange,
            dev.dreamveil.prism.api.visibility.PrismProjectionType projectionType) {
        var convention = clipConvention(nearPlane, depthRange, projectionType);
        return new dev.dreamveil.prism.api.visibility.PrismProjectionDescriptor(projectionMatrix, convention);
    }
}

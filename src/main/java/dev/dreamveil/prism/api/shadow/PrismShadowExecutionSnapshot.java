package dev.dreamveil.prism.api.shadow;

/** Read-only status of the current Minecraft bridge's shadow execution path. */
public record PrismShadowExecutionSnapshot(
        boolean visibilityPlanningAvailable,
        boolean gpuShadowPassAvailable,
        boolean recursiveLevelRenderForbidden,
        String blockerCode,
        String blockerMessage) {

    public static final PrismShadowExecutionSnapshot UNAVAILABLE = new PrismShadowExecutionSnapshot(
            false, false, true, "shadow_bridge_unavailable", "No Prism shadow bridge is active.");

    public PrismShadowExecutionSnapshot {
        blockerCode = blockerCode == null ? "" : blockerCode;
        blockerMessage = blockerMessage == null ? "" : blockerMessage;
        if (gpuShadowPassAvailable && (!blockerCode.isEmpty() || !blockerMessage.isEmpty())) {
            throw new IllegalArgumentException("An active GPU shadow pass cannot publish a blocker");
        }
        if (!gpuShadowPassAvailable && blockerCode.isBlank()) {
            throw new IllegalArgumentException("Unavailable GPU shadow execution requires a blockerCode");
        }
    }
}

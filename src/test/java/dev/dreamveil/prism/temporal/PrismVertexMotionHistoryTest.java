package dev.dreamveil.prism.temporal;

public final class PrismVertexMotionHistoryTest {
    public static void main(String[] args) {
        var history = new PrismVertexMotionHistory<String>(4);
        float[] first = {0,0,0.5f,1, 1,0,0.5f,1};
        float[] deformed = {0,0,0.5f,1, 1,0.2f,0.5f,1};
        history.beginFrame(false);
        check(history.record("entity-A/leg", first, 7) == null, "first appearance must reject history");
        history.beginFrame(true);
        var old = history.record("entity-A/leg", deformed, 7);
        check(old != null && old.clips()[5] == 0 && deformed[5] == 0.2f, "deformation must retain earlier vertex positions");
        check(history.record("entity-B/leg", first, 7) == null, "entities must never share history");
        history.beginFrame(true);
        check(history.record("entity-A/leg", first, 8) == null, "topology change must reject history");
        history.beginFrame(true); // invisible this frame
        history.beginFrame(true);
        check(history.record("entity-A/leg", first, 8) == null, "return after visibility gap must reject history");
        history.beginFrame(false);
        check(history.record("entity-A/leg", first, 8) == null, "camera cut/resize/reload must reject history");
        check(history.record("entity-C", new float[16], 1) == null, "history budget must be bounded");
        history.clear();
        history.beginFrame(true);
        check(history.record("entity-A/leg", first, 8) == null, "world reset must clear identities");
        System.out.println("PRISM_MODEL_MOTION_HISTORY_PASS");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

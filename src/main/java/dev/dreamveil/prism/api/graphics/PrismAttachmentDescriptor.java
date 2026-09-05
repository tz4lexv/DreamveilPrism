package dev.dreamveil.prism.api.graphics;

import java.util.Arrays;

/**
 * API 1.4 render-attachment semantics without exposing backend objects.
 *
 * clearValue uses RGBA for color attachments or depth in element 0 for depth attachments.
 * Prism does not infer that an attachment is color/depth from this object; the resource format
 * remains the source of truth.
 */
public record PrismAttachmentDescriptor(
        PrismAttachmentLoadOp loadOp,
        PrismAttachmentStoreOp storeOp,
        double[] clearValue) {

    public PrismAttachmentDescriptor {
        if (loadOp == null || storeOp == null) {
            throw new IllegalArgumentException("Attachment load/store operations must not be null");
        }
        clearValue = clearValue == null ? new double[0] : Arrays.copyOf(clearValue, clearValue.length);
        if (loadOp == PrismAttachmentLoadOp.CLEAR && clearValue.length == 0) {
            throw new IllegalArgumentException("CLEAR attachments require a clear value");
        }
        for (double value : clearValue) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Attachment clear values must be finite");
            }
        }
    }

    @Override
    public double[] clearValue() {
        return Arrays.copyOf(clearValue, clearValue.length);
    }

    public static PrismAttachmentDescriptor loadStore() {
        return new PrismAttachmentDescriptor(PrismAttachmentLoadOp.LOAD, PrismAttachmentStoreOp.STORE, new double[0]);
    }

    public static PrismAttachmentDescriptor discardStore() {
        return new PrismAttachmentDescriptor(PrismAttachmentLoadOp.DISCARD, PrismAttachmentStoreOp.STORE, new double[0]);
    }

    public static PrismAttachmentDescriptor clearColor(double r, double g, double b, double a) {
        return new PrismAttachmentDescriptor(PrismAttachmentLoadOp.CLEAR, PrismAttachmentStoreOp.STORE, new double[] {r, g, b, a});
    }

    public static PrismAttachmentDescriptor clearDepth(double depth) {
        return new PrismAttachmentDescriptor(PrismAttachmentLoadOp.CLEAR, PrismAttachmentStoreOp.STORE, new double[] {depth});
    }
}

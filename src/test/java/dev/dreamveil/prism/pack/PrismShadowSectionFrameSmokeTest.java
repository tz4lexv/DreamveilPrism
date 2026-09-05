package dev.dreamveil.prism.pack;

/** Standalone smoke test for alpha.5's compact immutable section-visibility snapshot layout. */
public final class PrismShadowSectionFrameSmokeTest {
    private PrismShadowSectionFrameSmokeTest() {}

    public static void main(String[] args) {
        int horizontalDiameter = 3;
        int verticalSections = 2;
        int candidates = horizontalDiameter * horizontalDiameter * verticalSections;
        long[] mask = new long[(candidates + Long.SIZE - 1) / Long.SIZE];
        long[] resident = new long[mask.length];
        accept(mask, 0);
        accept(mask, 4);
        accept(mask, 17);
        accept(resident, 0);
        accept(resident, 17);

        PrismShadowSectionFrameData frame = new PrismShadowSectionFrameData(
                7L,
                32.25, 70.0, -15.75,
                10, -4, -3,
                horizontalDiameter,
                verticalSections,
                candidates,
                3,
                candidates - 3,
                2,
                1,
                11,
                mask,
                resident);

        require(frame.frameIndex() == 7L, "frame index");
        require(frame.candidates() == 18, "candidate count");
        require(frame.accepted() == 3, "accepted count");
        require(frame.culled() == 15, "culled count");
        require(frame.residentResolved() == 2, "resident resolved count");
        require(frame.residentMissing() == 1, "resident missing count");
        require(frame.mainCameraVisibleDiagnostic() == 11, "main-camera diagnostic");

        // linear = (z * diameter + x) * verticalCount + y
        require(frame.sectionX(0) == 10 && frame.sectionY(0) == -4 && frame.sectionZ(0) == -3,
                "first coordinate");
        require(frame.sectionX(4) == 12 && frame.sectionY(4) == -4 && frame.sectionZ(4) == -3,
                "x-major coordinate");
        require(frame.sectionX(17) == 12 && frame.sectionY(17) == -3 && frame.sectionZ(17) == -1,
                "last coordinate");
        require(frame.isAccepted(0) && frame.isAccepted(4) && frame.isAccepted(17), "accepted bits");
        require(!frame.isAccepted(1) && !frame.isAccepted(16), "culled bits");
        require(frame.isResident(0) && !frame.isResident(4) && frame.isResident(17), "resident bits");

        require(frame.nextResidentIndex(0) == 0, "resident iterator first");
        require(frame.nextResidentIndex(1) == 17, "resident iterator skip empty words/bits");
        require(frame.nextResidentIndex(17) == 17, "resident iterator inclusive start");
        require(frame.nextResidentIndex(18) == -1, "resident iterator end");

        // Neither the builder's source array nor an accessor copy may mutate published state.
        mask[0] = 0L;
        resident[0] = 0L;
        require(frame.isAccepted(0) && frame.isAccepted(4) && frame.isAccepted(17), "source-mask isolation");
        require(frame.isResident(0) && frame.isResident(17), "resident source-mask isolation");
        long[] copied = frame.acceptedMaskCopy();
        long[] residentCopied = frame.residentMaskCopy();
        copied[0] = 0L;
        residentCopied[0] = 0L;
        require(frame.isAccepted(0) && frame.isAccepted(4) && frame.isAccepted(17), "copy isolation");
        require(frame.isResident(0) && frame.isResident(17), "resident copy isolation");

        require(PrismShadowSectionFrameData.EMPTY.candidates() == 0, "empty candidates");
        require(PrismShadowSectionFrameData.EMPTY.acceptedMaskCopy().length == 0, "empty mask");
        require(PrismShadowSectionFrameData.EMPTY.residentMaskCopy().length == 0, "empty resident mask");

        System.out.println("PRISM_SHADOW_SECTION_FRAME_SMOKE_OK");
    }

    private static void accept(long[] mask, int index) {
        mask[index >>> 6] |= 1L << (index & 63);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("PrismShadowSectionFrame smoke failed: " + label);
    }
}

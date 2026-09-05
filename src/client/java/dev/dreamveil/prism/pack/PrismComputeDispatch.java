package dev.dreamveil.prism.pack;

/** Workgroup dispatch policy: explicit group counts or ceil(resource extent / local size). */
record PrismComputeDispatch(
        String resource,
        int localSizeX,
        int localSizeY,
        int localSizeZ,
        int groupsX,
        int groupsY,
        int groupsZ) {
    PrismComputeDispatch {
        resource = resource == null ? "" : resource;
        boolean resourceDriven = !resource.isBlank();
        if (resourceDriven) {
            if (localSizeX < 1 || localSizeY < 1 || localSizeZ < 1
                    || groupsX != 0 || groupsY != 0 || groupsZ != 0) {
                throw new IllegalArgumentException("Resource dispatch requires positive local sizes and no explicit groups");
            }
        } else if (groupsX < 1 || groupsY < 1 || groupsZ < 1) {
            throw new IllegalArgumentException("Explicit dispatch group counts must be positive");
        }
    }

    boolean resourceDriven() {
        return !resource.isBlank();
    }
}

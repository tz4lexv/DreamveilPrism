package dev.dreamveil.prism.pack;

import java.util.*;

/** Immutable load-time authoring IR. No Vulkan handles and no effect-name semantics. */
record PrismAuthoringIR(List<Module> modules, List<Resource> resources) {
    PrismAuthoringIR { modules=List.copyOf(modules); resources=List.copyOf(resources); }
    record Resource(String name, String format, String extent, String lifetime, String filter, String wrap) {}
    record Module(String path, String source, String kind, String pass, String output, String dispatch,
                  List<String> after, List<String> requires, List<Resource> resources) {
        Module { after=List.copyOf(after); requires=List.copyOf(requires); resources=List.copyOf(resources); }
    }
}

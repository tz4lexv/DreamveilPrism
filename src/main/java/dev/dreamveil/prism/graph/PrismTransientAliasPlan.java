package dev.dreamveil.prism.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static logical-to-physical slot assignment for transient graph resources.
 * Resources may share a slot only when their physical descriptions are
 * compatible and their lifetimes do not overlap.
 */
public record PrismTransientAliasPlan(
        Map<String, Integer> slotByResource,
        Map<Integer, PrismResourceDescriptor> representativeBySlot) {

    public PrismTransientAliasPlan {
        slotByResource = Map.copyOf(slotByResource);
        representativeBySlot = Map.copyOf(representativeBySlot);
    }

    public int slotCount() {
        return representativeBySlot.size();
    }

    public int slotFor(String resourceName) {
        Integer slot = slotByResource.get(resourceName);
        if (slot == null) {
            throw new IllegalArgumentException("Transient resource has no physical slot: " + resourceName);
        }
        return slot;
    }

    static PrismTransientAliasPlan build(
            Map<String, PrismResourceDescriptor> resources,
            Map<String, PrismResourceLifetime> lifetimes) {
        List<PrismResourceDescriptor> transients = resources.values().stream()
                .filter(descriptor -> !descriptor.imported())
                .sorted(Comparator
                        .comparingInt((PrismResourceDescriptor descriptor) ->
                                lifetimes.get(descriptor.name()).firstUsePass())
                        .thenComparing(PrismResourceDescriptor::name))
                .toList();

        List<SlotState> slots = new ArrayList<>();
        Map<String, Integer> slotByResource = new LinkedHashMap<>();
        Map<Integer, PrismResourceDescriptor> representativeBySlot = new LinkedHashMap<>();

        for (PrismResourceDescriptor descriptor : transients) {
            PrismResourceLifetime lifetime = lifetimes.get(descriptor.name());
            SlotState selected = null;

            for (SlotState slot : slots) {
                if (slot.lastUsePass < lifetime.firstUsePass()
                        && slot.representative.physicallyCompatibleWith(descriptor)) {
                    if (selected == null || slot.lastUsePass > selected.lastUsePass) {
                        selected = slot;
                    }
                }
            }

            if (selected == null) {
                selected = new SlotState(slots.size(), descriptor, lifetime.lastUsePass());
                slots.add(selected);
                representativeBySlot.put(selected.index, descriptor);
            } else {
                selected.lastUsePass = lifetime.lastUsePass();
            }

            slotByResource.put(descriptor.name(), selected.index);
        }

        return new PrismTransientAliasPlan(slotByResource, representativeBySlot);
    }

    private static final class SlotState {
        private final int index;
        private final PrismResourceDescriptor representative;
        private int lastUsePass;

        private SlotState(int index, PrismResourceDescriptor representative, int lastUsePass) {
            this.index = index;
            this.representative = representative;
            this.lastUsePass = lastUsePass;
        }
    }
}

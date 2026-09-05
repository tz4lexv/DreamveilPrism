package dev.dreamveil.prism.pack;

import java.util.*;

/** Keeps only K nearest candidates, instead of retaining/sorting all scanned world objects. */
final class PrismNearestCandidates<T> {
    private record Entry<T>(T value, double distance, long tie) {}
    private final int limit;
    private final Comparator<Entry<T>> order = Comparator.<Entry<T>>comparingDouble(Entry::distance).thenComparingLong(Entry::tie);
    private final PriorityQueue<Entry<T>> heap;
    PrismNearestCandidates(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive candidate limit required");
        this.limit=limit; heap=new PriorityQueue<>(limit,order.reversed());
    }
    void offer(T value,double distance,long tie) {
        if (!Double.isFinite(distance) || distance < 0) return;
        var entry = new Entry<>(value,distance,tie);
        if (heap.size() < limit) heap.add(entry);
        else if (order.compare(entry,heap.peek()) < 0) { heap.poll(); heap.add(entry); }
    }
    List<T> values() { return heap.stream().sorted(order).map(Entry::value).toList(); }
    int size() { return heap.size(); }
}

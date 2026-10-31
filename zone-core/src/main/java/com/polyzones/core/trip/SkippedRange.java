package com.polyzones.core.trip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record SkippedRange(long firstSeq, long lastSeq) {
    public SkippedRange {
        if (firstSeq < 0 || lastSeq < firstSeq) throw new IllegalArgumentException("Invalid skipped range");
    }
    public boolean contains(long seq) { return seq >= firstSeq && seq <= lastSeq; }

    /** Preserves exact membership while coalescing only overlapping or adjacent intervals. */
    public static List<SkippedRange> merge(List<SkippedRange> ranges, SkippedRange incoming) {
        List<SkippedRange> sorted = new ArrayList<>(ranges.size() + 1);
        sorted.addAll(ranges);
        sorted.add(incoming);
        sorted.sort(Comparator.comparingLong(SkippedRange::firstSeq));
        List<SkippedRange> result = new ArrayList<>(sorted.size());
        for (SkippedRange range : sorted) {
            if (result.isEmpty()) { result.add(range); continue; }
            SkippedRange previous = result.getLast();
            boolean adjacent = previous.lastSeq() == Long.MAX_VALUE || previous.lastSeq() + 1 >= range.firstSeq();
            if (adjacent) result.set(result.size() - 1,
                    new SkippedRange(previous.firstSeq(), Math.max(previous.lastSeq(), range.lastSeq())));
            else result.add(range);
        }
        return List.copyOf(result);
    }
}

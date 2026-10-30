package com.polyzones.core.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Y-band candidate index for ring edges. */
public final class EdgeBandIndex {
    private final double minY, maxY, bandHeight;
    private final int[][] bands;
    private final double[] edgeMinY, edgeMaxY;
    private final int edgeCount;
    private final double eps;

    public EdgeBandIndex(double[] ys, int edgeCount, double eps) {
        this.edgeCount = edgeCount; this.eps = eps;
        edgeMinY = new double[edgeCount]; edgeMaxY = new double[edgeCount];
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < edgeCount; i++) { lo = Math.min(lo, Math.min(ys[i], ys[i + 1])); hi = Math.max(hi, Math.max(ys[i], ys[i + 1])); }
        minY = lo; maxY = hi;
        int count = Math.max(1, (int) Math.ceil(Math.sqrt(edgeCount)));
        bandHeight = hi == lo ? 1 : (hi - lo) / count;
        List<List<Integer>> temp = new ArrayList<>(count);
        for (int b = 0; b < count; b++) temp.add(new ArrayList<>());
        for (int i = 0; i < edgeCount; i++) {
            edgeMinY[i] = Math.min(ys[i], ys[i + 1]) - eps;
            edgeMaxY[i] = Math.max(ys[i], ys[i + 1]) + eps;
            int first = bandForBuild(edgeMinY[i], lo, bandHeight, count), last = bandForBuild(edgeMaxY[i], lo, bandHeight, count);
            for (int b = first; b <= last; b++) temp.get(b).add(i);
        }
        bands = new int[count][];
        for (int i = 0; i < count; i++) bands[i] = temp.get(i).stream().mapToInt(Integer::intValue).toArray();
    }

    public void forPoint(double y, IntConsumer consumer) {
        if (y < minY - eps || y > maxY + eps) return;
        for (int edge : bands[band(y, bands.length)]) if (edgeMinY[edge] <= y && edgeMaxY[edge] >= y) consumer.accept(edge);
    }

    public void forRange(double lowY, double highY, IntConsumer consumer) {
        if (lowY > highY) { double swap = lowY; lowY = highY; highY = swap; }
        if (highY < minY - eps || lowY > maxY + eps) return;
        lowY = Math.max(lowY, minY);
        highY = Math.min(highY, maxY);
        int first = band(lowY, bands.length), last = band(highY, bands.length);
        boolean[] seen = new boolean[edgeCount];
        for (int b = first; b <= last; b++) for (int edge : bands[b]) if (!seen[edge] && edgeMinY[edge] <= highY + eps && edgeMaxY[edge] >= lowY - eps) { seen[edge] = true; consumer.accept(edge); }
    }

    private int band(double y, int count) {
        int index = bandHeight == 0 ? 0 : (int) Math.floor((y - minY) / bandHeight);
        return Math.max(0, Math.min(count - 1, index));
    }
    private static int bandForBuild(double y, double minY, double height, int count) {
        int index = height == 0 ? 0 : (int) Math.floor((y - minY) / height);
        return Math.max(0, Math.min(count - 1, index));
    }
}

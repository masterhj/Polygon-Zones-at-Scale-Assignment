package com.polyzones.core.index;

public record BuildStats(long buildNanos, int polygonCount, long vertexCount, long approxBytes) { }

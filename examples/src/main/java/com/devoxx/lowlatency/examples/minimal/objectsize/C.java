package com.devoxx.lowlatency.examples.minimal.objectsize;

import java.util.concurrent.ConcurrentHashMap;

// Slide companion: "Why GC kills latency"
// Looks like one field. Underneath there's a whole ConcurrentHashMap object —
// internal table array, segments, CAS state. JOL graph shows the real cost.
public class C {
    private int i;
    private ConcurrentHashMap<Object, Object> chm = new ConcurrentHashMap<>();
}

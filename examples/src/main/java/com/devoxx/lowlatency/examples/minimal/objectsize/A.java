package com.devoxx.lowlatency.examples.minimal.objectsize;

// Slide companion: "The memory wall" / "Where the GC actually pauses you"
// Inspect with JOL: java -jar jol-cli.jar internals com.devoxx.lowlatency.examples.minimal.objectsize.A
// Object header alone: 16 bytes on a 64-bit JVM with compressed oops.
public class A {
    private int i;
}

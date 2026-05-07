package com.devoxx.lowlatency.examples.minimal.objectsize;

import java.util.Locale;

// Slide companion: "The memory wall"
// One reference field. JOL shows the 4-byte compressed oop appended after the int.
// The Locale instance itself is shared; this object only owns the pointer.
public class B {
    private int i;
    private Locale l = Locale.US;
}

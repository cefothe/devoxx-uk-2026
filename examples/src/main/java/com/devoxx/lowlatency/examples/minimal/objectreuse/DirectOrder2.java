package com.devoxx.lowlatency.examples.minimal.objectreuse;

// Slide companion: "Ring-buffer object pool" / "Truth #3 — Pool beats allocate"
// Mutable struct-like carrier. Fields are package-private so the pool can
// reset them; in production you'd guard with package boundaries.
public class DirectOrder2 {
    int size;
    int volume;
}

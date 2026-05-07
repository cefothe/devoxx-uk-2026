package com.devoxx.lowlatency.examples.minimal.objectreuse;

// Slide companion: "The pool's evil twins" — Joshua Bloch's enum singleton.
// One JVM-wide instance, allocated once at class init, never reclaimed.
// Serialisation-safe and reflection-resistant by language guarantee, which
// no synchronized/volatile/double-checked-lock pattern can match.
public enum SingletonEnum {
    INSTANCE;

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}

package com.devoxx.lowlatency.examples.minimal.canonicalobjects;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

// Slide companion: "The pool's evil twins" — canonical objects via WeakHashMap.
// One canonical instance per logical key; eligible for GC once nothing else
// references it. Different from String.intern (perm-gen-ish historically) and
// from Integer.valueOf cache (fixed range).
public class ImmutableObject {

    private static final WeakHashMap<String, WeakReference<ImmutableObject>> map = new WeakHashMap<>();

    public static ImmutableObject canonicalVersion(String key) {
        synchronized (map) {
            WeakReference<ImmutableObject> ref = map.get(key);
            ImmutableObject canonical = ref != null ? ref.get() : null;
            if (canonical == null) {
                canonical = new ImmutableObject();
                map.put(key, new WeakReference<>(canonical));
            }
            return canonical;
        }
    }
}

package com.devoxx.lowlatency.examples.minimal.objectreuse;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

// Slide companion: "Ring-buffer object pool" — alternative shape.
// Stack-keyed-by-type pool. Different design from the ring-buffer pool in
// examples/.../memory/RingBufferPoolExample.java: this one supports explicit
// release (put), variable-rate consumption, and multiple object types in one
// container. Trade-off: no implicit rotation, so callers must put what they
// take or the pool drains.
public class ObjectsPool {

    public static final int ORDER         = 0;
    public static final int DIRECT_ORDER  = 1;
    public static final int DIRECT_BUCKET = 2;

    private final ArrayStack[] pools;

    public ObjectsPool(Map<Integer, Integer> sizesConfig) {
        int maxStack = sizesConfig.keySet().stream().max(Integer::compareTo).orElse(0);
        this.pools = new ArrayStack[maxStack + 1];
        sizesConfig.forEach((type, size) -> this.pools[type] = new ArrayStack(size));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(int type, Supplier<T> supplier) {
        T obj = (T) pools[type].pop();
        return obj != null ? obj : supplier.get();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(int type, Function<ObjectsPool, T> constructor) {
        T obj = (T) pools[type].pop();
        return obj != null ? obj : constructor.apply(this);
    }

    public void put(int type, Object object) {
        pools[type].add(object);
    }

    private static final class ArrayStack {
        private final Object[] objects;
        private int count;

        ArrayStack(int fixedSize) {
            this.objects = new Object[fixedSize];
        }

        void add(Object element) {
            if (count != objects.length) {
                objects[count++] = element;
            }
        }

        Object pop() {
            if (count == 0) return null;
            Object object = objects[--count];
            objects[count] = null;
            return object;
        }
    }

    public static void main(String[] args) {
        Map<Integer, Integer> config = new HashMap<>();
        config.put(DIRECT_ORDER,  1024 * 1024);
        config.put(DIRECT_BUCKET, 1024 * 64);

        ObjectsPool pool = new ObjectsPool(config);
        pool.put(DIRECT_ORDER, new DirectOrder2());
        DirectOrder2 reused = pool.get(DIRECT_ORDER, DirectOrder2::new);
        System.out.println("ObjectsPool — typed stack pool. Reused instance: " + reused);
    }
}

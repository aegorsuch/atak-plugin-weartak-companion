package com.weartak.atak.weartak_companion.plugin;

import com.ditto.dittoffi.DynamicArray;
import com.ditto.kotlinx.cinterop.JvmTypesKt;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

final class DittoCInteropCompat {
    private static final String TYPE_CACHE_ACCESSOR = "access$getTypeOfCache$p";

    private DittoCInteropCompat() {
    }

    static void primeDynamicArrayType() {
        try {
            // Ditto 5.2.0 exposes this internal cache only through a synthetic accessor.
            Method accessor = JvmTypesKt.class.getDeclaredMethod(TYPE_CACHE_ACCESSOR);
            Object cache = accessor.invoke(null);
            if (!(cache instanceof ConcurrentHashMap)) {
                throw new IllegalStateException("Ditto C interop type cache is unavailable");
            }
            putIfAbsent((ConcurrentHashMap<?, ?>) cache);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to initialize Ditto C interop", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putIfAbsent(ConcurrentHashMap<?, ?> cache) {
        ((ConcurrentHashMap) cache).putIfAbsent(DynamicArray.class, DynamicArray.Companion);
    }
}

package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class DittoCInteropCompatTest {
    @Test
    public void primesBothUnsignedArrayTypesIdempotentlyAgainstSdk() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        Class<?> array = Class.forName("com.ditto.dittoffi.DynamicArray");
        Class<?> element = Class.forName("com.ditto.kotlinx.cinterop.UByteVarOf");
        Object previousArray = cache.remove(array);
        Object previousElement = cache.remove(element);
        try {
            DittoCInteropCompat.primeRequiredTypes();
            DittoCInteropCompat.primeRequiredTypes();
            assertSame(array.getField("Companion").get(null), cache.get(array));
            assertSame(element.getField("Companion").get(null), cache.get(element));
        } finally {
            restore(cache, array, previousArray);
            restore(cache, element, previousElement);
        }
    }

    @Test
    public void rejectsConflictingElementCacheEntryWithoutOverwritingIt() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        Class<?> array = Class.forName("com.ditto.dittoffi.DynamicArray");
        Class<?> element = Class.forName("com.ditto.kotlinx.cinterop.UByteVarOf");
        Object previousArray = cache.get(array);
        Object conflict = new Object();
        Object previousElement = cache.put(element, conflict);
        try {
            assertThrows(IllegalStateException.class, DittoCInteropCompat::primeRequiredTypes);
            assertSame(conflict, cache.get(element));
        } finally {
            restore(cache, array, previousArray);
            restore(cache, element, previousElement);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Object, Object> cache() throws Exception {
        return (ConcurrentMap<Object, Object>) Class.forName(
                "com.ditto.kotlinx.cinterop.JvmTypesKt")
                .getDeclaredMethod("access$getTypeOfCache$p").invoke(null);
    }

    private static void restore(ConcurrentMap<Object, Object> cache,
            Class<?> type, Object previous) {
        if (previous == null) cache.remove(type);
        else cache.put(type, previous);
    }
}

package com.weartak.atak.weartak_companion.plugin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class DittoCInteropCompatTest {
    private static final String[] STARTUP_TYPES = {
            "com.ditto.dittoffi.DynamicArray",
            "com.ditto.kotlinx.cinterop.UByteVarOf",
            "com.ditto.dittoffi.jvm.dittoffi_result_CDitto_ptr",
            "com.ditto.kotlinx.cinterop.CPointerVarOf",
            "com.ditto.dittoffi.jvm.dittoffi_result_dittoffi_sync_subscription_ptr",
            "com.ditto.dittoffi.BoxDynFnMut2_void_dittoffi_query_result_ptr_ArcDynFn0_void",
            "com.ditto.dittoffi.jvm.dittoffi_result_dittoffi_store_observer_ptr",
            "com.ditto.dittoffi.jvm.dittoffi_result_void"
    };
    private final Map<Class<?>, Object> previous = new HashMap<>();

    @Before
    public void isolateStartupEntries() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        for (String name : STARTUP_TYPES) {
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            previous.put(type, cache.remove(type));
        }
    }

    @After
    public void restoreStartupEntries() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        for (Map.Entry<Class<?>, Object> entry : previous.entrySet()) {
            if (entry.getValue() == null) cache.remove(entry.getKey());
            else cache.put(entry.getKey(), entry.getValue());
        }
    }

    @Test
    public void primesAuditedStartupCompanionsIdempotentlyAgainstSdk() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        DittoCInteropCompat.primeRequiredTypes();
        DittoCInteropCompat.primeRequiredTypes();
        for (String name : STARTUP_TYPES) {
            Class<?> type = Class.forName(name);
            assertSame(type.getField("Companion").get(null), cache.get(type));
        }
    }

    @Test
    public void rejectsConflictingElementCacheEntryWithoutOverwritingIt() throws Exception {
        ConcurrentMap<Object, Object> cache = cache();
        Class<?> element = Class.forName("com.ditto.kotlinx.cinterop.UByteVarOf");
        Object conflict = new Object();
        cache.put(element, conflict);
        assertThrows(IllegalStateException.class, DittoCInteropCompat::primeRequiredTypes);
        assertSame(conflict, cache.get(element));
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Object, Object> cache() throws Exception {
        return (ConcurrentMap<Object, Object>) Class.forName(
                "com.ditto.kotlinx.cinterop.JvmTypesKt")
                .getDeclaredMethod("access$getTypeOfCache$p").invoke(null);
    }

}

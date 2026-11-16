package com.weartak.atak.weartak_companion.plugin;

import com.ditto.dittoffi.DynamicArray;
import com.ditto.kotlinx.cinterop.JvmTypesKt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

final class DittoCInteropCompat {
    private static final String EXPECTED_DITTO_VERSION = "5.2.0";
    private static final String TYPE_CACHE_ACCESSOR = "access$getTypeOfCache$p";

    private DittoCInteropCompat() {
    }

    static void primeDynamicArrayType() {
        try {
            verifySdkVersion();
            if (JvmTypesKt.class.getClassLoader() != DynamicArray.class.getClassLoader()) {
                throw new IllegalStateException("Ditto C interop classes use different class loaders");
            }
            // Ditto 5.2.0 has no public registration API; this is its only cache accessor.
            Method accessor = JvmTypesKt.class.getDeclaredMethod(TYPE_CACHE_ACCESSOR);
            if (accessor.getReturnType() != ConcurrentHashMap.class) {
                throw new IllegalStateException("Ditto C interop type cache has an unexpected type");
            }
            Object cache = accessor.invoke(null);
            if (!(cache instanceof ConcurrentHashMap)) {
                throw new IllegalStateException("Ditto C interop type cache is unavailable");
            }
            putIfAbsent((ConcurrentHashMap<?, ?>) cache);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to initialize Ditto C interop", e);
        }
    }

    private static void verifySdkVersion() throws ReflectiveOperationException {
        Class<?> buildConfig = Class.forName(
                "com.ditto.internal.BuildConfig", false, JvmTypesKt.class.getClassLoader());
        Field versionField = buildConfig.getField("VERSION");
        Object version = versionField.get(null);
        if (!EXPECTED_DITTO_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "Ditto C interop workaround supports SDK " + EXPECTED_DITTO_VERSION
                            + " only; found " + version);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putIfAbsent(ConcurrentHashMap<?, ?> cache) {
        Object existing = ((ConcurrentHashMap) cache).putIfAbsent(
                DynamicArray.class, DynamicArray.Companion);
        if (existing != null && existing != DynamicArray.Companion) {
            throw new IllegalStateException("Ditto C interop type cache contains a conflicting type");
        }
    }
}

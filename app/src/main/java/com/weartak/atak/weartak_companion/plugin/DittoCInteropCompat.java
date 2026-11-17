package com.weartak.atak.weartak_companion.plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

final class DittoCInteropCompat {
    private static final String EXPECTED_DITTO_VERSION = "5.2.0";
    private static final String DYNAMIC_ARRAY_CLASS = "com.ditto.dittoffi.DynamicArray";
    private static final String JVM_TYPES_CLASS = "com.ditto.kotlinx.cinterop.JvmTypesKt";
    private static final String TYPE_CACHE_ACCESSOR = "access$getTypeOfCache$p";

    private DittoCInteropCompat() {
    }

    static void primeDynamicArrayType() {
        try {
            ClassLoader pluginClassLoader = DittoCInteropCompat.class.getClassLoader();
            Class<?> jvmTypes = Class.forName(JVM_TYPES_CLASS, false, pluginClassLoader);
            Class<?> dynamicArray = Class.forName(DYNAMIC_ARRAY_CLASS, false, pluginClassLoader);
            if (jvmTypes.getClassLoader() != dynamicArray.getClassLoader()) {
                throw new IllegalStateException("Ditto C interop classes use different class loaders");
            }
            verifySdkVersion(jvmTypes.getClassLoader());

            // Ditto 5.2.0 has no public registration API; this is its only cache accessor.
            Method accessor = jvmTypes.getDeclaredMethod(TYPE_CACHE_ACCESSOR);
            if (!Modifier.isStatic(accessor.getModifiers())
                    || accessor.getReturnType() != ConcurrentHashMap.class) {
                throw new IllegalStateException("Ditto C interop type cache has an unexpected type");
            }
            Object cache = accessor.invoke(null);
            if (!(cache instanceof ConcurrentHashMap)) {
                throw new IllegalStateException("Ditto C interop type cache is unavailable");
            }
            Field companionField = dynamicArray.getField("Companion");
            if (!Modifier.isStatic(companionField.getModifiers())) {
                throw new IllegalStateException("Ditto DynamicArray companion is not static");
            }
            Object companion = companionField.get(null);
            if (!companionField.getType().isInstance(companion)) {
                throw new IllegalStateException("Ditto DynamicArray companion has an unexpected type");
            }
            putIfAbsent((ConcurrentHashMap<?, ?>) cache, dynamicArray, companion);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to initialize Ditto C interop", e);
        }
    }

    private static void verifySdkVersion(ClassLoader sdkClassLoader)
            throws ReflectiveOperationException {
        Class<?> buildConfig = Class.forName(
                "com.ditto.internal.BuildConfig", false, sdkClassLoader);
        Field versionField = buildConfig.getField("VERSION");
        Object version = versionField.get(null);
        if (!EXPECTED_DITTO_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "Ditto C interop workaround supports SDK " + EXPECTED_DITTO_VERSION
                            + " only; found " + version);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putIfAbsent(ConcurrentHashMap<?, ?> cache,
            Class<?> dynamicArray, Object companion) {
        Object existing = ((ConcurrentHashMap) cache).putIfAbsent(
                dynamicArray, companion);
        if (existing != null && existing != companion) {
            throw new IllegalStateException("Ditto C interop type cache contains a conflicting type");
        }
    }
}

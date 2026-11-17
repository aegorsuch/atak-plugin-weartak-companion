package com.weartak.atak.weartak_companion.plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

final class DittoCInteropCompat {
    private static final String EXPECTED_DITTO_VERSION = "5.2.0";
    private static final String[] REQUIRED_TYPES = {
            "com.ditto.dittoffi.DynamicArray",
            "com.ditto.kotlinx.cinterop.UByteVarOf",
            "com.ditto.dittoffi.jvm.dittoffi_result_CDitto_ptr",
            "com.ditto.kotlinx.cinterop.CPointerVarOf",
            "com.ditto.dittoffi.jvm.dittoffi_result_dittoffi_sync_subscription_ptr",
            "com.ditto.dittoffi.BoxDynFnMut2_void_dittoffi_query_result_ptr_ArcDynFn0_void",
            "com.ditto.dittoffi.jvm.dittoffi_result_dittoffi_store_observer_ptr",
            "com.ditto.dittoffi.jvm.dittoffi_result_void"
    };
    private static final String JVM_TYPES_CLASS = "com.ditto.kotlinx.cinterop.JvmTypesKt";
    private static final String C_VARIABLE_TYPE_CLASS = "com.ditto.kotlinx.cinterop.CVariable$Type";
    private static final String TYPE_CACHE_ACCESSOR = "access$getTypeOfCache$p";

    private DittoCInteropCompat() {
    }

    static void primeRequiredTypes() {
        try {
            ClassLoader pluginClassLoader = DittoCInteropCompat.class.getClassLoader();
            Class<?> jvmTypes = Class.forName(JVM_TYPES_CLASS, false, pluginClassLoader);
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
            Class<?> cVariableType = Class.forName(
                    C_VARIABLE_TYPE_CLASS, false, jvmTypes.getClassLoader());
            // SDK 5.2.0 factory, offline license, subscription, observer, and sync-start paths.
            for (String typeName : REQUIRED_TYPES) {
                Class<?> variable = Class.forName(typeName, false, pluginClassLoader);
                if (jvmTypes.getClassLoader() != variable.getClassLoader()) {
                    throw new IllegalStateException("Ditto C interop classes use different class loaders");
                }
                Field companionField = variable.getField("Companion");
                if (!Modifier.isStatic(companionField.getModifiers())) {
                    throw new IllegalStateException("Ditto C interop companion is not static");
                }
                Object companion = companionField.get(null);
                if (!cVariableType.isInstance(companion)) {
                    throw new IllegalStateException("Ditto companion is not a C variable type");
                }
                putIfAbsent((ConcurrentHashMap<?, ?>) cache, variable, companion);
            }
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
            Class<?> variable, Object companion) {
        Object existing = ((ConcurrentHashMap) cache).putIfAbsent(
                variable, companion);
        if (existing != null && existing != companion) {
            throw new IllegalStateException("Ditto C interop type cache contains a conflicting type");
        }
    }
}

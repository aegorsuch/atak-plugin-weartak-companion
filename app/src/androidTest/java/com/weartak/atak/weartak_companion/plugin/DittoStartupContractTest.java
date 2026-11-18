package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.ditto.internal.android.InternalDittoInitializer;
import com.ditto.kotlin.Ditto;
import com.ditto.kotlin.DittoConfig;
import com.ditto.kotlin.DittoException;
import com.ditto.kotlin.DittoFactory;
import com.ditto.kotlin.DittoStoreObserver;
import com.ditto.kotlin.DittoSyncSubscription;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineScopeKt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DittoStartupContractTest {
    private Ditto ditto;
    private File directory;

    @Before
    public void createIsolatedStore() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        new InternalDittoInitializer().create(context);
        DittoCInteropCompat.primeRequiredTypes();
        directory = new File(context.getCacheDir(), "ditto-contract-" + UUID.randomUUID());
        ditto = create(new DittoConfig.Connect.SmallPeersOnly(), directory);
    }

    @After
    public void closeIsolatedStore() {
        if (ditto != null) ditto.close();
        if (directory != null) deleteTestDirectory(directory);
    }

    @Test
    public void syntheticInvalidLicenseReachesNativeValidation() {
        assertThrows(DittoException.class,
                () -> ditto.setOfflineOnlyLicenseToken("synthetic-invalid-license"));
    }

    @Test
    public void registersEmptyQueryResourcesAndRejectsUnlicensedSync() throws Exception {
        String query = "SELECT * FROM weartak_startup_contract";
        CountDownLatch snapshot = new CountDownLatch(1);
        try (DittoSyncSubscription subscription = ditto.getSync()
                .registerSubscription(query, Collections.emptyMap());
             DittoStoreObserver observer = ditto.getStore()
                .registerObserver(query, Collections.emptyMap(), (result, continuation) -> {
                    snapshot.countDown();
                    return Unit.INSTANCE;
                })) {
            assertTrue("Initial synthetic snapshot not delivered", snapshot.await(10, TimeUnit.SECONDS));
            assertThrows(DittoException.class, () -> ditto.getSync().start());
            ditto.getSync().stop();
        }
    }

    @Test
    public void createsServerIdentityWithoutCredentialsOrSync() {
        File serverDirectory = new File(directory, "server");
        try (Ditto server = create(
                new DittoConfig.Connect.Server("https://127.0.0.1"), serverDirectory)) {
            assertNotNull(server.getAuth());
        }
    }

    private static Ditto create(DittoConfig.Connect connect, File directory) {
        DittoConfig config = new DittoConfig(UUID.randomUUID().toString(), connect,
                directory.getAbsolutePath(), new DittoConfig.Experimental(false));
        return DittoFactory.INSTANCE.create(config, CoroutineScopeKt.MainScope());
    }

    private static void deleteTestDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) deleteTestDirectory(child);
        }
        assertTrue("Cannot remove isolated test file", !directory.exists() || directory.delete());
    }
}

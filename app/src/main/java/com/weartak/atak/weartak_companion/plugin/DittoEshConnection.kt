package com.weartak.atak.weartak_companion.plugin

import android.content.Context
import androidx.startup.AppInitializer
import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoAuthenticationProvider
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory
import com.ditto.kotlin.DittoInitializer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

interface DittoEshListener {
    fun onConnectionStage(stage: String)
    fun onCollectionSnapshot(collection: String, documents: List<String>)
    fun onEshConnectionStatus(connected: Boolean)
    fun onObserverError(category: String)
}

object DittoEshConnection {
    private val lock = Any()
    private val connectLock = Any()

    private var instance: Ditto? = null

    private var sessionEnabled = false
    private var sessionGeneration = 0L
    private var operationGeneration = 0L
    private var instanceGeneration = 0L
    private var subscriptions = mutableListOf<AutoCloseable>()
    private var observers = mutableListOf<AutoCloseable>()

    @JvmStatic
    fun startSession(): Long = synchronized(lock) {
        sessionEnabled = true
        ++sessionGeneration
    }

    @JvmStatic
    fun invalidateSession(): Long = synchronized(lock) {
        sessionEnabled = false
        ++operationGeneration
        ++sessionGeneration
    }

    @JvmStatic
    fun connect(
        context: Context,
        databaseId: String,
        serverUrl: String,
        token: String,
        listener: DittoEshListener
    ) = synchronized(connectLock) {
        reportStage(listener, "esh-inputs")
        connectSerially(context, databaseId, serverUrl, token, null, listener)
    }

    @JvmStatic
    fun connectCommunity(context: Context, listener: DittoEshListener) = synchronized(connectLock) {
        reportStage(listener, "community-license")
        val license = DittoCommunityLicense.parse(
            BuildConfig.DITTO_COMMUNITY_IDENTITY, System.currentTimeMillis()
        )
        connectSerially(context, license.appId, "", "", license, listener)
    }

    private fun connectSerially(
        context: Context,
        databaseId: String,
        serverUrl: String,
        token: String,
        community: DittoCommunityLicense?,
        listener: DittoEshListener
    ) {
        reportStage(listener, "input-validation")
        require(databaseId.isNotBlank()) { "Ditto database ID is required" }
        if (community == null) {
            require(serverUrl.isNotBlank()) { "ESH server URL is required" }
            require(token.isNotBlank()) { "Ditto development token is required" }
        }

        reportStage(listener, "lifecycle")
        val operation = synchronized(lock) {
            check(sessionEnabled) { "Ditto connection is unavailable while plugin is stopped" }
            check(instance == null || instanceGeneration < sessionGeneration) {
                "Disconnect the current Ditto session before reconnecting"
            }
            ++operationGeneration
            sessionGeneration to operationGeneration
        }
        synchronized(lock) {
            check(sessionEnabled && sessionGeneration == operation.first &&
                operationGeneration == operation.second) {
                "Ditto connection was superseded by a lifecycle change"
            }
            if (instance != null && instanceGeneration < operation.first) closeCurrent()
            check(instance == null) { "Disconnect the current Ditto session before reconnecting" }
        }

        reportStage(listener, "configuration")
        val normalizedDatabaseId = databaseId.trim()
        val config = DittoConfig(
            databaseId = normalizedDatabaseId,
            persistenceDirectory = persistenceDirectory(context,
                if (community == null) normalizedDatabaseId else "community:$normalizedDatabaseId"),
            connect = if (community == null) DittoConfig.Connect.Server(url = serverUrl.trim())
                else DittoConfig.Connect.SmallPeersOnly(community.sharedKey)
        )
        reportStage(listener, "interop-compat")
        DittoCInteropCompat.primeRequiredTypes()
        // ATAK loads plugin APKs into its process, so the plugin's Startup provider is not run.
        reportStage(listener, "sdk-initializer")
        AppInitializer.getInstance(context.applicationContext)
            .initializeComponent(DittoInitializer::class.java)
        reportStage(listener, "sdk-factory")
        val ditto = DittoFactory.create(config)

        val nextSubscriptions = mutableListOf<AutoCloseable>()
        val nextObservers = mutableListOf<AutoCloseable>()
        try {
            if (community != null) {
                reportStage(listener, "community-identity")
                ditto.setOfflineOnlyLicenseToken(community.token)
            } else {
                reportStage(listener, "esh-authentication")
                val auth = checkNotNull(ditto.auth) { "Ditto authentication is unavailable" }
                auth.expirationHandler = { dittoInstance, _ ->
                    dittoInstance.auth?.login(token, DittoAuthenticationProvider.development())
                }

                runBlocking {
                    auth.login(token, DittoAuthenticationProvider.development())
                }
            }

            reportStage(listener, "subscriptions")
            COLLECTIONS.forEach { collection ->
                val query = "SELECT * FROM $collection"
                nextSubscriptions += ditto.sync.registerSubscription(query)
                reportStage(listener, "observers")
                nextObservers += ditto.store.registerObserver(query) { result ->
                    if (isOperationCurrent(operation.first, operation.second)) {
                        val documents = result.items.map { it.jsonString() }
                        if (documents.size > MAX_SNAPSHOT_DOCUMENTS ||
                            documents.sumOf { it.length.toLong() } > MAX_SNAPSHOT_CHARACTERS) {
                            listener.onObserverError("snapshot-limit")
                        } else {
                            listener.onCollectionSnapshot(collection, documents)
                        }
                    }
                }
            }

            if (community == null) try {
                nextObservers += ditto.store.registerObserver(
                    "SELECT * FROM system:data_sync_info"
                ) { result ->
                    if (isOperationCurrent(operation.first, operation.second)) {
                        val connected = result.items.any { item ->
                            val peer = JSONObject(item.jsonString())
                            peer.optBoolean("is_ditto_server") &&
                                peer.optJSONObject("documents")
                                    ?.optString("sync_session_status") == "Connected"
                        }
                        listener.onEshConnectionStatus(connected)
                    }
                }
            } catch (_: Exception) {
                listener.onObserverError("sync-status")
            }

            synchronized(lock) {
                check(sessionEnabled && sessionGeneration == operation.first &&
                    operationGeneration == operation.second) {
                    "Ditto connection was superseded by a lifecycle change"
                }
                reportStage(listener, "sync-start")
                ditto.sync.start()
                instance = ditto
                instanceGeneration = operation.first
                subscriptions = nextSubscriptions
                observers = nextObservers
            }
            reportStage(listener, "sync-started")
        } catch (e: Exception) {
            closeHandles(nextObservers)
            closeHandles(nextSubscriptions)
            runBlocking { ditto.close() }
            throw e
        }
    }

    @JvmStatic
    fun disconnect() {
        synchronized(lock) {
            ++operationGeneration
            closeCurrent()
        }
    }

    @JvmStatic
    fun stopSync(expectedGeneration: Long) {
        synchronized(lock) {
            if (instance != null && instanceGeneration < expectedGeneration) {
                closeCurrent()
            }
        }
    }

    @JvmStatic
    fun isSyncActive(): Boolean = synchronized(lock) {
        instance?.sync?.isActive == true
    }

    private fun closeCurrent() {
        val current = instance
        closeHandles(observers)
        closeHandles(subscriptions)
        observers = mutableListOf()
        subscriptions = mutableListOf()
        if (current != null) {
            var cleanupFailure: Exception? = null
            try {
                if (current.sync.isActive) current.sync.stop()
            } catch (e: Exception) {
                cleanupFailure = e
            }
            try {
                runBlocking { current.close() }
            } catch (e: Exception) {
                if (cleanupFailure == null) cleanupFailure = e
                else cleanupFailure.addSuppressed(e)
            }
            instance = null
            instanceGeneration = 0L
            if (cleanupFailure != null) throw cleanupFailure
        } else {
            instance = null
            instanceGeneration = 0L
        }
    }

    private fun isOperationCurrent(session: Long, operation: Long): Boolean =
        synchronized(lock) {
            sessionEnabled && sessionGeneration == session &&
                operationGeneration == operation
        }

    private fun reportStage(listener: DittoEshListener, stage: String) {
        android.util.Log.i(TAG, "Connection stage=$stage")
        listener.onConnectionStage(stage)
    }

    private fun persistenceDirectory(context: Context, databaseId: String): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(databaseId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return File(context.filesDir, "weartak-companion/ditto/$key").absolutePath
    }

    private fun closeHandles(handles: List<AutoCloseable>) {
        handles.forEach { handle ->
            try {
                handle.close()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Ditto resource cleanup failed (${e.javaClass.simpleName})")
            }
        }
    }

    private const val TAG = "WTK/Ditto"
    private const val MAX_SNAPSHOT_DOCUMENTS = 4096
    private const val MAX_SNAPSHOT_CHARACTERS = 2 * 1024 * 1024
    private val COLLECTIONS = listOf("track", "mapitem", "alert")
}

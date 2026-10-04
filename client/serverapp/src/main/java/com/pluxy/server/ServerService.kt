package com.pluxy.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pluxy.server.discovery.DiscoveryResponder
import com.pluxy.server.http.PluxyHttpServer
import com.pluxy.server.library.LibrarySource
import com.pluxy.server.library.LibrarySourceKind
import com.pluxy.server.library.MediaLibrary
import com.pluxy.server.metadata.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Service premier plan : le téléphone peut verrouiller son écran sans couper le film. */
class ServerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferences: ServerPreferences
    private lateinit var library: MediaLibrary
    private lateinit var metadata: TmdbRepository
    private lateinit var http: PluxyHttpServer
    private lateinit var discovery: DiscoveryResponder
    private lateinit var commands: ServerCommandQueue<String?>

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallbackRegistered = false
    @Volatile private var actuallyRunning = false
    private var lastError: String? = null
    private val rescanPending = AtomicBoolean(false)
    @Volatile private var destroying = false
    @Volatile private var initializing = true

    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshStatus()
        override fun onLost(network: Network) = refreshStatus()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshStatus()
    }

    override fun onCreate() {
        super.onCreate()
        preferences = ServerPreferences(this)
        createNotificationChannel()
        foreground(buildNotification("Démarrage…"))

        commands = ServerCommandQueue(
            scope = CoroutineScope(serviceScope.coroutineContext + Dispatchers.Main.immediate),
            initialize = ::initializeServer,
            handle = ::handleCommand,
            onFailure = { failAndStop(it.message ?: "Impossible de restaurer la bibliothèque.") },
        )
        serviceScope.launch {
            RemotePlaybackRuntime.state.collect {
                applyPowerLocks()
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // An early Stop must cancel disk restoration, rather than wait for it.
            commands.close()
            stopByUser()
        } else {
            commands.submit(intent?.action)
        }
        return START_STICKY
    }

    private suspend fun initializeServer() {
        // Constructors restore the persisted index and create disk caches. Keep that
        // work off Android's main thread, then publish all dependencies together.
        val initialized = withContext(Dispatchers.IO) {
            val restoredMetadata = TmdbRepository(this@ServerService, preferences)
            val restoredLibrary = MediaLibrary(this@ServerService) { scanning, count, error ->
                if (destroying) return@MediaLibrary
                lastError = error
                ServerRuntime.update { it.copy(scanning = scanning, mediaCount = count, error = error) }
                updateNotification()
            }
            InitializedServer(
                library = restoredLibrary,
                metadata = restoredMetadata,
                http = PluxyHttpServer(
                    context = this@ServerService,
                    preferences = preferences,
                    library = restoredLibrary,
                    metadata = restoredMetadata,
                    scope = serviceScope,
                    requestScan = ::scanLibrary,
                ),
                discovery = DiscoveryResponder(this@ServerService, preferences),
            )
        }
        library = initialized.library
        metadata = initialized.metadata
        http = initialized.http
        discovery = initialized.discovery
        ServerRuntime.replaceCatalog(library.all())
        initializing = false
        registerNetworkCallback()
    }

    private suspend fun handleCommand(action: String?) {
        when (action) {
            ACTION_SCAN -> if (actuallyRunning) scanLibrary() else startServer()
            ACTION_START -> startServer()
            ACTION_REMOTE_PLAY_PAUSE -> runCatching { RemotePlaybackRuntime.playPause() }
            ACTION_REMOTE_STOP -> runCatching { RemotePlaybackRuntime.stop() }
            else -> if (preferences.runningDesired) startServer() else stopSelf()
        }
    }

    private data class InitializedServer(
        val library: MediaLibrary,
        val metadata: TmdbRepository,
        val http: PluxyHttpServer,
        val discovery: DiscoveryResponder,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (!preferences.hasMediaSource()) {
            failAndStop("Aucune source multimédia sélectionnée.")
            return
        }
        if (actuallyRunning) {
            applyPowerLocks()
            refreshStatus()
            return
        }
        try {
            lastError = null
            http.start()
            discovery.start()
            actuallyRunning = true
            preferences.runningDesired = true
            applyPowerLocks()
            refreshStatus()
            scanLibrary()
        } catch (e: Exception) {
            failAndStop(e.message ?: "Impossible de démarrer le serveur.")
        }
    }

    private fun scanLibrary() {
        val job = library.scanAsync(serviceScope, selectedSources()) { items, complete ->
            ServerRuntime.replaceCatalog(items)
            if (complete) {
                val validIds = items.mapTo(HashSet(items.size)) { it.id }
                preferences.pruneWatchState(validIds)
                metadata.prune(validIds)
            }
            serviceScope.launch { metadata.enrichAll(items, force = false) }
        }
        if (job == null) {
            rescanPending.set(true)
            return
        }
        job.invokeOnCompletion {
            if (actuallyRunning && rescanPending.getAndSet(false)) {
                scanLibrary()
            }
        }
    }

    private fun selectedSources(): List<LibrarySource> = buildList {
        if (preferences.autoDiscoverMedia) {
            add(LibrarySource.automatic(LibrarySourceKind.VIDEO))
            add(LibrarySource.automatic(LibrarySourceKind.PHOTO))
            add(LibrarySource.automatic(LibrarySourceKind.AUDIO))
        }
        preferences.videoFolders.filter { it.visible }.forEach {
            add(LibrarySource(LibrarySourceKind.VIDEO, it.uri, it.title))
        }
        preferences.photoFolders.filter { it.visible }.forEach {
            add(LibrarySource(LibrarySourceKind.PHOTO, it.uri, it.title))
        }
        preferences.audioFolders.filter { it.visible }.forEach {
            add(LibrarySource(LibrarySourceKind.AUDIO, it.uri, it.title))
        }
    }

    private fun stopByUser() {
        preferences.runningDesired = false
        actuallyRunning = false
        serviceScope.launch {
            runCatching { RemotePlaybackRuntime.stop() }
            stopSelf()
        }
    }

    private fun failAndStop(message: String) {
        commands.close()
        initializing = false
        lastError = message
        preferences.runningDesired = false
        actuallyRunning = false
        ServerRuntime.replace(ServerSnapshot(error = message))
        updateNotification()
        stopSelf()
    }

    @Synchronized
    private fun applyPowerLocks() {
        if (destroying || (!actuallyRunning && !RemotePlaybackRuntime.state.value.active) ||
            (!preferences.keepAwake && !RemotePlaybackRuntime.state.value.active)
        ) {
            releasePowerLocks()
            return
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pluxy:AndroidServer")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pluxy:StreamingWifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    @Synchronized
    private fun releasePowerLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock = null
    }

    private fun refreshStatus() {
        val wifi = NetworkUtils.isWifiConnected(this)
        val ip = NetworkUtils.primaryLanIp(this)
        val address = ip?.let { "http://$it:${preferences.httpPort}" }
        ServerRuntime.update {
            it.copy(
                running = actuallyRunning,
                mediaCount = library.all().size,
                address = address,
                wifiConnected = wifi,
                error = lastError,
            )
        }
        updateNotification()
    }

    private fun updateNotification() {
        val state = ServerRuntime.state.value
        val remote = RemotePlaybackRuntime.state.value
        val text = when {
            remote.active -> "${remote.media?.title ?: "Média"} → ${remote.device?.name ?: "TV"}"
            initializing -> "Démarrage… chargement de la bibliothèque"
            state.error != null -> state.error
            !state.wifiConnected -> "Actif · en attente du Wi-Fi"
            state.scanning -> "Analyse de la bibliothèque… ${state.mediaCount} média(s)"
            else -> "${state.mediaCount} média(s) · ${state.address.orEmpty().removePrefix("http://")}".trim()
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val remote = RemotePlaybackRuntime.state.value
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(
                this,
                if (remote.active) ReceiverActivity::class.java else MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_small)
            .setContentTitle("Pluxy Serveur Android")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(actuallyRunning || remote.active)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (remote.active) {
            val playPauseIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, ServerService::class.java).setAction(ACTION_REMOTE_PLAY_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val remoteStopIntent = PendingIntent.getService(
                this,
                4,
                Intent(this, ServerService::class.java).setAction(ACTION_REMOTE_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .addAction(
                    R.drawable.ic_server_small,
                    if (remote.phase.name == "PLAYING") "Pause" else "Lecture",
                    playPauseIntent,
                )
                .addAction(R.drawable.ic_server_small, "Arrêter la TV", remoteStopIntent)
        }
        builder.addAction(R.drawable.ic_server_small, "Arrêter le serveur", stopIntent)
        return builder.build()
    }

    private fun foreground(notification: Notification) {
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onDestroy() {
        destroying = true
        actuallyRunning = false
        commands.close()
        if (this::http.isInitialized) runCatching { http.stop() }
        if (this::discovery.isInitialized) runCatching { discovery.stop() }
        releasePowerLocks()
        if (networkCallbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        RemotePlaybackRuntime.release()
        serviceScope.cancel()
        ServerRuntime.update { it.copy(running = false, scanning = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.pluxy.server.action.START"
        const val ACTION_STOP = "com.pluxy.server.action.STOP"
        const val ACTION_SCAN = "com.pluxy.server.action.SCAN"
        const val ACTION_REMOTE_PLAY_PAUSE = "com.pluxy.server.action.REMOTE_PLAY_PAUSE"
        const val ACTION_REMOTE_STOP = "com.pluxy.server.action.REMOTE_STOP"

        private const val CHANNEL_ID = "pluxy_server"
        private const val NOTIFICATION_ID = 8420

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }

        fun scan(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerService::class.java).setAction(ACTION_SCAN),
            )
        }
    }
}

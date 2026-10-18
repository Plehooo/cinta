package com.plehooo.cinta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Sinkron foto", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Cinta aktif")
            .setContentText("Menjaga foto widget tetap terbaru")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        registerNetworkWatcher()
        registerScreenWatcher()
        scope.launch {
            while (isActive) {
                delay(30_000)
                refreshNow()
            }
        }
    }

    private fun registerNetworkWatcher() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    delay(1_500)
                    PhotoSyncRepository.startRealtimeObserver(applicationContext)
                    if (PhotoQueue.hasAnyPending(applicationContext)) SyncScheduler.enqueueNow(applicationContext)
                    refreshNow()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Throwable) {
        }
    }

    // Layar menyala / dibuka kuncinya: tarik foto terbaru seketika supaya widget sudah baru saat dilihat.
    private fun registerScreenWatcher() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                scope.launch {
                    PhotoSyncRepository.startRealtimeObserver(applicationContext)
                    refreshNow()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
        } catch (_: Throwable) {
        }
    }

    private suspend fun refreshNow() {
        syncLock.withLock {
            try {
                val repo = PhotoSyncRepository(applicationContext)
                if (repo.awaitUid() != null) repo.refreshPartnerPhoto()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PhotoSyncRepository.startRealtimeObserver(this)
        scope.launch { refreshNow() }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (_: Throwable) {
        }
        try {
            networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cinta-sync"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RealtimeService.start(context)
        }
    }
}

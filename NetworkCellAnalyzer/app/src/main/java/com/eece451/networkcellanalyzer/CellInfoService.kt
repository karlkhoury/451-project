package com.eece451.networkcellanalyzer

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

/**
 * CellInfoService is a FOREGROUND SERVICE that:
 * 1. Runs continuously in the background (even if the user switches apps)
 * 2. Every 10 seconds, reads cell info using CellInfoCollector
 * 3. Sends that data to the server using ServerClient
 * 4. Broadcasts the data to MainActivity so the UI can update in real-time
 *
 * WHY A FOREGROUND SERVICE?
 * Android kills background tasks aggressively to save battery.
 * A foreground service shows a persistent notification and tells Android
 * "this app is doing important work, don't kill it."
 */
class CellInfoService : Service() {

    companion object {
        const val ACTION_CELL_UPDATE = "com.eece451.networkcellanalyzer.CELL_UPDATE"
        const val EXTRA_SERVER_URL = "server_url"
        const val CHANNEL_ID = "cell_monitor_channel"
        private const val TAG = "CellInfoService"
        private const val INTERVAL_MS = 10_000L // 10 seconds
    }

    private lateinit var collector: CellInfoCollector
    private lateinit var serverClient: ServerClient
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitoringJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        collector = CellInfoCollector(this)
        serverClient = ServerClient("http://10.0.2.2:5000")
        createNotificationChannel()
    }

    /**
     * Called when the service starts. We extract the server URL from the intent,
     * show a foreground notification, and begin the monitoring loop.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "http://10.0.2.2:5000"
        serverClient.updateServerUrl(serverUrl)

        // Show a persistent notification (required for foreground services)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        // Start the monitoring loop
        startMonitoring()

        return START_STICKY // Tells Android to restart the service if it gets killed
    }

    /**
     * The main monitoring loop:
     * - Collects cell info
     * - Sends it to the server
     * - Broadcasts it to the UI
     * - Waits 10 seconds
     * - Repeats
     */
    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    val cellData = collector.collect()
                    if (cellData != null) {
                        // Send to server (runs on IO thread internally)
                        val sent = serverClient.sendCellData(cellData)
                        Log.d(TAG, "Cell data sent to server: $sent")

                        // Broadcast to UI so MainActivity can display it
                        val updateIntent = Intent(ACTION_CELL_UPDATE).apply {
                            putExtra("operator", cellData.operator)
                            putExtra("signalPower", cellData.signalPower)
                            putExtra("sinr", cellData.sinr ?: Int.MAX_VALUE)
                            putExtra("networkType", cellData.networkType)
                            putExtra("frequencyBand", cellData.frequencyBand ?: -1)
                            putExtra("cellId", cellData.cellId)
                            putExtra("timestamp", cellData.timestamp)
                            putExtra("serverStatus", sent)
                            setPackage(packageName)
                        }
                        sendBroadcast(updateIntent)
                    } else {
                        Log.w(TAG, "Could not collect cell info (permissions or no cell)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop", e)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Creates a notification channel (required on Android 8.0+).
     * Think of channels as categories of notifications that users can control.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cell Monitoring",
                NotificationManager.IMPORTANCE_LOW // Low = no sound, just shows in status bar
            ).apply {
                description = "Shows that cell monitoring is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification opens the main activity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Cell Analyzer")
            .setContentText("Monitoring cell info...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()
    }
}

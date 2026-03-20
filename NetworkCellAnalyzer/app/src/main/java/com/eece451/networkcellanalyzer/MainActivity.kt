package com.eece451.networkcellanalyzer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * MainActivity: The main screen of the app.
 *
 * WHAT IT DOES:
 * 1. Requests necessary permissions (location + phone state)
 * 2. Lets the user enter the server URL
 * 3. Start/Stop button controls the CellInfoService (background monitoring)
 * 4. Listens for broadcasts from the service and updates the UI in real-time
 * 5. Has a button to open StatisticsActivity
 *
 * HOW THE UI UPDATES WORK:
 * - CellInfoService collects data and sends a broadcast Intent
 * - MainActivity registers a BroadcastReceiver that listens for those intents
 * - When a broadcast arrives, we extract the data and update TextViews
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private var isMonitoring = false

    // UI elements (we find these by ID from activity_main.xml)
    private lateinit var editServerUrl: EditText
    private lateinit var btnStartStop: Button
    private lateinit var btnStatistics: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOperator: TextView
    private lateinit var tvNetworkType: TextView
    private lateinit var tvSignalPower: TextView
    private lateinit var tvSinr: TextView
    private lateinit var tvCellId: TextView
    private lateinit var tvFrequencyBand: TextView
    private lateinit var tvTimestamp: TextView

    /**
     * BroadcastReceiver: listens for updates from CellInfoService.
     * Every 10 seconds the service sends a broadcast with fresh cell data.
     * This receiver catches it and updates the TextViews.
     */
    private val cellUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CellInfoService.ACTION_CELL_UPDATE) {
                // Extract data from the broadcast intent
                tvOperator.text = intent.getStringExtra("operator") ?: "--"
                tvNetworkType.text = intent.getStringExtra("networkType") ?: "--"

                val signalPower = intent.getIntExtra("signalPower", Int.MAX_VALUE)
                tvSignalPower.text = if (signalPower != Int.MAX_VALUE) "$signalPower dBm" else "-- dBm"

                val sinr = intent.getIntExtra("sinr", Int.MAX_VALUE)
                tvSinr.text = if (sinr != Int.MAX_VALUE) "$sinr dB" else "N/A"

                tvCellId.text = intent.getStringExtra("cellId") ?: "--"

                val band = intent.getIntExtra("frequencyBand", -1)
                tvFrequencyBand.text = if (band != -1) band.toString() else "N/A"

                tvTimestamp.text = intent.getStringExtra("timestamp") ?: "--"

                // Show server connection status
                val serverOk = intent.getBooleanExtra("serverStatus", false)
                tvStatus.text = if (serverOk) "Status: Running (Server Connected)"
                else "Status: Running (Server Unreachable)"
                tvStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (serverOk) R.color.signal_good else R.color.signal_medium
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find all UI elements by their IDs (defined in activity_main.xml)
        editServerUrl = findViewById(R.id.editServerUrl)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnStatistics = findViewById(R.id.btnStatistics)
        tvStatus = findViewById(R.id.tvStatus)
        tvOperator = findViewById(R.id.tvOperator)
        tvNetworkType = findViewById(R.id.tvNetworkType)
        tvSignalPower = findViewById(R.id.tvSignalPower)
        tvSinr = findViewById(R.id.tvSinr)
        tvCellId = findViewById(R.id.tvCellId)
        tvFrequencyBand = findViewById(R.id.tvFrequencyBand)
        tvTimestamp = findViewById(R.id.tvTimestamp)

        // Start/Stop button click handler
        btnStartStop.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                // First check permissions, then start
                if (checkPermissions()) {
                    startMonitoring()
                } else {
                    requestPermissions()
                }
            }
        }

        // Statistics button: opens the StatisticsActivity
        btnStatistics.setOnClickListener {
            val intent = Intent(this, StatisticsActivity::class.java)
            intent.putExtra("server_url", editServerUrl.text.toString())
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Register to receive cell updates from the service
        val filter = IntentFilter(CellInfoService.ACTION_CELL_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cellUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cellUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(cellUpdateReceiver)
    }

    /**
     * Starts the foreground service that monitors cell info.
     */
    private fun startMonitoring() {
        val serverUrl = editServerUrl.text.toString()
        val serviceIntent = Intent(this, CellInfoService::class.java).apply {
            putExtra(CellInfoService.EXTRA_SERVER_URL, serverUrl)
        }

        // startForegroundService is needed on Android 8.0+ for foreground services
        ContextCompat.startForegroundService(this, serviceIntent)

        isMonitoring = true
        btnStartStop.text = "Stop Monitoring"
        tvStatus.text = "Status: Starting..."
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.signal_medium))
    }

    private fun stopMonitoring() {
        stopService(Intent(this, CellInfoService::class.java))
        isMonitoring = false
        btnStartStop.text = "Start Monitoring"
        tvStatus.text = "Status: Stopped"
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.signal_bad))
    }

    /**
     * Check if we have all required permissions.
     * Android requires FINE_LOCATION to use getAllCellInfo() (even though it seems like
     * a phone permission, not a location one — that's just how Android works).
     */
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        // Android 13+ requires POST_NOTIFICATIONS permission for foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    /**
     * Called after the user responds to the permission dialog.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMonitoring()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required to monitor cell info",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

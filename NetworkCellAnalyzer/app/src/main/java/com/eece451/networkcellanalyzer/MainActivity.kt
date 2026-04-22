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
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

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
    private lateinit var tvQualityGrade: TextView
    private lateinit var switchDemoMode: SwitchMaterial
    private lateinit var signalChart: SignalChartView

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
                tvSinr.text = if (sinr != Int.MAX_VALUE) "SINR: $sinr dB" else "SINR: N/A"

                tvCellId.text = intent.getStringExtra("cellId") ?: "--"

                val band = intent.getIntExtra("frequencyBand", -1)
                tvFrequencyBand.text = if (band != -1) band.toString() else "N/A"

                tvTimestamp.text = intent.getStringExtra("timestamp") ?: "--"

                // Compute and display network quality grade (A/B/C/D)
                val sinrForGrade = if (sinr != Int.MAX_VALUE) sinr else null
                updateQualityGrade(signalPower, sinrForGrade)

                // Update the live signal chart
                if (signalPower != Int.MAX_VALUE) signalChart.addValue(signalPower)

                // Show server connection status (with offline-queue size if any)
                val serverOk = intent.getBooleanExtra("serverStatus", false)
                val queueSize = intent.getIntExtra("queueSize", 0)
                tvStatus.text = when {
                    serverOk && queueSize == 0 -> "Status: Running (Server Connected)"
                    serverOk && queueSize > 0  -> "Status: Flushing queue ($queueSize left)"
                    else                       -> "Status: Offline — queued $queueSize measurements"
                }
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
        tvQualityGrade = findViewById(R.id.tvQualityGrade)
        switchDemoMode = findViewById(R.id.switchDemoMode)
        signalChart = findViewById(R.id.signalChart)

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
            putExtra(CellInfoService.EXTRA_DEMO_MODE, switchDemoMode.isChecked)
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
    /**
     * Grades the network from A (excellent) to D (poor) using a simple
     * scoring model: signal power weighted with SINR when available.
     */
    private fun updateQualityGrade(signalDbm: Int, sinr: Int?) {
        if (signalDbm == Int.MAX_VALUE) {
            tvQualityGrade.text = "-"
            tvQualityGrade.setBackgroundColor(Color.parseColor("#9E9E9E"))
            return
        }
        // Signal score: -60 dBm or better = 100, -110 dBm or worse = 0
        val signalScore = ((signalDbm + 110).coerceIn(0, 50) / 50.0) * 100.0
        // SINR score: 20+ dB = 100, -5 dB = 0
        val sinrScore = if (sinr != null) ((sinr + 5).coerceIn(0, 25) / 25.0) * 100.0 else signalScore
        val combined = (signalScore * 0.6 + sinrScore * 0.4)

        val (grade, colorHex) = when {
            combined >= 80 -> "A" to "#2E7D32"   // green
            combined >= 60 -> "B" to "#689F38"   // light green
            combined >= 40 -> "C" to "#F9A825"   // amber
            else           -> "D" to "#C62828"   // red
        }
        tvQualityGrade.text = grade
        tvQualityGrade.setBackgroundColor(Color.parseColor(colorHex))
    }

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

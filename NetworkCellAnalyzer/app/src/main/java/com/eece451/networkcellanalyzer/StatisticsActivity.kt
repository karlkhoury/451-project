package com.eece451.networkcellanalyzer

import android.app.DatePickerDialog
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * StatisticsActivity: Shows network statistics fetched from the server.
 *
 * HOW IT WORKS:
 * 1. User picks a start and end date using Android DatePicker dialogs
 * 2. We send a GET request to the server: /api/stats?start=...&end=...&device_id=...
 * 3. Server queries its database and returns JSON with computed statistics
 * 4. We parse the JSON and display it in TextViews
 *
 * Statistics shown (as required by the project):
 * - Average connectivity time per operator (e.g., "Alfa: 60%, Touch: 40%")
 * - Average connectivity time per network type (e.g., "4G: 70%, 3G: 30%")
 * - Average signal power per network type
 * - Average signal power per device
 * - Average SINR/SNR per network type
 */
class StatisticsActivity : AppCompatActivity() {

    private lateinit var btnStartDate: Button
    private lateinit var btnEndDate: Button
    private lateinit var btnFetch: Button
    private lateinit var tvStatsOperator: TextView
    private lateinit var tvStatsNetworkType: TextView
    private lateinit var tvStatsSignal: TextView
    private lateinit var tvStatsSignalDevice: TextView
    private lateinit var tvStatsSinr: TextView

    private var startDate: String = ""
    private var endDate: String = ""
    private lateinit var serverClient: ServerClient
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        val serverUrl = intent.getStringExtra("server_url") ?: "https://four51-project.onrender.com"
        serverClient = ServerClient(serverUrl)

        // Find UI elements
        btnStartDate = findViewById(R.id.btnStartDate)
        btnEndDate = findViewById(R.id.btnEndDate)
        btnFetch = findViewById(R.id.btnFetchStats)
        tvStatsOperator = findViewById(R.id.tvStatsOperator)
        tvStatsNetworkType = findViewById(R.id.tvStatsNetworkType)
        tvStatsSignal = findViewById(R.id.tvStatsSignal)
        tvStatsSignalDevice = findViewById(R.id.tvStatsSignalDevice)
        tvStatsSinr = findViewById(R.id.tvStatsSinr)

        // Date picker for start date
        btnStartDate.setOnClickListener { showDatePicker(isStart = true) }
        btnEndDate.setOnClickListener { showDatePicker(isStart = false) }

        // Fetch stats button
        btnFetch.setOnClickListener {
            if (startDate.isEmpty() || endDate.isEmpty()) {
                Toast.makeText(this, "Please select both dates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchStatistics()
        }
    }

    /**
     * Shows an Android DatePickerDialog.
     * When the user picks a date, we store it as a string like "2026-03-19".
     */
    private fun showDatePicker(isStart: Boolean) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val date = String.format("%04d-%02d-%02d", year, month + 1, day)
                if (isStart) {
                    startDate = date
                    btnStartDate.text = date
                } else {
                    endDate = date
                    btnEndDate.text = date
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * Fetches statistics from the server and updates the UI.
     */
    private fun fetchStatistics() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        scope.launch {
            btnFetch.isEnabled = false
            btnFetch.text = "Loading..."

            val json = serverClient.fetchStatistics(startDate, endDate, deviceId)

            if (json != null) {
                try {
                    // Parse the JSON response into a Map
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val stats: Map<String, Any> = Gson().fromJson(json, type)

                    // Display each statistic
                    tvStatsOperator.text = formatMap(stats["connectivity_per_operator"])
                    tvStatsNetworkType.text = formatMap(stats["connectivity_per_network_type"])
                    tvStatsSignal.text = formatMap(stats["avg_signal_per_network_type"])
                    tvStatsSignalDevice.text = formatMap(stats["avg_signal_per_device"])
                    tvStatsSinr.text = formatMap(stats["avg_sinr_per_network_type"])
                } catch (e: Exception) {
                    Toast.makeText(this@StatisticsActivity, "Error parsing stats", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@StatisticsActivity, "Could not reach server", Toast.LENGTH_SHORT).show()
            }

            btnFetch.isEnabled = true
            btnFetch.text = "Fetch Statistics"
        }
    }

    /**
     * Helper to format a map like {"4G": 70.0, "3G": 30.0} into a readable string:
     * "4G: 70.0%\n3G: 30.0%"
     */
    private fun formatMap(data: Any?): String {
        if (data == null) return "No data"
        return when (data) {
            is Map<*, *> -> data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            else -> data.toString()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

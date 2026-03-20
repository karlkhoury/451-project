package com.eece451.networkcellanalyzer

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ServerClient sends cell data to the server and fetches statistics.
 *
 * HOW IT WORKS:
 * - Uses OkHttp (a popular HTTP library for Android) to make REST API calls
 * - POST /api/celldata  -> sends a cell measurement to the server
 * - GET  /api/stats?start=...&end=...&device_id=... -> fetches statistics
 *
 * All network calls run on Dispatchers.IO (background thread) using coroutines,
 * because Android does NOT allow network calls on the main/UI thread.
 */
class ServerClient(private var serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun updateServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    /**
     * Sends one cell data measurement to the server.
     * Called every 10 seconds by the background service.
     *
     * Returns true if the server accepted the data, false otherwise.
     */
    suspend fun sendCellData(data: CellInfoData): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(data)
            val body = json.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$serverUrl/api/celldata")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Fetches statistics from the server for a given date range.
     *
     * @param startDate  ISO date string (e.g. "2026-03-01")
     * @param endDate    ISO date string (e.g. "2026-03-19")
     * @param deviceId   This device's unique ID
     * @return Raw JSON string from the server, or null on failure
     */
    suspend fun fetchStatistics(startDate: String, endDate: String, deviceId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/stats?start=$startDate&end=$endDate&device_id=$deviceId")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
}

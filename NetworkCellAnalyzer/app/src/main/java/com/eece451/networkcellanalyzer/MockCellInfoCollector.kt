package com.eece451.networkcellanalyzer

import android.content.Context
import android.provider.Settings
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * MockCellInfoCollector generates realistic fake cell data for demos and
 * testing on emulators (which have no real cellular antenna).
 *
 * Produces data that looks like a real phone moving through different
 * towers and networks — operator rotates, network type shifts, signal
 * drifts within realistic ranges.
 */
class MockCellInfoCollector(context: Context) {

    private val deviceId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "emulator-${Random.nextInt(10000, 99999)}"

    private val macAddress: String = computePseudoMac(deviceId)

    private val operators = listOf("Alfa", "touch")
    private val networkTypes = listOf("2G", "3G", "4G", "4G", "4G", "5G") // weighted toward 4G

    private var tickCount = 0
    private var currentOperator = operators.random()
    private var currentNetwork = "4G"
    private var currentCellId = randomCellId("4G")
    private var currentBand = randomBand("4G")

    fun collect(): CellInfoData {
        tickCount++

        // Every ~6 ticks (~1 min), switch operator/network/cell to simulate movement
        if (tickCount % 6 == 0) {
            currentOperator = operators.random()
            currentNetwork = networkTypes.random()
            currentCellId = randomCellId(currentNetwork)
            currentBand = randomBand(currentNetwork)
        }

        val signal = randomSignal(currentNetwork)
        val sinr = randomSinr(currentNetwork)

        // Simulate walking around Beirut (AUB campus area, ~33.90 N, 35.48 E)
        // with small random drift so heatmap shows multiple points
        val latitude = 33.9000 + Random.nextDouble(-0.005, 0.005)
        val longitude = 35.4800 + Random.nextDouble(-0.005, 0.005)

        return CellInfoData(
            operator = currentOperator,
            signalPower = signal,
            sinr = sinr,
            networkType = currentNetwork,
            frequencyBand = currentBand,
            cellId = currentCellId,
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            deviceId = deviceId,
            macAddress = macAddress,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun randomSignal(network: String): Int = when (network) {
        "2G" -> Random.nextInt(-105, -75)
        "3G" -> Random.nextInt(-100, -70)
        "4G" -> Random.nextInt(-110, -75)
        "5G" -> Random.nextInt(-105, -70)
        else -> -90
    }

    private fun randomSinr(network: String): Int? = when (network) {
        "4G" -> Random.nextInt(-5, 25)
        "5G" -> Random.nextInt(0, 30)
        else -> null
    }

    private fun randomBand(network: String): Int = when (network) {
        "2G" -> Random.nextInt(0, 124)
        "3G" -> Random.nextInt(10562, 10838)
        "4G" -> listOf(1300, 1800, 2850, 6200).random()
        "5G" -> Random.nextInt(620000, 653333)
        else -> 0
    }

    private fun randomCellId(network: String): String {
        val prefix = Random.nextInt(100, 999)
        val suffix = Random.nextInt(100000, 9999999)
        return "$prefix-$suffix"
    }

    private fun computePseudoMac(id: String): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (nif in interfaces) {
                if (!nif.name.equals("wlan0", ignoreCase = true)) continue
                val bytes = nif.hardwareAddress ?: continue
                if (bytes.isEmpty()) continue
                return bytes.joinToString(":") { String.format("%02X", it) }
            }
        } catch (_: Exception) { }
        val hex = (id + "000000000000").take(12).uppercase()
        return "${hex.substring(0,2)}:${hex.substring(2,4)}:${hex.substring(4,6)}:" +
                "${hex.substring(6,8)}:${hex.substring(8,10)}:${hex.substring(10,12)}"
    }
}

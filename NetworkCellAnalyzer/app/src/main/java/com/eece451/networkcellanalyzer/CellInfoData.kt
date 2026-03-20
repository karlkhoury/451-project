package com.eece451.networkcellanalyzer

/**
 * Data class that holds one snapshot of cell information.
 * This is what gets sent to the server every 10 seconds.
 *
 * Fields match the project requirements:
 *  - operator: e.g. "Alfa", "Touch"
 *  - signalPower: in dBm (e.g. -85)
 *  - sinr: Signal-to-Interference-plus-Noise ratio in dB (null if not available)
 *  - networkType: "2G", "3G", or "4G"
 *  - frequencyBand: EARFCN/ARFCN/UARFCN number (null if unavailable)
 *  - cellId: unique cell identifier string
 *  - timestamp: ISO format string like "2026-03-19T14:30:00"
 *  - deviceId: unique ID per device so the server can distinguish phones
 */
data class CellInfoData(
    val operator: String,
    val signalPower: Int,
    val sinr: Int?,
    val networkType: String,
    val frequencyBand: Int?,
    val cellId: String,
    val timestamp: String,
    val deviceId: String
)

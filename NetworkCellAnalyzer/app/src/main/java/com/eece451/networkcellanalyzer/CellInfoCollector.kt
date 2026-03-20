package com.eece451.networkcellanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.*
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * CellInfoCollector reads cellular network information from the Android TelephonyManager.
 *
 * HOW IT WORKS:
 * 1. Android provides TelephonyManager.getAllCellInfo() which returns a list of CellInfo objects
 * 2. Each CellInfo can be one of:
 *    - CellInfoGsm    -> 2G (GSM/GPRS/EDGE)
 *    - CellInfoWcdma   -> 3G (UMTS)
 *    - CellInfoLte     -> 4G (LTE)
 * 3. We check which type it is and extract the relevant fields from each
 * 4. We only care about the REGISTERED cell (the one your phone is connected to)
 */
class CellInfoCollector(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /**
     * Collects current cell information.
     * Returns null if permissions are missing or no cell info is available.
     */
    fun collect(): CellInfoData? {
        // Check that we have the required permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        // Get the operator name (e.g. "Alfa", "Touch")
        val operatorName = telephonyManager.networkOperatorName.ifEmpty { "Unknown" }

        // Get a unique device ID (we use Android ID, which is unique per device)
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        // Get current timestamp
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        // getAllCellInfo() returns info about ALL cells the phone can see.
        // We want only the one the phone is REGISTERED to (i.e., actively connected).
        val cellInfoList = telephonyManager.allCellInfo ?: return null

        for (cellInfo in cellInfoList) {
            // isRegistered = true means this is the cell we're connected to
            if (!cellInfo.isRegistered) continue

            return when (cellInfo) {
                // ==================== 2G (GSM) ====================
                is CellInfoGsm -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength

                    CellInfoData(
                        operator = operatorName,
                        // getDbm() returns the signal strength in dBm
                        signalPower = signal.dbm,
                        // GSM does not have SINR, so we pass null
                        sinr = null,
                        networkType = "2G",
                        // getArfcn() = Absolute Radio Frequency Channel Number (GSM frequency)
                        frequencyBand = if (identity.arfcn != Int.MAX_VALUE) identity.arfcn else null,
                        // Cell ID format: LAC-CID (Location Area Code - Cell ID)
                        cellId = "${identity.lac}-${identity.cid}",
                        timestamp = timestamp,
                        deviceId = deviceId
                    )
                }

                // ==================== 3G (WCDMA/UMTS) ====================
                is CellInfoWcdma -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength

                    CellInfoData(
                        operator = operatorName,
                        signalPower = signal.dbm,
                        // WCDMA doesn't provide SINR directly via the standard API
                        sinr = null,
                        networkType = "3G",
                        // getUarfcn() = UTRA Absolute Radio Frequency Channel Number
                        frequencyBand = if (identity.uarfcn != Int.MAX_VALUE) identity.uarfcn else null,
                        // Cell ID format: LAC-CID
                        cellId = "${identity.lac}-${identity.cid}",
                        timestamp = timestamp,
                        deviceId = deviceId
                    )
                }

                // ==================== 4G (LTE) ====================
                is CellInfoLte -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength

                    // LTE provides RSSNR (Reference Signal SNR) — this is the SINR value
                    val sinrValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val rssnr = signal.rssnr
                        if (rssnr != Int.MAX_VALUE) rssnr else null
                    } else null

                    CellInfoData(
                        operator = operatorName,
                        // For LTE, dbm gives RSRP (Reference Signal Received Power)
                        signalPower = signal.dbm,
                        sinr = sinrValue,
                        networkType = "4G",
                        // getEarfcn() = E-UTRA Absolute Radio Frequency Channel Number
                        frequencyBand = if (identity.earfcn != Int.MAX_VALUE) identity.earfcn else null,
                        // Cell ID format: TAC-CI (Tracking Area Code - Cell Identity)
                        cellId = "${identity.tac}-${identity.ci}",
                        timestamp = timestamp,
                        deviceId = deviceId
                    )
                }

                else -> null
            }
        }
        return null
    }
}

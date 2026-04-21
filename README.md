# Network Cell Analyzer

A two-part system that collects cellular network metrics from Android devices and analyzes them on a cloud-hosted server.

**Course:** EECE 451 — Mobile Communications

---

## What It Does

- **Android app** reads real-time cellular data (operator, signal strength, network type, SINR, cell ID, frequency band) from the phone every 10 seconds
- **Flask server** receives the data, stores it in a SQLite database, and computes statistics
- **Web dashboard** displays connected devices and recent measurements, auto-refreshing every 10 seconds
- Supports **2G (GSM), 3G (WCDMA), 4G (LTE), and 5G (NR)**

---

## Live Deployment

- **Server:** https://four51-project.onrender.com
- **Dashboard:** open the URL in a browser
- **API base:** `https://four51-project.onrender.com/api`

The server runs 24/7 on Render's free tier. Phones can connect from any network — mobile data, Wi-Fi, anywhere.

---

## Architecture

```
┌─────────────┐       HTTPS POST /api/celldata        ┌──────────────┐
│ Android App │ ───────────────────────────────────▶  │ Flask Server │
│ (Kotlin)    │       every 10 seconds                │ (Python)     │
│             │                                       │              │
│             │       HTTPS GET  /api/stats           │              │
│             │ ◀───────────────────────────────────  │              │
└─────────────┘                                       └──────┬───────┘
                                                              │
                                                              ▼
                                                       ┌──────────────┐
                                                       │ SQLite DB    │
                                                       │ (celldata.db)│
                                                       └──────────────┘
```

---

## Project Structure

```
451-project/
└── NetworkCellAnalyzer/
    ├── app/                          # Android app (Kotlin)
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/.../             # 6 Kotlin source files
    │       └── res/                  # Layouts, colors, themes
    │
    ├── server/                       # Flask backend
    │   ├── server.py                 # REST API + dashboard
    │   ├── requirements.txt
    │   ├── Procfile                  # Render deploy config
    │   └── render.yaml
    │
    └── build.gradle.kts              # Gradle build config
```

---

## Android App Source Files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Main UI — start/stop monitoring, live data display |
| `CellInfoCollector.kt` | Reads cell data via TelephonyManager (2G/3G/4G/5G) |
| `CellInfoData.kt` | Data class for one measurement |
| `CellInfoService.kt` | Foreground service — runs every 10s |
| `ServerClient.kt` | OkHttp client for REST API calls |
| `StatisticsActivity.kt` | Statistics screen with date range |

---

## Server API

| Endpoint | Method | Purpose |
|---|---|---|
| `/` | GET | Web dashboard (auto-refresh every 10s) |
| `/api/celldata` | POST | Receive one cell measurement |
| `/api/stats` | GET | Compute statistics over date range |

### POST /api/celldata payload
```json
{
  "operator": "Alfa",
  "signalPower": -85,
  "sinr": 5,
  "networkType": "4G",
  "frequencyBand": 1300,
  "cellId": "37100-81937409",
  "timestamp": "2026-03-19T14:30:00",
  "deviceId": "abc123"
}
```

### GET /api/stats?start=YYYY-MM-DD&end=YYYY-MM-DD&device_id=XXX
Returns JSON with 5 statistics:
- `connectivity_per_operator` — % time per operator
- `connectivity_per_network_type` — % time per 2G/3G/4G/5G
- `avg_signal_per_network_type` — average signal (dBm) per network type
- `avg_signal_per_device` — average signal per device
- `avg_sinr_per_network_type` — average SINR (dB) per network type

---

## Running Locally (Server)

```bash
cd NetworkCellAnalyzer/server
pip install -r requirements.txt
python server.py
```

Then point the Android app at `http://<your-pc-ip>:5000` (same Wi-Fi) or use the cloud URL.

---

## Building The Android App

1. Open `NetworkCellAnalyzer/` in Android Studio
2. Connect an Android device with USB debugging enabled
3. Click the green ▶ Run button

**Minimum Android version:** 8.0 (API 26). **Target:** Android 14 (API 34).

### Required Permissions
- `READ_PHONE_STATE`
- `ACCESS_FINE_LOCATION` (required for `getAllCellInfo()`)
- `INTERNET`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)

---

## Technologies

**Android:** Kotlin · Gradle 8.2 · OkHttp 4.12 · Gson 2.10 · Kotlin Coroutines · Material Components

**Server:** Python · Flask 3.0 · SQLite3 · Gunicorn (production WSGI)

**Deployment:** Render (cloud) · GitHub (source control, auto-deploy)

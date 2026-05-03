# Network Cell Analyzer

A two-part system that collects cellular network metrics from Android devices and analyzes them on a cloud-hosted server.

**Course:** EECE 451 — Mobile Communications &nbsp;·&nbsp; **University:** AUB &nbsp;·&nbsp; **Semester:** Spring 2026

---

## What It Does

- **Android app** reads real-time cellular data — operator, signal strength, network type, SINR, cell ID, frequency band — from the phone every 10 seconds, plus GPS coordinates
- **Flask server** receives each measurement, stores it in SQLite, and computes statistics
- **Web dashboard** auto-refreshes every 10 seconds, showing connected devices, recent measurements, and a signal heatmap on a Leaflet map
- Supports **2G (GSM), 3G (WCDMA), 4G (LTE), and 5G (NR)**

---

## Live Deployment

| Resource | URL |
|---|---|
| Dashboard | https://four51-project.onrender.com |
| Source code | https://github.com/karlkhoury/451-project |
| Stats API | `https://four51-project.onrender.com/api/stats` |
| Heatmap API | `https://four51-project.onrender.com/api/heatmap` |
| CSV export | `https://four51-project.onrender.com/api/export.csv` |

The server runs 24/7 on Render's free tier — phones can connect from any network, anywhere.

---

## Architecture

```
┌─────────────┐    HTTPS POST /api/celldata    ┌──────────────┐
│ Android App │ ─────────────────────────────▶ │ Flask Server │ ─▶ SQLite DB
│ (Kotlin)    │       every 10 seconds          │ (Gunicorn on │
│             │                                 │  Render)     │ ─▶ Web Dashboard
│             │ ◀─── GET /api/stats response ── │              │    + Heatmap
└─────────────┘                                 └──────────────┘
```

---

## Installing The App On An Android Phone

The app is installed by **building and running it from Android Studio**.

### Prerequisites
- Android Studio installed (Hedgehog 2023.1.1 or newer)
- An Android phone running Android 8.0+ (API 26)
- A USB cable
- USB Debugging enabled on the phone:
  *Settings → About phone → tap Build number 7 times → Settings → Developer options → enable USB Debugging*

### Steps
1. Clone or download this repo:
   ```bash
   git clone https://github.com/karlkhoury/451-project.git
   ```
2. Open Android Studio → **Open** → select the `NetworkCellAnalyzer/` folder
3. Wait for Gradle sync to finish (first time can take 1–2 minutes)
4. Plug in your Android phone with USB debugging on; tap **Allow** on the phone when prompted
5. Verify the phone appears in Android Studio's device dropdown (top toolbar)
6. Click the green **▶ Run** button (or press `Shift+F10`)
7. Once the app launches, grant the requested permissions (location, phone state, notifications)
8. The server URL is pre-filled with the cloud server — just tap **Start Monitoring**

The app will continue running in the background as a foreground service even when minimized.

---

## Project Structure

```
451-project/
└── NetworkCellAnalyzer/
    ├── app/                              # Android module
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/eece451/networkcellanalyzer/
    │       │   ├── MainActivity.kt              # Main UI
    │       │   ├── CellInfoCollector.kt         # Reads TelephonyManager (real cell data)
    │       │   ├── MockCellInfoCollector.kt     # Demo Mode synthetic data
    │       │   ├── CellInfoData.kt              # Data class for one measurement
    │       │   ├── CellInfoService.kt           # Foreground service, runs every 10s
    │       │   ├── ServerClient.kt              # OkHttp client
    │       │   ├── StatisticsActivity.kt        # Statistics screen
    │       │   └── SignalChartView.kt           # Custom live chart
    │       └── res/                              # Layouts, drawables, themes
    │
    ├── server/
    │   ├── server.py                     # Flask app + dashboard
    │   ├── requirements.txt              # flask, gunicorn
    │   ├── Procfile                      # Render deploy
    │   └── render.yaml                   # Render Blueprint
    │
    └── build.gradle.kts                  # Root Gradle config
```

---

## Server REST API

| Endpoint | Method | Purpose |
|---|---|---|
| `/` | GET | Web dashboard (auto-refreshes every 10s) |
| `/about` | GET | About page |
| `/api/celldata` | POST | Receive one measurement (JSON body) |
| `/api/stats` | GET | Compute statistics over a date range |
| `/api/heatmap` | GET | Recent geo-tagged points for the map |
| `/api/compare_operators` | GET | Per-operator averages |
| `/api/export.csv` | GET | Download all data as CSV |

### Sample POST body
```json
{
  "operator": "touch",
  "signalPower": -87,
  "sinr": 5,
  "networkType": "4G",
  "frequencyBand": 1300,
  "cellId": "803-1821448",
  "timestamp": "2026-04-22T15:30:10",
  "deviceId": "4b1023df9efd6729",
  "macAddress": "4B:10:23:DF:9E:FD",
  "latitude": 33.9001,
  "longitude": 35.4802
}
```

### `/api/stats` returns
- `connectivity_per_operator` — % time per operator
- `connectivity_per_network_type` — % time per network type
- `avg_signal_per_network_type` — avg signal (dBm) per network type
- `avg_signal_per_device` — avg signal per device
- `avg_sinr_per_network_type` — avg SINR (dB) per network type

---

## Running The Server Locally (Optional)

```bash
cd NetworkCellAnalyzer/server
pip install -r requirements.txt
python server.py
```

Then point the Android app at `http://<your-pc-ip>:5000` (phone must be on the same Wi-Fi). The cloud deployment is recommended for the actual demo.

---

## Required Android Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Required by Android for `getAllCellInfo()` and GPS |
| `READ_PHONE_STATE` | Read operator name |
| `INTERNET` | Send data to the server |
| `ACCESS_NETWORK_STATE` | Detect connectivity |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Run the 10-second loop in the background |
| `POST_NOTIFICATIONS` | Display the foreground-service and weak-signal notifications (Android 13+) |

---

## Features

### Required (per project spec)
- Real-time 2G / 3G / 4G cell info collection
- Periodic transmission to the server (every 10s)
- Multi-device server with SQLite persistence
- Five required statistics with date-range filtering
- Web dashboard showing connected devices (IP + MAC, current and historical)

### Beyond the spec
- 5G (NR) support
- GPS-tagged signal heatmap on the dashboard (Leaflet + OpenStreetMap)
- Live in-app signal chart (custom Canvas view, no third-party library)
- Network quality grading (A/B/C/D weighted score)
- Weak-signal push notifications (rate-limited)
- Offline measurement queue (auto-flushes when server returns)
- Operator comparison cards on the dashboard
- CSV export
- Demo Mode (synthetic data for emulator testing)

---

## Technologies

**Android:** Kotlin · Gradle 8.2 · OkHttp 4.12 · Gson 2.10 · Kotlin Coroutines · Material Components

**Server:** Python · Flask 3.0 · SQLite3 · Gunicorn

**Frontend:** HTML5 · CSS3 · Leaflet.js · OpenStreetMap

**Deployment:** Render (cloud) · GitHub (auto-deploy on push)

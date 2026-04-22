"""
Network Cell Analyzer - Backend Server

This Flask server does 3 things:
1. RECEIVES cell data from Android apps via POST /api/celldata
2. STORES it in a SQLite database
3. PROVIDES statistics via GET /api/stats
4. SHOWS a web dashboard at / (server interface requirement)

HOW TO RUN:
    pip install flask
    python server.py

The server listens on port 5000 by default.
Use http://10.0.2.2:5000 from the Android emulator (10.0.2.2 is the host machine).
Use http://<your-pc-ip>:5000 from a real phone on the same Wi-Fi network.
"""

from flask import Flask, request, jsonify, render_template_string, Response
import sqlite3
import datetime
import threading
import os
import csv
import io

app = Flask(__name__)
DB_NAME = "celldata.db"

# Lock for thread-safe database writes (multiple phones sending data simultaneously)
db_lock = threading.Lock()

# Track connected devices (IP -> last seen timestamp)
connected_devices = {}


def get_db():
    """Create a new database connection (SQLite connections are per-thread)."""
    conn = sqlite3.connect(DB_NAME)
    conn.row_factory = sqlite3.Row  # So we can access columns by name
    return conn


def init_db():
    """
    Create the celldata table if it doesn't exist.
    This table stores every measurement sent by every phone.
    """
    conn = get_db()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS celldata (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            operator TEXT NOT NULL,
            signal_power INTEGER NOT NULL,
            sinr INTEGER,
            network_type TEXT NOT NULL,
            frequency_band INTEGER,
            cell_id TEXT NOT NULL,
            timestamp TEXT NOT NULL,
            device_id TEXT NOT NULL,
            ip_address TEXT,
            mac_address TEXT,
            latitude REAL,
            longitude REAL,
            received_at TEXT NOT NULL
        )
    """)
    # Migrations: add columns to existing DBs that predate them
    for col, col_type in [
        ("mac_address", "TEXT"),
        ("latitude", "REAL"),
        ("longitude", "REAL"),
    ]:
        try:
            conn.execute(f"ALTER TABLE celldata ADD COLUMN {col} {col_type}")
        except sqlite3.OperationalError:
            pass
    conn.commit()
    conn.close()


# ==================== API ENDPOINTS ====================

@app.route("/api/celldata", methods=["POST"])
def receive_celldata():
    """
    POST /api/celldata
    Receives one cell measurement from an Android device and stores it.

    Expected JSON body:
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
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "No JSON body"}), 400

    client_ip = request.remote_addr
    now = datetime.datetime.now().isoformat()

    mac_address = data.get("macAddress", "N/A")

    # Track this device as connected
    connected_devices[data.get("deviceId", "unknown")] = {
        "ip": client_ip,
        "mac": mac_address,
        "last_seen": now
    }

    with db_lock:
        conn = get_db()
        conn.execute(
            """INSERT INTO celldata
               (operator, signal_power, sinr, network_type, frequency_band,
                cell_id, timestamp, device_id, ip_address, mac_address,
                latitude, longitude, received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                data.get("operator", "Unknown"),
                data.get("signalPower", 0),
                data.get("sinr"),          # Can be null
                data.get("networkType", "Unknown"),
                data.get("frequencyBand"), # Can be null
                data.get("cellId", ""),
                data.get("timestamp", now),
                data.get("deviceId", "unknown"),
                client_ip,
                mac_address,
                data.get("latitude"),       # Can be null
                data.get("longitude"),      # Can be null
                now,
            ),
        )
        conn.commit()
        conn.close()

    return jsonify({"status": "ok"}), 200


@app.route("/api/stats", methods=["GET"])
def get_statistics():
    """
    GET /api/stats?start=2026-03-01&end=2026-03-19&device_id=abc123

    Computes and returns statistics for the given date range.
    All statistics are calculated from the stored measurements.
    """
    start = request.args.get("start", "2000-01-01")
    end = request.args.get("end", "2099-12-31")
    device_id = request.args.get("device_id", "")

    conn = get_db()

    # --- 1. Connectivity time per operator ---
    # We count how many measurements each operator has (each = 10 sec interval)
    # and compute the percentage
    rows = conn.execute(
        """SELECT operator, COUNT(*) as cnt FROM celldata
           WHERE timestamp >= ? AND timestamp <= ?
           GROUP BY operator""",
        (start, end + "T23:59:59"),
    ).fetchall()
    total = sum(r["cnt"] for r in rows)
    connectivity_operator = {}
    for r in rows:
        pct = round(r["cnt"] / total * 100, 1) if total > 0 else 0
        connectivity_operator[r["operator"]] = f"{pct}%"

    # --- 2. Connectivity time per network type ---
    rows = conn.execute(
        """SELECT network_type, COUNT(*) as cnt FROM celldata
           WHERE timestamp >= ? AND timestamp <= ?
           GROUP BY network_type""",
        (start, end + "T23:59:59"),
    ).fetchall()
    connectivity_network = {}
    for r in rows:
        pct = round(r["cnt"] / total * 100, 1) if total > 0 else 0
        connectivity_network[r["network_type"]] = f"{pct}%"

    # --- 3. Average signal power per network type ---
    rows = conn.execute(
        """SELECT network_type, ROUND(AVG(signal_power), 1) as avg_signal FROM celldata
           WHERE timestamp >= ? AND timestamp <= ?
           GROUP BY network_type""",
        (start, end + "T23:59:59"),
    ).fetchall()
    avg_signal_network = {r["network_type"]: f"{r['avg_signal']} dBm" for r in rows}

    # --- 4. Average signal power per device ---
    rows = conn.execute(
        """SELECT device_id, ROUND(AVG(signal_power), 1) as avg_signal FROM celldata
           WHERE timestamp >= ? AND timestamp <= ?
           GROUP BY device_id""",
        (start, end + "T23:59:59"),
    ).fetchall()
    avg_signal_device = {r["device_id"]: f"{r['avg_signal']} dBm" for r in rows}

    # --- 5. Average SINR per network type (only where sinr is not null) ---
    rows = conn.execute(
        """SELECT network_type, ROUND(AVG(sinr), 1) as avg_sinr FROM celldata
           WHERE timestamp >= ? AND timestamp <= ? AND sinr IS NOT NULL
           GROUP BY network_type""",
        (start, end + "T23:59:59"),
    ).fetchall()
    avg_sinr_network = {r["network_type"]: f"{r['avg_sinr']} dB" for r in rows}

    conn.close()

    return jsonify({
        "connectivity_per_operator": connectivity_operator,
        "connectivity_per_network_type": connectivity_network,
        "avg_signal_per_network_type": avg_signal_network,
        "avg_signal_per_device": avg_signal_device,
        "avg_sinr_per_network_type": avg_sinr_network,
    })


# ==================== CSV EXPORT ====================

@app.route("/api/export.csv")
def export_csv():
    """Download all measurements as a CSV file."""
    conn = get_db()
    rows = conn.execute(
        "SELECT * FROM celldata ORDER BY received_at DESC"
    ).fetchall()
    conn.close()

    buf = io.StringIO()
    writer = csv.writer(buf)
    cols = [
        "id", "operator", "signal_power", "sinr", "network_type",
        "frequency_band", "cell_id", "timestamp", "device_id",
        "ip_address", "mac_address", "latitude", "longitude", "received_at"
    ]
    writer.writerow(cols)
    for r in rows:
        writer.writerow([r[k] if k in r.keys() else "" for k in cols])

    return Response(
        buf.getvalue(),
        mimetype="text/csv",
        headers={"Content-Disposition": "attachment; filename=cell_measurements.csv"}
    )


# ==================== OPERATOR COMPARISON ====================

@app.route("/api/compare_operators")
def compare_operators():
    """Return side-by-side comparison of average metrics per operator."""
    conn = get_db()
    rows = conn.execute(
        """SELECT operator,
                  COUNT(*) as samples,
                  ROUND(AVG(signal_power), 1) as avg_signal,
                  ROUND(AVG(sinr), 1) as avg_sinr
           FROM celldata
           GROUP BY operator
           ORDER BY samples DESC"""
    ).fetchall()
    conn.close()
    return jsonify([
        {
            "operator": r["operator"],
            "samples": r["samples"],
            "avg_signal_dbm": r["avg_signal"],
            "avg_sinr_db": r["avg_sinr"],
        }
        for r in rows
    ])


# ==================== HEATMAP DATA ====================

@app.route("/api/heatmap")
def heatmap_data():
    """Return recent geo-located measurements for the dashboard map."""
    conn = get_db()
    rows = conn.execute(
        """SELECT latitude, longitude, signal_power, network_type, operator, timestamp
           FROM celldata
           WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ORDER BY received_at DESC
           LIMIT 500"""
    ).fetchall()
    conn.close()
    return jsonify([
        {
            "lat": r["latitude"],
            "lng": r["longitude"],
            "signal": r["signal_power"],
            "network": r["network_type"],
            "operator": r["operator"],
            "timestamp": r["timestamp"],
        }
        for r in rows
    ])


# ==================== ABOUT ====================

@app.route("/about")
def about():
    return render_template_string(ABOUT_HTML)


# ==================== WEB DASHBOARD (Server Interface) ====================

DASHBOARD_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>Network Cell Analyzer - Server Dashboard</title>
    <meta http-equiv="refresh" content="10">
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            max-width: 1100px; margin: 40px auto; padding: 0 20px;
            background: #f7f7fb; color: #222;
        }
        h1 { color: #6200EE; margin-bottom: 6px; }
        h2 { color: #333; margin-top: 30px; border-bottom: 2px solid #eaeaea; padding-bottom: 6px; }
        nav a {
            display: inline-block; padding: 6px 14px; margin-right: 8px;
            background: white; border: 1px solid #ddd; border-radius: 20px;
            color: #6200EE; text-decoration: none; font-size: 14px;
        }
        nav a:hover { background: #6200EE; color: white; border-color: #6200EE; }
        .stat-box {
            background: linear-gradient(135deg, #6200EE 0%, #9C27B0 100%);
            color: white; padding: 20px; border-radius: 12px; margin: 16px 0;
            display: flex; justify-content: space-between; align-items: center;
            box-shadow: 0 4px 14px rgba(98,0,238,0.25);
        }
        .stat-box h3 { margin: 0; font-size: 22px; }
        .download-btn {
            background: white; color: #6200EE; padding: 10px 18px;
            border-radius: 8px; text-decoration: none; font-weight: bold;
            transition: transform 0.15s;
        }
        .download-btn:hover { transform: scale(1.05); }
        table {
            border-collapse: collapse; width: 100%; margin: 16px 0;
            background: white; border-radius: 8px; overflow: hidden;
            box-shadow: 0 2px 8px rgba(0,0,0,0.05);
        }
        th, td { border-bottom: 1px solid #eaeaea; padding: 12px; text-align: left; }
        th { background: #6200EE; color: white; font-weight: 600; }
        tr:nth-child(even) td { background: #fafafa; }
        .connected { color: #2E7D32; font-weight: bold; }
        .disconnected { color: #999; }
        .card-grid {
            display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 16px; margin: 16px 0;
        }
        .op-card {
            background: white; border-radius: 12px; padding: 18px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.06);
            border-left: 5px solid #6200EE;
        }
        .op-card h3 { margin: 0 0 10px 0; color: #6200EE; }
        .op-card .metric { display: flex; justify-content: space-between; margin: 6px 0; }
        .op-card .metric .label { color: #888; font-size: 13px; }
        .op-card .metric .value { font-weight: bold; }
        #map {
            height: 400px; border-radius: 12px; margin: 16px 0;
            box-shadow: 0 2px 10px rgba(0,0,0,0.08);
        }
        .map-legend {
            background: white; padding: 8px 12px; border-radius: 6px;
            font-size: 13px; box-shadow: 0 1px 4px rgba(0,0,0,0.15);
        }
        .legend-dot { display: inline-block; width: 12px; height: 12px;
                      border-radius: 50%; margin-right: 6px; vertical-align: middle; }
        footer { margin-top: 40px; color: #888; font-size: 13px; text-align: center; }
    </style>
</head>
<body>
    <h1>Network Cell Analyzer</h1>
    <p style="color:#666;margin-top:0;">Centralized dashboard for cellular network measurements</p>

    <nav>
        <a href="/">Dashboard</a>
        <a href="/api/export.csv">Download CSV</a>
        <a href="/api/stats">Stats API</a>
        <a href="/about">About</a>
    </nav>

    <div class="stat-box">
        <h3>&#x1F4F1; Active Devices: {{ device_count }}</h3>
        <a href="/api/export.csv" class="download-btn">&#x2B07; Download CSV</a>
    </div>

    {% if operator_comparison %}
    <h2>Operator Comparison</h2>
    <div class="card-grid">
        {% for op in operator_comparison %}
        <div class="op-card">
            <h3>{{ op.operator }}</h3>
            <div class="metric"><span class="label">Samples</span><span class="value">{{ op.samples }}</span></div>
            <div class="metric"><span class="label">Avg Signal</span><span class="value">{{ op.avg_signal_dbm }} dBm</span></div>
            <div class="metric"><span class="label">Avg SINR</span><span class="value">{{ op.avg_sinr_db if op.avg_sinr_db is not none else 'N/A' }} dB</span></div>
        </div>
        {% endfor %}
    </div>
    {% endif %}

    <h2>&#x1F30D; Signal Heatmap</h2>
    <p style="color:#666;font-size:14px;margin-top:0;">
        Color-coded locations of recent measurements —
        <span class="legend-dot" style="background:#2E7D32;"></span>strong
        <span class="legend-dot" style="background:#F9A825;margin-left:10px;"></span>medium
        <span class="legend-dot" style="background:#C62828;margin-left:10px;"></span>weak.
    </p>
    <div id="map"></div>

    <h2>Device List (Currently and Previously Connected)</h2>
    <table>
        <tr>
            <th>Device ID</th>
            <th>IP Address</th>
            <th>MAC Address</th>
            <th>Last Seen</th>
            <th>Status</th>
        </tr>
        {% for device_id, info in devices.items() %}
        <tr>
            <td>{{ device_id[:12] }}...</td>
            <td>{{ info.ip }}</td>
            <td>{{ info.mac }}</td>
            <td>{{ info.last_seen }}</td>
            <td class="{{ 'connected' if info.active else 'disconnected' }}">
                {{ 'Active' if info.active else 'Inactive' }}
            </td>
        </tr>
        {% endfor %}
    </table>

    <h2>Recent Measurements</h2>
    <table>
        <tr>
            <th>Time</th>
            <th>Device</th>
            <th>Operator</th>
            <th>Type</th>
            <th>Signal</th>
            <th>SINR</th>
            <th>Cell ID</th>
        </tr>
        {% for row in recent %}
        <tr>
            <td>{{ row.timestamp }}</td>
            <td>{{ row.device_id[:8] }}...</td>
            <td>{{ row.operator }}</td>
            <td>{{ row.network_type }}</td>
            <td>{{ row.signal_power }} dBm</td>
            <td>{{ row.sinr if row.sinr else 'N/A' }}</td>
            <td>{{ row.cell_id }}</td>
        </tr>
        {% endfor %}
    </table>

    <footer>
        Auto-refreshes every 10 seconds &middot;
        <a href="https://github.com/karlkhoury/451-project" style="color:#6200EE;">Source on GitHub</a>
    </footer>

    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <script>
        // Initialize map centered on Beirut by default
        const map = L.map('map').setView([33.8938, 35.5018], 13);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap'
        }).addTo(map);

        function colorFor(dbm) {
            if (dbm >= -80) return '#2E7D32';   // strong (green)
            if (dbm >= -95) return '#F9A825';   // medium (amber)
            return '#C62828';                    // weak (red)
        }

        fetch('/api/heatmap')
            .then(r => r.json())
            .then(points => {
                if (!points.length) return;
                const group = L.featureGroup();
                points.forEach(p => {
                    const marker = L.circleMarker([p.lat, p.lng], {
                        radius: 7,
                        fillColor: colorFor(p.signal),
                        color: '#fff',
                        weight: 1.5,
                        fillOpacity: 0.85
                    }).bindPopup(
                        `<b>${p.operator}</b> (${p.network})<br>` +
                        `Signal: ${p.signal} dBm<br>` +
                        `<small>${p.timestamp}</small>`
                    );
                    group.addLayer(marker);
                });
                group.addTo(map);
                map.fitBounds(group.getBounds().pad(0.3));
            })
            .catch(() => { /* no geo data yet */ });
    </script>
</body>
</html>
"""


ABOUT_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>About - Network Cell Analyzer</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            max-width: 800px; margin: 60px auto; padding: 0 20px;
            background: #f7f7fb; color: #222; line-height: 1.6;
        }
        h1 { color: #6200EE; }
        .pill {
            display: inline-block; background: #6200EE; color: white;
            padding: 3px 10px; border-radius: 12px; font-size: 13px; margin: 2px;
        }
        a.btn {
            display: inline-block; background: #6200EE; color: white;
            padding: 10px 18px; border-radius: 8px; text-decoration: none;
            font-weight: bold; margin-top: 10px;
        }
    </style>
</head>
<body>
    <h1>About This Project</h1>
    <p>
        <strong>Network Cell Analyzer</strong> is a distributed system for
        crowdsourced cellular network measurement, built for EECE 451
        (Mobile Communications) at the American University of Beirut, Spring 2026.
    </p>
    <p>
        An Android app reads real-time cell data from the TelephonyManager API
        every 10 seconds and streams it to a Flask server hosted on Render.
        The server stores measurements in SQLite, computes statistics, and
        exposes this live dashboard with a geographic heatmap.
    </p>
    <p>
        <span class="pill">Kotlin</span>
        <span class="pill">Android</span>
        <span class="pill">Flask</span>
        <span class="pill">SQLite</span>
        <span class="pill">Gunicorn</span>
        <span class="pill">Render</span>
        <span class="pill">Leaflet.js</span>
    </p>
    <h2>Features</h2>
    <ul>
        <li>Real-time cell data collection (2G / 3G / 4G / 5G)</li>
        <li>Live in-app signal chart</li>
        <li>Network quality grade (A / B / C / D)</li>
        <li>GPS-tagged measurements with heatmap visualization</li>
        <li>Offline queue — buffers measurements when server is unreachable</li>
        <li>Operator comparison view (Alfa vs touch)</li>
        <li>CSV export of all measurements</li>
        <li>Statistics over any date range</li>
        <li>Demo mode for emulator testing</li>
    </ul>
    <a class="btn" href="/">&larr; Back to Dashboard</a>
    <a class="btn" href="https://github.com/karlkhoury/451-project"
       style="background:#333;">View Source on GitHub</a>
</body>
</html>
"""


@app.route("/")
def dashboard():
    """
    Web dashboard showing:
    - Number of connected devices
    - IP addresses and status of all devices
    - Recent measurements
    """
    now = datetime.datetime.now()

    # Mark devices as active if seen in the last 30 seconds
    devices = {}
    for dev_id, info in connected_devices.items():
        last_seen = datetime.datetime.fromisoformat(info["last_seen"])
        active = (now - last_seen).total_seconds() < 30
        devices[dev_id] = {
            "ip": info["ip"],
            "mac": info.get("mac", "N/A"),
            "last_seen": info["last_seen"],
            "active": active,
        }

    active_count = sum(1 for d in devices.values() if d["active"])

    # Get last 20 measurements
    conn = get_db()
    recent = conn.execute(
        "SELECT * FROM celldata ORDER BY received_at DESC LIMIT 20"
    ).fetchall()
    # Operator comparison for the dashboard card grid
    op_rows = conn.execute(
        """SELECT operator,
                  COUNT(*) as samples,
                  ROUND(AVG(signal_power), 1) as avg_signal,
                  ROUND(AVG(sinr), 1) as avg_sinr
           FROM celldata
           GROUP BY operator
           ORDER BY samples DESC"""
    ).fetchall()
    conn.close()

    operator_comparison = [
        {
            "operator": r["operator"],
            "samples": r["samples"],
            "avg_signal_dbm": r["avg_signal"],
            "avg_sinr_db": r["avg_sinr"],
        }
        for r in op_rows
    ]

    return render_template_string(
        DASHBOARD_HTML,
        device_count=active_count,
        devices=devices,
        recent=recent,
        operator_comparison=operator_comparison,
    )


# Initialize DB at import time so it runs under gunicorn (cloud) too
init_db()

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    debug = os.environ.get("FLASK_DEBUG", "1") == "1"
    print("=" * 50)
    print("Network Cell Analyzer Server")
    print("=" * 50)
    print(f"Dashboard:  http://localhost:{port}")
    print(f"API:        http://localhost:{port}/api/celldata")
    print(f"Stats:      http://localhost:{port}/api/stats")
    print()
    print("From Android emulator use: http://10.0.2.2:" + str(port))
    print("From real phone use your PC's IP address")
    print("=" * 50)
    app.run(host="0.0.0.0", port=port, debug=debug)

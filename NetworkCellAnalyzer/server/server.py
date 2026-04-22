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
            received_at TEXT NOT NULL
        )
    """)
    # Migration: add mac_address to existing DBs that predate this column
    try:
        conn.execute("ALTER TABLE celldata ADD COLUMN mac_address TEXT")
    except sqlite3.OperationalError:
        pass  # Column already exists
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
                cell_id, timestamp, device_id, ip_address, mac_address, received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
    writer.writerow([
        "id", "operator", "signal_power", "sinr", "network_type",
        "frequency_band", "cell_id", "timestamp", "device_id",
        "ip_address", "mac_address", "received_at"
    ])
    for r in rows:
        writer.writerow([r[k] for k in [
            "id", "operator", "signal_power", "sinr", "network_type",
            "frequency_band", "cell_id", "timestamp", "device_id",
            "ip_address", "mac_address", "received_at"
        ]])

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


# ==================== WEB DASHBOARD (Server Interface) ====================

DASHBOARD_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>Network Cell Analyzer - Server Dashboard</title>
    <meta http-equiv="refresh" content="10">
    <style>
        body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; padding: 0 20px; }
        h1 { color: #6200EE; }
        table { border-collapse: collapse; width: 100%; margin: 20px 0; }
        th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
        th { background: #6200EE; color: white; }
        tr:nth-child(even) { background: #f9f9f9; }
        .stat-box { background: #f0f0f0; padding: 15px; border-radius: 8px; margin: 10px 0; }
        .connected { color: green; font-weight: bold; }
        .disconnected { color: gray; }
    </style>
</head>
<body>
    <h1>Network Cell Analyzer - Server Dashboard</h1>

    <div class="stat-box">
        <h3>Connected Devices: {{ device_count }}</h3>
        <p style="margin-top:10px;">
            <a href="/api/export.csv"
               style="background:#6200EE;color:white;padding:8px 14px;border-radius:6px;text-decoration:none;font-weight:bold;">
               &#x2B07; Download All Data (CSV)
            </a>
        </p>
    </div>

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

    <p><em>Page auto-refreshes every 10 seconds.</em></p>
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
    conn.close()

    return render_template_string(
        DASHBOARD_HTML,
        device_count=active_count,
        devices=devices,
        recent=recent,
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

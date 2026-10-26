#!/usr/bin/env python3
"""Answers /stat?action=get_clients with the proxy's JSON shape, for emulator checks.

The emulator cannot run the AceStream engine (docs/Emulator.md), so the app cannot read a real
/stat answer there. This fixture lets the app side be exercised end to end over adb reverse:

    python3 tools/stat-fixture.py &
    adb reverse tcp:8888 tcp:8888

It is not packaged into the app and never replaces a device check of the proxy.
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

CLIENTS = [
    {"sessionID": "1", "channelName": "Canal Uno", "clientIP": "192.168.1.20",
     "userAgent": "TiviMate/4.7.0", "bytesSent": 1048576, "megabytesSent": 1.0},
    {"sessionID": "2", "channelName": "Canal Dos", "clientIP": "192.168.1.31",
     "userAgent": "VLC/3.0.20", "bytesSent": 524288, "megabytesSent": 0.5},
    {"sessionID": "3", "channelName": "Canal Tres", "clientIP": "192.168.1.44",
     "userAgent": "Kodi/21", "bytesSent": 7340032, "megabytesSent": 7.0},
]

STREAMS = [
    {"channel": "Canal Uno", "clients": 1, "bytesSent": 1048576, "packets": 5580,
     "payloadBytes": 1022976, "bitrateBps": 1504000.0, "continuityErrors": 0,
     "syncErrors": 0, "nullPackets": 12, "pcrDiscontinuities": 0,
     "lastPayloadAgeMs": 240, "stalled": False, "synchronized": True},
    {"channel": "Canal Dos", "clients": 1, "bytesSent": 524288, "packets": 0,
     "payloadBytes": 0, "bitrateBps": 0.0, "continuityErrors": 0,
     "syncErrors": 512, "nullPackets": 0, "pcrDiscontinuities": 0,
     "lastPayloadAgeMs": -1, "stalled": False, "synchronized": False},
    {"channel": "Canal Tres", "clients": 1, "bytesSent": 7340032, "packets": 39000,
     "payloadBytes": 7000000, "bitrateBps": 0.0, "continuityErrors": 7,
     "syncErrors": 0, "nullPackets": 40, "pcrDiscontinuities": 2,
     "lastPayloadAgeMs": 120000, "stalled": True, "synchronized": True},
]

CLIENTS_ANSWER = {
    "status": "success", "total_clients": 3, "active_broadcasts": 3,
    "clients_data": CLIENTS, "streams": STREAMS,
}

STATUS_ANSWER = {
    "status": "success",
    "sys_info": {"uptime": "1 day", "python_version": "3.11"},
    "server_config": {
        "aceproxy": {"host": "127.0.0.1", "port": 8888},
        "acestream": {"host": "127.0.0.1", "api_port": 62062, "http_port": 6878},
        "limits": {"max_connections": 20, "max_concurrent_channels": 5},
        "plugins": {"enabled": ["aio"]},
    },
    "server_info": {"runtime": "C++20", "os_name": "native",
                    "acestream_engine": {"status": "connected", "version": "3.2.3"},
                    "plugins_loaded": []},
    "connection_info": {"max_clients": 20, "total_clients": 3, "active_broadcasts": 3},
    "clients_data": CLIENTS, "streams": STREAMS,
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        body = STATUS_ANSWER
        if parsed.path == "/stat":
            action = parse_qs(parsed.query).get("action", [""])[0]
            if action in ("get_clients", ""):
                body = CLIENTS_ANSWER
            elif action == "get_plugins":
                body = {"status": "success", "plugins": []}
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print("stat fixture on http://127.0.0.1:8888 (adb reverse tcp:8888 tcp:8888)")
    HTTPServer(("127.0.0.1", 8888), Handler).serve_forever()

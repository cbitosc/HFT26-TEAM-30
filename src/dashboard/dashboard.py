#!/usr/bin/env python3
"""EmergencyMesh AI dashboard. It needs Python 3.9 or newer and nothing else installed.

The mesh never depends on this. Phones with internet can upload alerts here, the service holds the
Featherless key, and people see AI suggestions beside the original messages.

    python3 dashboard.py          this laptop only
    python3 dashboard.py --lan    also accepts uploads from phones on the same network
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import queue
import socket
import socketserver
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import parse_qs, unquote, urlsplit

from assistant import DEFAULT_BASE_URL, DEFAULT_MODEL, LANGUAGES, Assistant, AssistantUnavailable
from mesh_node import InvalidPacket, MeshNode, NodeFull, clean_packet, node_reply
from store import AlertStore, InvalidAlert, clean_alert
from triage import URGENCIES, rank

HERE = Path(__file__).resolve().parent
MAX_BODY_BYTES = 256 * 1024
MAX_ALERTS_PER_UPLOAD = 100
LOCAL_HOSTS = ("127.0.0.1", "localhost", "[::1]")
MAX_DEVICE_ID_CHARS = 64


def load_env(path: Path) -> Dict[str, str]:
    """Reads KEY=VALUE lines from .env, so the key stays out of the code and the repo."""
    values: Dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                name, value = line.split("=", 1)
                values[name.strip()] = value.strip().strip("'\"")
    return values


class Analyst:
    """Runs one AI call at a time, because Featherless plans limit concurrent requests."""

    def __init__(self, store: AlertStore, assistant: Assistant) -> None:
        self._store = store
        self._assistant = assistant
        self._jobs: "queue.Queue[str]" = queue.Queue()
        self._call_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._last_error: Optional[str] = None

    def start(self) -> None:
        threading.Thread(target=self._run, daemon=True).start()
        for alert_id in self._store.waiting_ids():
            self.enqueue(alert_id)

    def enqueue(self, alert_id: str) -> None:
        if self._assistant.configured:
            self._jobs.put(alert_id)

    def status(self) -> Dict[str, Any]:
        with self._state_lock:
            error = self._last_error
        if not self._assistant.configured:
            return {"state": "off", "model": None, "detail": "Add a Featherless key to turn AI on"}
        if error:
            return {"state": "unavailable", "model": self._assistant.model, "detail": error}
        return {"state": "on", "model": self._assistant.model, "detail": None}

    def translate(self, text: str, language: str) -> str:
        return self._call(lambda: self._assistant.translate(text, language))

    def _run(self) -> None:
        while True:
            alert_id = self._jobs.get()
            alert = self._store.get(alert_id)
            if alert is None or alert["analysis_state"] != "waiting":
                continue
            try:
                analysis = self._call(lambda: self._assistant.analyze(alert["text"]))
            except AssistantUnavailable:
                self._store.mark_analysis_failed(alert_id)
            else:
                self._store.save_analysis(alert_id, analysis)

    def _call(self, call: Any) -> Any:
        with self._call_lock:
            try:
                result = call()
            except AssistantUnavailable as failure:
                self._record(str(failure))
                raise
            self._record(None)
            return result

    def _record(self, error: Optional[str]) -> None:
        with self._state_lock:
            self._last_error = error


class Dashboard:
    def __init__(
        self,
        store: AlertStore,
        analyst: Analyst,
        upload_token: Optional[str],
        node: MeshNode,
    ) -> None:
        self.store = store
        self.analyst = analyst
        self.upload_token = upload_token
        self.node = node

    def state(self) -> Dict[str, Any]:
        alerts = rank(self.store.all())
        counts = {urgency: 0 for urgency in URGENCIES}
        for alert in alerts:
            if not alert["reviewed"]:
                counts[alert["assessment"]["urgency"]] += 1
        return {
            "ai": self.analyst.status(),
            "languages": LANGUAGES,
            "urgencies": URGENCIES,
            "counts": counts,
            "node": self.node.stats(),
            "alerts": alerts,
        }

    def upload(self, payload: Any) -> Dict[str, int]:
        items = payload.get("alerts") if isinstance(payload, dict) else None
        if not isinstance(items, list) or len(items) > MAX_ALERTS_PER_UPLOAD:
            raise InvalidAlert("Send a list of alerts")
        return self._add(clean_alert(item, "phone") for item in items)

    def load_samples(self) -> Dict[str, int]:
        samples = json.loads((HERE / "sample_alerts.json").read_text(encoding="utf-8"))
        now = time.time()
        return self._add(
            clean_alert({**sample, "created_at": now - sample["age_minutes"] * 60}, "sample")
            for sample in samples
        )

    def _add(self, alerts: Any) -> Dict[str, int]:
        accepted = list(alerts)
        added = 0
        for alert in accepted:
            if self.store.add(alert):
                added += 1
                self.analyst.enqueue(alert["id"])
        return {"added": added, "repeats": len(accepted) - added}


class QuickServer(ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self) -> None:
        # The standard version looks up this laptop's name, which stalls for seconds offline.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


class Handler(BaseHTTPRequestHandler):
    server_version = "EmergencyMeshDashboard"
    dashboard: Dashboard

    def log_message(self, format: str, *args: Any) -> None:
        pass  # Requests could reveal who is asking for help, so nothing is logged.

    def do_GET(self) -> None:
        if urlsplit(self.path).path == "/mesh/packets":
            return self._hand_out_packets()
        if not self._from_this_laptop():
            return self._reply(403, {"error": "Open the dashboard on this laptop"})
        if self.path in ("/", "/index.html"):
            page = (HERE / "static" / "index.html").read_bytes()
            return self._send(200, page, "text/html; charset=utf-8")
        if self.path == "/api/state":
            return self._reply(200, self.dashboard.state())
        self._reply(404, {"error": "Not found"})

    def do_POST(self) -> None:
        # A JSON content type forces a browser preflight, which this server never approves.
        # So a website open in another tab can't send requests to the dashboard.
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            return self._reply(415, {"error": "Send JSON"})
        try:
            payload = self._read_json()
            if self.path == "/api/alerts":
                if not self._may_upload():
                    return self._reply(401, {"error": "Upload token is missing or wrong"})
                return self._reply(200, self.dashboard.upload(payload))
            if self.path == "/mesh/packets":
                return self._take_packet(payload)
            if not self._from_this_laptop():
                return self._reply(403, {"error": "Open the dashboard on this laptop"})
            self._route(payload)
        except (InvalidAlert, InvalidPacket) as problem:
            self._reply(400, {"error": str(problem)})
        except ValueError:
            self._reply(400, {"error": "Invalid JSON"})

    def _take_packet(self, payload: Any) -> None:
        if not self._may_upload():
            return self._reply(401, {"error": "Upload token is missing or wrong"})
        try:
            stored = self.dashboard.node.store(clean_packet(payload, time.time()))
        except NodeFull:
            return self._reply(507, {"error": "This node is full"})
        self._reply(200, {"stored": stored})

    def _hand_out_packets(self) -> None:
        if not self._may_upload():
            return self._reply(401, {"error": "Upload token is missing or wrong"})
        query = parse_qs(urlsplit(self.path).query)
        device = query.get("device", [""])[0]
        try:
            after = int(query.get("after", ["0"])[0])
        except ValueError:
            after = -1
        if not 0 < len(device) <= MAX_DEVICE_ID_CHARS or after < 0:
            return self._reply(400, {"error": "Invalid request"})
        cursor, packets = self.dashboard.node.fetch(device, after)
        self._send(200, node_reply(cursor, packets), "text/plain; charset=ascii")

    def _route(self, payload: Any) -> None:
        if self.path == "/api/samples":
            return self._reply(200, self.dashboard.load_samples())
        if self.path == "/api/samples/clear":
            return self._reply(200, {"removed": self.dashboard.store.clear_samples()})
        parts = self.path.strip("/").split("/")
        if len(parts) != 4 or parts[:2] != ["api", "alerts"]:
            return self._reply(404, {"error": "Not found"})
        alert_id, action = unquote(parts[2]), parts[3]
        store = self.dashboard.store
        alert = store.get(alert_id)
        if alert is None:
            return self._reply(404, {"error": "Alert not found"})
        if not isinstance(payload, dict):
            raise InvalidAlert("Send an object")
        if action == "override":
            store.set_override(alert_id, payload.get("urgency"))
        elif action == "reviewed":
            store.set_reviewed(alert_id, payload.get("reviewed") is True)
        elif action == "retry":
            store.mark_analysis_waiting(alert_id)
            self.dashboard.analyst.enqueue(alert_id)
        elif action == "translate":
            return self._translate(alert, payload.get("language"))
        else:
            return self._reply(404, {"error": "Not found"})
        self._reply(200, {"ok": True})

    def _translate(self, alert: Dict[str, Any], language: Any) -> None:
        if language not in LANGUAGES:
            raise InvalidAlert("Unsupported language")
        cached = alert["translations"].get(language)
        if cached:
            return self._reply(200, {"translation": cached})
        try:
            translation = self.dashboard.analyst.translate(alert["text"], language)
        except AssistantUnavailable as failure:
            return self._reply(503, {"error": str(failure)})
        self.dashboard.store.save_translation(alert["id"], language, translation)
        self._reply(200, {"translation": translation})

    def _may_upload(self) -> bool:
        token = self.dashboard.upload_token
        if not token:
            return self._from_this_laptop()
        given = self.headers.get("X-Upload-Token", "")
        return hmac.compare_digest(given.encode("utf-8"), token.encode("utf-8"))

    def _from_this_laptop(self) -> bool:
        # Checking the host name stops a website from pointing its own domain at this laptop.
        host = self.headers.get("Host", "").rsplit(":", 1)[0]
        return self.client_address[0] in ("127.0.0.1", "::1") and host in LOCAL_HOSTS

    def _read_json(self) -> Any:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise InvalidAlert("Invalid length") from None
        if not 0 <= length <= MAX_BODY_BYTES:
            self.close_connection = True
            raise InvalidAlert("Too much data")
        return json.loads(self.rfile.read(length) or b"{}")

    def _reply(self, status: int, payload: Any) -> None:
        self._send(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json")

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)


def build_server(
    host: str,
    port: int,
    store: AlertStore,
    assistant: Assistant,
    upload_token: Optional[str],
    node: MeshNode,
) -> ThreadingHTTPServer:
    analyst = Analyst(store, assistant)
    dashboard = Dashboard(store, analyst, upload_token, node)
    handler = type("BoundHandler", (Handler,), {"dashboard": dashboard})
    server = QuickServer((host, port), handler)
    analyst.start()
    return server


def lan_address() -> str:
    """Finds the address phones should use. No packet is sent by connecting a UDP socket."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(("10.255.255.255", 1))
            return probe.getsockname()[0]
        except OSError:
            return "this laptop's address"


def main() -> None:
    parser = argparse.ArgumentParser(description="EmergencyMesh AI dashboard")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--lan", action="store_true", help="accept uploads from phones on this network")
    parser.add_argument("--db", default=str(HERE / "dashboard.db"))
    options = parser.parse_args()

    settings = {**load_env(HERE / ".env"), **os.environ}
    token = settings.get("DASHBOARD_UPLOAD_TOKEN") or None
    if options.lan and not token:
        parser.error("--lan needs DASHBOARD_UPLOAD_TOKEN in src/dashboard/.env")
    assistant = Assistant(
        settings.get("FEATHERLESS_API_KEY"),
        model=settings.get("FEATHERLESS_MODEL") or DEFAULT_MODEL,
        base_url=settings.get("FEATHERLESS_BASE_URL") or DEFAULT_BASE_URL,
    )
    host = "0.0.0.0" if options.lan else "127.0.0.1"
    server = build_server(
        host, options.port, AlertStore(options.db), assistant, token, MeshNode(options.db),
    )
    print("Dashboard: http://127.0.0.1:%d" % options.port)
    if options.lan:
        print("Phones upload to: http://%s:%d" % (lan_address(), options.port))
    print("AI: %s" % ("on, " + assistant.model if assistant.configured else "off, no Featherless key"), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

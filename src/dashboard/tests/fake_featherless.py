#!/usr/bin/env python3
"""A stand-in for Featherless, for tests and for checking the page without a key.

It only looks for keywords. Its output is never evidence of how a real model performs, so never
use it for accuracy numbers or in the demo.

    python3 tests/fake_featherless.py 9911
"""
from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dashboard import QuickServer  # noqa: E402

CRITICAL_WORDS = ("trapped", "bleeding", "collapsed", "boat", "smoke", "बेहोश", "मलबे", "फँसे", "స్పృహ", "శిథిలాల", "చిక్కుకున్నాం", "మంటలు")
HIGH_WORDS = ("insulin", "missing", "doctor", "no food", "दवा", "पुलिस", "కనిపించడం", "డాక్టర్")
LOW_WORDS = ("safe", "सुरक्षित", "సురక్షితంగా")


def classify(text: str) -> Dict[str, Any]:
    lowered = text.lower()
    if any(word in lowered for word in CRITICAL_WORDS):
        urgency, category = "critical", "trapped"
    elif any(word in lowered for word in HIGH_WORDS):
        urgency, category = "high", "medical"
    elif any(word in lowered for word in LOW_WORDS):
        urgency, category = "low", "safe_update"
    else:
        urgency, category = "medium", "supplies"
    reply: Dict[str, Any] = {
        "category": category,
        "urgency": urgency,
        "confidence": 0.82,
        "reason": "Fake model: matched a keyword in the message.",
    }
    if len(text) > 200:
        reply["key_facts"] = {
            "people": "Fake model: people named in the message",
            "place": "Fake model: place named in the message",
            "injuries": "",
            "needs": "Fake model: what they asked for",
            "deadline": "",
            "personal_note": "",
        }
    return reply


def default_reply(system: str, message: str) -> str:
    if "Translate the message" in system:
        return json.dumps({"translation": "[Fake translation] " + message})
    return json.dumps(classify(message))


class FakeFeatherless:
    """Records every request, so a test can check exactly what would leave the laptop."""

    def __init__(self, port: int = 0, reply: Callable[[str, str], str] = default_reply) -> None:
        self.reply = reply
        self.status = 200
        self.requests: List[Dict[str, Any]] = []
        self.headers: List[Any] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:
                pass

            def do_POST(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fake.requests.append(body)
                fake.headers.append(self.headers)
                if fake.status != 200:
                    self.send_error(fake.status)
                    return
                system = body["messages"][0]["content"]
                message = json.loads(body["messages"][1]["content"])["message"]
                content = fake.reply(system, message)
                data = json.dumps({"choices": [{"message": {"content": content}}]}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = QuickServer(("127.0.0.1", port), Handler)
        self.base_url = "http://127.0.0.1:%d/v1" % self.server.server_port
        self._thread: Optional[threading.Thread] = None

    def start(self) -> "FakeFeatherless":
        self._thread = threading.Thread(target=self.server.serve_forever, args=(0.01,), daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()


if __name__ == "__main__":
    fake = FakeFeatherless(int(sys.argv[1]))
    print(fake.base_url)
    fake.server.serve_forever()

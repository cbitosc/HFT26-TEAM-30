"""Keeps alerts on this laptop in SQLite, so a restart doesn't lose them."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional

from triage import URGENCIES

MAX_TEXT_CHARS = 2000
MAX_LABEL_CHARS = 64
PRIORITIES = ("P0", "P1", "P2")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS alerts (
    id TEXT PRIMARY KEY,
    sender TEXT NOT NULL,
    priority TEXT NOT NULL,
    text TEXT NOT NULL,
    hops INTEGER NOT NULL,
    latitude REAL,
    longitude REAL,
    created_at REAL NOT NULL,
    source TEXT NOT NULL,
    analysis TEXT,
    analysis_state TEXT NOT NULL DEFAULT 'waiting',
    translations TEXT NOT NULL DEFAULT '{}',
    override TEXT,
    reviewed INTEGER NOT NULL DEFAULT 0
)
"""


class InvalidAlert(ValueError):
    pass


def _coordinate(value: Any, limit: float) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or abs(value) > limit:
        raise InvalidAlert("Invalid location")
    return float(value)


def clean_alert(raw: Any, source: str, now: Optional[float] = None) -> Dict[str, Any]:
    """Uploads come from the network, so nothing in them is trusted until it passes here."""
    if not isinstance(raw, dict):
        raise InvalidAlert("Each alert must be an object")
    alert_id, text = raw.get("id"), raw.get("text")
    if not isinstance(alert_id, str) or not 0 < len(alert_id) <= MAX_LABEL_CHARS:
        raise InvalidAlert("Invalid alert ID")
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
        raise InvalidAlert("Alert text is empty or too long")
    priority = raw.get("priority")
    if priority not in PRIORITIES:
        raise InvalidAlert("Invalid priority")
    sender = raw.get("sender")
    sender = sender if isinstance(sender, str) and sender.strip() else "Unknown phone"
    hops = raw.get("hops", 0)
    if isinstance(hops, bool) or not isinstance(hops, int) or not 0 <= hops <= 64:
        raise InvalidAlert("Invalid hop count")
    created_at = raw.get("created_at", now if now is not None else time.time())
    if isinstance(created_at, bool) or not isinstance(created_at, (int, float)) or created_at <= 0:
        raise InvalidAlert("Invalid time")
    return {
        "id": alert_id,
        "sender": sender.strip()[:MAX_LABEL_CHARS],
        "priority": priority,
        "text": text,
        "hops": hops,
        "latitude": _coordinate(raw.get("latitude"), 90),
        "longitude": _coordinate(raw.get("longitude"), 180),
        "created_at": float(created_at),
        "source": source,
    }


class AlertStore:
    def __init__(self, path: str) -> None:
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._db:
            self._db.execute(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def add(self, alert: Dict[str, Any]) -> bool:
        """False means this alert ID is already stored, so a repeat upload changes nothing."""
        with self._lock, self._db:
            cursor = self._db.execute(
                """INSERT OR IGNORE INTO alerts
                   (id, sender, priority, text, hops, latitude, longitude, created_at, source)
                   VALUES (:id, :sender, :priority, :text, :hops, :latitude, :longitude,
                           :created_at, :source)""",
                alert,
            )
            return cursor.rowcount == 1

    def all(self) -> List[Dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM alerts").fetchall()
        return [self._to_alert(row) for row in rows]

    def get(self, alert_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            row = self._db.execute("SELECT * FROM alerts WHERE id = ?", (alert_id,)).fetchone()
        return self._to_alert(row) if row else None

    def waiting_ids(self) -> List[str]:
        with self._lock:
            rows = self._db.execute(
                "SELECT id FROM alerts WHERE analysis_state = 'waiting' ORDER BY created_at"
            ).fetchall()
        return [row["id"] for row in rows]

    def save_analysis(self, alert_id: str, analysis: Dict[str, Any]) -> None:
        self._update(alert_id, "analysis = ?, analysis_state = 'done'", json.dumps(analysis))

    def mark_analysis_failed(self, alert_id: str) -> None:
        self._update(alert_id, "analysis_state = 'failed'")

    def mark_analysis_waiting(self, alert_id: str) -> None:
        self._update(alert_id, "analysis_state = 'waiting'")

    def set_override(self, alert_id: str, urgency: Optional[str]) -> None:
        if urgency is not None and urgency not in URGENCIES:
            raise InvalidAlert("Invalid urgency")
        self._update(alert_id, "override = ?", urgency)

    def set_reviewed(self, alert_id: str, reviewed: bool) -> None:
        self._update(alert_id, "reviewed = ?", 1 if reviewed else 0)

    def save_translation(self, alert_id: str, language: str, translation: str) -> None:
        with self._lock, self._db:
            row = self._db.execute(
                "SELECT translations FROM alerts WHERE id = ?", (alert_id,)
            ).fetchone()
            if row is None:
                return
            translations = json.loads(row["translations"])
            translations[language] = translation
            self._db.execute(
                "UPDATE alerts SET translations = ? WHERE id = ?",
                (json.dumps(translations, ensure_ascii=False), alert_id),
            )

    def clear_samples(self) -> int:
        with self._lock, self._db:
            return self._db.execute("DELETE FROM alerts WHERE source = 'sample'").rowcount

    def _update(self, alert_id: str, assignment: str, *values: Any) -> None:
        with self._lock, self._db:
            self._db.execute(
                "UPDATE alerts SET %s WHERE id = ?" % assignment, (*values, alert_id)
            )

    @staticmethod
    def _to_alert(row: sqlite3.Row) -> Dict[str, Any]:
        alert = dict(row)
        alert["analysis"] = json.loads(alert["analysis"]) if alert["analysis"] else None
        alert["translations"] = json.loads(alert["translations"])
        alert["reviewed"] = bool(alert["reviewed"])
        return alert

"""Lets this laptop act as one more neighbor on the mesh.

A laptop can't run Nearby Connections, so phones reach it over Wi-Fi. It stores each packet and
passes it to the other phones, which can then carry messages between phones that are out of radio
range of each other. It holds no keys, so it can't read or change what it carries: every phone still
checks each packet's signature against the original sender.
"""
from __future__ import annotations

import base64
import binascii
import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional, Tuple

MAX_PACKET_BYTES = 32 * 1024
MAX_STORED_PACKETS = 5000
MAX_RETENTION_SECONDS = 25 * 60 * 60
MAX_ID_CHARS = 64
FETCH_LIMIT = 50
PHONE_ACTIVE_SECONDS = 30

_SCHEMA = """
CREATE TABLE IF NOT EXISTS mesh_packets (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    sender TEXT NOT NULL,
    data BLOB NOT NULL,
    expires_at REAL NOT NULL
)
"""


class InvalidPacket(ValueError):
    pass


class NodeFull(Exception):
    """The phone keeps its copy and tries again, so nothing is lost."""


def clean_packet(raw: Any, now: float) -> Dict[str, Any]:
    """Phones can only be trusted to be on the network, so everything here is checked."""
    if not isinstance(raw, dict):
        raise InvalidPacket("Send an object")
    device, packet_id = raw.get("device"), raw.get("id")
    for value in (device, packet_id):
        if not isinstance(value, str) or not 0 < len(value) <= MAX_ID_CHARS:
            raise InvalidPacket("Invalid ID")
    expires_at = raw.get("expires_at")
    if isinstance(expires_at, bool) or not isinstance(expires_at, (int, float)):
        raise InvalidPacket("Invalid expiry")
    if expires_at <= now:
        raise InvalidPacket("This packet has expired")
    encoded = raw.get("data")
    if not isinstance(encoded, str):
        raise InvalidPacket("Invalid packet")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        raise InvalidPacket("Invalid packet") from None
    if not 0 < len(data) <= MAX_PACKET_BYTES:
        raise InvalidPacket("Invalid packet size")
    return {
        "device": device,
        "id": packet_id,
        "data": data,
        "expires_at": min(float(expires_at), now + MAX_RETENTION_SECONDS),
    }


class MeshNode:
    def __init__(self, path: str) -> None:
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._last_seen: Dict[str, float] = {}
        with self._db:
            self._db.execute(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def store(self, packet: Dict[str, Any], now: Optional[float] = None) -> bool:
        """False means the node already holds this packet, which is still a success for a phone."""
        now = time.time() if now is None else now
        with self._lock, self._db:
            self._prune(now)
            self._last_seen[packet["device"]] = now
            known = self._db.execute(
                "SELECT 1 FROM mesh_packets WHERE id = ?", (packet["id"],)
            ).fetchone()
            if known:
                return False
            if self._count() >= MAX_STORED_PACKETS:
                raise NodeFull()
            self._db.execute(
                "INSERT INTO mesh_packets (id, sender, data, expires_at) VALUES (?, ?, ?, ?)",
                (packet["id"], packet["device"], packet["data"], packet["expires_at"]),
            )
            return True

    def fetch(self, device: str, after: int, now: Optional[float] = None) -> Tuple[int, List[bytes]]:
        """Returns the next cursor, and packets for this phone that it didn't hand in itself."""
        now = time.time() if now is None else now
        with self._lock, self._db:
            self._prune(now)
            self._last_seen[device] = now
            newest = self._db.execute("SELECT seq FROM sqlite_sequence WHERE name = 'mesh_packets'").fetchone()
            newest_seq = newest["seq"] if newest else 0
            # A cursor past the newest packet means this node lost its data and started again.
            start = 0 if after > newest_seq else max(after, 0)
            rows = self._db.execute(
                "SELECT seq, sender, data FROM mesh_packets WHERE seq > ? ORDER BY seq LIMIT ?",
                (start, FETCH_LIMIT),
            ).fetchall()
        cursor = rows[-1]["seq"] if rows else start
        return cursor, [bytes(row["data"]) for row in rows if row["sender"] != device]

    def stats(self, now: Optional[float] = None) -> Dict[str, int]:
        now = time.time() if now is None else now
        with self._lock, self._db:
            self._prune(now)
            phones = sum(now - seen <= PHONE_ACTIVE_SECONDS for seen in self._last_seen.values())
            return {"phones": phones, "packets": self._count()}

    def _count(self) -> int:
        return self._db.execute("SELECT COUNT(*) AS total FROM mesh_packets").fetchone()["total"]

    def _prune(self, now: float) -> None:
        self._db.execute("DELETE FROM mesh_packets WHERE expires_at <= ?", (now,))


def node_reply(cursor: int, packets: List[bytes]) -> bytes:
    """Plain lines, so a phone needs no JSON library: the cursor, then one packet per line."""
    lines = [str(cursor)] + [base64.b64encode(packet).decode("ascii") for packet in packets]
    return ("\n".join(lines) + "\n").encode("ascii")

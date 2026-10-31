"""Decides the review order. A person's choice always wins, and AI can never bury an SOS."""
from __future__ import annotations

from typing import Any, Dict, List

URGENCIES = ("critical", "high", "medium", "low")
LOW_CONFIDENCE = 0.6

_LEVEL = {name: index for index, name in enumerate(URGENCIES)}
_SENDER_URGENCY = {"P0": "critical", "P1": "high", "P2": "medium"}
_PRIORITY_ORDER = {"P0": 0, "P1": 1, "P2": 2}


def assess(alert: Dict[str, Any]) -> Dict[str, str]:
    """Returns the urgency to rank by, who set it, and why."""
    override = alert.get("override")
    if override:
        return {"urgency": override, "source": "person", "reason": "Set by a person"}

    analysis = alert.get("analysis")
    if not analysis:
        return {
            "urgency": _SENDER_URGENCY[alert["priority"]],
            "source": "sender",
            "reason": "No AI result, so this uses the sender's priority",
        }

    level = _LEVEL[analysis["urgency"]]
    notes = [analysis["reason"]]
    if analysis["confidence"] < LOW_CONFIDENCE and level > 0:
        level -= 1
        notes.append("Raised one level because AI confidence is low")
    if alert["priority"] == "P0" and level > _LEVEL["high"]:
        level = _LEVEL["high"]
        notes.append("Kept at high because the sender raised an SOS")
    return {"urgency": URGENCIES[level], "source": "ai", "reason": " ".join(notes)}


def rank(alerts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Unreviewed alerts first, then urgency, then sender priority, then whoever waited longest."""
    assessed = [{**alert, **{"assessment": assess(alert)}} for alert in alerts]
    assessed.sort(key=lambda alert: (
        bool(alert.get("reviewed")),
        _LEVEL[alert["assessment"]["urgency"]],
        _PRIORITY_ORDER[alert["priority"]],
        alert["created_at"],
    ))
    return [{**alert, "rank": position} for position, alert in enumerate(assessed, start=1)]

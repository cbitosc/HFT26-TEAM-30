#!/usr/bin/env python3
"""Scores models against the labelled sample alerts. It needs a Featherless key.

    python3 evaluate.py
    python3 evaluate.py --models sarvamai/sarvam-m,Qwen/Qwen2.5-72B-Instruct

A missed critical alert matters more than overall accuracy, so it gets its own line.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, List

from assistant import DEFAULT_BASE_URL, DEFAULT_MODEL, Assistant, AssistantUnavailable
from dashboard import HERE, load_env
from triage import assess


def score(results: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Each result holds the sample and its analysis, or None if the call failed."""
    answered = [result for result in results if result["analysis"] is not None]
    critical = [r for r in results if r["sample"]["expected_urgency"] == "critical"]
    missed = []
    for result in critical:
        analysis = result["analysis"]
        alert = {"priority": result["sample"]["priority"], "analysis": analysis}
        if analysis is None or assess(alert)["urgency"] != "critical":
            missed.append(result["sample"]["id"])
    return {
        "total": len(results),
        "answered": len(answered),
        "urgency_correct": sum(
            r["analysis"]["urgency"] == r["sample"]["expected_urgency"] for r in answered
        ),
        "category_correct": sum(
            r["analysis"]["category"] == r["sample"]["expected_category"] for r in answered
        ),
        "critical_total": len(critical),
        "missed_critical": missed,
    }


def evaluate(assistant: Assistant, samples: List[Dict[str, Any]]) -> Dict[str, Any]:
    results = []
    for sample in samples:
        try:
            analysis = assistant.analyze(sample["text"])
        except AssistantUnavailable as failure:
            print("  %s failed: %s" % (sample["id"], failure), file=sys.stderr)
            analysis = None
        results.append({"sample": sample, "analysis": analysis})
    return score(results)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", default=None, help="comma-separated Featherless model IDs")
    options = parser.parse_args()

    settings = {**load_env(HERE / ".env"), **os.environ}
    key = settings.get("FEATHERLESS_API_KEY")
    if not key:
        sys.exit("Add FEATHERLESS_API_KEY to src/dashboard/.env first.")
    models = (options.models or settings.get("FEATHERLESS_MODEL") or DEFAULT_MODEL).split(",")
    samples = json.loads((HERE / "sample_alerts.json").read_text(encoding="utf-8"))
    base_url = settings.get("FEATHERLESS_BASE_URL") or DEFAULT_BASE_URL

    for model in models:
        print(model.strip())
        result = evaluate(Assistant(key, model=model.strip(), base_url=base_url), samples)
        print("  Answered:          %d of %d" % (result["answered"], result["total"]))
        print("  Urgency correct:   %d of %d" % (result["urgency_correct"], result["answered"]))
        print("  Category correct:  %d of %d" % (result["category_correct"], result["answered"]))
        print("  Missed critical:   %d of %d %s" % (
            len(result["missed_critical"]), result["critical_total"], result["missed_critical"] or "",
        ))


if __name__ == "__main__":
    main()

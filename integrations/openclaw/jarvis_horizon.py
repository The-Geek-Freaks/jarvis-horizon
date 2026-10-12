#!/usr/bin/env python3
"""Capability-gated Jarvis Horizon calls plus an idempotent routine cache.

The OpenClaw CLI is the transport and its parsed result remains authoritative.
This helper never retries an uncertain action.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any

CLI_TIMEOUT_SECONDS = 20
ALLOWED_ACTIONS = {
    "jarvis.display": {"list", "open", "fullscreen", "minimize", "close", "status"},
    "jarvis.ui": set(),
    "jarvis.media": {"status", "play", "pause", "next", "previous", "volume"},
    "jarvis.schedule": {"set_timer", "set_alarm", "list", "status", "cancel", "stop", "snooze", "pause", "resume", "extend"},
}
STEP_COMMAND = {
    "jarvis_display": "jarvis.display",
    "jarvis_ui": "jarvis.ui",
    "jarvis_media": "jarvis.media",
    "jarvis_schedule": "jarvis.schedule",
}
BAD_STATES = {"error", "failed", "unknown", "unavailable", "queued", "accepted", "requested", "connecting", "pending", "running"}
GOOD_STATES = {"ok", "completed", "succeeded", "live", "snapshot", "scheduled", "cancelled", "closed"}


class HorizonError(RuntimeError):
    pass


def emit(value: dict[str, Any]) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True))


def normalize_alias(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower().removeprefix("jarvis ").rstrip(".!?")).strip()


def parse_object(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as exc:
        raise HorizonError(f"invalid JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise HorizonError("params must be a JSON object")
    return parsed


def validate_command(command: str, params: dict[str, Any]) -> None:
    if command not in ALLOWED_ACTIONS:
        raise HorizonError("command is outside this integration allowlist")
    if command == "jarvis.ui":
        if params.get("t") != "ui" or "show" not in params:
            raise HorizonError("jarvis.ui requires t=ui and show")
        return
    action = params.get("action")
    if not isinstance(action, str) or action not in ALLOWED_ACTIONS[command]:
        raise HorizonError(f"unsupported {command} action")
    if command == "jarvis.media" and action == "volume":
        volume = params.get("volume")
        if not isinstance(volume, (int, float)) or not 0 <= volume <= 1:
            raise HorizonError("volume must be 0 through 1")


def run_cli(arguments: list[str], binary: str) -> Any:
    try:
        result = subprocess.run([binary, *arguments], capture_output=True, text=True, timeout=CLI_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as exc:
        raise HorizonError("openclaw CLI timed out; outcome is unknown") from exc
    if result.returncode:
        raise HorizonError((result.stderr or result.stdout or "openclaw CLI failed").strip())
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise HorizonError("openclaw CLI did not return JSON") from exc


def advertised_commands(node_id: str, binary: str) -> set[str]:
    """Read the selected node effective command surface from describe."""
    detail = run_cli(
        ["nodes", "describe", "--node", node_id, "--json"],
        binary,
    )
    if not isinstance(detail, dict) or detail.get("nodeId") != node_id:
        raise HorizonError("nodes describe did not return the selected node")
    if detail.get("connected") is not True:
        raise HorizonError("target node is not connected")
    approval = detail.get("approvalState")
    if approval not in (None, "approved"):
        raise HorizonError("target node does not have an approved command surface")
    commands = detail.get("commands")
    if not isinstance(commands, list) or not all(
        isinstance(item, str) for item in commands
    ):
        raise HorizonError("nodes describe has no valid advertised command list")
    return set(commands)

def classify_result(value: Any) -> tuple[bool, str]:
    """Require explicit terminal success; outer CLI success alone is insufficient."""
    if not isinstance(value, dict):
        return False, "non-object result"
    if value.get("ok") is False or value.get("success") is False or value.get("error") not in (None, False, ""):
        return False, "explicit error"
    # The transport envelope can be successful while the node payload reports
    # a queued or otherwise non-terminal operation. The payload decides first.
    payload = value.get("payload")
    if isinstance(payload, dict):
        return classify_result(payload)
    for key in ("status", "state", "lifecycle"):
        raw = value.get(key)
        if isinstance(raw, str):
            state = raw.lower()
            if state in BAD_STATES:
                return False, state
            if state in GOOD_STATES:
                return True, state
    if value.get("ok") is True or value.get("success") is True:
        return True, "ok"
    return False, "missing terminal success"


def invoke(node: str, command: str, params: dict[str, Any], binary: str) -> dict[str, Any]:
    validate_command(command, params)
    if command not in advertised_commands(node, binary):
        return {"status": "unavailable", "node": node, "command": command, "reason": "not_advertised"}
    result = run_cli([
        "nodes", "invoke", "--node", node, "--command", command,
        "--params", json.dumps(params, separators=(",", ":")),
        "--invoke-timeout", "15000", "--json",
    ], binary)
    confirmed, outcome = classify_result(result)
    return {
        "status": "confirmed" if confirmed else "unknown",
        "node": node,
        "command": command,
        "outcome": outcome,
        "result": result,
    }


def open_db(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE IF NOT EXISTS plans(id TEXT PRIMARY KEY, plan TEXT NOT NULL, updated_ms INTEGER NOT NULL)")
    connection.execute("CREATE TABLE IF NOT EXISTS receipts(request_id TEXT PRIMARY KEY, receipt TEXT NOT NULL, created_ms INTEGER NOT NULL)")
    return connection


def validate_plan(plan: dict[str, Any]) -> dict[str, Any]:
    if plan.get("version") != 1 or not isinstance(plan.get("id"), str) or not plan["id"].strip():
        raise HorizonError("plan requires version=1 and non-empty id")
    aliases, steps = plan.get("aliases"), plan.get("steps")
    if not isinstance(aliases, list) or not aliases or not all(isinstance(alias, str) and normalize_alias(alias) for alias in aliases):
        raise HorizonError("plan requires aliases")
    if not isinstance(steps, list) or not 1 <= len(steps) <= 8:
        raise HorizonError("plan requires 1 through 8 steps")
    for step in steps:
        if not isinstance(step, dict) or step.get("type") not in STEP_COMMAND or not isinstance(step.get("params"), dict):
            raise HorizonError("routine step invalid")
        validate_command(STEP_COMMAND[step["type"]], step["params"])
    return plan


def all_plans(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    return [json.loads(row[0]) for row in connection.execute("SELECT plan FROM plans ORDER BY id")]


def remember(connection: sqlite3.Connection, plan: dict[str, Any]) -> dict[str, Any]:
    plan = validate_plan(plan)
    connection.execute(
        "INSERT INTO plans VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET plan=excluded.plan, updated_ms=excluded.updated_ms",
        (plan["id"], json.dumps(plan, separators=(",", ":")), int(time.time() * 1000)),
    )
    connection.commit()
    return {"status": "remembered", "id": plan["id"]}


def resolve(connection: sqlite3.Connection, text: str) -> dict[str, Any]:
    alias = normalize_alias(text)
    matches = [plan for plan in all_plans(connection) if alias in {normalize_alias(item) for item in plan["aliases"]}]
    if len(matches) == 1:
        return {"status": "resolved", "plan": matches[0]}
    return {"status": "unknown" if not matches else "ambiguous", "alias": alias}


def get_receipt(connection: sqlite3.Connection, request_id: str) -> dict[str, Any] | None:
    row = connection.execute("SELECT receipt FROM receipts WHERE request_id=?", (request_id,)).fetchone()
    return json.loads(row[0]) if row else None


def persist_receipt(connection: sqlite3.Connection, request_id: str, value: dict[str, Any]) -> None:
    connection.execute("UPDATE receipts SET receipt=? WHERE request_id=?", (json.dumps(value, separators=(",", ":")), request_id))
    connection.commit()


def run_routine(connection: sqlite3.Connection, text: str, request_id: str, node: str, binary: str) -> dict[str, Any]:
    try:
        uuid.UUID(request_id)
    except ValueError as exc:
        raise HorizonError("request-id must be UUID") from exc
    prior = get_receipt(connection, request_id)
    if prior is not None:
        return prior
    resolved = resolve(connection, text)
    if resolved["status"] != "resolved":
        value = {"status": resolved["status"], "requestId": request_id}
        connection.execute("INSERT INTO receipts VALUES(?,?,?)", (request_id, json.dumps(value), int(time.time() * 1000)))
        connection.commit()
        return value
    value: dict[str, Any] = {"status": "running", "requestId": request_id, "planId": resolved["plan"]["id"], "steps": []}
    # Reserve receipt before the first side effect. Any exception settles as unknown and cannot replay.
    connection.execute("INSERT INTO receipts VALUES(?,?,?)", (request_id, json.dumps(value), int(time.time() * 1000)))
    connection.commit()
    try:
        for index, step in enumerate(resolved["plan"]["steps"]):
            result = invoke(node, STEP_COMMAND[step["type"]], step["params"], binary)
            value["steps"].append({"index": index, "type": step["type"], "result": result})
            if result["status"] != "confirmed":
                value["status"] = "stopped"
                persist_receipt(connection, request_id, value)
                return value
    except (HorizonError, OSError) as exc:
        value["status"] = "unknown"
        value["error"] = str(exc)
        persist_receipt(connection, request_id, value)
        return value
    value["status"] = "completed"
    persist_receipt(connection, request_id, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--openclaw-bin", default="openclaw")
    commands = parser.add_subparsers(dest="mode", required=True)
    capabilities = commands.add_parser("capabilities")
    capabilities.add_argument("--node", required=True)
    call = commands.add_parser("invoke")
    call.add_argument("--node", required=True); call.add_argument("--command", required=True); call.add_argument("--params", required=True)
    routine = commands.add_parser("routine"); routine_commands = routine.add_subparsers(dest="routine", required=True)
    for name in ("list", "remember", "resolve", "invoke", "status"):
        item = routine_commands.add_parser(name); item.add_argument("--db", required=True)
        if name == "remember": item.add_argument("--plan-file", required=True)
        if name == "resolve": item.add_argument("--text", required=True)
        if name == "invoke": item.add_argument("--text", required=True); item.add_argument("--request-id", required=True); item.add_argument("--node", required=True)
        if name == "status": item.add_argument("--request-id", required=True)
    args = parser.parse_args()
    try:
        if args.mode == "capabilities":
            emit({"status": "ok", "node": args.node, "commands": sorted(advertised_commands(args.node, args.openclaw_bin))})
        elif args.mode == "invoke":
            emit(invoke(args.node, args.command, parse_object(args.params), args.openclaw_bin))
        else:
            connection = open_db(Path(args.db))
            if args.routine == "list": emit({"status": "ok", "plans": all_plans(connection)})
            elif args.routine == "remember": emit(remember(connection, parse_object(Path(args.plan_file).read_text(encoding="utf-8"))))
            elif args.routine == "resolve": emit(resolve(connection, args.text))
            elif args.routine == "status": emit(get_receipt(connection, args.request_id) or {"status": "not_found", "requestId": args.request_id})
            else: emit(run_routine(connection, args.text, args.request_id, args.node, args.openclaw_bin))
    except HorizonError as exc:
        emit({"status": "error", "error": str(exc)})
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

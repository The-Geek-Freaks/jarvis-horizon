import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location(
    "jarvis_horizon",
    ROOT / "jarvis_horizon.py",
)
assert SPEC is not None
assert SPEC.loader is not None
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)


class JarvisHorizonTests(unittest.TestCase):
    def test_normalizes_only_wake_prefix_and_punctuation(self):
        self.assertEqual(
            H.normalize_alias(" Jarvis  Show Weather! "),
            "show weather",
        )

    def test_allowlist_rejects_unrelated_command(self):
        with self.assertRaises(H.HorizonError):
            H.validate_command("system.notify", {})

    def test_describe_reads_effective_inventory(self):
        calls = []
        original = H.run_cli

        def fake_cli(arguments, binary):
            calls.append(arguments)
            if arguments == [
                "nodes",
                "describe",
                "--node",
                "fixture-node",
                "--json",
            ]:
                return {
                    "nodeId": "fixture-node",
                    "commands": ["jarvis.ui"],
                    "approvalState": "approved",
                    "connected": True,
                }
            raise AssertionError(arguments)

        H.run_cli = fake_cli
        try:
            self.assertEqual(
                H.advertised_commands("fixture-node", "openclaw"),
                {"jarvis.ui"},
            )
        finally:
            H.run_cli = original
        self.assertEqual(
            calls,
            [["nodes", "describe", "--node", "fixture-node", "--json"]],
        )

    def test_pinned_describe_pending_reapproval_is_rejected(self):
        detail = json.loads(
            (
                ROOT
                / "fixtures"
                / "pinned-nodes-describe-pending-reapproval.json"
            ).read_text(encoding="utf-8")
        )
        original = H.run_cli

        def fake_cli(arguments, binary):
            if arguments == [
                "nodes",
                "describe",
                "--node",
                "ios-node",
                "--json",
            ]:
                return detail
            raise AssertionError(arguments)

        H.run_cli = fake_cli
        try:
            with self.assertRaisesRegex(
                H.HorizonError,
                "approved command surface",
            ):
                H.advertised_commands("ios-node", "openclaw")
        finally:
            H.run_cli = original

    def test_nested_requested_is_unknown_even_with_outer_ok(self):
        self.assertEqual(
            H.classify_result(
                {"ok": True, "payload": {"lifecycle": "connecting"}}
            ),
            (False, "connecting"),
        )

    def test_nested_pending_overrides_outer_terminal_status(self):
        self.assertEqual(
            H.classify_result(
                {
                    "ok": True,
                    "status": "ok",
                    "payload": {"status": "accepted"},
                }
            ),
            (False, "accepted"),
        )

    def test_nested_error_is_unknown_even_with_outer_ok(self):
        self.assertEqual(
            H.classify_result(
                {"ok": True, "payload": {"error": "node rejected command"}}
            ),
            (False, "explicit error"),
        )

    def test_repeat_request_is_not_replayed(self):
        plan = {
            "version": 1,
            "id": "weather",
            "aliases": ["show weather"],
            "steps": [
                {
                    "type": "jarvis_ui",
                    "params": {"t": "ui", "show": "weather"},
                }
            ],
        }
        with tempfile.TemporaryDirectory():
            connection = H.open_db(Path(":memory:"))
            self.addCleanup(connection.close)
            H.remember(connection, plan)
            calls = []
            original = H.invoke
            H.invoke = (
                lambda *args: calls.append(args)
                or {"status": "confirmed", "result": {"ok": True}}
            )
            try:
                request_id = "0f974e1b-24c8-4c04-bda1-c36660f42a1d"
                first = H.run_routine(
                    connection,
                    "show weather",
                    request_id,
                    "node-demo.invalid",
                    "openclaw",
                )
                second = H.run_routine(
                    connection,
                    "show weather",
                    request_id,
                    "node-demo.invalid",
                    "openclaw",
                )
            finally:
                H.invoke = original
            self.assertEqual(first, second)
            self.assertEqual(len(calls), 1)

    def test_unknown_result_stops_chain(self):
        plan = {
            "version": 1,
            "id": "two",
            "aliases": ["two"],
            "steps": [
                {
                    "type": "jarvis_media",
                    "params": {"action": "status"},
                },
                {
                    "type": "jarvis_schedule",
                    "params": {"action": "list"},
                },
            ],
        }
        with tempfile.TemporaryDirectory():
            connection = H.open_db(Path(":memory:"))
            self.addCleanup(connection.close)
            H.remember(connection, plan)
            calls = []
            original = H.invoke
            H.invoke = (
                lambda *args: calls.append(args)
                or {"status": "unknown", "outcome": "explicit error"}
            )
            try:
                result = H.run_routine(
                    connection,
                    "two",
                    "d01d62bd-571b-4916-8e1a-2ed6e544649f",
                    "node-demo.invalid",
                    "openclaw",
                )
            finally:
                H.invoke = original
            self.assertEqual(result["status"], "stopped")
            self.assertEqual(result["steps"][0]["result"]["outcome"], "explicit error")
            self.assertEqual(len(calls), 1)

    def test_exception_persists_unknown_without_retry(self):
        plan = {
            "version": 1,
            "id": "fail",
            "aliases": ["fail"],
            "steps": [
                {
                    "type": "jarvis_media",
                    "params": {"action": "status"},
                }
            ],
        }
        with tempfile.TemporaryDirectory():
            connection = H.open_db(Path(":memory:"))
            self.addCleanup(connection.close)
            H.remember(connection, plan)
            original = H.invoke
            H.invoke = lambda *args: (_ for _ in ()).throw(
                H.HorizonError("timeout")
            )
            try:
                request_id = "3924e8fc-72eb-4e4f-92d5-4a7b5fdf3954"
                first = H.run_routine(
                    connection,
                    "fail",
                    request_id,
                    "node-demo.invalid",
                    "openclaw",
                )
                second = H.run_routine(
                    connection,
                    "fail",
                    request_id,
                    "node-demo.invalid",
                    "openclaw",
                )
            finally:
                H.invoke = original
            self.assertEqual(first["status"], "unknown")
            self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()

import importlib.util
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("staged-app-updater.py")
SPEC = importlib.util.spec_from_file_location("staged_app_updater", MODULE_PATH)
assert SPEC and SPEC.loader
updater = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(updater)


def test_wait_for_health_accepts_a_backend_that_becomes_ready_before_deadline():
    with patch.object(updater.time, "monotonic", side_effect=(0, 0, 0, 0.5)), \
         patch.object(updater.time, "sleep") as sleep, \
         patch.object(updater, "health", side_effect=(False, True)) as health:
        assert updater.wait_for_health("http://127.0.0.1:8765/healthz")

    assert health.call_count == 2
    assert sleep.call_args_list[0].args == (updater.BACKEND_HEALTH_RETRY_SECONDS,)


def test_wait_for_health_stops_at_the_startup_deadline():
    with patch.object(updater.time, "monotonic", side_effect=(0, 0, 0, 30)), \
         patch.object(updater.time, "sleep") as sleep, \
         patch.object(updater, "health", return_value=False) as health:
        assert not updater.wait_for_health("http://127.0.0.1:8765/healthz")

    assert health.call_count == 1
    sleep.assert_called_once_with(updater.BACKEND_HEALTH_RETRY_SECONDS)

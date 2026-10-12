import asyncio
import base64
import tempfile
from pathlib import Path
from unittest.mock import AsyncMock, patch

import numpy as np

import voice
from voice import GatewayTalk, INPUT_HZ, MAX_FRAME_BYTES, VoiceError, VoiceRelay, resample_pcm16


def test_gateway_create_and_append_use_existing_talk_contract():
    emitted = AsyncMock()
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"jarvis-pi"}}, emitted)
    client.request = AsyncMock(return_value={"relaySessionId":"relay-1"})
    async def scenario():
        await client.start(); await client.append(b"\0\0" * 80); await client.close_turn()
    asyncio.run(scenario())
    assert client.request.await_args_list[0].args[0] == "talk.session.create"
    assert client.request.await_args_list[0].args[1]["brain"] == "agent-consult"
    assert client.request.await_args_list[0].args[1]["language"] == "de"
    # The browser callback only enqueues; a websocket round trip never blocks capture.
    assert client.audio_queue.empty()


def test_slow_gateway_append_fills_the_existing_bounded_capture_queue():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"jarvis-pi"}}, AsyncMock())
    blocked = asyncio.Event()

    async def request(method, _params):
        if method == "talk.session.create": return {"relaySessionId":"relay-slow"}
        if method == "talk.session.appendAudio": await blocked.wait()
        return {}

    client.request = AsyncMock(side_effect=request)
    async def scenario():
        await client.start()
        for _ in range(32): await client.append(b"\0\0" * 640)
        try: await client.append(b"\0\0" * 640)
        except VoiceError as error: assert "queue is full" in str(error)
        else: raise AssertionError("slow gateway did not fill the bounded capture queue")
        await client.close_turn()
    asyncio.run(scenario())


def test_sender_coalesces_already_queued_pcm_in_order_without_waiting_for_more_audio():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"jarvis-pi"}}, AsyncMock())
    release = asyncio.Event()
    sent = []

    async def request(method, params):
        if method == "talk.session.create": return {"relaySessionId":"relay-batch"}
        if method == "talk.session.appendAudio":
            sent.append(base64.b64decode(params["audioBase64"]))
            await release.wait()
        return {}

    client.request = AsyncMock(side_effect=request)
    source_frames = [bytes([value, 0]) * 640 for value in range(1, 7)]
    expected = b"".join(resample_pcm16(frame, INPUT_HZ, 24_000) for frame in source_frames)
    async def scenario():
        await client.start()
        for frame in source_frames: await client.append(frame)
        await asyncio.sleep(0)
        assert sent == [expected[:9_600]]
        release.set()
        for _ in range(4): await asyncio.sleep(0)
        assert sent == [expected[:9_600], expected[9_600:]]
        assert client.audio_queue.empty()
        await client.close_turn()
    asyncio.run(scenario())


def test_steady_40ms_producer_drains_exact_pcm_through_120ms_gateway_rpcs():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"jarvis-pi"}}, AsyncMock())
    sent = []

    async def request(method, params):
        if method == "talk.session.create": return {"relaySessionId":"relay-steady"}
        if method == "talk.session.appendAudio":
            sent.append(base64.b64decode(params["audioBase64"]))
            await asyncio.sleep(.12)
        return {}

    client.request = AsyncMock(side_effect=request)
    source_frames = [bytes([value, 0]) * 640 for value in range(1, 61)]
    expected = b"".join(resample_pcm16(frame, INPUT_HZ, 24_000) for frame in source_frames)
    async def scenario():
        await client.start()
        max_depth = 0
        for frame in source_frames:
            await client.append(frame)
            max_depth = max(max_depth, client.audio_queue.qsize())
            await asyncio.sleep(.04)
        await asyncio.wait_for(client.audio_queue.join(), timeout=4)
        assert b"".join(sent) == expected
        assert len(sent[0]) == 1_920, "the first frame is sent immediately rather than held for a batch"
        assert max_depth <= 6, "120-ms RPCs may backlog briefly but stay far below the unchanged 32-frame bound"
        await client.close_turn()
    asyncio.run(scenario())


def test_actual_android_talk_event_envelope_emits_pcm_and_terminal():
    emitted = AsyncMock()
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, emitted)
    client.relay_id = "relay-1"
    async def scenario():
        await client._talk_event({"relaySessionId":"relay-1", "type":"responseStarted", "turnId":"turn-1"})
        await client._talk_event({"relaySessionId":"relay-1", "type":"audio", "audioBase64":"AAE=", "talkEvent":{"turnId":"turn-1"}})
        await client._talk_event({"relaySessionId":"relay-1", "type":"audioDone", "talkEvent":{"turnId":"turn-1"}})
    asyncio.run(scenario())
    assert [call.args[0]["type"] for call in emitted.await_args_list] == ["state", "audio", "state", "audio.end"]


def test_actual_android_transcript_event_projects_only_the_active_relay_caption():
    emitted = AsyncMock()
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, emitted)
    client.relay_id = "relay-current"
    async def scenario():
        await client._talk_event({"relaySessionId":"relay-retired", "type":"transcript", "role":"assistant", "text":"stale"})
        await client._talk_event({"relaySessionId":"relay-current", "type":"transcript", "role":"user", "text":"Wie wird das Wetter?", "final":False})
        await client._talk_event({"relaySessionId":"relay-current", "type":"transcript", "role":"assistant", "text":"Sonnig.", "final":True})
        await client._talk_event({"relaySessionId":"relay-current", "type":"transcript", "role":"tool", "text":"never shown"})
    asyncio.run(scenario())
    assert [call.args[0] for call in emitted.await_args_list] == [
        {"type":"transcript", "role":"user", "text":"Wie wird das Wetter?", "final":False},
        {"type":"transcript", "role":"assistant", "text":"Sonnig.", "final":True},
    ]


def test_pcm_rejects_odd_or_unbounded_input_without_starting_gateway():
    relay = VoiceRelay({"voice":{}}, AsyncMock())
    relay.gateway.append = AsyncMock()
    for frame in (b"x", b"\0" * (MAX_FRAME_BYTES + 2)):
        try: asyncio.run(relay.handle_browser_message(None, frame))
        except VoiceError: pass
        else: raise AssertionError("invalid PCM accepted")
    relay.gateway.append.assert_not_awaited()


def test_owner_disconnect_cancels_and_closes_the_active_relay_once():
    emitted = AsyncMock()
    relay = VoiceRelay({"voice":{}}, emitted)
    relay.active = True
    relay.consult_deadline = 123.0
    relay.gateway.relay_id = "relay-owner"
    relay.gateway.cancel = AsyncMock()
    async def close_turn(): relay.gateway.relay_id = None
    relay.gateway.close_turn = AsyncMock(side_effect=close_turn)
    async def scenario():
        await relay.cancel_active()
        await relay.cancel_active()
    asyncio.run(scenario())
    relay.gateway.cancel.assert_awaited_once()
    relay.gateway.close_turn.assert_awaited_once()
    assert not relay.active and relay.consult_deadline == 0.0
    assert [call.args[0] for call in emitted.await_args_list] == [
        {"type":"audio.end"}, {"type":"state", "state":"idle"},
    ]


def test_unknown_talk_session_retires_before_close_and_never_retries_the_old_id():
    emitted = AsyncMock()
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, emitted)
    client.relay_id = "already-retired"
    async def request(method, _params):
        if method == "talk.session.cancelOutput": return {}
        raise VoiceError("UNAVAILABLE,realtime_unavailable: Unknown Talk session")
    client.request = AsyncMock(side_effect=request)
    async def scenario():
        await client.cancel()
        await client.close_turn()
        await client.close_turn()
    asyncio.run(scenario())
    assert client.relay_id is None
    assert [call.args[0] for call in client.request.await_args_list] == ["talk.session.cancelOutput", "talk.session.close"]

    client.relay_id = "retired-during-cancel"
    client.request = AsyncMock(side_effect=VoiceError("UNAVAILABLE: Unknown Talk session"))
    asyncio.run(client.cancel())
    assert client.relay_id is None
    client.request.assert_awaited_once()


def test_close_turn_keeps_non_retirement_gateway_errors_visible():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, AsyncMock())
    client.relay_id = "relay-forbidden"
    client.request = AsyncMock(side_effect=VoiceError("FORBIDDEN: operator.talk required"))
    try:
        asyncio.run(client.close_turn())
    except VoiceError as error:
        assert "FORBIDDEN" in str(error)
    else:
        raise AssertionError("non-retirement close error was swallowed")
    assert client.relay_id is None


def test_abandon_closes_the_exact_old_relay_once_and_later_cancel_targets_only_new_owner():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, AsyncMock())
    client.relay_id = "relay-slow"
    calls = []

    async def request(method, params):
        calls.append((method, params["sessionId"]))
        return {}

    client.request = AsyncMock(side_effect=request)

    async def scenario():
        await client.abandon()
        await client.cancel()  # The old relay is already retired locally.
        client.relay_id = "relay-next"
        await client.cancel()

    asyncio.run(scenario())
    assert calls == [
        ("talk.session.close", "relay-slow"),
        ("talk.session.cancelOutput", "relay-next"),
    ]
    assert client.relay_id == "relay-next"


def test_request_cancellation_removes_its_pending_future():
    client = GatewayTalk({"voice":{"gateway_url":"ws://gateway", "gateway_token":"t", "session_key":"voice-active"}}, AsyncMock())

    class Transport:
        async def send_json(self, _frame):
            await asyncio.Event().wait()

    client.ws = Transport()
    client.open = AsyncMock()

    async def scenario():
        task = asyncio.create_task(client.request("talk.session.close", {"sessionId":"relay-slow"}))
        await asyncio.sleep(0)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    asyncio.run(scenario())
    assert client.pending == {}


def test_follow_up_silence_closes_at_seven_seconds_without_touching_consult_or_output_drain():
    emitted = AsyncMock()
    relay = VoiceRelay({"voice": {}}, emitted)
    relay.active = True
    relay.gateway.relay_id = "relay-follow-up"
    relay.vad = type("SilentVad", (), {"speech": lambda _self, _pcm: False})()
    relay.gateway.close_turn = AsyncMock()
    relay.gateway.append = AsyncMock()
    relay.last_speech_at = voice.time.monotonic() - voice.FOLLOW_UP_SILENCE_SECONDS - .01

    asyncio.run(relay.handle_browser_message(None, b"\0\0" * 640))

    relay.gateway.close_turn.assert_awaited_once()
    relay.gateway.append.assert_not_awaited()
    assert relay.active is False
    assert [call.args[0] for call in emitted.await_args_list] == [{"type": "state", "state": "idle"}]


def test_slow_gateway_queue_exhaustion_recovers_the_browser_turn_with_an_error():
    emitted = AsyncMock()
    relay = VoiceRelay({"voice": {}}, emitted)
    relay.active = True
    relay.gateway.relay_id = "relay-slow"
    relay.last_speech_at = voice.time.monotonic()
    relay.vad = type("SilentVad", (), {"speech": lambda _self, _pcm: False})()
    relay.gateway.append = AsyncMock(side_effect=VoiceError("talk audio queue is full"))

    asyncio.run(relay.handle_browser_message(None, b"\0\0" * 640))

    assert relay.active is False
    assert relay.gateway.relay_id is None
    assert [call.args[0] for call in emitted.await_args_list] == [
        {"type": "error", "message": "Sprachverbindung ist zu langsam – bitte erneut versuchen"},
        {"type": "state", "state": "idle"},
    ]


def test_model_requirement_is_explicit_before_inference():
    relay = VoiceRelay({"voice":{"wake_model_dir":"Z:/jarvis/no-such-wake-model"}}, AsyncMock())
    try: relay._wake()
    except VoiceError as error: assert "incomplete" in str(error) or "not installed" in str(error)
    else: raise AssertionError("missing model accepted")


def test_wake_spotter_uses_single_native_worker_without_changing_model_options():
    captured = {}

    class FakeKeywordSpotter:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def create_stream(self):
            return object()

    class FakeSherpa:
        KeywordSpotter = FakeKeywordSpotter

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        for name in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt"):
            (directory / name).touch()
        with patch.object(voice, "sherpa_onnx", FakeSherpa):
            spotter = voice.WakeSpotter(directory)
    assert spotter.stream is not None
    assert captured == {
        "tokens": str(directory / "tokens.txt"),
        "encoder": str(directory / "encoder.onnx"),
        "decoder": str(directory / "decoder.onnx"),
        "joiner": str(directory / "joiner.onnx"),
        "keywords_file": str(directory / "keywords.txt"),
        "num_threads": 1,
        "provider": "cpu",
    }


def test_16khz_browser_pcm_is_exactly_resampled_to_android_realtime_24khz():
    source = b"\x00\x00\x00\x40\x00\x80\xff\x7f"
    output = resample_pcm16(source, 16_000, 24_000)
    assert len(output) == 12
    assert output[:2] == source[:2]
    assert output[-2:] == source[-2:]


def test_voice_diagnostics_start_empty_without_audio_or_models():
    relay = VoiceRelay({"voice": {}}, AsyncMock())
    snapshot = relay.diagnostic_snapshot()
    assert snapshot == {
        "framesReceived": 0, "bytesReceived": 0, "lastPcmAgeMs": None,
        "lastRms": 0.0, "lastPeak": 0, "wakeDetections": 0,
        "lastNativeKwsEvaluationMs": None, "maxNativeKwsEvaluationMs": 0.0,
        "activeRelay": False, "queueDepth": 0,
        "lastSpeechAgeMs": None, "consultRemainingMs": None,
        "awaitingOutputDrain": False, "lastVadSpeech": None,
    }


def test_voice_diagnostics_aggregate_pcm_and_native_kws_walltime():
    relay = VoiceRelay({"voice": {}}, AsyncMock())

    class Wake:
        def accept(self, _pcm):
            return True

    relay.wake = Wake()
    relay.gateway.start = AsyncMock()
    relay.gateway.append = AsyncMock()
    pcm = np.array([0, 3_000, -4_000, 0], dtype="<i2").tobytes()
    asyncio.run(relay.handle_browser_message(None, pcm))
    snapshot = relay.diagnostic_snapshot()
    assert snapshot["framesReceived"] == 1
    assert snapshot["bytesReceived"] == len(pcm)
    assert snapshot["lastPcmAgeMs"] is not None
    assert snapshot["lastRms"] == 2_500.0
    assert snapshot["lastPeak"] == 4_000
    assert snapshot["wakeDetections"] == 1
    assert snapshot["lastNativeKwsEvaluationMs"] is not None
    assert snapshot["maxNativeKwsEvaluationMs"] >= snapshot["lastNativeKwsEvaluationMs"]
    assert snapshot["activeRelay"] is False
    assert snapshot["lastSpeechAgeMs"] is not None
    assert snapshot["consultRemainingMs"] is None
    assert snapshot["awaitingOutputDrain"] is False
    assert snapshot["lastVadSpeech"] is None
    relay.gateway.relay_id = "opaque-test-relay"
    relay.gateway.audio_queue.put_nowait(b"queued")
    relay.active = True
    relay.vad = type("Speech", (), {"speech": lambda _self, _pcm: True})()
    asyncio.run(relay.handle_browser_message(None, pcm))
    active_snapshot = relay.diagnostic_snapshot()
    assert active_snapshot["activeRelay"] is True
    assert active_snapshot["queueDepth"] == 1
    assert active_snapshot["lastVadSpeech"] is True
    assert active_snapshot["lastSpeechAgeMs"] is not None
    relay.consult_deadline = voice.time.monotonic() + 2
    relay.awaiting_output_drain = True
    gated_snapshot = relay.diagnostic_snapshot()
    assert 0 <= gated_snapshot["consultRemainingMs"] <= 2_000
    assert gated_snapshot["awaitingOutputDrain"] is True


def test_voice_diagnostic_pcm_metrics_match_float_reference_at_pcm16_extremes():
    relay = VoiceRelay({"voice": {}}, AsyncMock())
    values = np.array([-32_768, -12_345, -1, 0, 1, 12_345, 32_767], dtype="<i2")
    pcm = values.tobytes()

    reference = values.astype(np.float64)
    relay._record_pcm(pcm)

    assert relay.last_rms == round(float(np.sqrt(np.mean(reference * reference))), 3)
    assert relay.last_peak == int(np.max(np.abs(reference)))

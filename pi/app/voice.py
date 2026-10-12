"""Local Pi voice relay: Sherpa wake detection plus the existing OpenClaw talk relay.

The browser supplies only ephemeral 16-kHz PCM.  This module never writes
audio, transcripts, or a conversation history to disk.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import math
import time
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable

import aiohttp
import numpy as np
try:  # Pi provisioning installs this; allowing import keeps non-audio checks runnable elsewhere.
    import sherpa_onnx
except ModuleNotFoundError:  # pragma: no cover - exercised by deployment diagnostics
    sherpa_onnx = None
from gateway import MIN_NODE_PROTOCOL, PROTOCOL, NodeIdentity

INPUT_HZ = 16_000
GATEWAY_INPUT_HZ = 24_000
OUTPUT_HZ = 24_000
MAX_FRAME_BYTES = INPUT_HZ * 2  # one second; normal worklet frames are far smaller
GATEWAY_FRAME_BYTES = GATEWAY_INPUT_HZ * 2 // 25
GATEWAY_BATCH_BYTES = GATEWAY_FRAME_BYTES * 5
FOLLOW_UP_SILENCE_SECONDS = 7.0
StateSink = Callable[[dict[str, Any]], Awaitable[None]]
LOG = logging.getLogger("jarvis-mirror.voice")


class VoiceError(RuntimeError):
    pass


def is_retired_talk_session(error: Exception) -> bool:
    """The gateway alone may retire a relay while cancel/close is in flight."""
    return "Unknown Talk session" in str(error)


def resample_pcm16(pcm: bytes, source_hz: int, target_hz: int) -> bytes:
    """Same mono PCM16 linear interpolation contract as Android's wake handoff."""
    if len(pcm) % 2 or source_hz <= 0 or target_hz <= 0: raise VoiceError("invalid PCM resample input")
    if source_hz == target_hz: return pcm
    source = np.frombuffer(pcm, dtype="<i2")
    if not len(source): return b""
    count = max(1, len(source) * target_hz // source_hz)
    positions = np.arange(count, dtype=np.int64) * source_hz
    left = np.minimum(positions // target_hz, len(source) - 1)
    right = np.minimum(left + 1, len(source) - 1)
    fraction = positions % target_hz
    samples = source[left].astype(np.int64) + ((source[right].astype(np.int64) - source[left].astype(np.int64)) * fraction // target_hz)
    return samples.astype("<i2").tobytes()


class GatewayTalk:
    """Small OpenClaw gateway RPC client for the established talk.session relay."""

    def __init__(self, config: dict[str, Any], emit: StateSink):
        self.config = config
        voice = config.get("voice", {})
        self.url = str(voice.get("gateway_url", config.get("gatewayUrl", ""))).strip()
        self.identity_dir = Path(str(config.get("identityDir", "/var/lib/jarvis-mirror")))
        self.token_path = self.identity_dir / "operator-talk-device-token"
        self.token = ""
        # Talk must attach to the operator-resolved active voice session; a
        # friendly fallback such as "main" would silently change its owner.
        self.session_key = str(voice.get("session_key", "")).strip()
        self.emit = emit
        self.ws: aiohttp.ClientWebSocketResponse | None = None
        self.http: aiohttp.ClientSession | None = None
        self.pending: dict[str, asyncio.Future[Any]] = {}
        self.reader: asyncio.Task[None] | None = None
        self.sender: asyncio.Task[None] | None = None
        self.audio_queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=32)
        self.output_drained = False
        self.on_talk_event: Callable[[dict[str, Any]], Awaitable[None]] | None = None
        self.on_agent_event: Callable[[str, dict[str, Any]], Awaitable[None]] | None = None
        self.relay_id: str | None = None

    async def open(self) -> None:
        if self.ws and not self.ws.closed:
            return
        if self.ws:
            await self._close_transport(self.ws)
        self.token = self.token_path.read_text().strip() if self.token_path.exists() else str(self.config.get("gatewayToken", "")).strip()
        if not self.url or not self.token or not self.session_key:
            raise VoiceError("voice gateway URL, bootstrap token, and resolved session_key are required")
        self.http = aiohttp.ClientSession()
        self.ws = await self.http.ws_connect(self.url, heartbeat=20, max_msg_size=2**20)
        await self._signed_connect()
        self.reader = asyncio.create_task(self._read(self.ws), name="jarvis-gateway-talk")

    async def _signed_connect(self) -> None:
        assert self.ws
        challenge = await self.ws.receive_json()
        if challenge.get("type") != "event" or challenge.get("event") != "connect.challenge":
            raise VoiceError("gateway did not issue connect.challenge")
        data = challenge.get("payload") or {}
        nonce, signed_at = data.get("nonce"), data.get("ts")
        if not isinstance(nonce, str) or not isinstance(signed_at, int): raise VoiceError("invalid connect.challenge")
        identity = NodeIdentity.load(self.identity_dir)
        client_id, mode, role, scopes = "node-host", "ui", "operator", ["operator.talk", "operator.read"]
        signature = "|".join(["v3", identity.device_id, client_id, mode, role, ",".join(scopes), str(signed_at), self.token, nonce, "linux", "raspberry-pi"])
        request_id = str(uuid.uuid4())
        await self.ws.send_json({"type":"req", "id":request_id, "method":"connect", "params":{
            "minProtocol":MIN_NODE_PROTOCOL, "maxProtocol":PROTOCOL,
            "client":{"id":client_id, "displayName":"Jarvis Kitchen Talk", "version":"1.0", "platform":"linux", "deviceFamily":"raspberry-pi", "mode":mode, "instanceId":identity.device_id},
            "role":role, "scopes":scopes, "caps":[], "auth":{"deviceToken":self.token} if self.token_path.exists() else {"token":self.token}, "locale":self.config.get("locale", "de-DE"), "userAgent":"jarvis-pi/1.0",
            "device":{"id":identity.device_id, "publicKey":identity.public_key_b64url(), "signature":identity.sign_b64url(signature), "signedAt":signed_at, "nonce":nonce},
        }})
        reply = await self.ws.receive_json()
        if reply.get("type") != "res" or reply.get("id") != request_id or not reply.get("ok"):
            raise VoiceError(str(reply.get("error") or "operator Talk gateway connect failed"))
        issued = ((reply.get("payload") or {}).get("auth") or {}).get("deviceToken")
        if isinstance(issued, str) and issued:
            self.identity_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
            self.token_path.write_text(issued + "\n")
            self.token_path.chmod(0o600)

    async def _read(self, transport: aiohttp.ClientWebSocketResponse) -> None:
        async for msg in transport:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            if frame.get("type") == "res":
                waiter = self.pending.pop(str(frame.get("id")), None)
                if waiter and not waiter.done():
                    if frame.get("ok"):
                        waiter.set_result(frame.get("payload") or {})
                    else:
                        waiter.set_exception(VoiceError(str(frame.get("error") or "gateway request failed")))
                continue
            if frame.get("type") == "event" and frame.get("event") == "talk.event":
                await self._talk_event(frame.get("payload") or {})
            elif frame.get("type") == "event" and frame.get("event") in {"agent", "session.tool"} and self.on_agent_event:
                payload = frame.get("payload") or {}
                if isinstance(payload, dict): await self.on_agent_event(str(frame["event"]), payload)
        # A retiring transport can finish after a fresh one has connected.  It
        # must never fail request futures owned by the new transport.
        if self.ws is transport:
            self._fail_pending(VoiceError("gateway disconnected"))

    def _fail_pending(self, error: VoiceError) -> None:
        for waiter in self.pending.values():
            if not waiter.done(): waiter.set_exception(error)
        self.pending.clear()

    async def _talk_event(self, payload: dict[str, Any]) -> None:
        event = payload
        # Android correlates every Talk event to the random relaySessionId before
        # touching its active owner.  Do the same before projecting any caption.
        event_session_id = event.get("relaySessionId") or event.get("sessionId") if isinstance(event, dict) else None
        if not isinstance(event, dict) or not self.relay_id or event_session_id != self.relay_id:
            return
        kind = event.get("type")
        if self.on_talk_event: await self.on_talk_event(event)
        if kind == "ready":
            await self.emit({"type":"state", "state":"listening"})
        elif kind == "responseStarted":
            await self.emit({"type":"state", "state":"thinking"})
        elif kind == "audio":
            audio = event.get("audioBase64")
            if isinstance(audio, str):
                await self.emit({"type":"audio", "pcmBase64":audio, "sampleRate":OUTPUT_HZ})
                await self.emit({"type":"state", "state":"speaking"})
        elif kind == "transcript":
            # Exact Android Talk envelope: role, text and final under talk.event.
            role, text = event.get("role"), event.get("text")
            if role in {"user", "assistant"} and isinstance(text, str) and text.strip():
                await self.emit({"type":"transcript", "role":role, "text":text, "final":event.get("final") is True})
        elif kind == "audioDone":
            await self.emit({"type":"audio.end"})
            self.output_drained = False

    async def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        await self.open()
        transport = self.ws
        assert transport
        request_id = str(uuid.uuid4())
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await transport.send_json({"type":"req", "id":request_id, "method":method, "params":params})
            result = await asyncio.wait_for(future, timeout=15)
            return result if isinstance(result, dict) else {}
        finally:
            if self.pending.get(request_id) is future:
                self.pending.pop(request_id, None)

    async def start(self) -> None:
        result = await self.request("talk.session.create", {"sessionKey":self.session_key, "mode":"realtime", "transport":"gateway-relay", "brain":"agent-consult", "language":"de"})
        self.relay_id = str(result.get("relaySessionId") or result.get("sessionId") or "") or None
        if not self.relay_id: raise VoiceError("talk.session.create returned no session id")
        self.sender = asyncio.create_task(self._send_audio(), name="jarvis-talk-audio")

    async def _send_audio(self) -> None:
        while True:
            batch = [await self.audio_queue.get()]
            try:
                total = len(batch[0])
                while total < GATEWAY_BATCH_BYTES:
                    try: pcm = self.audio_queue.get_nowait()
                    except asyncio.QueueEmpty: break
                    batch.append(pcm); total += len(pcm)
                if self.relay_id:
                    await self.request("talk.session.appendAudio", {"sessionId":self.relay_id, "audioBase64":base64.b64encode(b"".join(batch)).decode("ascii"), "timestamp":round(time.monotonic() * 1000)})
            finally:
                for _ in batch: self.audio_queue.task_done()

    async def append(self, pcm: bytes) -> None:
        if not self.relay_id: raise VoiceError("talk relay is not active")
        resampled = resample_pcm16(pcm, INPUT_HZ, GATEWAY_INPUT_HZ)
        chunks = [resampled[offset:offset + GATEWAY_FRAME_BYTES] for offset in range(0, len(resampled), GATEWAY_FRAME_BYTES)]
        try:
            if self.audio_queue.qsize() + len(chunks) > self.audio_queue.maxsize: raise asyncio.QueueFull()
            for chunk in chunks: self.audio_queue.put_nowait(chunk)
        except asyncio.QueueFull as error: raise VoiceError("talk audio queue is full") from error

    async def cancel(self) -> None:
        if self.relay_id:
            try:
                await self.request("talk.session.cancelOutput", {"sessionId":self.relay_id})
            except VoiceError as error:
                if not is_retired_talk_session(error): raise
                self.relay_id = None

    async def close_turn(self) -> None:
        if self.sender:
            self.sender.cancel()
            await asyncio.gather(self.sender, return_exceptions=True)
            self.sender = None
        while not self.audio_queue.empty():
            self.audio_queue.get_nowait(); self.audio_queue.task_done()
        # Retire before the RPC. cancelOutput or the gateway itself may have
        # already closed this relay; never let a retry target the old ID.
        relay_id, self.relay_id = self.relay_id, None
        if relay_id:
            try:
                await self.request("talk.session.close", {"sessionId":relay_id})
            except VoiceError as error:
                if not is_retired_talk_session(error): raise

    async def _close_transport(self, expected: aiohttp.ClientWebSocketResponse | None = None) -> None:
        """Close only the transport that owned a retired relay, never a newer one."""
        transport = expected or self.ws
        if transport is None or self.ws is not transport:
            return
        http, reader = self.http, self.reader
        self.ws = None
        self.http = None
        self.reader = None
        self._fail_pending(VoiceError("gateway transport retired"))
        if reader and reader is not asyncio.current_task():
            reader.cancel()
        try:
            await transport.close()
        except Exception:
            LOG.warning("failed to close retired gateway websocket", exc_info=True)
        if http:
            try:
                await http.close()
            except Exception:
                LOG.warning("failed to close retired gateway HTTP session", exc_info=True)
        if reader and reader is not asyncio.current_task():
            await asyncio.gather(reader, return_exceptions=True)

    async def abandon(self) -> None:
        """Retire a slow relay before a later wake can acquire a fresh owner."""
        if self.sender:
            self.sender.cancel()
            await asyncio.gather(self.sender, return_exceptions=True)
            self.sender = None
        while not self.audio_queue.empty():
            self.audio_queue.get_nowait(); self.audio_queue.task_done()
        # Remove ownership before waiting.  A later cancel/close may only see a
        # new relay, never retarget this exact old ID.
        relay_id, self.relay_id = self.relay_id, None
        if not relay_id:
            return
        transport = self.ws
        try:
            await asyncio.wait_for(
                self.request("talk.session.close", {"sessionId":relay_id}), timeout=2,
            )
        except asyncio.TimeoutError:
            LOG.warning("timed out closing retired talk relay %s; retiring transport", relay_id)
            await self._close_transport(transport)
        except asyncio.CancelledError:
            raise
        except Exception:
            LOG.warning("failed closing retired talk relay %s; retiring transport", relay_id, exc_info=True)
            await self._close_transport(transport)


class WakeSpotter:
    def __init__(self, directory: Path):
        if sherpa_onnx is None:
            raise VoiceError("sherpa-onnx is not installed")
        needed = [directory / part for part in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")]
        if not all(path.is_file() for path in needed): raise VoiceError("Sherpa wake model files are incomplete")
        # Controlled Pi timing with this model shows one native worker avoids
        # scheduler contention and completes the fixed silence fixture faster.
        self.kws = sherpa_onnx.KeywordSpotter(tokens=str(directory/"tokens.txt"), encoder=str(directory/"encoder.onnx"), decoder=str(directory/"decoder.onnx"), joiner=str(directory/"joiner.onnx"), keywords_file=str(directory/"keywords.txt"), num_threads=1, provider="cpu")
        self.stream = self.kws.create_stream()

    def accept(self, pcm: bytes) -> bool:
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        self.stream.accept_waveform(INPUT_HZ, samples)
        while self.kws.is_ready(self.stream):
            self.kws.decode_stream(self.stream)
            if self.kws.get_result(self.stream):
                self.kws.reset_stream(self.stream)
                return True
        return False


class SpeechGate:
    """Silero VAD is the silence authority; raw microphone energy never extends a turn."""
    def __init__(self, directory: Path):
        model = directory / "silero-vad.onnx"
        if not model.is_file(): raise VoiceError("Silero VAD model is missing")
        config = sherpa_onnx.VadModelConfig()
        config.silero_vad.model = str(model)
        config.sample_rate = INPUT_HZ
        self.vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=30)

    def speech(self, pcm: bytes) -> bool:
        self.vad.accept_waveform(np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0)
        return bool(self.vad.is_speech_detected())


class VoiceRelay:
    def __init__(self, config: dict[str, Any], emit: StateSink):
        self.emit, self.config = emit, config
        self.gateway = GatewayTalk(config, emit)
        self.gateway.on_talk_event = self._on_gateway_talk_event
        self.gateway.on_agent_event = self._on_gateway_agent_event
        self.wake: WakeSpotter | None = None
        self.vad: SpeechGate | None = None
        self.active = False
        self.last_speech_at = 0.0
        self.awaiting_output_drain = False
        self.consult_deadline = 0.0
        self.lock = asyncio.Lock()
        # Aggregates only: never retain microphone content, captions, tokens,
        # or the gateway's opaque relay session identifiers.
        self.frames_received = 0
        self.bytes_received = 0
        self.last_pcm_at: float | None = None
        self.last_rms = 0.0
        self.last_peak = 0
        self.wake_detections = 0
        self.last_native_kws_evaluation_ms: float | None = None
        self.max_native_kws_evaluation_ms = 0.0
        self.last_vad_speech: bool | None = None
        # The status endpoint exposes only aggregates, but capture runs 25 times
        # per second.  Keep one fixed scratch buffer so diagnostic accounting
        # cannot allocate a float64 copy for each packet while Jarvis is idle.
        self._pcm_metric_squares = np.empty(MAX_FRAME_BYTES // 2, dtype=np.int64)

    def diagnostic_snapshot(self) -> dict[str, Any]:
        """Return safe aggregate capture and native-KWS health for /runtime.json."""
        now = time.monotonic()
        age_ms = None if self.last_pcm_at is None else max(0, round((now - self.last_pcm_at) * 1000))
        speech_age_ms = None if self.last_speech_at <= 0 else max(0, round((now - self.last_speech_at) * 1000))
        consult_remaining_ms = None if self.consult_deadline <= 0 else max(0, round((self.consult_deadline - now) * 1000))
        return {
            "framesReceived": self.frames_received,
            "bytesReceived": self.bytes_received,
            "lastPcmAgeMs": age_ms,
            "lastRms": self.last_rms,
            "lastPeak": self.last_peak,
            "wakeDetections": self.wake_detections,
            "lastNativeKwsEvaluationMs": self.last_native_kws_evaluation_ms,
            "maxNativeKwsEvaluationMs": self.max_native_kws_evaluation_ms,
            "activeRelay": self.active and self.gateway.relay_id is not None,
            "queueDepth": self.gateway.audio_queue.qsize(),
            "lastSpeechAgeMs": speech_age_ms,
            "consultRemainingMs": consult_remaining_ms,
            "awaitingOutputDrain": self.awaiting_output_drain,
            "lastVadSpeech": self.last_vad_speech,
        }

    def _record_pcm(self, pcm: bytes) -> None:
        samples = np.frombuffer(pcm, dtype="<i2")
        sample_count = samples.size
        self.frames_received += 1
        self.bytes_received += len(pcm)
        self.last_pcm_at = time.monotonic()
        if not sample_count:
            self.last_rms = 0.0
            self.last_peak = 0
            return
        # `int16` multiplication would overflow for normal loud samples.  The
        # reusable int64 destination retains the old float64 calculation's
        # precision for every valid PCM16 value without a per-frame copy.
        squares = self._pcm_metric_squares[:sample_count]
        np.multiply(samples, samples, dtype=np.int64, out=squares)
        self.last_rms = round(math.sqrt(int(np.sum(squares, dtype=np.int64)) / sample_count), 3)
        self.last_peak = max(int(samples.max()), -int(samples.min()))

    def _accept_wake(self, pcm: bytes) -> bool:
        wake = self._wake()
        started = time.perf_counter()
        try:
            return wake.accept(pcm)
        finally:
            elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
            self.last_native_kws_evaluation_ms = elapsed_ms
            self.max_native_kws_evaluation_ms = max(self.max_native_kws_evaluation_ms, elapsed_ms)

    def _wake(self) -> WakeSpotter:
        if not self.wake:
            root = Path(str(self.config.get("voice", {}).get("wake_model_dir", Path(__file__).with_name("wake-model"))))
            self.wake = WakeSpotter(root)
        return self.wake

    def _vad(self) -> SpeechGate:
        if not self.vad:
            root = Path(str(self.config.get("voice", {}).get("wake_model_dir", Path(__file__).with_name("wake-model"))))
            self.vad = SpeechGate(root)
        return self.vad

    async def _on_gateway_talk_event(self, event: dict[str, Any]) -> None:
        if event.get("type") != "consult.pending": return
        self.consult_deadline = time.monotonic() + 90 if event.get("phase") == "start" else 0.0

    async def _on_gateway_agent_event(self, _event: str, payload: dict[str, Any]) -> None:
        if payload.get("sessionKey") != self.gateway.session_key: return
        data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        phase = str(data.get("phase", "")).lower()
        if payload.get("stream") == "lifecycle" and phase == "start": self.consult_deadline = time.monotonic() + 90
        elif payload.get("stream") == "lifecycle" and phase in {"end", "final", "error", "aborted"}: self.consult_deadline = 0.0

    async def cancel_active(self) -> None:
        """Close the current relay when its sole browser owner disappears."""
        async with self.lock:
            had_turn = self.active or self.gateway.relay_id is not None
            self.active = False
            self.awaiting_output_drain = False
            self.consult_deadline = 0.0
            if had_turn:
                try:
                    await self.gateway.cancel()
                finally:
                    await self.gateway.close_turn()
        if had_turn:
            await self.emit({"type":"audio.end"})
            await self.emit({"type":"state", "state":"idle"})

    async def _fail_slow_gateway_turn(self) -> None:
        self.active = False
        self.awaiting_output_drain = False
        self.consult_deadline = 0.0
        await self.gateway.abandon()
        await self.emit({"type":"error", "message":"Sprachverbindung ist zu langsam – bitte erneut versuchen"})
        await self.emit({"type":"state", "state":"idle"})

    async def handle_browser_message(self, _ws: Any, payload: Any) -> None:
        if isinstance(payload, (bytes, bytearray, memoryview)):
            pcm = bytes(payload)
            if not pcm or len(pcm) > MAX_FRAME_BYTES or len(pcm) % 2: raise VoiceError("invalid PCM16 frame")
            async with self.lock:
                self._record_pcm(pcm)
                if self.active:
                    if self.consult_deadline and time.monotonic() >= self.consult_deadline:
                        LOG.warning("voice consult deadline exceeded frames=%d bytes=%d wakes=%d active_relay=%s queue_depth=%d",
                                    self.frames_received, self.bytes_received, self.wake_detections,
                                    self.gateway.relay_id is not None, self.gateway.audio_queue.qsize())
                        await self.gateway.close_turn(); self.active = False; self.consult_deadline = 0.0
                        await self.emit({"type":"state", "state":"error"}); return
                    if self.awaiting_output_drain:
                        return
                    self.last_vad_speech = self._vad().speech(pcm)
                    if self.last_vad_speech: self.last_speech_at = time.monotonic()
                    if not self.consult_deadline and time.monotonic() - self.last_speech_at >= FOLLOW_UP_SILENCE_SECONDS:
                        await self.gateway.close_turn(); self.active = False
                        await self.emit({"type":"state", "state":"idle"})
                    else:
                        try:
                            await self.gateway.append(pcm)
                        except VoiceError as error:
                            if "queue is full" not in str(error): raise
                            LOG.warning("voice relay sender stalled; abandoning local turn frames=%d bytes=%d queue_depth=%d",
                                        self.frames_received, self.bytes_received, self.gateway.audio_queue.qsize())
                            await self._fail_slow_gateway_turn()
                elif self._accept_wake(pcm):
                    self.wake_detections += 1
                    self.active = True
                    self.last_speech_at = time.monotonic()
                    LOG.info("voice wake detected frames=%d bytes=%d rms=%.3f peak=%d kws_ms=%.3f queue_depth=%d",
                             self.frames_received, self.bytes_received, self.last_rms, self.last_peak,
                             self.last_native_kws_evaluation_ms or 0.0, self.gateway.audio_queue.qsize())
                    await self.emit({"type":"state", "state":"listening"})
                    try:
                        await self.gateway.start()
                        await self.gateway.append(pcm)
                    except Exception as error:
                        self.active = False
                        LOG.warning("voice relay start failed frames=%d bytes=%d wakes=%d active_relay=%s queue_depth=%d error=%s",
                                    self.frames_received, self.bytes_received, self.wake_detections,
                                    self.gateway.relay_id is not None, self.gateway.audio_queue.qsize(), type(error).__name__)
                        await self.emit({"type":"state", "state":"error"})
                        raise
            return
        if not isinstance(payload, dict): raise VoiceError("invalid voice control message")
        command = payload.get("type")
        if command == "voice.start":
            await self.emit({"type":"state", "state":"idle"})
        elif command == "voice.stop":
            async with self.lock:
                if self.active: await self.gateway.close_turn()
                self.active = False
            await self.emit({"type":"state", "state":"idle"})
        elif command == "voice.cancel":
            await self.cancel_active()
        elif command == "voice.output.drained":
            self.awaiting_output_drain = False
            self.last_speech_at = time.monotonic()
            await self.emit({"type":"state", "state":"listening"})


async def create_voice_relay(config: dict[str, Any], state_sink: StateSink) -> VoiceRelay:
    """Imported by server.py; construction stays lazy so /health works without a mic."""
    return VoiceRelay(config, state_sink)

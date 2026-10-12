"""Small, real OpenClaw *node* client for the Jarvis Pi.

The browser never receives gateway credentials.  This module owns the outbound
WebSocket, device identity, pairing token and the node.invoke response path.
"""
from __future__ import annotations

import asyncio, base64, hashlib, json, os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

import aiohttp
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

PROTOCOL = 4
MIN_NODE_PROTOCOL = 3

def app_version() -> str:
    """Release provenance is written into this sibling file by the image finalizer."""
    try:
        value=Path(__file__).with_name("VERSION").read_text(encoding="utf-8").strip()
        return value or "unknown"
    except OSError:
        return "unknown"


@dataclass
class NodeIdentity:
    device_id: str
    private_key: Ed25519PrivateKey

    @classmethod
    def load(cls, directory: Path) -> "NodeIdentity":
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        key_path, id_path = directory / "device-ed25519.pem", directory / "device-id"
        if key_path.exists():
            key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
            if not isinstance(key, Ed25519PrivateKey):
                raise ValueError("device key is not Ed25519")
        else:
            key = Ed25519PrivateKey.generate()
            key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            os.chmod(key_path, 0o600)
        raw_public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        # Gateway device identity is the lowercase SHA-256 hex of the raw public key.
        device_id = hashlib.sha256(raw_public).hexdigest()
        if id_path.exists() and id_path.read_text().strip() != device_id:
            raise ValueError("persisted device-id does not match the Ed25519 public key")
        if not id_path.exists():
            id_path.write_text(device_id + "\n")
            os.chmod(id_path, 0o600)
        return cls(device_id, key)

    def public_key_b64url(self) -> str:
        raw = self.private_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    def sign_b64url(self, payload: str) -> str:
        return base64.urlsafe_b64encode(self.private_key.sign(payload.encode())).decode().rstrip("=")


class GatewayNode:
    """Reconnectable node-role client. Pairing is deliberately gateway-owned."""
    def __init__(self, config: dict[str, Any], identity_dir: Path, on_status: Callable[[bool, str], Awaitable[None]], on_invoke: Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]):
        self.config, self.identity = config, NodeIdentity.load(identity_dir)
        self.on_status, self.on_invoke = on_status, on_invoke
        self._request_id = 0
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._token_path = identity_dir / "node-device-token"

    def _next_id(self) -> str:
        self._request_id += 1
        return f"pi-{self._request_id}"

    def _stored_token(self) -> str | None:
        return self._token_path.read_text().strip() if self._token_path.exists() else None

    def _store_token(self, token: str) -> None:
        self._token_path.write_text(token + "\n")
        os.chmod(self._token_path, 0o600)

    async def run(self) -> None:
        delay = 1
        while True:
            try:
                await self._run_once()
                delay = 1
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                await self.on_status(False, str(exc))
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30)

    async def _run_once(self) -> None:
        url = self.config.get("gatewayUrl")
        if not url:
            await self.on_status(False, "gatewayUrl is not configured")
            await asyncio.Future()
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(url, heartbeat=20, max_msg_size=2**20) as ws:
                challenge = await self._challenge(ws)
                await self._connect(ws, challenge)
                await self.on_status(True, "connected")
                async for message in ws:
                    if message.type == aiohttp.WSMsgType.TEXT:
                        await self._handle(ws, json.loads(message.data))
                    elif message.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.ERROR):
                        break

    async def _challenge(self, ws: aiohttp.ClientWebSocketResponse) -> dict[str, Any]:
        async for message in ws:
            if message.type != aiohttp.WSMsgType.TEXT:
                raise ConnectionError("gateway closed before connect.challenge")
            frame = json.loads(message.data)
            if frame.get("type") == "event" and frame.get("event") == "connect.challenge":
                payload = frame.get("payload") or {}
                if not isinstance(payload.get("nonce"), str) or not isinstance(payload.get("ts"), int) or payload["ts"] < 0:
                    raise ValueError("invalid connect.challenge")
                return payload
        raise ConnectionError("gateway closed before connect.challenge")

    async def _connect(self, ws: aiohttp.ClientWebSocketResponse, challenge: dict[str, Any]) -> None:
        nonce, signed_at = challenge["nonce"], challenge["ts"]
        client_id, mode, role, scopes = "node-host", "node", "node", []
        token = self._stored_token() or self.config.get("gatewayToken")
        if not token:
            raise ValueError("gatewayToken is required for initial pairing")
        auth = {"deviceToken": token} if self._stored_token() else {"token": token}
        signature_payload = "|".join(["v3", self.identity.device_id, client_id, mode, role, ",".join(scopes), str(signed_at), token, nonce, "linux", "raspberry-pi"])
        request_id = self._next_id()
        await ws.send_json({"type": "req", "id": request_id, "method": "connect", "params": {
            "minProtocol": MIN_NODE_PROTOCOL, "maxProtocol": PROTOCOL,
            "client": {"id": client_id, "displayName": self.config.get("nodeName", "Jarvis Kitchen"), "version": app_version(), "platform": "linux", "deviceFamily": "raspberry-pi", "mode": mode, "instanceId": self.identity.device_id},
            "role": role, "scopes": scopes, "caps": ["canvas"],
            "commands": ["jarvis.ui", "device.status"], "permissions": {}, "auth": auth,
            "locale": self.config.get("locale", "de-DE"), "userAgent": "jarvis-pi/1.0",
            "device": {"id": self.identity.device_id, "publicKey": self.identity.public_key_b64url(), "signature": self.identity.sign_b64url(signature_payload), "signedAt": signed_at, "nonce": nonce},
        }})
        while True:
            message = await ws.receive()
            if message.type != aiohttp.WSMsgType.TEXT:
                raise ConnectionError("gateway closed during connect")
            frame = json.loads(message.data)
            if frame.get("type") == "res" and frame.get("id") == request_id:
                if not frame.get("ok"):
                    error = frame.get("error", {})
                    raise PermissionError(error.get("message", "gateway pairing required"))
                auth_reply = (frame.get("payload") or {}).get("auth") or {}
                if auth_reply.get("deviceToken"):
                    self._store_token(auth_reply["deviceToken"])
                return

    async def _handle(self, ws: aiohttp.ClientWebSocketResponse, frame: dict[str, Any]) -> None:
        if frame.get("type") != "event" or frame.get("event") != "node.invoke.request":
            return
        payload = frame.get("payload") or {}
        if isinstance(payload, str):
            payload = json.loads(payload)
        if not isinstance(payload, dict):
            return
        invoke_id, node_id, command = payload.get("id"), payload.get("nodeId"), payload.get("command")
        if not all(isinstance(value, str) and value for value in (invoke_id, node_id, command)):
            return
        try:
            params = json.loads(payload.get("paramsJSON") or "{}")
            result = await self.on_invoke(command, params if isinstance(params, dict) else {})
            response = {"id": invoke_id, "nodeId": node_id, "ok": True, "payload": result}
        except Exception as exc:
            response = {"id": invoke_id, "nodeId": node_id, "ok": False, "error": {"code": "INVALID_REQUEST", "message": str(exc)[:240]}}
        await ws.send_json({"type": "req", "id": self._next_id(), "method": "node.invoke.result", "params": response})

"""Local-only Jarvis Horizon state service for the DietPi kiosk."""
from __future__ import annotations
import argparse, asyncio, json, logging, os, time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
import aiohttp
from aiohttp import web
from gateway import GatewayNode, app_version

LOG = logging.getLogger("jarvis-mirror")


def usb_microphone_status() -> dict[str, Any]:
    """Resolve only the Pi's selected USB input, without probing video devices."""
    import subprocess
    unavailable = {"available": False, "source": "", "label": ""}
    env = dict(os.environ, PULSE_SERVER=f"unix:/run/user/{os.getuid()}/pulse/native")
    # The service exposes just the audio socket; its private /run/user parent
    # is root-owned. Connect explicitly instead of libpulse's XDG discovery.
    env.pop("XDG_RUNTIME_DIR", None)
    try:
        selected = subprocess.run(["pactl", "get-default-source"], env=env, capture_output=True,
                                  text=True, timeout=2, check=True).stdout.strip()
        if not selected.startswith("alsa_input.usb-"):
            return unavailable
        sources = json.loads(subprocess.run(["pactl", "--format=json", "list", "sources"],
                             env=env, capture_output=True, text=True, timeout=2, check=True).stdout)
        source = next((item for item in sources if item.get("name") == selected), None)
        if source is None:
            return unavailable
        return {"available": True, "source": selected,
                "label": source.get("description") or selected}
    except (OSError, ValueError, subprocess.SubprocessError):
        return unavailable

WEATHER_LABELS={"sunny":"Sonnig","clear-night":"Klar","partlycloudy":"Wolkig","cloudy":"Bedeckt","rainy":"Regen","pouring":"Regen","snowy":"Schnee","fog":"Nebel"}
def horizon_weather(entity: dict[str,Any], home_name: str | None) -> dict[str,Any]:
    attributes=entity.get("attributes",{}); temperature=attributes.get("temperature"); unit=attributes.get("temperature_unit")
    rendered_temperature=None if temperature is None else f"{str(temperature).replace('.',',')}{unit or '°C'}"
    return {"temperature":rendered_temperature,"summary":WEATHER_LABELS.get(entity.get("state"),entity.get("state")),"location":home_name,"source":"Home Assistant","humidity":None if attributes.get("humidity") is None else f"{attributes['humidity']} %"}
def horizon_event_time(value: Any, now: datetime) -> str | None:
    if not isinstance(value,str): return None
    if "T" not in value:
        try:
            day=datetime.fromisoformat(value).date(); today=now.date()
            return "Heute" if day==today else "Morgen" if day==today+timedelta(days=1) else day.strftime("%d.%m.")
        except ValueError: return None
    try: return datetime.fromisoformat(value.replace("Z","+00:00")).astimezone().strftime("%H:%M")
    except ValueError: return None

def configured_calendar_entities(cfg: dict[str, Any], entities: dict[str, Any]) -> list[str]:
    configured=cfg.get("calendarEntities")
    candidates=configured if isinstance(configured,list) and configured else [cfg.get("calendarEntity")]
    return list(dict.fromkeys(entity for entity in candidates if isinstance(entity,str) and entity.startswith("calendar.") and entity in entities))

def horizon_next_event(events: list[dict[str, Any]], now: datetime) -> dict[str, Any] | None:
    def start_of(event: dict[str, Any]) -> datetime | None:
        start=event.get("start",{}); value=start.get("dateTime") or start.get("date")
        if not isinstance(value,str): return None
        try:
            parsed=datetime.fromisoformat(value.replace("Z","+00:00"))
            return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
        except ValueError: return None
    candidates=[(start_of(event),event) for event in events]
    candidates=[item for item in candidates if item[0] is not None and isinstance(item[1].get("summary"),str)]
    if not candidates: return None
    _,event=min(candidates,key=lambda item:item[0]); start=event.get("start",{}); value=start.get("dateTime") or start.get("date")
    return {"time":horizon_event_time(value,now),"title":event["summary"],"meta":"Kalender"}

class MirrorState:
    def __init__(self, config: dict[str, Any]):
        self.config, self.clients = config, set()
        self.snapshot: dict[str, Any] = {"mode":"idle","night":False,"muted":False,"offline":True,"clock":{},"weather":None,"nextEvent":None,"caption":None,"services":[],"media":None,"tile":None}
        self.executions: dict[str,dict[str,Any]] = {}
        self.closed_executions: set[str] = set()
        self.tile_expiry: asyncio.Task | None = None
    async def broadcast(self, message: dict[str, Any]) -> None:
        dead=[]
        for ws in self.clients:
            try: await ws.send_json(message)
            except ConnectionError: dead.append(ws)
        self.clients.difference_update(dead)
    async def update(self, **change: Any) -> None:
        # HA's poll and state_changed paths often repeat the same projection.
        # Suppress only equivalent projected fields: a new browser still
        # receives the authoritative snapshot on connect, and all real changes
        # retain their immediate broadcast.
        changed={key:value for key,value in change.items() if self.snapshot.get(key, object()) != value}
        if not changed: return
        self.snapshot.update(changed); await self.broadcast({"type":"snapshot","snapshot":self.snapshot})
    async def gateway_status(self, connected: bool, detail: str) -> None:
        await self.update(offline=not connected, services=[{"id":"GATEWAY","status":"ok" if connected else "error","detail":detail}])
    async def invoke(self, command: str, params: dict[str, Any]) -> dict[str, Any]:
        if command == "jarvis.ui":
            return await self.jarvis_ui(params)
        if command == "device.status": return {"status":"ok","platform":"linux","display":"horizon","version":app_version()}
        raise ValueError("unsupported command")

    async def jarvis_ui(self, params: dict[str, Any]) -> dict[str, Any]:
        kind=params.get("t")
        if not isinstance(kind,str): raise ValueError("expected a Horizon presentation event")
        if kind == "status":
            return {"status":"ok","tile":self.snapshot["tile"]}
        if kind == "briefing":
            if self.snapshot["weather"] is None: raise ValueError("DATA_UNAVAILABLE: Home Assistant is not connected")
            return {"weather":self.snapshot["weather"],"nextEvent":self.snapshot["nextEvent"],"media":self.snapshot["media"]}
        if kind == "ui":
            show=params.get("show")
            if show is None or show == "null":
                if self.tile_expiry: self.tile_expiry.cancel()
                await self.update(tile=None); return {"status":"accepted"}
            if show not in {"weather","music","camera","tasks","terminal","timer","home","message"}: raise ValueError("unsupported tile")
            tile_id=params.get("id",f"tile-{show}"); data=params.get("data",{}); ttl=params.get("ttl")
            if not isinstance(tile_id,str) or not tile_id or len(tile_id)>80 or not all(c.isalnum() or c in "._:-" for c in tile_id): raise ValueError("valid tile id is required")
            if not isinstance(data,dict) or len(json.dumps(data))>12000: raise ValueError("invalid tile data")
            sticky=bool(params.get("sticky",False))
            if not sticky and (not isinstance(ttl,int) or ttl<1000 or ttl>86400000): raise ValueError("ttl must be between 1000 and 86400000")
            tile={"id":tile_id,"type":show,"data":data,"sticky":sticky,"ttl":0 if sticky else ttl,"expiresAtMs":None if sticky else int(time.time()*1000)+ttl}
            if self.tile_expiry: self.tile_expiry.cancel()
            if not sticky: self.tile_expiry=asyncio.create_task(self._expire_tile(tile_id,ttl))
            await self.update(tile=tile); return {"status":"accepted"}
        if kind == "exec":
            ident=params.get("id"); title=params.get("title"); steps=params.get("steps",[])
            if not isinstance(ident,str) or not isinstance(title,str) or not ident or not title or ident in self.executions or ident in self.closed_executions: raise ValueError("valid unused execution id and title are required")
            if not isinstance(steps,list) or len(steps)>12 or any(not isinstance(x,str) or len(x)>96 for x in steps): raise ValueError("invalid execution steps")
            self.executions[ident]={"title":title[:120],"steps":steps,"lines":[]}; await self._publish_execution(ident); return {"status":"accepted"}
        if kind == "line":
            ident=params.get("id"); text=params.get("text"); line_kind=params.get("kind")
            if ident not in self.executions or line_kind not in {"cmd","out","ok","err","warn","done"} or not isinstance(text,str) or not text or len(text)>240: raise ValueError("invalid execution line")
            self.executions[ident]["lines"]=(self.executions[ident]["lines"]+[{"kind":line_kind,"text":text,"step":params.get("step"),"progress":params.get("progress")}])[-24:]
            await self._publish_execution(ident); return {"status":"accepted"}
        if kind == "exit":
            ident=params.get("id"); code=params.get("code"); elapsed=params.get("ms")
            if ident not in self.executions or not isinstance(code,int) or code not in range(-255,256) or not isinstance(elapsed,int) or elapsed<0 or elapsed>86400000: raise ValueError("invalid execution exit")
            execution=self.executions.pop(ident); self.closed_executions.add(ident); self.closed_executions=set(list(self.closed_executions)[-64:])
            execution["exit"]=code; execution["elapsedMs"]=elapsed
            if code==0:
                if self.tile_expiry: self.tile_expiry.cancel()
                self.tile_expiry=asyncio.create_task(self._expire_tile(ident,9000))
            await self.update(mode="success" if code==0 else "error",tile={"id":ident,"type":"terminal","data":execution,"sticky":code!=0,"ttl":0 if code else 9000,"expiresAtMs":None if code else int(time.time()*1000)+9000}); return {"status":"accepted"}
        raise ValueError("unsupported presentation event")

    async def _publish_execution(self, ident: str) -> None:
        execution=self.executions[ident]
        # `executions` remains mutable while tool lines arrive.  Publish an
        # owned projection so equality suppression compares the prior rendered
        # lines, rather than the same dict after it has been mutated.
        projection={**execution, "steps":list(execution["steps"]),
                    "lines":[dict(line) for line in execution["lines"]]}
        await self.update(mode="working",tile={"id":ident,"type":"terminal","data":projection,"sticky":True,"ttl":0,"expiresAtMs":None})

    async def _expire_tile(self, ident: str, ttl: int) -> None:
        try:
            await asyncio.sleep(ttl/1000)
            if self.snapshot.get("tile",{}).get("id") == ident: await self.update(tile=None)
        except asyncio.CancelledError: pass

async def release_voice_owner(app: Any, ws: Any) -> None:
    """Release only the browser that owns the active local voice capture."""
    if app.get("voice_owner") is not ws: return
    app["voice_owner"]=None
    relay=app.get("voice")
    if not relay: return
    cancel=getattr(relay,"cancel_active",None) or getattr(relay,"cancel",None)
    if cancel:
        try: await cancel()
        except Exception as exc: LOG.warning("voice cleanup failed: %s",exc)
    else: await relay.handle_browser_message(ws,{"type":"voice.cancel"})

async def forward_voice_binary_frame(relay: Any, ws: Any, pcm: bytes) -> None:
    """Keep ordered PCM forwarding cooperative while the relay's fast path is CPU-bound."""
    await relay.handle_browser_message(ws, pcm)
    await asyncio.sleep(0)

async def ha_poll(state: MirrorState) -> None:
    cfg=state.config; base=(cfg.get("haUrl") or "").rstrip("/"); token=cfg.get("haToken")
    if not base or not token: return
    headers={"Authorization":f"Bearer {token}"}
    async with aiohttp.ClientSession(headers=headers) as session:
      while True:
        try:
          async with session.get(base+"/api/states",timeout=12) as response:
            response.raise_for_status(); entities={x.get("entity_id"):x for x in await response.json()}
          update={}
          weather=entities.get(cfg.get("weatherEntity"));
          if weather: update["weather"]=horizon_weather(weather,cfg.get("homeName"))
          media=entities.get(cfg.get("mediaEntity"));
          if media: a=media.get("attributes",{}); update["media"]={"playing":media.get("state")=="playing","title":a.get("media_title"),"artist":a.get("media_artist"),"source":a.get("friendly_name")}
          calendars=configured_calendar_entities(cfg,entities)
          if cfg.get("calendarEntities") or cfg.get("calendarEntity"):
            update["nextEvent"]=None
          if calendars:
            now=datetime.now(timezone.utc); finish=now+timedelta(days=1); calendar_events=[]
            for calendar in calendars:
              try:
                async with session.get(base+"/api/calendars/"+calendar,params={"start":now.isoformat(),"end":finish.isoformat()},timeout=12) as response:
                  if response.status==200:
                    payload=await response.json()
                    if isinstance(payload,list): calendar_events.extend(item for item in payload if isinstance(item,dict))
              except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as exc:
                LOG.warning("Calendar poll failed for %s: %s",calendar,type(exc).__name__)
            update["nextEvent"]=horizon_next_event(calendar_events,now)
          if update: await state.update(**update)
        except Exception as exc:
          LOG.warning("Home Assistant poll failed: %s",exc)
          await state.update(weather=None,media=None,nextEvent=None)
        await asyncio.sleep(30)

async def ha_events(state: MirrorState) -> None:
    """Use HA's authenticated state_changed stream to wake the renderer promptly.

    Polling remains the full snapshot repair path; events only publish the changed
    live weather/media fact and never invent a missing value.
    """
    cfg=state.config; base=(cfg.get("haUrl") or "").rstrip("/"); token=cfg.get("haToken")
    if not base or not token: return
    url=("wss://" if base.startswith("https://") else "ws://") + base.split("://",1)[1] + "/api/websocket"
    tracked={value for value in (cfg.get("weatherEntity"),cfg.get("mediaEntity")) if isinstance(value,str)}
    while True:
      try:
        async with aiohttp.ClientSession() as session:
          async with session.ws_connect(url,heartbeat=20) as ws:
            hello=await ws.receive_json()
            if hello.get("type")!="auth_required": raise RuntimeError("unexpected HA websocket greeting")
            await ws.send_json({"type":"auth","access_token":token})
            if (await ws.receive_json()).get("type")!="auth_ok": raise PermissionError("Home Assistant websocket authentication failed")
            await ws.send_json({"id":1,"type":"subscribe_events","event_type":"state_changed"})
            await ws.receive_json()
            async for message in ws:
              if message.type!=aiohttp.WSMsgType.TEXT: break
              event=json.loads(message.data).get("event",{}).get("data",{})
              entity_id=event.get("entity_id"); new=event.get("new_state") or {}
              if entity_id not in tracked: continue
              attrs=new.get("attributes",{})
              if entity_id==cfg.get("weatherEntity"):
                await state.update(weather=horizon_weather(new,cfg.get("homeName")))
              elif entity_id==cfg.get("mediaEntity"):
                await state.update(media={"playing":new.get("state")=="playing","title":attrs.get("media_title"),"artist":attrs.get("media_artist"),"source":attrs.get("friendly_name")})
      except asyncio.CancelledError: raise
      except Exception as exc: LOG.warning("Home Assistant event stream failed: %s",exc); await asyncio.sleep(5)

async def app_main(config_path: Path, host: str, port: int) -> None:
    config=json.loads(config_path.read_text(encoding="utf-8")) if config_path.exists() else {}
    state=MirrorState(config); root=Path(__file__).parent; horizon=root/"horizon"
    app=web.Application()
    index=horizon/"index.html"
    async def health(_):
        ready=index.is_file()
        return web.json_response({"ok":ready,"gatewayConnected":not state.snapshot["offline"]},status=200 if ready else 503)
    async def home(_): return web.FileResponse(index)
    async def runtime(_):
        microphone = await asyncio.to_thread(usb_microphone_status)
        relay = app.get("voice")
        return web.json_response({"nodeName":config.get("nodeName","Jarvis Kitchen"),
                                  "audioInput":microphone,
                                  "voiceRelay":relay.diagnostic_snapshot() if relay else None}, headers={"Cache-Control":"no-store"})
    async def ws_handler(request):
        ws=web.WebSocketResponse(heartbeat=20); await ws.prepare(request); state.clients.add(ws)
        await ws.send_json({"type":"snapshot","snapshot":state.snapshot})
        try:
            async for message in ws:
                if message.type==aiohttp.WSMsgType.TEXT:
                    payload=json.loads(message.data)
                    if payload.get("type") in {"voice.start","voice.stop","voice.cancel","voice.output.drained"}:
                        relay=app.get("voice")
                        if relay:
                            owner=app.get("voice_owner")
                            if owner is not None and owner is not ws:
                                await ws.send_json({"type":"error","message":"Sprache wird bereits von diesem Gerät verwendet"})
                                await ws.close()
                                break
                                continue
                            await relay.handle_browser_message(ws,payload)
                            if payload["type"] == "voice.start": app["voice_owner"]=ws
                        else: await ws.send_json({"type":"error","message":"Sprachdienst ist nicht verfügbar"})
                elif message.type==aiohttp.WSMsgType.BINARY:
                    relay=app.get("voice")
                    if relay and app.get("voice_owner") is ws: await forward_voice_binary_frame(relay,ws,message.data)
                    else:
                        await ws.send_json({"type":"error","message":"Sprachdienst ist nicht verfügbar"})
                        await asyncio.sleep(0)
        except (json.JSONDecodeError, ValueError, RuntimeError) as exc:
            LOG.info("browser websocket closed after invalid voice frame: %s",exc)
            await ws.send_json({"type":"error","message":"Ungültige Sprachdaten – Verbindung wird neu gestartet"})
            await ws.close()
        except Exception:
            LOG.warning("browser websocket voice relay failed")
            await ws.send_json({"type":"error","message":"Sprachdienstfehler – Verbindung wird neu gestartet"})
            await ws.close()
        finally:
            state.clients.discard(ws)
            await release_voice_owner(app,ws)
        return ws
    # Revalidate local assets after OTA; Chromium may otherwise reuse a fresh
    # cached stylesheet from the previous release despite a kiosk restart.
    async def revalidate_assets(_request, response):
        response.headers.setdefault("Cache-Control", "no-cache")
    app.on_response_prepare.append(revalidate_assets)
    app.router.add_get("/",home); app.router.add_get("/runtime.json",runtime); app.router.add_get("/healthz",health); app.router.add_get("/health",health); app.router.add_get("/ws",ws_handler); app.router.add_static("/",horizon)
    identity_dir=Path(config.get("identityDir","/var/lib/jarvis-mirror"))
    node=GatewayNode(config,identity_dir,state.gateway_status,state.invoke)
    async def startup(_):
        app["voice_owner"]=None
        app["node"]=asyncio.create_task(node.run()); app["ha"]=asyncio.create_task(ha_poll(state)); app["ha_events"]=asyncio.create_task(ha_events(state))
        try:
            from voice import create_voice_relay
            app["voice"]=await create_voice_relay(config,state.broadcast)
        except ImportError:
            LOG.info("voice relay is not installed")
    async def cleanup(_):
        relay=app.get("voice")
        cancel=getattr(relay,"cancel_active",None) or getattr(relay,"cancel",None) if relay else None
        if cancel:
            try: await cancel()
            except Exception as exc: LOG.warning("voice shutdown cleanup failed: %s",exc)
        app["voice_owner"]=None
        for task in (app["node"],app["ha"],app["ha_events"]): task.cancel()
        await asyncio.gather(app["node"],app["ha"],app["ha_events"],return_exceptions=True)
    app.on_startup.append(startup); app.on_cleanup.append(cleanup)
    runner=web.AppRunner(app); await runner.setup(); await web.TCPSite(runner,host,port).start(); await asyncio.Future()

if __name__=="__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("--config",default="/etc/jarvis-mirror/config.json"); parser.add_argument("--host",default="127.0.0.1"); parser.add_argument("--port",default=8765,type=int); args=parser.parse_args()
    logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO")); asyncio.run(app_main(Path(args.config),args.host,args.port))

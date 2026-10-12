import asyncio
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import serialization

sys.path.insert(0, str(Path(__file__).parents[1]))
from gateway import GatewayNode, app_version
from server import MirrorState, configured_calendar_entities, forward_voice_binary_frame, horizon_event_time, horizon_next_event, horizon_weather, release_voice_owner


class FakeWs:
    def __init__(self): self.sent=[]
    async def send_json(self, value): self.sent.append(json.loads(json.dumps(value)))

class FakeRelay:
    def __init__(self): self.cancelled=0
    async def cancel_active(self): self.cancelled+=1

class FailingRelay:
    async def cancel_active(self): raise RuntimeError("unknown session")

class ImmediateRelay:
    def __init__(self): self.frames=[]
    async def handle_browser_message(self, _ws, pcm): self.frames.append(pcm)


class GatewayProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def test_device_id_is_sha256_of_persisted_public_key(self):
        async def nop(*args): return None
        with tempfile.TemporaryDirectory() as tmp:
            node=GatewayNode({"gatewayUrl":"ws://invalid","gatewayToken":"bootstrap"},Path(tmp),nop,nop)
            raw=node.identity.private_key.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)
            self.assertEqual(node.identity.device_id,hashlib.sha256(raw).hexdigest())
            self.assertEqual(len(node.identity.device_id),64)
    async def test_node_invoke_returns_canonical_result_frame(self):
        events=[]
        async def status(*args): events.append(args)
        async def invoke(command, params):
            self.assertEqual((command, params), ("jarvis.ui", {"show":"weather"}))
            return {"status":"accepted"}
        with tempfile.TemporaryDirectory() as tmp:
            node=GatewayNode({"gatewayUrl":"ws://invalid","gatewayToken":"bootstrap"},Path(tmp),status,invoke)
            ws=FakeWs()
            await node._handle(ws,{"type":"event","event":"node.invoke.request","payload":{"id":"i1","nodeId":"n1","command":"jarvis.ui","paramsJSON":json.dumps({"show":"weather"})}})
        self.assertEqual(ws.sent[0]["method"],"node.invoke.result")
        self.assertEqual(ws.sent[0]["params"],{"id":"i1","nodeId":"n1","ok":True,"payload":{"status":"accepted"}})

    async def test_no_gateway_starts_offline_without_fake_facts(self):
        state=MirrorState({})
        self.assertTrue(state.snapshot["offline"])
        self.assertIsNone(state.snapshot["weather"])
        self.assertIsNone(state.snapshot["media"])
        self.assertEqual(state.snapshot["mode"],"idle")

    async def test_browser_snapshot_contract_is_horizon_update_shape(self):
        state=MirrorState({})
        browser=FakeWs(); state.clients.add(browser)
        await state.update(mode="thinking",caption={"speaker":"user","text":"Hallo","streaming":True})
        self.assertEqual(browser.sent,[{"type":"snapshot","snapshot":state.snapshot}])
        self.assertEqual(browser.sent[0]["snapshot"]["mode"],"thinking")

    async def test_identical_projection_does_not_repaint_connected_browser(self):
        state=MirrorState({})
        browser=FakeWs(); state.clients.add(browser)
        weather={"temperature":"13,3°C","summary":"Wolkig"}
        await state.update(weather=weather)
        await state.update(weather=dict(weather))
        self.assertEqual(len(browser.sent),1)
        self.assertEqual(browser.sent[0]["snapshot"]["weather"],weather)

    async def test_jarvis_ui_execution_wire_contract(self):
        state=MirrorState({})
        self.assertEqual(await state.invoke("jarvis.ui",{"t":"exec","id":"job-1","title":"Licht","steps":["starte"]}),{"status":"accepted"})
        self.assertEqual(await state.invoke("jarvis.ui",{"t":"line","id":"job-1","kind":"out","text":"läuft"}),{"status":"accepted"})
        self.assertEqual(await state.invoke("jarvis.ui",{"t":"exit","id":"job-1","code":0,"ms":12}),{"status":"accepted"})
        status=await state.invoke("jarvis.ui",{"t":"status"})
        self.assertEqual(status["tile"]["id"],"job-1")
        self.assertEqual(status["tile"]["data"]["lines"][0]["text"],"läuft")

    async def test_execution_line_updates_are_detached_for_equality_and_reconnect(self):
        state=MirrorState({})
        browser=FakeWs(); state.clients.add(browser)
        await state.invoke("jarvis.ui",{"t":"exec","id":"job-lines","title":"Prüfung","steps":["starte"]})
        await state.invoke("jarvis.ui",{"t":"line","id":"job-lines","kind":"cmd","text":"erste Zeile"})
        await state.invoke("jarvis.ui",{"t":"line","id":"job-lines","kind":"out","text":"zweite Zeile"})
        await state.invoke("jarvis.ui",{"t":"exit","id":"job-lines","code":0,"ms":12})

        self.assertEqual([len(frame["snapshot"]["tile"]["data"]["lines"]) for frame in browser.sent], [0,1,2,2])
        # A browser connecting after those updates receives the current, not a
        # stale aliased projection.  This mirrors ws_handler's initial frame.
        reconnect=FakeWs()
        await reconnect.send_json({"type":"snapshot","snapshot":state.snapshot})
        self.assertEqual([line["text"] for line in reconnect.sent[0]["snapshot"]["tile"]["data"]["lines"]], ["erste Zeile","zweite Zeile"])

    async def test_jarvis_ui_rejects_show_without_bounded_ttl(self):
        state=MirrorState({})
        with self.assertRaisesRegex(ValueError,"ttl"):
            await state.invoke("jarvis.ui",{"t":"ui","show":"weather","data":{}})

    async def test_home_assistant_horizon_projection_uses_localized_display_values(self):
        weather=horizon_weather({"state":"partlycloudy","attributes":{"temperature":13.3,"temperature_unit":"°C","humidity":75}},"Zuhause")
        self.assertEqual(weather,{"temperature":"13,3°C","summary":"Wolkig","location":"Zuhause","source":"Home Assistant","humidity":"75 %"})
        now=__import__("datetime").datetime(2026,10,5,tzinfo=__import__("datetime").timezone.utc)
        self.assertEqual(horizon_event_time("2026-10-05",now),"Heute")
        self.assertEqual(horizon_event_time("2026-10-06",now),"Morgen")

    async def test_calendar_projection_uses_available_configured_calendars_and_earliest_start(self):
        entities={"calendar.familie":{},"calendar.shared":{},"sensor.not_a_calendar":{}}
        cfg={"calendarEntities":["calendar.missing","calendar.shared","calendar.familie","calendar.shared"],"calendarEntity":"calendar.legacy"}
        self.assertEqual(configured_calendar_entities(cfg,entities),["calendar.shared","calendar.familie"])
        now=__import__("datetime").datetime(2026,10,10,tzinfo=__import__("datetime").timezone.utc)
        next_event=horizon_next_event([
            {"summary":"Später","start":{"dateTime":"2026-10-11T18:30:00+02:00"}},
            {"summary":"Früher","start":{"dateTime":"2026-10-10T09:30:00+02:00"}},
            {"summary":"Ungültig","start":{}},
        ],now)
        self.assertEqual(next_event,{"time":"09:30","title":"Früher","meta":"Kalender"})
        self.assertEqual(configured_calendar_entities({"calendarEntity":"calendar.familie"},entities),["calendar.familie"])

    async def test_voice_owner_release_cancels_only_the_disconnected_owner(self):
        owner, passive=object(),object(); relay=FakeRelay(); app={"voice_owner":owner,"voice":relay}
        await release_voice_owner(app,passive)
        self.assertIs(app["voice_owner"],owner); self.assertEqual(relay.cancelled,0)
        await release_voice_owner(app,owner)
        self.assertIsNone(app["voice_owner"]); self.assertEqual(relay.cancelled,1)

    async def test_voice_owner_release_bounds_relay_cleanup_error(self):
        owner=object(); app={"voice_owner":owner,"voice":FailingRelay()}
        await release_voice_owner(app,owner)
        self.assertIsNone(app["voice_owner"])

    async def test_binary_pcm_forwarding_stays_ordered_and_yields_to_health_work(self):
        relay=ImmediateRelay(); ws=object(); health_ran=asyncio.Event()
        async def health(): health_ran.set()
        health_task=asyncio.create_task(health())
        await forward_voice_binary_frame(relay,ws,b"frame-1")
        self.assertTrue(health_ran.is_set())
        for frame in (b"frame-2",b"frame-3"):
            await forward_voice_binary_frame(relay,ws,frame)
        await health_task
        self.assertEqual(relay.frames,[b"frame-1",b"frame-2",b"frame-3"])

    async def test_device_status_reports_release_provenance(self):
        status=await MirrorState({}).invoke("device.status",{})
        self.assertEqual(status["version"],app_version())

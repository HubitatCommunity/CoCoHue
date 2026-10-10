// Off-hub tests for the CoCoHue Bridge driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} is skipped) on top of HubStub, a minimal
// stand-in for the Hubitat sandbox. Child devices are fakes that record the maps the bridge hands
// them. All IDs are made up.

import org.codehaus.groovy.control.CompilerConfiguration
import groovy.json.JsonOutput

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''package hubitat.scheduling
class AsyncResponse { int status = 200; def json; Map headers = [:] }''')
gcl.parseClass('''package com.hubitat.app
class DeviceWrapper { String deviceNetworkId; String v1Id; List received = []; List receivedV1 = []; int v1IdCalls = 0
  void createEventsFromMapV2(Map m) { received << m }
  void createEventsFromMapV1(Map m, Boolean b = false) { receivedV1 << m }
  void setMemberBulbIDs(List ids) {}
  // each call is a call into another device on the hub, which is what made V1 polling slow
  String getHueDeviceIdV1() { v1IdCalls++; v1Id ?: deviceNetworkId.tokenize("/").last() } }''')
gcl.parseClass('''
abstract class HubStub extends Script {
  Map settingsMap = [:]; Map state = [:]; List errors = []; List warns = []; List debugs = []
  def log = [warn: { m -> warns << m.toString() }, error: { m -> errors << m.toString() },
             info: { m -> }, debug: { m -> debugs << m.toString() }, trace: { m -> }]
  Map eventStreamConnect = [:]
  def interfaces = [eventStream: [close: { -> }, connect: { String url, Map opts -> eventStreamConnect = [url: url, opts: opts] }]]
  def device = [deviceNetworkId: "CCH/1", idAsLong: 1L, currentValue: { a -> "connected" }]
  Map children = [:]
  long clock = 1_000_000L
  def parent
  def propertyMissing(String n) { settingsMap[n] }
  Map getSettings() { settingsMap }
  long now() { clock }
  void advanceClock(long ms) { clock += ms }  // d.clock += x from a test would set a binding variable, not this field
  void unschedule(String h = null) {}
  void runIn(Long s, String h, Map o = null) {}
  void sendEvent(Map m) {}
  String getStackTrace(Throwable t) { t.toString() }
  void setup() {
    def self = this
    parent = [getChildDevice: { String dni -> self.children[dni] },
              getChildDevices: { -> self.children.values() as List },
              setBridgeOnlineStatus: { b -> }, getEventStreamEnabledSetting: { -> true },
              getBridgeData: { -> [apiVersion: "2", ip: "192.0.2.1", username: "FAKEkey0123456789abcdefghijklmnopqrstuv"] }]
  }
  com.hubitat.app.DeviceWrapper child(String kind, String id) {
    def d = new com.hubitat.app.DeviceWrapper(deviceNetworkId: "CCH/1/$kind/$id"); children[d.deviceNetworkId] = d; d
  }
}''')
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
String filter = args.length > 1 ? args[1] : ""
def fresh = { def d = shell.parse(driverFile); d.setup(); d }
def asyncResponse = { json -> gcl.loadClass("hubitat.scheduling.AsyncResponse").newInstance(json: json) }

int failures = 0, ran = 0
def test = { String name, Closure body ->
  if (!name.toLowerCase().contains(filter.toLowerCase())) return
  ran++
  boolean ok; String detail = ""
  try { def r = body(); ok = r[0]; detail = r[1].toString() }
  catch (Throwable e) { ok = false; detail = "threw ${e.class.simpleName}: ${e.message?.take(200)}" }
  if (!ok) failures++
  println "${ok ? 'PASS' : 'FAIL'}  $name${!ok ? '  ' + detail.take(400) : ''}"
}

// ── Fake Hue data ────────────────────────────────────────────────────────
def uuid = { int n -> String.format("00000000-0000-4000-8000-%012d", n) }
def power = { String owner, int lvl -> [type: "device_power", id: "p-$owner", owner: [rid: owner, rtype: "device"], power_state: [battery_level: lvl]] }
// One SSE event as the Hue bridge sends it: an id line, one data line holding a JSON array, a blank line
int sseId = 0
def sse = { List events -> "id: 1791249${String.format('%03d', sseId++)}:0\ndata: ${JsonOutput.toJson(events)}\n\n" }
def update = { List data -> [creationtime: "2026-10-06T01:27:18Z", data: data, id: uuid(9000 + sseId), type: "update"] }

// Shaped like the 2026-10-05 failure: two button reports, a scene recall, then on/dimming/colour for
// each light in the scene, all batched into one SSE event of more than 4096 bytes
int lightCount = 8
def lightIds = (1..lightCount).collect { uuid(100 + it) }
def sceneRecallEvent = {
  def button = { String ev -> update([[button: [button_report: [event: ev, updated: "2026-10-06T01:27:18.195Z"], last_event: ev],
                                       id: uuid(1), id_v1: "/sensors/15", owner: [rid: uuid(2), rtype: "device"], type: "button"]]) }
  def lightData = lightIds.withIndex().collectMany { String id, int i ->
    def common = [id: id, id_v1: "/lights/${i + 3}", owner: [rid: uuid(200 + i), rtype: "device"], service_id: 0, type: "light"]
    [[on: [on: true]] + common, [dimming: [brightness: 56.13]] + common,
     [color: [xy: [x: 0.5019, y: 0.4152]], color_temperature: [mirek: 447, mirek_valid: true]] + common]
  }
  sse([button("short_release"), button("initial_press"),
       update([[id: uuid(3), id_v1: "/scenes/AbCdEfGhIjKlMnOp", status: [active: "static", last_recall: "2026-10-06T01:27:18.495Z"], type: "scene"]]),
       update(lightData)])
}
def withLights = { d -> lightIds.collect { d.child("Light", it) } }
def lightUpdateCounts = { List lights -> lights.collect { it.received.size() } }
def chunks = { String s, int size -> (0..<s.length()).step(size).collect { s.substring(it, Math.min(it + size, s.length())) } }

// ── Refresh (parseStatesV2) ──────────────────────────────────────────────
def resource = [data: [
  [type: "motion", id: "m1", id_v1: "/sensors/5", owner: [rid: "dev-motion"], motion: [motion: true]],
  [type: "temperature", id: "t1", owner: [rid: "dev-motion"], temperature: [temperature: 21.5]],
  [type: "light_level", id: "l1", owner: [rid: "dev-motion"], light: [light_level: 10000]],
  [type: "contact_report", id: "c1", owner: [rid: "dev-contact"], contact_report: [state: "contact"]],
  power("dev-motion", 80), power("dev-button", 55), power("dev-contact", 90)]]

test("refresh: reaches motion, contact and battery for all three device kinds") {
  def d = fresh(); def m = d.child("Sensor", "dev-motion"); def b = d.child("Button", "dev-button"); def c = d.child("Contact", "dev-contact")
  d.parseStatesV2(asyncResponse(resource), null)
  def types = { dev -> dev.received*.type as Set }
  [types(m) == ["motion", "temperature", "light_level", "device_power"] as Set && types(b) == ["device_power"] as Set
     && types(c) == ["contact_report", "device_power"] as Set && d.errors.isEmpty(),
   [m: types(m), b: types(b), c: types(c), errors: d.errors]]
}

test("refresh: no WARN logging with logging off") {
  def d = fresh(); d.child("Button", "dev-button")
  d.parseStatesV2(asyncResponse(resource), null)
  [d.warns.isEmpty(), d.warns]
}

// ── V1 polling (parseStatesV1) ───────────────────────────────────────────
// Shaped like a V2 setup polled with V1 (the app's default): children have V2 IDs in their DNIs, and the
// Bridge reports more lights and groups (rooms, zones, ...) than were imported into Hubitat
def v1Setup = { d ->
  def lights = (1..25).collect { int n -> def c = d.child("Light", uuid(500 + n)); c.v1Id = "$n"; c }
  def groups = (1..10).collect { int n -> def c = d.child("Group", uuid(700 + n)); c.v1Id = "${n + 80}"; c }
  (1..20).each { d.child("Sensor", uuid(900 + it)) }
  [lights: lights, groups: groups,
   json: [lights: (1..40).collectEntries { ["$it", [state: [on: true, bri: it]]] },
          groups: (81..120).collectEntries { ["$it", [action: [on: true], state: [any_on: true], lights: ["1"]]] },
          sensors: [:]]]
}

test("v1 poll: every imported light and group gets its own state") {
  def d = fresh(); def s = v1Setup(d)
  d.parseStatesV1(asyncResponse(s.json), null)
  boolean lightsOk = s.lights.every { it.receivedV1 == [[on: true, bri: it.v1Id as int]] }
  boolean groupsOk = s.groups.every { it.receivedV1.size() == 2 }
  [lightsOk && groupsOk && d.errors.isEmpty(), [lights: s.lights*.receivedV1.take(3), groups: s.groups*.receivedV1.take(2), errors: d.errors]]
}

test("v1 poll: asks each child for its V1 ID at most once per poll") {
  def d = fresh(); def s = v1Setup(d)
  d.parseStatesV1(asyncResponse(s.json), null)
  int calls = d.children.values().sum { it.v1IdCalls }
  int maxCalls = s.lights.size() + s.groups.size()
  [calls <= maxCalls, "$calls V1 ID lookups for $maxCalls light and group children"]
}

test("v1 poll: children with V1 IDs in their DNIs are found without asking any child") {
  def d = fresh(); def l = d.child("Light", "7"); def g = d.child("Group", "3")
  d.parseStatesV1(asyncResponse([lights: ["7": [state: [on: false]]], groups: ["3": [action: [on: false], state: [any_on: false], lights: []]], sensors: [:]]), null)
  [l.receivedV1 == [[on: false]] && g.receivedV1.size() == 2 && l.v1IdCalls + g.v1IdCalls == 0, [l: l.receivedV1, g: g.receivedV1]]
}

// ── Eventstream: battery ─────────────────────────────────────────────────
test("eventstream: button battery reaches the Button child") {
  def d = fresh(); def b = d.child("Button", "dev-button")
  d.parse(sse([update([power("dev-button", 42)])]))
  [b.received*.power_state*.battery_level == [42], [received: b.received, errors: d.errors]]
}

test("eventstream: motion battery still reaches the Sensor child") {
  def d = fresh(); def m = d.child("Sensor", "dev-motion")
  d.parse(sse([update([power("dev-motion", 70)])]))
  [m.received.size() == 1, m.received]
}

// ── Eventstream: as the hub delivers it (one line per parse() call, no newlines; captured 2026-10-09) ──
test("eventstream: hub-style id line, then data line, updates the light with no warnings") {
  def d = fresh(); def l = d.child("Light", uuid(101))
  d.parse(": hi"); d.parse("id: 1791559424:0")
  d.parse("data: " + JsonOutput.toJson([update([[id: uuid(101), dimming: [brightness: 72.33], type: "light"]])]))
  [l.received.size() == 1 && d.warns.isEmpty() && d.errors.isEmpty(), [received: l.received, warns: d.warns, errors: d.errors]]
}

// ── Eventstream connection ───────────────────────────────────────────────
test("connect: debug log masks the Hue API key, but the bridge still gets the full key") {
  def d = fresh(); d.settingsMap.logEnable = true
  d.connectEventStream()
  String logged = d.debugs.join("\n")
  [!logged.contains("FAKEkey0123456789") && logged.contains("'FAKE****'")
     && d.eventStreamConnect.opts?.headers?."hue-application-key" == "FAKEkey0123456789abcdefghijklmnopqrstuv",
   [debugs: d.debugs, headers: d.eventStreamConnect.opts?.headers]]
}

println ran ? (failures ? "$failures of $ran failed" : "all $ran passed") : "no tests match \"$filter\""
System.exit(failures ? 1 : 0)

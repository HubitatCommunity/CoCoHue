/*
 * =============================  CoCoHue RGBW Bulb (Driver) ===============================
 *
 *  Copyright 2019-2023 Robert Morris
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * =======================================================================================
 *
 *  Last modified: 2023-01-30
 * 
 *  Changelog:
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0.2  - Fix to avoid unepected "off" transition time
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added preliminary color
 *            and CT prestating coommands; added "reachable" attribte from Bridge to bulb and group
 *            drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1.1  - Fix for setColorTempeature() not turning bulb on in some cases
 *  v3.1    - Improved error handling and debug logging; added optional setColorTemperature parameters
 *  v3.0    - Improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - Added optional rate to setColor per Hubitat (used by Hubitat Groups and Scenes); more static typing
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Parse xy as ct (previously did rgb but without parsing actual color)
 *  v1.8c   - Added back color/CT events for manual commands not from bridge without polling
 *  v1.8b   - Fix for sprious color name event if bulb in different mode
 *  v1.8    - Changed effect state to custom attribute instead of colorMode
 *            Added ability to disable bulb->group state propagation;
 *            Removed ["alert:" "none"] from on() command, now possible explicitly with flashOff()
 *  v1.7b   - Modified startLevelChange behavior to avoid possible problems with third-party devices
 *  v1.7    - Bulb switch/level states now propgate to groups w/o polling
 *  v1.6b   - Changed bri_inc to match Hubitat behavior
 *  v1.6    - Eliminated duplicate color/CT events on refresh
 *  v1.5    - Added additional custom commands and more consistency with effect behavior
 *  v1.1    - Added flash commands
 *  v1.0    - Initial Release
 */ 









import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

@Field static final Integer debugAutoDisableMinutes = 30

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

@Field static final Map<Integer,String> lightEffects = [0: "None", 1:"Color Loop"]
@Field static final Integer maxEffectNumber = 1

// These defaults are specified in Hue (decisecond) durations, used if not specified in preference or command:
@Field static final Integer defaultLevelTransitionTime = 4
@Field static final Integer defaultOnTransitionTime = 4

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on", "ct", "bri"]

// "ct" or "hs" for now -- to be finalized later:
@Field static final String xyParsingMode = "ct"

metadata {
   definition(name: "CoCoHue RGBW Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgbw-bulb-driver.groovy") {
      capability "Actuator"
      capability "ColorControl"
      capability "ColorTemperature"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "LevelPreset"
      capability "ChangeLevel"
      capability "Light"
      capability "ColorMode"
      capability "LightEffects"

      command "flash"
      command "flashOnce"
      command "flashOff"

      // Not (yet?) standard, but hopefully will be standardized soon (and similar to this--as analagous to LevelPreset as possible):
      command "presetColorTemperature", [[name:"Color temperature*",type:"NUMBER", description:"Color temperature to prestage", constraints:["NUMBER"]]]
      command "presetColor", [[name:"Color Map*",type:"JSON_OBJECT", description:"Color to prestage (Map with keys: hue, saturation, value; also accepts JSON object for better UI compatibility, subject to change)"]]
      attribute "colorTemperaturePreset", "number"
      attribute "huePreset", "number"
      attribute "saturationPreset", "number"
      
      attribute "effect", "string"
      attribute "reachable", "string"
   }

   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Level transition time", options:
         [[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
      /*
      // Sending "bri" with "on:true" alone seems to have no effect, so might as well not implement this for now...
      input name: "onTransitionTime", type: "enum", description: "", title: "On transition time", options:
         [[(-2): "Hue default/do not specify (recommended; default; Hue may ignore other values)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -2
      // Not recommended because of problem described here:  https://developers.meethue.com/forum/t/using-transitiontime-with-on-false-resets-bri-to-1/4585
      input name: "offTransitionTime", type: "enum", description: "", title: "Off transition time", options:
         [[(-2): "Hue default/do not specify (recommended; default)"],[(-1): "Use on transition time"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      */
      input name: "ctTransitionTime", type: "enum", description: "", title: "Color temperature transition time", options:
         [[(-2): "Hue default/do not specify (default)"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "rgbTransitionTime", type: "enum", description: "", title: "RGB transition time", options:
         [[(-2): "Hue default/do not specify (default)"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "hiRezHue", type: "bool", title: "Enable hue in degrees (0-360 instead of 0-100)", defaultValue: false
      if (colorStaging) input name: "colorStaging", type: "bool", description: "DEPRECATED. Please use new prestaging commands instead. May be removed in future.", title: "Enable color pseudo-prestaging", defaultValue: false
      if (levelStaging) input name: "levelStaging", type: "bool", description: "DEPRECATED. Please use new presetLevel() command instead. May be removed in future.", title: "Enable level pseudo-prestaging", defaultValue: false
      // Note: the following setting does not apply to SSE, which should update the group state immediately regardless:
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when bulb state changes",
         defaultValue: false
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   groovy.json.JsonBuilder le = new groovy.json.JsonBuilder(lightEffects)
   sendEvent(name: "lightEffects", value: le)
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   log.debug "initialize()"
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
}

// Probably won't happen but...
void parse(String description) {
   log.warn "Running unimplemented parse for: '${description}'"
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on(Number transitionTime = null) {
   if (enableDebug == true) log.debug "on()"
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : getScaledOnTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": true]
   }
   else {
      bridgeCmd = ["on": true, "transitiontime": scaledRate]
   }
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (enableDebug == true) log.debug "off()"
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : null
   if (scaledRate == null) {
      bridgeCmd = ["on": false]
   }
   else {
      bridgeCmd = ["on": false, "transitiontime": scaledRate]
   }
   // Shouldn't need to do (on() would clear and should have been turned on in meantime), but some users may want to:
   //clearPrestagedCommands()
   sendBridgeCommand(bridgeCmd)
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * (for "classic"/v1 HTTP API)
 * Iterates over Hue light state commands/states in Hue API v1 format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge or if pre-staged attribute is changed and "real" command not yet able to be sent, or
 * to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; if true, will ignore differences for prestaged attributes if switch state is off (TODO: how did new prestaging affect this?)
 */
void createEventsFromMap(Map bridgeCommandMap, Boolean isFromBridge = false, Set<String> keysToIgnoreIfSSEEnabledAndNotFromBridge=listKeysToIgnoreIfSSEEnabledAndNotFromBridge) {
   if (!bridgeCommandMap) {
      if (enableDebug == true) log.debug "createEventsFromMap called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (enableDebug == true) log.debug "Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   if (!isFromBridge && keysToIgnoreIfSSEEnabledAndNotFromBridge && parent.getEventStreamOpenStatus() == true) {
      bridgeMap.keySet().removeAll(keysToIgnoreIfSSEEnabledAndNotFromBridge)
      if (enableDebug == true) log.debug "Map after ignored keys removed: ${bridgeMap}"
   }
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   String colorMode = bridgeMap["colormode"]
   if (isFromBridge && colorMode == "xy") {
      if (xyParsingMode == "ct") {
         colorMode = "ct"
      }
      else {
         colorMode = "hs"
      }
      if (enableDebug == true) log.debug "In XY mode but parsing as CT (colorMode = $colorMode)"
   }
   Boolean isOn = bridgeMap["on"]
   bridgeMap.each {
      switch (it.key) {
         case "on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "bri":
            eventName = "level"
            eventValue = scaleBriFromBridge(it.value, "1")
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "colormode":
            eventName = "colorMode"
            eventValue = (colorMode == "ct" ? "CT" : "RGB")
            // Doing this above instead of reading from Bridge like used to...
            //eventValue = (it.value == "hs" ? "RGB" : "CT")
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "ct":
            eventName = "colorTemperature"
            eventValue = scaleCTFromBridge(it.value)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue) {
               if (isFromBridge && colorMode == "hs") {
                  if (enableDebug == true) log.debug "Skipping colorTemperature event creation because light not in ct mode"
                  break
               }
               doSendEvent(eventName, eventValue, eventUnit)
            }
            if (isFromBridge && colorMode == "hs") break
            setGenericTempName(eventValue)
            eventName = "colorMode"
            eventValue = "CT"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "hue":
            eventName = "hue"
            eventValue = scaleHueFromBridge(it.value)
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            if (isFromBridge && colorMode != "hs") {
                  if (enableDebug == true) log.debug "Skipping colorMode and color name event creation because light not in hs mode"
                  break
            }
            setGenericName(eventValue)
            if (isFromBridge) break
            eventName = "colorMode"
            eventValue = "RGB"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "sat":
            eventName = "saturation"
            eventValue = scaleSatFromBridge(it.value)
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            if (isFromBridge) break
            eventName = "colorMode"
            eventValue = "RGB"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "effect":
            eventName = "effect"
            eventValue = (it.value == "colorloop" ? "colorloop" : "none")
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "reachable":
            eventName = "reachable"
            eventValue = it.value ? "true" : "false"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "transitiontime":
         case "mode":
         case "alert":
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * (for "new"/v2/EventSocket [SSE] API; not documented and subject to change)
 * Iterates over Hue light state states in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromSSE(Map data) {
   if (enableDebug == true) log.debug "createEventsFromSSE($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean hasCT = data.color_temperature?.mirek != null
   data.each { String key, value ->
      switch (key) {
         case "on":
            eventName = "switch"
            eventValue = value.on ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "dimming":
            eventName = "level"
            eventValue = scaleBriFromBridge(value.brightness, "2")
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "color": 
            if (!hasCT) {
               if (enableDebug == true) log.debug "color received (presuming xy, no CT)"
               // no point in doing this yet--but maybe if can convert XY/HS some day:
               //parent.refreshBridgeWithDealay()
            }
            else {
               if (enableDebug == true) log.debug "color received but also have CT, so assume CT parsing"
            }
            break
         case "color_temperature":
            if (!hasCT) {
               if (enableDebug == true) "ignoring color_temperature because mirek null"
               return
            }
            eventName = "colorTemperature"
            eventValue = scaleCTFromBridge(value.mirek)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            setGenericTempName(eventValue)
            eventName = "colorMode"
            eventValue = "CT"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         default:
            if (enableDebug == true) "not handling: $key: $value"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the either command map provided
 * @param commandMap Groovy Map (will be converted to JSON) of Hue API commands to send, e.g., [on: true]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if ["on": true] in map)
 */
void sendBridgeCommand(Map commandMap, Boolean createHubEvents=true) {
   if (enableDebug == true) log.debug "sendBridgeCommand($commandMap)"
   if (commandMap == null || commandMap == [:]) {
      if (enableDebug == true) log.debug "Commands not sent to Bridge because command map null or empty"
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights/${getHueDeviceNumber()}/state",
      contentType: 'application/json',
      body: commandMap,
      timeout: 15
   ]
   asynchttpPut("parseSendCommandResponse", params, createHubEvents ? commandMap : null)
   if (enableDebug == true) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug == true) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (enableDebug == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceNumber())
      }
   }
   else {
      if (enableDebug == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}
// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.2 // library marker RMoRobert.CoCoHue_Common_Lib, line 1

// 1.0.2  - HTTP error handling tweaks // library marker RMoRobert.CoCoHue_Common_Lib, line 3

library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 5
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 6
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 7
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 8
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 9
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 10
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 11
) // library marker RMoRobert.CoCoHue_Common_Lib, line 12

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 14
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 15
   device.updateSetting("enableDebug", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 16
} // library marker RMoRobert.CoCoHue_Common_Lib, line 17

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 19
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 20
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 21
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 22
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 23
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 24
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 25
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 26
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 27
      if (resp.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 28
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 29
         if (resp.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 30
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 31
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 32
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 33
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 34
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 35
         if (resp.json instanceof List && resp.json[0]?.error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 36
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 37
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 38
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 39
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 40
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 41
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 42
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 43
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 44
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 45
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 46
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 47
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 48
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 49
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 50
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 51
      if (isOK == true) parent.setBridgeStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 52
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 53
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 54
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 55
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 56
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 57
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 58
} // library marker RMoRobert.CoCoHue_Common_Lib, line 59

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 61
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 62
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 63
   if (settings.enableDesc == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 64
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 65
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 66
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 67
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 68
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 69
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 70
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 71
} // library marker RMoRobert.CoCoHue_Common_Lib, line 72

// ~~~~~ end include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~

// ~~~~~ start include (2) RMoRobert.CoCoHue_Bri_Lib ~~~~~
// Version 1.0.2 // library marker RMoRobert.CoCoHue_Bri_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Bri_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Bri_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Bri_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Bri_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains brightness/level-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Bri_Lib, line 7
   name: "CoCoHue_Bri_Lib", // library marker RMoRobert.CoCoHue_Bri_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Bri_Lib, line 9
) // library marker RMoRobert.CoCoHue_Bri_Lib, line 10

// "SwitchLevel" commands: // library marker RMoRobert.CoCoHue_Bri_Lib, line 12

void startLevelChange(direction) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 14
   if (enableDebug == true) log.debug "startLevelChange($direction)..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 15
   Map cmd = ["bri": (direction == "up" ? 254 : 1), // library marker RMoRobert.CoCoHue_Bri_Lib, line 16
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ? // library marker RMoRobert.CoCoHue_Bri_Lib, line 17
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))] // library marker RMoRobert.CoCoHue_Bri_Lib, line 18
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 19
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 20

void stopLevelChange() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 22
   if (enableDebug == true) log.debug "stopLevelChange()..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 23
   Map cmd = ["bri_inc": 0] // library marker RMoRobert.CoCoHue_Bri_Lib, line 24
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 25
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 26

void setLevel(value) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 28
   if (enableDebug == true) log.debug "setLevel($value)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 29
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000) // library marker RMoRobert.CoCoHue_Bri_Lib, line 30
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 31

void setLevel(Number value, Number rate) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 33
   if (enableDebug == true) log.debug "setLevel($value, $rate)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 34
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_Bri_Lib, line 35
   if (levelStaging) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 36
      log.warn "Level prestaging preference enabled and setLevel() called. This is deprecated and may be removed in the future. Please move to new, standard presetLevel() command." // library marker RMoRobert.CoCoHue_Bri_Lib, line 37
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 38
         presetLevel(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 39
         return // library marker RMoRobert.CoCoHue_Bri_Lib, line 40
      } // library marker RMoRobert.CoCoHue_Bri_Lib, line 41
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 42
   if (value < 0) value = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 43
   else if (value > 100) value = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 44
   else if (value == 0) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 45
      off(rate) // library marker RMoRobert.CoCoHue_Bri_Lib, line 46
      return // library marker RMoRobert.CoCoHue_Bri_Lib, line 47
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 48
   Integer newLevel = scaleBriToBridge(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 49
   Integer scaledRate = (rate * 10).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 50
   Map bridgeCmd = [ // library marker RMoRobert.CoCoHue_Bri_Lib, line 51
      "on": true, // library marker RMoRobert.CoCoHue_Bri_Lib, line 52
      "bri": newLevel, // library marker RMoRobert.CoCoHue_Bri_Lib, line 53
      "transitiontime": scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 54
   ] // library marker RMoRobert.CoCoHue_Bri_Lib, line 55
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_Bri_Lib, line 56
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 57
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_Bri_Lib, line 58
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 59
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_Bri_Lib, line 60
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 61

void presetLevel(Number level) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 63
   if (enableDebug == true) log.debug "presetLevel($level)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 64
   if (level < 0) level = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 65
   else if (level > 100) level = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 66
   Integer newLevel = scaleBriToBridge(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 67
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 1000).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 68
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_Bri_Lib, line 69
   doSendEvent("levelPreset", level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 70
   if (isOn) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 71
      setLevel(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 72
   } else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 73
      state.presetLevel = true // library marker RMoRobert.CoCoHue_Bri_Lib, line 74
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 75
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 76

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 78
 * Reads device preference for on() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 79
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 80
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 81
Integer getScaledOnTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 82
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 83
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 84
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 85
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 86
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 87
      scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 88
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 89
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 90
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 91


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 94
 * Reads device preference for off() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 95
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 96
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 97
Integer getScaledOffTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 98
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 99
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 100
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 101
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 102
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 103
      scaledRate = getScaledOnTransitionTime() // library marker RMoRobert.CoCoHue_Bri_Lib, line 104
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 105
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 106
      scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 107
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 108
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 109
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 110

// Internal methods for scaling // library marker RMoRobert.CoCoHue_Bri_Lib, line 112


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 115
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 116
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 117
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 118
Number scaleBriToBridge(Number hubitatLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 119
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 120
      Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 121
      scaledLevel = Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_Bri_Lib, line 122
      return Math.round(scaledLevel) as Integer // library marker RMoRobert.CoCoHue_Bri_Lib, line 123
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 124
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 125
      BigDecimal scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 126
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 127
      scaledLevel = hubitatLevel == 1 ? 0.0 : hubitatLevel.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP) // library marker RMoRobert.CoCoHue_Bri_Lib, line 128
      return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 129
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 130
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 131

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 133
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 134
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 135
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 136
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 137
   Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 138
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 139
      scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 140
      if (scaledLevel < 1) scaledLevel = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 141
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 142
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 143
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 144
      scaledLevel = Math.round(bridgeLevel <= 1.49 ? 1 : bridgeLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 145
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 146
   return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 147
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 148

// ~~~~~ end include (2) RMoRobert.CoCoHue_Bri_Lib ~~~~~

// ~~~~~ start include (3) RMoRobert.CoCoHue_CT_Lib ~~~~~
// Version 1.0.1 // library marker RMoRobert.CoCoHue_CT_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_CT_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_CT_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_CT_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_CT_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains CT-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_CT_Lib, line 7
    name: "CoCoHue_CT_Lib", // library marker RMoRobert.CoCoHue_CT_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_CT_Lib, line 9
) // library marker RMoRobert.CoCoHue_CT_Lib, line 10

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 12
   if (enableDebug == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)" // library marker RMoRobert.CoCoHue_CT_Lib, line 13
   state.lastKnownColorMode = "CT" // library marker RMoRobert.CoCoHue_CT_Lib, line 14
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_CT_Lib, line 15
   if (colorStaging) { // library marker RMoRobert.CoCoHue_CT_Lib, line 16
      log.warn "Color prestaging preference enabled and setColorTemperature() called. This is deprecated and may be removed in the future. Please move to new presetColorTemperature() command." // library marker RMoRobert.CoCoHue_CT_Lib, line 17
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_CT_Lib, line 18
         presetColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 19
         return // library marker RMoRobert.CoCoHue_CT_Lib, line 20
      } // library marker RMoRobert.CoCoHue_CT_Lib, line 21
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 22
   Integer newCT = scaleCTToBridge(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 23
   Integer scaledRate = defaultLevelTransitionTime/100 // library marker RMoRobert.CoCoHue_CT_Lib, line 24
   if (transitionTime != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 25
      scaledRate = (transitionTime * 10) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 26
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 27
   else if (settings["transitionTime"] != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 28
      scaledRate = ((settings["transitionTime"] as Integer) / 100) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 29
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 30
   Map bridgeCmd = ["on": true, "ct": newCT, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_CT_Lib, line 31
   if (level) { // library marker RMoRobert.CoCoHue_CT_Lib, line 32
      bridgeCmd << ["bri": scaleBriToBridge(level)] // library marker RMoRobert.CoCoHue_CT_Lib, line 33
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 34
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_CT_Lib, line 35
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_CT_Lib, line 36
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_CT_Lib, line 37
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 38
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_CT_Lib, line 39
} // library marker RMoRobert.CoCoHue_CT_Lib, line 40

// Not a standard command (yet?), but I hope it will get implemented as such soon in // library marker RMoRobert.CoCoHue_CT_Lib, line 42
// the same manner as this. Otherwise, subject to change if/when that happens.... // library marker RMoRobert.CoCoHue_CT_Lib, line 43
void presetColorTemperature(Number colorTemperature) { // library marker RMoRobert.CoCoHue_CT_Lib, line 44
   if (enableDebug == true) log.debug "presetColorTemperature($colorTemperature)" // library marker RMoRobert.CoCoHue_CT_Lib, line 45
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_CT_Lib, line 46
   doSendEvent("colorTemperaturePreset", colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 47
   if (isOn) { // library marker RMoRobert.CoCoHue_CT_Lib, line 48
      setColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 49
   } else { // library marker RMoRobert.CoCoHue_CT_Lib, line 50
      state.remove("presetCT") // library marker RMoRobert.CoCoHue_CT_Lib, line 51
      state.presetColorTemperature = true // library marker RMoRobert.CoCoHue_CT_Lib, line 52
      state.presetHue = false // library marker RMoRobert.CoCoHue_CT_Lib, line 53
      state.presetSaturation = false // library marker RMoRobert.CoCoHue_CT_Lib, line 54
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 55
} // library marker RMoRobert.CoCoHue_CT_Lib, line 56

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 58
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units) // library marker RMoRobert.CoCoHue_CT_Lib, line 59
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 60
private Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 61
   Integer mireds = Math.round(1000000/kelvinCT) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 62
   if (checkIfInRange == true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 63
      if (mireds < minMireds) mireds = minMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 64
      else if (mireds > maxMireds) mireds = maxMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 65
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 66
   return mireds // library marker RMoRobert.CoCoHue_CT_Lib, line 67
} // library marker RMoRobert.CoCoHue_CT_Lib, line 68

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 70
 * Scales CT from mireds (Hue units) to Kelvin (Hubitat units) // library marker RMoRobert.CoCoHue_CT_Lib, line 71
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 72
private Integer scaleCTFromBridge(Number mireds) { // library marker RMoRobert.CoCoHue_CT_Lib, line 73
   Integer kelvin = Math.round(1000000/mireds) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 74
   return kelvin // library marker RMoRobert.CoCoHue_CT_Lib, line 75
} // library marker RMoRobert.CoCoHue_CT_Lib, line 76

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 78
 * Reads device preference for CT transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_CT_Lib, line 79
 * can use input(name: ctTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_CT_Lib, line 80
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 81
Integer getScaledCTTransitionTime() { // library marker RMoRobert.CoCoHue_CT_Lib, line 82
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_CT_Lib, line 83
   if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) { // library marker RMoRobert.CoCoHue_CT_Lib, line 84
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_CT_Lib, line 85
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 86
   else if (settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) { // library marker RMoRobert.CoCoHue_CT_Lib, line 87
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : (defaultTransitionTime != null ? defaultTransitionTime : 250) // library marker RMoRobert.CoCoHue_CT_Lib, line 88
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 89
   else { // library marker RMoRobert.CoCoHue_CT_Lib, line 90
      scaledRate = Math.round(settings.ctTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_CT_Lib, line 91
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 92
   return scaledRate // library marker RMoRobert.CoCoHue_CT_Lib, line 93
} // library marker RMoRobert.CoCoHue_CT_Lib, line 94


// Hubitat-provided ct/name mappings // library marker RMoRobert.CoCoHue_CT_Lib, line 97
void setGenericTempName(temp) { // library marker RMoRobert.CoCoHue_CT_Lib, line 98
   if (!temp) return // library marker RMoRobert.CoCoHue_CT_Lib, line 99
   String genericName // library marker RMoRobert.CoCoHue_CT_Lib, line 100
   Integer value = temp.toInteger() // library marker RMoRobert.CoCoHue_CT_Lib, line 101
   if (value <= 2000) genericName = "Sodium" // library marker RMoRobert.CoCoHue_CT_Lib, line 102
   else if (value <= 2100) genericName = "Starlight" // library marker RMoRobert.CoCoHue_CT_Lib, line 103
   else if (value < 2400) genericName = "Sunrise" // library marker RMoRobert.CoCoHue_CT_Lib, line 104
   else if (value < 2800) genericName = "Incandescent" // library marker RMoRobert.CoCoHue_CT_Lib, line 105
   else if (value < 3300) genericName = "Soft White" // library marker RMoRobert.CoCoHue_CT_Lib, line 106
   else if (value < 3500) genericName = "Warm White" // library marker RMoRobert.CoCoHue_CT_Lib, line 107
   else if (value < 4150) genericName = "Moonlight" // library marker RMoRobert.CoCoHue_CT_Lib, line 108
   else if (value <= 5000) genericName = "Horizon" // library marker RMoRobert.CoCoHue_CT_Lib, line 109
   else if (value < 5500) genericName = "Daylight" // library marker RMoRobert.CoCoHue_CT_Lib, line 110
   else if (value < 6000) genericName = "Electronic" // library marker RMoRobert.CoCoHue_CT_Lib, line 111
   else if (value <= 6500) genericName = "Skylight" // library marker RMoRobert.CoCoHue_CT_Lib, line 112
   else if (value < 20000) genericName = "Polar" // library marker RMoRobert.CoCoHue_CT_Lib, line 113
   else genericName = "undefined" // shouldn't happen, but just in case // library marker RMoRobert.CoCoHue_CT_Lib, line 114
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName) // library marker RMoRobert.CoCoHue_CT_Lib, line 115
} // library marker RMoRobert.CoCoHue_CT_Lib, line 116

// ~~~~~ end include (3) RMoRobert.CoCoHue_CT_Lib ~~~~~

// ~~~~~ start include (6) RMoRobert.CoCoHue_HueSat_Lib ~~~~~
// Version 1.0.1 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_HueSat_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_HueSat_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_HueSat_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_HueSat_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains hue/saturation-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_HueSat_Lib, line 7
   name: "CoCoHue_HueSat_Lib", // library marker RMoRobert.CoCoHue_HueSat_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 9
) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 10

void setColor(Map value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 12
   if (enableDebug == true) log.debug "setColor($value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 13
   state.lastKnownColorMode = "RGB" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 14
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 15
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 16
      log.warn "Color prestaging preference enabled and setColor() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 17
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 18
         presetColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 19
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 20
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 21
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 22
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 23
      if (enableDebug == true) log.debug "Exiting setColor because no hue and/or saturation set" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 24
      return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 25
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 26
   Map bridgeCmd  // library marker RMoRobert.CoCoHue_HueSat_Lib, line 27
   Integer newHue = scaleHueToBridge(value.hue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 28
   Integer newSat = scaleSatToBridge(value.saturation) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 29
   Integer newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null // library marker RMoRobert.CoCoHue_HueSat_Lib, line 30
   Integer scaledRate = value.rate != null ? Math.round(value.rate * 10).toInteger() : getScaledRGBTransitionTime() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 31
   if (scaledRate == null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 32
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 33
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 34
   else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 35
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 36
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 37
   if (newBri) bridgeCmd << ["bri": newBri] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 38
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 39
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 40
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 41
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 42
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 43
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 44

// Really a hack to get this usable from the admin UI since you can only have one COLOR_MAP input, which // library marker RMoRobert.CoCoHue_HueSat_Lib, line 46
// is already implicitly taken by setColor(). Accepts JSON object like {"hue": 10, "saturation": 100, "level": 50} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 47
// and will convert to Groovy map for use with other implenentation of this command (which I hope will be standardized // library marker RMoRobert.CoCoHue_HueSat_Lib, line 48
// some day..) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 49
void presetColor(String jsonValue) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 50
   if (enableDebug == true) log.debug "presetColor(String $jsonValue)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 51
   Map value = new groovy.json.JsonSlurper().parseText(jsonValue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 52
   presetColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 53
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 54

// Not currently a standard Hubitat command, so implementation subject to change if it becomes one; // library marker RMoRobert.CoCoHue_HueSat_Lib, line 56
// for now, assuming it may be done by taking a color map like setColor() (but see also JSON variant above) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 57
// May also need presetHue() and presetSaturation(), but not including for now... // library marker RMoRobert.CoCoHue_HueSat_Lib, line 58
void presetColor(Map value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 59
   if (enableDebug == true) log.debug "presetColor(Map $value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 60
   if (value.hue != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 61
      doSendEvent("huePreset", value.hue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 62
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 63
   if (value.saturation != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 64
      doSendEvent("saturationPreset", value.saturation) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 65
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 66
   if (value.level != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 67
      doSendEvent("levelPreset", value.level) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 68
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 69
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 70
   if (isOn) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 71
      setColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 72
   } else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 73
      state.presetHue = (value.hue != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 74
      state.presetSaturation = (value.saturation != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 75
      state.presetLevel = (value.level != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 76
      state.presetColorTemperature = false // library marker RMoRobert.CoCoHue_HueSat_Lib, line 77
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 78
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 79

void setHue(value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 81
   if (enableDebug == true) log.debug "setHue($value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 82
   state.lastKnownColorMode = "RGB" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 83
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 84
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 85
      log.warn "Color prestaging preference enabled and setHue() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 86
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 87
         presetColor([hue: value]) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 88
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 89
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 90
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 91
   Integer newHue = scaleHueToBridge(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 92
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 93
   Map bridgeCmd = ["on": true, "hue": newHue, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 94
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 95
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 96
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 97
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 98
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 99
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 100

void setSaturation(value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 102
   if (enableDebug == true) log.debug "setSaturation($value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 103
   state.lastKnownColorMode = "RGB" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 104
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 105
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 106
      log.warn "Color prestaging preference enabled and setSaturation() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 107
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 108
         presetColor([saturation: value]) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 109
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 110
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 111
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 112
   Integer newSat = scaleSatToBridge(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 113
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 114
   Map bridgeCmd = ["on": true, "sat": newSat, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 115
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 116
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 117
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 118
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 119
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 120
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 121

Integer scaleHueToBridge(hubitatLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 123
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 124
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 125
   else if (scaledLevel > 65535) scaledLevel = 65535 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 126
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 127
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 128

Integer scaleHueFromBridge(bridgeLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 130
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100)) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 131
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 132
   else if (scaledLevel > 360) scaledLevel = 360 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 133
   else if (scaledLevel > 100 && !hiRezHue) scaledLevel = 100 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 134
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 135
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 136

Integer scaleSatToBridge(hubitatLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 138
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 139
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 140
   else if (scaledLevel > 254) scaledLevel = 254 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 141
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 142
   return scaleHueFromBridge() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 143
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 144

Integer scaleSatFromBridge(bridgeLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 146
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 147
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 148
   else if (scaledLevel > 100) scaledLevel = 100 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 149
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 150
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 151


/** // library marker RMoRobert.CoCoHue_HueSat_Lib, line 154
 * Reads device preference for setColor/RGB transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_HueSat_Lib, line 155
 * can use input(name: rgbTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_HueSat_Lib, line 156
 */ // library marker RMoRobert.CoCoHue_HueSat_Lib, line 157
Integer getScaledRGBTransitionTime() { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 158
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_HueSat_Lib, line 159
   if (settings.rgbTransitionTime == null || settings.rgbTransitionTime == "-2" || settings.rgbTransitionTime == -2) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 160
      // keep null; will result in not specifying with command // library marker RMoRobert.CoCoHue_HueSat_Lib, line 161
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 162
   else if (settings.rgbTransitionTime == "-1" || settings.rgbTransitionTime == -1) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 163
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : defaultTransitionTime // library marker RMoRobert.CoCoHue_HueSat_Lib, line 164
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 165
   else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 166
      scaledRate = Math.round(settings.rgbTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 167
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 168
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 169

// Hubiat-provided color/name mappings // library marker RMoRobert.CoCoHue_HueSat_Lib, line 171
void setGenericName(hue) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 172
   String colorName // library marker RMoRobert.CoCoHue_HueSat_Lib, line 173
   hue = hue.toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 174
   if (!hiRezHue) hue = (hue * 3.6) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 175
   switch (hue.toInteger()) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 176
      case 0..15: colorName = "Red" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 177
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 178
      case 16..45: colorName = "Orange" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 179
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 180
      case 46..75: colorName = "Yellow" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 181
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 182
      case 76..105: colorName = "Chartreuse" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 183
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 184
      case 106..135: colorName = "Green" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 185
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 186
      case 136..165: colorName = "Spring" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 187
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 188
      case 166..195: colorName = "Cyan" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 189
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 190
      case 196..225: colorName = "Azure" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 191
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 192
      case 226..255: colorName = "Blue" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 193
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 194
      case 256..285: colorName = "Violet" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 195
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 196
      case 286..315: colorName = "Magenta" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 197
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 198
      case 316..345: colorName = "Rose" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 199
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 200
      case 346..360: colorName = "Red" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 201
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 202
      default: colorName = "undefined" // shouldn't happen, but just in case // library marker RMoRobert.CoCoHue_HueSat_Lib, line 203
         break             // library marker RMoRobert.CoCoHue_HueSat_Lib, line 204
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 205
   if (device.currentValue("saturation") < 1) colorName = "White" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 206
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 207
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 208

// ~~~~~ end include (6) RMoRobert.CoCoHue_HueSat_Lib ~~~~~

// ~~~~~ start include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Flash_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Flash_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Flash_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Flash_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Flash_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains flash-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Flash_Lib, line 7
   name: "CoCoHue_Flash_Lib", // library marker RMoRobert.CoCoHue_Flash_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Flash_Lib, line 9
) // library marker RMoRobert.CoCoHue_Flash_Lib, line 10

void flash() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 12
   if (enableDebug == true) log.debug "flash()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 13
   if (settings.enableDesc == true) log.info("${device.displayName} started 15-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 14
   Map<String,String> cmd = ["alert": "lselect"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 15
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 16
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 17

void flashOnce() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 19
   if (enableDebug == true) log.debug "flashOnce()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 20
   if (settings.enableDesc == true) log.info("${device.displayName} started 1-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 21
   Map<String,String> cmd = ["alert": "select"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 22
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 23
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 24

void flashOff() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 26
   if (enableDebug == true) log.debug "flashOff()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 27
   if (settings.enableDesc == true) log.info("${device.displayName} was sent command to stop flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 28
   Map<String,String> cmd = ["alert": "none"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 29
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 30
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 31

// ~~~~~ end include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~

// ~~~~~ start include (4) RMoRobert.CoCoHue_Effect_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Effect_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Effect_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Effect_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Effect_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Effect_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains effects-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Effect_Lib, line 7
   name: "CoCoHue_Effect_Lib", // library marker RMoRobert.CoCoHue_Effect_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Effect_Lib, line 9
) // library marker RMoRobert.CoCoHue_Effect_Lib, line 10

void setEffect(String effect) { // library marker RMoRobert.CoCoHue_Effect_Lib, line 12
   if (enableDebug == true) log.debug "setEffect($effect)" // library marker RMoRobert.CoCoHue_Effect_Lib, line 13
   def id = lightEffects.find { it.value == effect } // library marker RMoRobert.CoCoHue_Effect_Lib, line 14
   if (id != null) setEffect(id.key) // library marker RMoRobert.CoCoHue_Effect_Lib, line 15
} // library marker RMoRobert.CoCoHue_Effect_Lib, line 16

void setEffect(Integer id) { // library marker RMoRobert.CoCoHue_Effect_Lib, line 18
   if (enableDebug == true) log.debug "setEffect($id)" // library marker RMoRobert.CoCoHue_Effect_Lib, line 19
   sendBridgeCommand(["effect": (id == 1 ? "colorloop" : "none"), "on": true]) // library marker RMoRobert.CoCoHue_Effect_Lib, line 20
} // library marker RMoRobert.CoCoHue_Effect_Lib, line 21

void setNextEffect() { // library marker RMoRobert.CoCoHue_Effect_Lib, line 23
   if (enableDebug == true) log.debug"setNextEffect()" // library marker RMoRobert.CoCoHue_Effect_Lib, line 24
   Integer currentEffect = state.crntEffectId ?: 0 // library marker RMoRobert.CoCoHue_Effect_Lib, line 25
   currentEffect++ // library marker RMoRobert.CoCoHue_Effect_Lib, line 26
   if (currentEffect > maxEffectNumber) currentEffect = 0 // library marker RMoRobert.CoCoHue_Effect_Lib, line 27
   setEffect(currentEffect) // library marker RMoRobert.CoCoHue_Effect_Lib, line 28
} // library marker RMoRobert.CoCoHue_Effect_Lib, line 29

void setPreviousEffect() { // library marker RMoRobert.CoCoHue_Effect_Lib, line 31
   if (enableDebug == true) log.debug "setPreviousEffect()" // library marker RMoRobert.CoCoHue_Effect_Lib, line 32
   Integer currentEffect = state.crntEffectId ?: 0 // library marker RMoRobert.CoCoHue_Effect_Lib, line 33
   currentEffect-- // library marker RMoRobert.CoCoHue_Effect_Lib, line 34
   if (currentEffect < 0) currentEffect = 1 // library marker RMoRobert.CoCoHue_Effect_Lib, line 35
   setEffect(currentEffect) // library marker RMoRobert.CoCoHue_Effect_Lib, line 36
} // library marker RMoRobert.CoCoHue_Effect_Lib, line 37


// ~~~~~ end include (4) RMoRobert.CoCoHue_Effect_Lib ~~~~~

// ~~~~~ start include (7) RMoRobert.CoCoHue_Prestage_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Prestage_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Prestage_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Prestage_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Prestage_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Prestage_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains prestaging-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Prestage_Lib, line 7
   name: "CoCoHue_Prestage_Lib", // library marker RMoRobert.CoCoHue_Prestage_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Prestage_Lib, line 9
) // library marker RMoRobert.CoCoHue_Prestage_Lib, line 10

// Note: includes internal driver methods only; actual "prestating"/"preset" commands are in driver or other library // library marker RMoRobert.CoCoHue_Prestage_Lib, line 12

/** // library marker RMoRobert.CoCoHue_Prestage_Lib, line 14
 * Returns Map containing any commands that would need to be sent to Bridge if anything is currently prestaged. // library marker RMoRobert.CoCoHue_Prestage_Lib, line 15
 * Otherwise, returns empty Map. // library marker RMoRobert.CoCoHue_Prestage_Lib, line 16
 * @param unsetPrestagingState If set to true (default), clears prestage flag // library marker RMoRobert.CoCoHue_Prestage_Lib, line 17
*/ // library marker RMoRobert.CoCoHue_Prestage_Lib, line 18
Map getPrestagedCommands(Boolean unsetPrestagingState=true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 19
   if (enableDebug == true) log.debug "getPrestagedCommands($unsetPrestagingState)" // library marker RMoRobert.CoCoHue_Prestage_Lib, line 20
   Map cmds = [:] // library marker RMoRobert.CoCoHue_Prestage_Lib, line 21
   if (state.presetLevel == true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 22
      cmds << [bri: scaleBriToBridge(device.currentValue("levelPreset"))] // library marker RMoRobert.CoCoHue_Prestage_Lib, line 23
   } // library marker RMoRobert.CoCoHue_Prestage_Lib, line 24
   if (state.presetColorTemperature == true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 25
      cmds << [ct: scaleCTToBridge(device.currentValue("colorTemperaturePreset"))] // library marker RMoRobert.CoCoHue_Prestage_Lib, line 26
   } // library marker RMoRobert.CoCoHue_Prestage_Lib, line 27
   if (state.presetHue == true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 28
      cmds << [hue: scaleHueToBridge(device.currentValue("huePreset"))] // library marker RMoRobert.CoCoHue_Prestage_Lib, line 29
   } // library marker RMoRobert.CoCoHue_Prestage_Lib, line 30
   if (state.presetSaturation == true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 31
      cmds << [sat: scaleSatToBridge(device.currentValue("saturationPreset"))] // library marker RMoRobert.CoCoHue_Prestage_Lib, line 32
   } // library marker RMoRobert.CoCoHue_Prestage_Lib, line 33
   if (unsetPrestagingState == true) { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 34
      clearPrestagedCommands() // library marker RMoRobert.CoCoHue_Prestage_Lib, line 35
   } // library marker RMoRobert.CoCoHue_Prestage_Lib, line 36
   if (enableDebug == true) log.debug "Returning: $cmds" // library marker RMoRobert.CoCoHue_Prestage_Lib, line 37
   return cmds // library marker RMoRobert.CoCoHue_Prestage_Lib, line 38
} // library marker RMoRobert.CoCoHue_Prestage_Lib, line 39

void clearPrestagedCommands() { // library marker RMoRobert.CoCoHue_Prestage_Lib, line 41
   state.presetLevel = false // library marker RMoRobert.CoCoHue_Prestage_Lib, line 42
   state.presetColorTemperature = false // library marker RMoRobert.CoCoHue_Prestage_Lib, line 43
   state.presetHue = false // library marker RMoRobert.CoCoHue_Prestage_Lib, line 44
   state.presetSaturation = false // library marker RMoRobert.CoCoHue_Prestage_Lib, line 45
} // library marker RMoRobert.CoCoHue_Prestage_Lib, line 46

// ~~~~~ end include (7) RMoRobert.CoCoHue_Prestage_Lib ~~~~~

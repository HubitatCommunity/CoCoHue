/*
 * =============================  CoCoHue RGBW Bulb (Driver) ===============================
 *
 *  Copyright 2019-2022 Robert Morris
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
 *  Last modified: 2022-01-02
 *
 *  Changelog:
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

#include RMoRobert.CoCoHue_Common_Lib
#include RMoRobert.CoCoHue_Bri_Lib
#include RMoRobert.CoCoHue_CT_Lib
#include RMoRobert.CoCoHue_HueSat_Lib
#include RMoRobert.CoCoHue_Flash_Lib
#include RMoRobert.CoCoHue_Effect_Lib
#include RMoRobert.CoCoHue_Prestage_Lib

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
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : getScaledOffTransitionTime()
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
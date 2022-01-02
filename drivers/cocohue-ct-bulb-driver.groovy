/*
 * =============================  CoCoHue CT Bulb (Driver) ===============================
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
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added "reachable" attribte
              from Bridge to bulb and group drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1.1  - Fix for setColorTempeature() not turning bulb on in some cases
 *  v3.1    - Improved error handling and debug logging; added optional setColorTemperature parameters
 *  v3.0    - Fix so events no created until Bridge response received (as was done for other drivers in 2.0); improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - More static typing
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Initial release (based on RGBW bulb driver)
 */ 







import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

@Field static final Integer debugAutoDisableMinutes = 30

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
// (for CT devices, should cover most things)
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on", "ct", "bri"]

metadata {
   definition(name: "CoCoHue CT Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-ct-bulb-driver.groovy") {
      capability "Actuator"
      capability "ColorTemperature"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "LevelPreset"
      capability "ChangeLevel"
      capability "Light"

      command "flash"
      command "flashOnce"
      command "flashOff"

      // Not (yet?) standard, but hopefully will be standardized soon (and similar to this--as analagous to LevelPreset as possible):
      command "presetColorTemperature", [[name:"Color temperature*",type:"NUMBER", description:"Color temperature to prestage", constraints:["NUMBER"]]]
      attribute "colorTemperaturePreset", "number"

      attribute "reachable", "string"
   }

   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      if (colorStaging) input name: "colorStaging", type: "bool", description: "DEPRECATED. Please use new presetColorTemperature() instead. May be removed in future.", title: "Enable color pseudo-prestaging", defaultValue: false
      if (levelStaging) input name: "levelStaging", type: "bool", description: "DEPRECATED. Please use new presetLevel() command instead. May be removed in future.", title: "Enable level pseudo-prestaging", defaultValue: false
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"      
      input name: "ctTransitionTime", type: "enum", description: "", title: "Color temperature transition time", options:
         [[(-2): "Hue default/do not specify (default)"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when bulb state changes",
         defaultValue: false
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
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
   log.warn("Running unimplemented parse for: '${description}'")
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
   Map bridgeCmd = ["on": true]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (enableDebug == true) log.debug "off()"
   Map bridgeCmd = ["on": false]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   sendBridgeCommand(bridgeCmd)
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
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
            eventValue = scaleBriFromBridge(it.value)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "ct":
            eventName = "colorTemperature"
            eventValue = scaleCTFromBridge(it.value)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            setGenericTempName(eventValue)
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
// Version 1.0.1 // library marker RMoRobert.CoCoHue_Common_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 7
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 9
) // library marker RMoRobert.CoCoHue_Common_Lib, line 10

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 12
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 13
   device.updateSetting("enableDebug", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 14
} // library marker RMoRobert.CoCoHue_Common_Lib, line 15

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 17
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 18
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 19
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 20
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 21
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 22
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 23
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 24
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 25
      if (resp?.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 26
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 27
         if (resp?.headers == null) log.error "Error: HTTP ${resp?.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 28
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 29
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 30
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 31
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 32
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 33
         if (resp.json[0]?.error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 34
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 35
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 36
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 37
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 38
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 39
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 40
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 41
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 42
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 43
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 44
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 45
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 46
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 47
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 48
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 49
      if (isOK == true) parent.setBridgeStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 50
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 51
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 52
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 53
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 54
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 55
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 56
} // library marker RMoRobert.CoCoHue_Common_Lib, line 57

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 59
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 60
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 61
   if (settings.enableDesc == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 62
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 63
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 64
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 65
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 66
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 67
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 68
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 69
} // library marker RMoRobert.CoCoHue_Common_Lib, line 70

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

/*
 * =============================  CoCoHue Dimmable Bulb (Driver) ===============================
 *
 *  Copyright 2019-2024 Robert Morris
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
 *  Last modified: 2024-07-29
 * 
 *  Changelog:
 *  v4.2    - Library updates, prep for more v2 API
 *  v4.1.7  - Fix for unexpected Hubitat event creation when v2 API reports level of 0
 *  v4.1.5  - Improved v2 brightness parsing
 *  v4.0.2  - Fix to avoid unepected "off" transition time
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added "reachable" attribte
              from Bridge to bulb and group drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Fix so events no created until Bridge response received (as was done for other drivers in 2.0); improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - Minor code cleanup and more static typing
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Initial release (based on CT bulb driver)
 */






import groovy.transform.Field
import hubitat.scheduling.AsyncResponse


@Field static final Integer debugAutoDisableMinutes = 30

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
// (for dim-only devices, should cover everything...)
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on", "bri"]

metadata {
   definition(name: "CoCoHue Dimmable Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-dimmable-bulb-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "LevelPreset"
      capability "ChangeLevel"
      capability "Light"

      command "flash"
      command "flashOnce"
      command "flashOff"

      attribute "reachable", "string"
   }
         
   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      if (levelStaging) input name: "levelStaging", type: "bool", description: "DEPRECATED. Please use new presetLevel() command instead. May be removed in future.", title: "Enable level pseudo-prestaging", defaultValue: false
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
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
            if (device.currentValue(eventName) != eventValue && eventValue > 0) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
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
// Version 1.0.4 // library marker RMoRobert.CoCoHue_Bri_Lib, line 1

// 1.0.4  - accept String for setLevel() level also  // library marker RMoRobert.CoCoHue_Bri_Lib, line 3
// 1.0.3  - levelhandling tweaks // library marker RMoRobert.CoCoHue_Bri_Lib, line 4

library ( // library marker RMoRobert.CoCoHue_Bri_Lib, line 6
   base: "driver", // library marker RMoRobert.CoCoHue_Bri_Lib, line 7
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Bri_Lib, line 8
   category: "Convenience", // library marker RMoRobert.CoCoHue_Bri_Lib, line 9
   description: "For internal CoCoHue use only. Not intended for external use. Contains brightness/level-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Bri_Lib, line 10
   name: "CoCoHue_Bri_Lib", // library marker RMoRobert.CoCoHue_Bri_Lib, line 11
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Bri_Lib, line 12
) // library marker RMoRobert.CoCoHue_Bri_Lib, line 13

// "SwitchLevel" commands: // library marker RMoRobert.CoCoHue_Bri_Lib, line 15

void startLevelChange(String direction) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 17
   if (enableDebug == true) log.debug "startLevelChange($direction)..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 18
   Map cmd = ["bri": (direction == "up" ? 254 : 1), // library marker RMoRobert.CoCoHue_Bri_Lib, line 19
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ? // library marker RMoRobert.CoCoHue_Bri_Lib, line 20
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))] // library marker RMoRobert.CoCoHue_Bri_Lib, line 21
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 22
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 23

void stopLevelChange() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 25
   if (enableDebug == true) log.debug "stopLevelChange()..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 26
   Map cmd = ["bri_inc": 0] // library marker RMoRobert.CoCoHue_Bri_Lib, line 27
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 28
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 29

void setLevel(value) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 31
   if (enableDebug == true) log.debug "setLevel($value)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 32
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000) // library marker RMoRobert.CoCoHue_Bri_Lib, line 33
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 34

void setLevel(Number value, Number rate) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 36
   if (enableDebug == true) log.debug "setLevel($value, $rate)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 37
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_Bri_Lib, line 38
   if (levelStaging) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 39
      log.warn "Level prestaging preference enabled and setLevel() called. This is deprecated and may be removed in the future. Please move to new, standard presetLevel() command." // library marker RMoRobert.CoCoHue_Bri_Lib, line 40
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 41
         presetLevel(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 42
         return // library marker RMoRobert.CoCoHue_Bri_Lib, line 43
      } // library marker RMoRobert.CoCoHue_Bri_Lib, line 44
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 45
   if (value < 0) value = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 46
   else if (value > 100) value = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 47
   else if (value == 0) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 48
      off(rate) // library marker RMoRobert.CoCoHue_Bri_Lib, line 49
      return // library marker RMoRobert.CoCoHue_Bri_Lib, line 50
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 51
   Integer newLevel = scaleBriToBridge(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 52
   Integer scaledRate = (rate * 10).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 53
   Map bridgeCmd = [ // library marker RMoRobert.CoCoHue_Bri_Lib, line 54
      "on": true, // library marker RMoRobert.CoCoHue_Bri_Lib, line 55
      "bri": newLevel, // library marker RMoRobert.CoCoHue_Bri_Lib, line 56
      "transitiontime": scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 57
   ] // library marker RMoRobert.CoCoHue_Bri_Lib, line 58
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_Bri_Lib, line 59
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 60
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_Bri_Lib, line 61
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 62
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_Bri_Lib, line 63
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 64

void setLevel(value, rate) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 66
   if (enableDebug == true) log.debug "setLevel(Object $value, Object $rate)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 67
   Float floatLevel = Float.parseFloat(value.toString()) // library marker RMoRobert.CoCoHue_Bri_Lib, line 68
   Integer intLevel = Math.round(floatLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 69
   Float floatRate = Float.parseFloat(rate.toString()) // library marker RMoRobert.CoCoHue_Bri_Lib, line 70
   setLevel(intLevel, floatRate) // library marker RMoRobert.CoCoHue_Bri_Lib, line 71
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 72

void presetLevel(Number level) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 74
   if (enableDebug == true) log.debug "presetLevel($level)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 75
   if (level < 0) level = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 76
   else if (level > 100) level = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 77
   Integer newLevel = scaleBriToBridge(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 78
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 1000).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 79
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_Bri_Lib, line 80
   doSendEvent("levelPreset", level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 81
   if (isOn) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 82
      setLevel(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 83
   } else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 84
      state.presetLevel = true // library marker RMoRobert.CoCoHue_Bri_Lib, line 85
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 86
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 87

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 89
 * Reads device preference for on() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 90
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 91
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 92
Integer getScaledOnTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 93
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 94
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 95
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 96
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 97
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 98
      scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 99
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 100
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 101
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 102


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 105
 * Reads device preference for off() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 106
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 107
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 108
Integer getScaledOffTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 109
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 110
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 111
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 112
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 113
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 114
      scaledRate = getScaledOnTransitionTime() // library marker RMoRobert.CoCoHue_Bri_Lib, line 115
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 116
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 117
      scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 118
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 119
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 120
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 121

// Internal methods for scaling // library marker RMoRobert.CoCoHue_Bri_Lib, line 123


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 126
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 127
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 128
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 129
Number scaleBriToBridge(Number hubitatLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 130
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 131
      Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 132
      scaledLevel = Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_Bri_Lib, line 133
      return Math.round(scaledLevel) as Integer // library marker RMoRobert.CoCoHue_Bri_Lib, line 134
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 135
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 136
      BigDecimal scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 137
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 138
      scaledLevel = hubitatLevel == 1 ? 0.0 : hubitatLevel.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP) // library marker RMoRobert.CoCoHue_Bri_Lib, line 139
      return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 140
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 141
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 142

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 144
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 145
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 146
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 147
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 148
   Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 149
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 150
      scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 151
      if (scaledLevel < 1) scaledLevel = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 152
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 153
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 154
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 155
      scaledLevel = Math.round(bridgeLevel <= 1.49 && bridgeLevel > 0.001 ? 1 : bridgeLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 156
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 157
   return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 158
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 159

// ~~~~~ end include (2) RMoRobert.CoCoHue_Bri_Lib ~~~~~

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

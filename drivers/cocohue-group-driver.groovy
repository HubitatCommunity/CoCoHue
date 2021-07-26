/*
 * =============================  CoCoHue Group (Driver) ===============================
 *
 *  Copyright 2019-2021 Robert Morris
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
 *  Last modified: 2021-07-25
 *
 *  Changelog:
 *  v3.5.2 -  setColor() fix (refactor code into library--was not previously)
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added preliminary color
 *            and CT prestating coommands; added "reachable" attribte from Bridge to bulb and group
 *            drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1.1  - Fix for setColorTempeature() not turning bulb on in some cases
 *  v3.1    - Improved error handling and debug logging; added optional setColorTemperature parameters
 *  v3.0    - Improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - Added optional rate to setColor per Hubitat (used by Hubitat Groups and Scenes); more static typing;
 *            GroupScenes for this group will now also be marked as off (if option enabled) when Bridge reports all group bulbs as off instead of only when off() sent
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Parse xy as ct (previously did rgb but without parsing actual color)
 *  v1.8c   - Added back color/CT events for manual commands not from bridge without polling
 *  v1.8b   - Skip spurious color name event if bulb not in correct mode
 *  v1.8    - Changed effect state to custom attribute instead of colorMode
 *            Added ability to disable group->bulb state propagation
 *  v1.7b   - Modified startLevelChange behavior to avoid possible problems with third-party devices
 *            Removed ["alert:" "none"] from on() command, now possible explicitly with flashOff()
 *  v1.7    - Bulb switch/level states now propgate to groups w/o polling
 *  v1.6b   - Changed bri_inc to match Hubitat behavior
 *  v1.5b   - Eliminated duplicate color/CT events on refresh
 *  v1.5    - Group switch/level/etc. states now propagated to member bulbs w/o polling
 *  v1.1    - Added parity with bulb features (effects, etc.)
 *  v1.0    - Initial Release
 */ 









import groovy.transform.Field

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

@Field static final Map<Integer,String> lightEffects = [0: "None", 1:"Color Loop"]
@Field static final Integer maxEffectNumber = 1

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

metadata {
   definition(name: "CoCoHue Group", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy") {
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
      command "presetColor", [[name:"Color Map*", type:"JSON_OBJECT", description:"Color to prestage (Map with keys: hue, saturation, value; also accepts JSON object for better UI compatibility, subject to change)"]]
      attribute "colorTemperaturePreset", "number"
      attribute "huePreset", "number"
      attribute "saturationPreset", "number"
      
      attribute "effect", "string"
      attribute "reachable", "string"
   }
       
   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      input name: "hiRezHue", type: "bool", title: "Enable hue in degrees (0-360 instead of 0-100)", defaultValue: false
      if (colorStaging) input name: "colorStaging", type: "bool", description: "DEPRECATED. Please use new prestaging commands instead. May be removed in future.", title: "Enable color pseudo-prestaging", defaultValue: false
      if (levelStaging) input name: "levelStaging", type: "bool", description: "DEPRECATED. Please use new presetLevel() command instead. May be removed in future.", title: "Enable level pseudo-prestaging", defaultValue: false
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
      input name: "updateBulbs", type: "bool", description: "", title: "Update member bulb states immediately when group state changes",
         defaultValue: true
      input name: "updateScenes", type: "bool", description: "", title: "Mark all GroupScenes for this group as off when group device turns off",
         defaultValue: true
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
   Integer disableMinutes = 30
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableMinutes} minutes"
      runIn(disableMinutes*60, debugOff)
   }
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Group/HueDeviceID", so just
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
void createEventsFromMap(Map bridgeCommandMap, Boolean isFromBridge = false) {
   if (!bridgeCommandMap) {
      if (enableDebug == true) log.debug "createEventsFromMap called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (enableDebug == true) log.debug "Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   String eventName, eventUnit, descriptionText
   def eventValue // could be string or number
   String colorMode = bridgeMap["colormode"]
   if (isFromBridge && bridgeMap["colormode"] == "xy") {
      colorMode == "ct"
      if (enableDebug == true) log.debug "In XY mode but parsing as CT"
   }
   Boolean isOn = bridgeMap["any_on"]
   bridgeMap.each {
      switch (it.key) {
         case "on":
            if (isFromBridge) break
         case "any_on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
               if (eventValue == "off" && settings["updateScenes"] != false) {
                  parent.updateSceneStateToOffForGroup(getHueDeviceNumber())
               }
            }
            break
         case "bri":
            eventName = "level"
            eventValue = scaleBriFromBridge(it.value)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "colormode":
            eventName = "colorMode"
            eventValue = (it.value == "hs" ? "RGB" : "CT")
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "ct":
            eventName = "colorTemperature"
            eventValue = it.value != 0 ? scaleCTFromBridge(it.value) : 0
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
      path: "/api/${data.username}/groups/${getHueDeviceNumber()}/action",
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
void parseSendCommandResponse(resp, data) {
   if (enableDebug == true) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (enableDebug == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateBulbs"]) {
         parent.updateMemberBulbStatesFromGroup(data, state.memberBulbs, device.getDeviceNetworkId().endsWith('/0'))
      }
      if (data["on"] == false && settings["updateScenes"] != false) {
         parent.updateSceneStateToOffForGroup(getHueDeviceNumber())
      }
   }
   else {
      if (enableDebug == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

/**
 *  Sets state.memberBulbs to IDs of bulbs contained in this group; used to manipulate CoCoHue member
 *  bulb states (e.g., on, off, level, etc.) when group state changed so this info propogates faster than
 *  polling (or if polling disabled)
 */ 
void setMemberBulbIDs(List ids) {
   state.memberBulbs = ids
}

/**
 *  Returns Hue IDs of member bulbs (see setMemberBulbIDs for use case; exposed for use by bridge child app)
 */
List getMemberBulbIDs() {
   return state.memberBulbs
}

/**
 * Sets all group attribute values to something, intended to be called when device initially created to avoid
 * missing attribute values (may cause problems with GH integration, etc. otherwise). Default values are
 * approximately warm white and off.
 */
private void setDefaultAttributeValues() {
   if (enableDebug == true) log.debug "Setting group device states to sensibile default values..."
   Map defaultValues = [any_on: false, bri: 254, hue: 8593, sat: 121, ct: 370 ]
   createEventsFromMap(defaultValues)
}
// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Common_Lib, line 1

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
private Boolean checkIfValidResponse(resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 22
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
// Version 1.0.1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 1

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

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 114
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 // library marker RMoRobert.CoCoHue_Bri_Lib, line 115
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 116
Integer scaleBriToBridge(hubitatLevel) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 117
   Integer scaledLevel =  Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_Bri_Lib, line 118
   return Math.round(scaledLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 119
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 120

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 122
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 123
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 124
Integer scaleBriFromBridge(bridgeLevel) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 125
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 126
   if (scaledLevel < 1) scaledLevel = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 127
   return Math.round(scaledLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 128
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 129

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
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_CT_Lib, line 14
   if (colorStaging) { // library marker RMoRobert.CoCoHue_CT_Lib, line 15
      log.warn "Color prestaging preference enabled and setColorTemperature() called. This is deprecated and may be removed in the future. Please move to new presetColorTemperature() command." // library marker RMoRobert.CoCoHue_CT_Lib, line 16
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_CT_Lib, line 17
         presetColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 18
         return // library marker RMoRobert.CoCoHue_CT_Lib, line 19
      } // library marker RMoRobert.CoCoHue_CT_Lib, line 20
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 21
   Integer newCT = scaleCTToBridge(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 22
   Integer scaledRate = defaultLevelTransitionTime/100 // library marker RMoRobert.CoCoHue_CT_Lib, line 23
   if (transitionTime != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 24
      scaledRate = (transitionTime * 10) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 25
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 26
   else if (settings["transitionTime"] != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 27
      scaledRate = ((settings["transitionTime"] as Integer) / 100) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 28
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 29
   Map bridgeCmd = ["on": true, "ct": newCT, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_CT_Lib, line 30
   if (level) { // library marker RMoRobert.CoCoHue_CT_Lib, line 31
      bridgeCmd << ["bri": scaleBriToBridge(level)] // library marker RMoRobert.CoCoHue_CT_Lib, line 32
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 33
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_CT_Lib, line 34
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_CT_Lib, line 35
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_CT_Lib, line 36
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 37
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_CT_Lib, line 38
} // library marker RMoRobert.CoCoHue_CT_Lib, line 39

// Not a standard command (yet?), but I hope it will get implemented as such soon in // library marker RMoRobert.CoCoHue_CT_Lib, line 41
// the same manner as this. Otherwise, subject to change if/when that happens.... // library marker RMoRobert.CoCoHue_CT_Lib, line 42
void presetColorTemperature(Number colorTemperature) { // library marker RMoRobert.CoCoHue_CT_Lib, line 43
   if (enableDebug == true) log.debug "presetColorTemperature($colorTemperature)" // library marker RMoRobert.CoCoHue_CT_Lib, line 44
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_CT_Lib, line 45
   doSendEvent("colorTemperaturePreset", colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 46
   if (isOn) { // library marker RMoRobert.CoCoHue_CT_Lib, line 47
      setColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 48
   } else { // library marker RMoRobert.CoCoHue_CT_Lib, line 49
      state.remove("presetCT") // library marker RMoRobert.CoCoHue_CT_Lib, line 50
      state.presetColorTemperature = true // library marker RMoRobert.CoCoHue_CT_Lib, line 51
      state.presetHue = false // library marker RMoRobert.CoCoHue_CT_Lib, line 52
      state.presetSaturation = false // library marker RMoRobert.CoCoHue_CT_Lib, line 53
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 54
} // library marker RMoRobert.CoCoHue_CT_Lib, line 55

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 57
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units) // library marker RMoRobert.CoCoHue_CT_Lib, line 58
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 59
private Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 60
   Integer mireds = Math.round(1000000/kelvinCT) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 61
   if (checkIfInRange == true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 62
      if (mireds < minMireds) mireds = minMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 63
      else if (mireds > maxMireds) mireds = maxMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 64
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 65
   return mireds // library marker RMoRobert.CoCoHue_CT_Lib, line 66
} // library marker RMoRobert.CoCoHue_CT_Lib, line 67

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 69
 * Scales CT from mireds (Hue units) to Kelvin (Hubitat units) // library marker RMoRobert.CoCoHue_CT_Lib, line 70
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 71
private Integer scaleCTFromBridge(Number mireds) { // library marker RMoRobert.CoCoHue_CT_Lib, line 72
   Integer kelvin = Math.round(1000000/mireds) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 73
   return kelvin // library marker RMoRobert.CoCoHue_CT_Lib, line 74
} // library marker RMoRobert.CoCoHue_CT_Lib, line 75

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 77
 * Reads device preference for CT transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_CT_Lib, line 78
 * can use input(name: ctTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_CT_Lib, line 79
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 80
Integer getScaledCTTransitionTime() { // library marker RMoRobert.CoCoHue_CT_Lib, line 81
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_CT_Lib, line 82
   if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) { // library marker RMoRobert.CoCoHue_CT_Lib, line 83
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_CT_Lib, line 84
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 85
   else if (settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) { // library marker RMoRobert.CoCoHue_CT_Lib, line 86
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : (defaultTransitionTime != null ? defaultTransitionTime : 250) // library marker RMoRobert.CoCoHue_CT_Lib, line 87
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 88
   else { // library marker RMoRobert.CoCoHue_CT_Lib, line 89
      scaledRate = Math.round(settings.ctTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_CT_Lib, line 90
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 91
   return scaledRate // library marker RMoRobert.CoCoHue_CT_Lib, line 92
} // library marker RMoRobert.CoCoHue_CT_Lib, line 93


// Hubitat-provided ct/name mappings // library marker RMoRobert.CoCoHue_CT_Lib, line 96
void setGenericTempName(temp) { // library marker RMoRobert.CoCoHue_CT_Lib, line 97
   if (!temp) return // library marker RMoRobert.CoCoHue_CT_Lib, line 98
   String genericName // library marker RMoRobert.CoCoHue_CT_Lib, line 99
   Integer value = temp.toInteger() // library marker RMoRobert.CoCoHue_CT_Lib, line 100
   if (value <= 2000) genericName = "Sodium" // library marker RMoRobert.CoCoHue_CT_Lib, line 101
   else if (value <= 2100) genericName = "Starlight" // library marker RMoRobert.CoCoHue_CT_Lib, line 102
   else if (value < 2400) genericName = "Sunrise" // library marker RMoRobert.CoCoHue_CT_Lib, line 103
   else if (value < 2800) genericName = "Incandescent" // library marker RMoRobert.CoCoHue_CT_Lib, line 104
   else if (value < 3300) genericName = "Soft White" // library marker RMoRobert.CoCoHue_CT_Lib, line 105
   else if (value < 3500) genericName = "Warm White" // library marker RMoRobert.CoCoHue_CT_Lib, line 106
   else if (value < 4150) genericName = "Moonlight" // library marker RMoRobert.CoCoHue_CT_Lib, line 107
   else if (value <= 5000) genericName = "Horizon" // library marker RMoRobert.CoCoHue_CT_Lib, line 108
   else if (value < 5500) genericName = "Daylight" // library marker RMoRobert.CoCoHue_CT_Lib, line 109
   else if (value < 6000) genericName = "Electronic" // library marker RMoRobert.CoCoHue_CT_Lib, line 110
   else if (value <= 6500) genericName = "Skylight" // library marker RMoRobert.CoCoHue_CT_Lib, line 111
   else if (value < 20000) genericName = "Polar" // library marker RMoRobert.CoCoHue_CT_Lib, line 112
   else genericName = "undefined" // shouldn't happen, but just in case // library marker RMoRobert.CoCoHue_CT_Lib, line 113
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName) // library marker RMoRobert.CoCoHue_CT_Lib, line 114
} // library marker RMoRobert.CoCoHue_CT_Lib, line 115

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
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 14
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 15
      log.warn "Color prestaging preference enabled and setColor() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 16
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 17
         presetColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 18
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 19
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 20
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 21
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 22
      if (enableDebug == true) log.debug "Exiting setColor because no hue and/or saturation set" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 23
      return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 24
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 25
   Map bridgeCmd  // library marker RMoRobert.CoCoHue_HueSat_Lib, line 26
   Integer newHue = scaleHueToBridge(value.hue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 27
   Integer newSat = scaleSatToBridge(value.saturation) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 28
   Integer newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null // library marker RMoRobert.CoCoHue_HueSat_Lib, line 29
   Integer scaledRate = value.rate != null ? Math.round(value.rate * 10).toInteger() : getScaledRGBTransitionTime() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 30
   if (scaledRate == null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 31
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 32
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 33
   else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 34
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 35
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 36
   if (newBri) bridgeCmd << ["bri": newBri] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 37
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 38
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 39
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 40
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 41
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 42
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 43

// Really a hack to get this usable from the admin UI since you can only have one COLOR_MAP input, which // library marker RMoRobert.CoCoHue_HueSat_Lib, line 45
// is already implicitly taken by setColor(). Accepts JSON object like {"hue": 10, "saturation": 100, "level": 50} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 46
// and will convert to Groovy map for use with other implenentation of this command (which I hope will be standardized // library marker RMoRobert.CoCoHue_HueSat_Lib, line 47
// some day..) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 48
void presetColor(String jsonValue) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 49
   if (enableDebug == true) log.debug "presetColor(String $jsonValue)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 50
   Map value = new groovy.json.JsonSlurper().parseText(jsonValue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 51
   presetColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 52
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 53

// Not currently a standard Hubitat command, so implementation subject to change if it becomes one; // library marker RMoRobert.CoCoHue_HueSat_Lib, line 55
// for now, assuming it may be done by taking a color map like setColor() (but see also JSON variant above) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 56
// May also need presetHue() and presetSaturation(), but not including for now... // library marker RMoRobert.CoCoHue_HueSat_Lib, line 57
void presetColor(Map value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 58
   if (enableDebug == true) log.debug "presetColor(Map $value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 59
   if (value.hue != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 60
      doSendEvent("huePreset", value.hue) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 61
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 62
   if (value.saturation != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 63
      doSendEvent("saturationPreset", value.saturation) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 64
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 65
   if (value.level != null) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 66
      doSendEvent("levelPreset", value.level) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 67
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 68
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 69
   if (isOn) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 70
      setColor(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 71
   } else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 72
      state.presetHue = (value.hue != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 73
      state.presetSaturation = (value.saturation != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 74
      state.presetLevel = (value.level != null) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 75
      state.presetColorTemperature = false // library marker RMoRobert.CoCoHue_HueSat_Lib, line 76
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 77
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 78

void setHue(value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 80
   if (enableDebug == true) log.debug "setHue($value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 81
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 82
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 83
      log.warn "Color prestaging preference enabled and setHue() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 84
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 85
         presetColor([hue: value]) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 86
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 87
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 88
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 89
   Integer newHue = scaleHueToBridge(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 90
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 91
   Map bridgeCmd = ["on": true, "hue": newHue, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 92
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 93
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 94
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 95
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 96
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 97
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 98

void setSaturation(value) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 100
   if (enableDebug == true) log.debug "setSaturation($value)" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 101
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_HueSat_Lib, line 102
   if (colorStaging) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 103
      log.warn "Color prestaging preference enabled and setSaturation() called. This is deprecated and may be removed in the future. Please move to new presetColor() command." // library marker RMoRobert.CoCoHue_HueSat_Lib, line 104
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 105
         presetColor([saturation: value]) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 106
         return // library marker RMoRobert.CoCoHue_HueSat_Lib, line 107
      } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 108
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 109
   Integer newSat = scaleSatToBridge(value) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 110
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 111
   Map bridgeCmd = ["on": true, "sat": newSat, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_HueSat_Lib, line 112
   Map prestagedCmds = getPrestagedCommands() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 113
   if (prestagedCmds) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 114
      bridgeCmd = prestagedCmds + bridgeCmd // library marker RMoRobert.CoCoHue_HueSat_Lib, line 115
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 116
   sendBridgeCommand(bridgeCmd) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 117
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 118

Integer scaleHueToBridge(hubitatLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 120
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 121
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 122
   else if (scaledLevel > 65535) scaledLevel = 65535 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 123
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 124
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 125

Integer scaleHueFromBridge(bridgeLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 127
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100)) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 128
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 129
   else if (scaledLevel > 360) scaledLevel = 360 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 130
   else if (scaledLevel > 100 && !hiRezHue) scaledLevel = 100 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 131
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 132
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 133

Integer scaleSatToBridge(hubitatLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 135
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 136
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 137
   else if (scaledLevel > 254) scaledLevel = 254 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 138
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 139
   return scaleHueFromBridge() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 140
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 141

Integer scaleSatFromBridge(bridgeLevel) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 143
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 144
   if (scaledLevel < 0) scaledLevel = 0 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 145
   else if (scaledLevel > 100) scaledLevel = 100 // library marker RMoRobert.CoCoHue_HueSat_Lib, line 146
   return scaledLevel // library marker RMoRobert.CoCoHue_HueSat_Lib, line 147
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 148


/** // library marker RMoRobert.CoCoHue_HueSat_Lib, line 151
 * Reads device preference for setColor/RGB transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_HueSat_Lib, line 152
 * can use input(name: rgbTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_HueSat_Lib, line 153
 */ // library marker RMoRobert.CoCoHue_HueSat_Lib, line 154
Integer getScaledRGBTransitionTime() { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 155
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_HueSat_Lib, line 156
   if (settings.rgbTransitionTime == null || settings.rgbTransitionTime == "-2" || settings.rgbTransitionTime == -2) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 157
      // keep null; will result in not specifying with command // library marker RMoRobert.CoCoHue_HueSat_Lib, line 158
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 159
   else if (settings.rgbTransitionTime == "-1" || settings.rgbTransitionTime == -1) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 160
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : defaultTransitionTime // library marker RMoRobert.CoCoHue_HueSat_Lib, line 161
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 162
   else { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 163
      scaledRate = Math.round(settings.rgbTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 164
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 165
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 166

// Hubiat-provided color/name mappings // library marker RMoRobert.CoCoHue_HueSat_Lib, line 168
void setGenericName(hue) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 169
   String colorName // library marker RMoRobert.CoCoHue_HueSat_Lib, line 170
   hue = hue.toInteger() // library marker RMoRobert.CoCoHue_HueSat_Lib, line 171
   if (!hiRezHue) hue = (hue * 3.6) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 172
   switch (hue.toInteger()) { // library marker RMoRobert.CoCoHue_HueSat_Lib, line 173
      case 0..15: colorName = "Red" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 174
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 175
      case 16..45: colorName = "Orange" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 176
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 177
      case 46..75: colorName = "Yellow" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 178
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 179
      case 76..105: colorName = "Chartreuse" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 180
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 181
      case 106..135: colorName = "Green" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 182
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 183
      case 136..165: colorName = "Spring" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 184
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 185
      case 166..195: colorName = "Cyan" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 186
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 187
      case 196..225: colorName = "Azure" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 188
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 189
      case 226..255: colorName = "Blue" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 190
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 191
      case 256..285: colorName = "Violet" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 192
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 193
      case 286..315: colorName = "Magenta" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 194
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 195
      case 316..345: colorName = "Rose" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 196
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 197
      case 346..360: colorName = "Red" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 198
         break // library marker RMoRobert.CoCoHue_HueSat_Lib, line 199
      default: colorName = "undefined" // shouldn't happen, but just in case // library marker RMoRobert.CoCoHue_HueSat_Lib, line 200
         break             // library marker RMoRobert.CoCoHue_HueSat_Lib, line 201
   } // library marker RMoRobert.CoCoHue_HueSat_Lib, line 202
   if (device.currentValue("saturation") < 1) colorName = "White" // library marker RMoRobert.CoCoHue_HueSat_Lib, line 203
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName) // library marker RMoRobert.CoCoHue_HueSat_Lib, line 204
} // library marker RMoRobert.CoCoHue_HueSat_Lib, line 205

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

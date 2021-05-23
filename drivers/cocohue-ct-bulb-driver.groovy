/*
 * =============================  CoCoHue CT Bulb (Driver) ===============================
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
 *  Last modified: 2021-05-23
 *
 *  Changelog:
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

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000


metadata {
   definition (name: "CoCoHue CT Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-ct-bulb-driver.groovy") {
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
      command "presetColorTemperature", [[name:"colorTemperature",type:"NUMBER", description:"Color temperature to prestage", constraints:["NUMBER"]]]
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
   Integer disableMinutes = 30
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableMinutes} minutes"
      runIn(disableMinutes*60, debugOff)
   }
}

void debugOff() {
   log.warn("Disabling debug logging")
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
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

void startLevelChange(direction) {
   if (enableDebug == true) log.debug "startLevelChange($direction)..."
   Map cmd = ["bri": (direction == "up" ? 254 : 1),
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))]
   sendBridgeCommand(cmd, false) 
}

void stopLevelChange() {
   if (enableDebug == true) log.debug "stopLevelChange()..."
   Map cmd = ["bri_inc": 0]
   sendBridgeCommand(cmd, false) 
}

void setLevel(value) {
   if (enableDebug == true) log.debug "setLevel($value)"
   setLevel(value, ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime)) / 1000)
}

void setLevel(Number value, Number rate) {
   if (enableDebug == true) log.debug "setLevel($value, $rate)"
   // For backwards compatibility; will be removed in future version:
   if (levelStaging) {
      log.warn "Level prestaging preference enabled and setLevel() called. This is deprecated and may be removed in the future. Please move to new, standard presetLevel() command."
      if (device.currentValue("switch") != "on") {
         presetLevel(value)
         return
      }
   }
   if (value < 0) value = 1
   else if (value > 100) value = 100
   else if (value == 0) {
      off(rate)
      return
   }
   Integer newLevel = scaleBriToBridge(value)
   Integer scaledRate = (rate * 10).toInteger()
   Map bridgeCmd = [
      "on": true,
      "bri": newLevel,
      "transitiontime": scaledRate
   ]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void presetLevel(Number level) {
   if (enableDebug == true) log.debug "presetLevel($level)"
   if (level < 0) level = 1
   else if (level > 100) level = 100
   Integer newLevel = scaleBriToBridge(level)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 1000).toInteger()
   Boolean isOn = device.currentValue("switch") == "on"
   doSendEvent("levelPreset", level)
   if (isOn) {
      setLevel(level)
   } else {
      state.presetLevel = true
   }
}

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) {
   if (enableDebug == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setColorTemperature() called. This is deprecated and may be removed in the future. Please move to new presetColorTemperature() command."
      if (device.currentValue("switch") != "on") {
         presetColorTemperature(colorTemperature)
         return
      }
   }
   Integer newCT = scaleCTToBridge(colorTemperature)
   Integer scaledRate = defaultLevelTransitionTime/100
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
   }
   else if (settings["transitionTime"] != null) {
      scaledRate = ((settings["transitionTime"] as Integer) / 100) as Integer
   }
   Map bridgeCmd = ["on": true, "ct": newCT, "transitiontime": scaledRate]
   if (level) {
      bridgeCmd << ["bri": scaleBriToBridge(level)]
   }
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

// Not a standard command (yet?), but I hope it will get implemented as such soon in
// the same manner as this. Otherwise, subject to change if/when that happens....
void presetColorTemperature(Number colorTemperature) {
   if (enableDebug == true) log.debug "presetColorTemperature($colorTemperature)"
   Boolean isOn = device.currentValue("switch") == "on"
   doSendEvent("colorTemperaturePreset", colorTemperature)
   if (isOn) {
      setColorTemperature(colorTemperature)
   } else {
      state.remove("presetCT")
      state.presetColorTemperature = true
   }
}

void flash() {
   if (enableDebug == true) log.debug "flash()"
   if (settings.enableDesc == true) log.info("${device.displayName} started 15-cycle flash")
   Map<String,String> cmd = ["alert": "lselect"]
   sendBridgeCommand(cmd, false) 
}

void flashOnce() {
   if (enableDebug == true) log.debug "flashOnce()"
   if (settings.enableDesc == true) log.info("${device.displayName} started 1-cycle flash")
   Map<String,String> cmd = ["alert": "select"]
   sendBridgeCommand(cmd, false) 
}

void flashOff() {
   if (enableDebug == true) log.debug "flashOff()"
   if (settings.enableDesc == true) log.info("${device.displayName} was sent command to stop flash")
   Map<String,String> cmd = ["alert": "none"]
   sendBridgeCommand(cmd, false) 
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
 * Returns Map containing any commands that would need to be sent to Bridge if anything is currently prestaged.
 * Otherwise, returns empty Map.
 * @param unsetPrestagingState If set to true (default), clears prestage flag
*/
Map getPrestagedCommands(Boolean unsetPrestagingState=true) {
   if (enableDebug == true) log.debug "getPrestagedCommands($unsetPrestagingState)"
   Map cmds = [:]
   if (state.presetLevel == true) {
      cmds << [bri: scaleBriToBridge(device.currentValue("levelPreset"))]
   }
   if (state.presetColorTemperature == true) {
      cmds << [ct: scaleCTToBridge(device.currentValue("colorTemperaturePreset"))]
   }
   if (unsetPrestagingState == true) {
      state.presetLevel = false
      state.presetColorTemperature = false
   }
   if (enableDebug == true) log.debug "Returning: $cmds"
   return cmds
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
void parseSendCommandResponse(resp, data) {
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

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @param resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(resp) {
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..."
   Boolean isOK = true
   if (resp.status < 400) {
      if (resp?.json == null) {
         isOK = false
         if (resp?.headers == null) log.error "Error: HTTP ${resp?.status} when attempting to communicate with Bridge"
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})"
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeStatus(false)
      }
      else if (resp.json) {
         if (resp.json[0]?.error) {
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.):
            isOK = false
            log.warn "Error from Hue Bridge: ${resp.json[0].error}"
            // Not setting Bridge to offline when light/scene/group devices end up here because could
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set
            // to online because wasn't successful attempt)
         }
         // Otherwise: probably OK (not changing anything because isOK = true already)
      }
      else {
         isOK = false
         log.warn("HTTP status code ${resp.status} from Bridge")
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeStatus(false)
      }
      if (isOK == true) parent.setBridgeStatus(true)
   }
   else {
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}"
      isOK = false
   }
   return isOK
}

void doSendEvent(String eventName, eventValue, String eventUnit=null) {
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (settings.enableDesc == true) log.info(descriptionText)
   if (eventUnit) {
      sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
   }
}

// Hubitat-provided ct/name mappings
void setGenericTempName(temp) {
   if (!temp) return
   String genericName
   Integer value = temp.toInteger()
   if (value <= 2000) genericName = "Sodium"
   else if (value <= 2100) genericName = "Starlight"
   else if (value < 2400) genericName = "Sunrise"
   else if (value < 2800) genericName = "Incandescent"
   else if (value < 3300) genericName = "Soft White"
   else if (value < 3500) genericName = "Warm White"
   else if (value < 4150) genericName = "Moonlight"
   else if (value <= 5000) genericName = "Horizon"
   else if (value < 5500) genericName = "Daylight"
   else if (value < 6000) genericName = "Electronic"
   else if (value <= 6500) genericName = "Skylight"
   else if (value < 20000) genericName = "Polar"
   else genericName = "undefined" // shouldn't happen, but just in case
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName)
}

/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254
 */
private Integer scaleBriToBridge(hubitatLevel) {
   Integer scaledLevel =  Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254)
   return Math.round(scaledLevel)
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100
 */
private Integer scaleBriFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
   if (scaledLevel < 1) scaledLevel = 1
   return Math.round(scaledLevel)
}

/**
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units)
 */
private Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) {
   Integer mireds = Math.round(1000000/kelvinCT) as Integer
   if (checkIfInRange == true) {
      if (mireds < minMireds) mireds = minMireds
      else if (mireds > maxMireds) mireds = maxMireds
   }
   return mireds
}

/**
 * Scales CT from mireds (Hue units) to Kelvin (Hubitat units)
 */
private Integer scaleCTFromBridge(Number mireds) {
   Integer kelvin = Math.round(1000000/mireds) as Integer
   return kelvin
}
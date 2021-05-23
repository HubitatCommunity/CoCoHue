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
*  Last modified: 2021-05-23
 *
 *  Changelog:
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
   definition (name: "CoCoHue Group", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy") {
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
      command "presetColorTemperature", [[name:"colorTemperature",type:"NUMBER", description:"Color temperature to prestage", constraints:["NUMBER"]]]
      command "presetColor", [[name:"color",type:"JSON_OBJECT", description:"Color to prestage (Map with keys: hue, saturation, value; also accepts JSON object for better UI compatibility, subject to change)"]]
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
      state.presetHue = false
      state.presetSaturation = false
   }
}

void setColor(Map value) {
   if (enableDebug == true) log.debug "setColor($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setColor() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor(value)
         return
      }
   }
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") {
      if (enableDebug == true) log.debug "Exiting setColor because no hue and/or saturation set"
      return
   }
   Integer newHue = scaleHueToBridge(value.hue)
   Integer newSat = scaleSatToBridge(value.saturation)
   Integer newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null
   Integer scaledRate
   if (value.rate != null) {
      scaledRate = (value.rate * 10).toInteger()
   }
   else {
      scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger()
   }
   Map bridgeCmd = ["on": true, "hue": newHue, "sat": newSat, "transitiontime": scaledRate]
   if (newBri) bridgeCmd << ["bri": newBri]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

// Really a hack to get this usable from the admin UI since you can only have one COLOR_MAP input, which
// is already implicitly taken by setColor(). Accepts JSON object like {"hue": 10, "saturation": 100, "level": 50}
// and will convert to Groovy map for use with other implenentation of this command (which I hope will be standardized
// some day..)
void presetColor(String jsonValue) {
   if (enableDebug == true) log.debug "presetColor(String $jsonValue)"
   Map value = new groovy.json.JsonSlurper().parseText(jsonValue)
   presetColor(value)
}

// Not currently a standard Hubitat command, so implementation subject to change if it becomes one;
// for now, assuming it may be done by taking a color map like setColor() (but see also JSON variant above)
// May also need presetHue() and presetSaturation(), but not including for now...
void presetColor(Map value) {
   if (enableDebug == true) log.debug "presetColor(Map $value)"
   if (value.hue != null) {
      doSendEvent("huePreset", value.hue)
   }
   if (value.saturation != null) {
      doSendEvent("saturationPreset", value.saturation)
   }
   if (value.level != null) {
      doSendEvent("levelPreset", value.level)
   }
   Boolean isOn = device.currentValue("switch") == "on"
   if (isOn) {
      setColor(value)
   } else {
      state.presetHue = (value.hue != null)
      state.presetSaturation = (value.saturation != null)
      state.presetLevel = (value.level != null)
      state.presetColorTemperature = false
   }
}

void setHue(value) {
   if (enableDebug == true) log.debug "setHue($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setHue() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor([hue: value])
         return
      }
   }
   Integer newHue = scaleHueToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger()
   Map bridgeCmd = ["on": true, "hue": newHue, "transitiontime": scaledRate]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void setSaturation(value) {
   if (enableDebug == true) log.debug "setSaturation($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setSaturation() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor([saturation: value])
         return
      }
   }
   Integer newSat = scaleSatToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
   Map bridgeCmd = ["on": true, "sat": newSat, "transitiontime": scaledRate]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void setEffect(String effect) {
   if (enableDebug == true) log.debug "setEffect($effect)"
   def id = lightEffects.find { it.value == effect }
   if (id != null) setEffect(id.key)
}

void setEffect(Integer id) {
   if (enableDebug == true) log.debug "setEffect($id)"
   sendBridgeCommand(["effect": (id == 1 ? "colorloop" : "none"), "on": true])
}

void setNextEffect() {
   if (enableDebug == true) log.debug"setNextEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect++
   if (currentEffect > maxEffectNumber) currentEffect = 0
   setEffect(currentEffect)
}

void setPreviousEffect() {
   if (enableDebug == true) log.debug "setPreviousEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect--
   if (currentEffect < 0) currentEffect = 1
   setEffect(currentEffect)
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
   if (state.presetHue == true) {
      cmds << [hue: scaleHueToBridge(device.currentValue("huePreset"))]
   }
   if (state.presetSaturation == true) {
      cmds << [sat: scaleSatToBridge(device.currentValue("saturationPreset"))]
   }
   if (unsetPrestagingState == true) {
      state.presetLevel = false
      state.presetColorTemperature = false
      state.presetHue = false
      state.presetSaturation = false
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

// Hubiat-provided color/name mappings
void setGenericName(hue) {
   String colorName
   hue = hue.toInteger()
   if (!hiRezHue) hue = (hue * 3.6)
   switch (hue.toInteger()) {
      case 0..15: colorName = "Red"
         break
      case 16..45: colorName = "Orange"
         break
      case 46..75: colorName = "Yellow"
         break
      case 76..105: colorName = "Chartreuse"
         break
      case 106..135: colorName = "Green"
         break
      case 136..165: colorName = "Spring"
         break
      case 166..195: colorName = "Cyan"
         break
      case 196..225: colorName = "Azure"
         break
      case 226..255: colorName = "Blue"
         break
      case 256..285: colorName = "Violet"
         break
      case 286..315: colorName = "Magenta"
         break
      case 316..345: colorName = "Rose"
         break
      case 346..360: colorName = "Red"
         break
      default: colorName = "undefined" // shouldn't happen, but just in case
         break
   }
   if (device.currentValue("saturation") < 1) colorName = "White"
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName)
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

private Integer scaleHueToBridge(hubitatLevel) {
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 65535) scaledLevel = 65535
   return scaledLevel
}

private Integer scaleHueFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100))
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 360) scaledLevel = 360
   else if (scaledLevel > 100 && !hiRezHue) scaledLevel = 100
   return scaledLevel
}

private Integer scaleSatToBridge(hubitatLevel) {
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / 100 * 254)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 254) scaledLevel = 254
   return scaledLevel
   return scaleHueFromBridge()
}

private Integer scaleSatFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 100) scaledLevel = 100
   return scaledLevel
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
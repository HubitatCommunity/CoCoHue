/*
 * =============================  CoCoHue Group (Driver) ===============================
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
 *  Last modified: 2024-09-15
 *
 *  Changelog:
 *  v5.0.1 - Fix for missing V1 IDs after device creation or upgrade
 *  v5.0   - Use API v2 by default, remove deprecated features
 *  v4.2    - Library updates, prep for more v2 API
 *  v4.1.7  - Fix for unexpected Hubitat event creation when v2 API reports level of 0
 *  v4.1.5  - Improved v2 brightness parsing
 *  v4.0    - Add SSE support for push
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
import hubitat.scheduling.AsyncResponse

@Field static final Integer debugAutoDisableMinutes = 30

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

@Field static final Map<Integer,String> lightEffects = [0: "None", 1:"Color Loop"]
@Field static final Integer maxEffectNumber = 1

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
// Unlike bulbs, SSE API does not seem to send anything besides on/off:
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on"]

// "ct" or "hs" for now -- to be finalized later:
@Field static final String xyParsingMode = "ct"

metadata {
   definition(name: "CoCoHue Group", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy") {
      capability "Actuator"
      capability "ColorControl"
      capability "ColorTemperature"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "ChangeLevel"
      capability "Light"
      capability "ColorMode"
      capability "LightEffects"

      command "flash"
      command "flashOnce"
      command "flashOff"
   
      attribute "effect", "string"
      attribute "reachable", "string"
   }
       
   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
      input name: "ctTransitionTime", type: "enum", description: "", title: "Color temperature transition time", options:
         [[(-2): "Hue default/do not specify (default)"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "rgbTransitionTime", type: "enum", description: "", title: "RGB transition time", options:
         [[(-2): "Hue default/do not specify (default)"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "hiRezHue", type: "bool", title: "Enable hue in degrees (0-360 instead of 0-100)", defaultValue: false
      // Note: the following setting does not apply to SSE, which should update the group state immediately regardless:
      input name: "updateBulbs", type: "bool", description: "", title: "Update member bulb states immediately when group state changes (applicable only if not using V2 API/eventstream)",
         defaultValue: true
      input name: "updateScenes", type: "bool", description: "", title: "Mark all GroupScenes for this group as off when group device turns off (applicable only if not using V2 API/eventstream)",
         defaultValue: true
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses V1 Hue Bridge device ID number out of Hubitat DNI for use with Hue V1 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Group/HueDeviceID", so just
 * looks for number after last "/" character; or try state if DNI is V2 format (avoid if posssible,
 *  as Hue is likely to deprecate V1 ID data in future)
 */
String getHueDeviceIdV1() {
   String id = device.deviceNetworkId.split("/").last()
   if (id.length() > 32) { // max length of last part of V1 IDs per V2 API regex spec, though never seen anything non-numeric longer than 2 (or 3?) for non-scenes
      id = state.id_v1?.split("/")?.last()
      if (state.id_v1 == null) {
         log.warn "Attempting to retrieve V1 ID but not in DNI or state."
      }
   }
   return id
}

/**
 * Parses V2 Hue Bridge device ID out of Hubitat DNI for use with Hue V2 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Group/HueDeviceID", so just
 * looks for string after last "/" character
 */
String getHueDeviceIdV2() {
   return device.deviceNetworkId.split("/").last()
}

void on(Number transitionTime = null) {
   if (logEnable == true) log.debug "on()"
   Map bridgeCmd = ["on": true]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (logEnable == true) log.debug "off()"
   Map bridgeCmd = ["on": false]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void refresh() {
   log.warn "Refresh Hue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * (for "classic"/v1 HTTP API)
 * Iterates over Hue light state commands/states in Hue v1 format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge or to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; TODO: see if still needed now that pseudo-prestaging removed
 */
void createEventsFromMapV1(Map bridgeCommandMap, Boolean isFromBridge = false, Set<String> keysToIgnoreIfSSEEnabledAndNotFromBridge=listKeysToIgnoreIfSSEEnabledAndNotFromBridge) {
   if (!bridgeCommandMap) {
      if (logEnable == true) log.debug "createEventsFromMapV1 called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (logEnable == true) log.debug "Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   if (!isFromBridge && keysToIgnoreIfSSEEnabledAndNotFromBridge && parent.getEventStreamOpenStatus() == true) {
      bridgeMap.keySet().removeAll(keysToIgnoreIfSSEEnabledAndNotFromBridge)
      if (logEnable == true) log.debug "Map after ignored keys removed: ${bridgeMap}"
   }
   String eventName, eventUnit, descriptionText
   def eventValue // could be string or number
   String colorMode = bridgeMap["colormode"]
   if (isFromBridge && colorMode == "xy") {
      if (xyParsingMode == "ct") {
         colorMode = "ct"
      }
      else {
         colorMode = "hs"
      }
      if (logEnable == true) log.debug "In XY mode but parsing as CT (colorMode = $colorMode)"
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
                  parent.updateSceneStateToOffForGroup(getHueDeviceIdV1())
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
                  if (logEnable == true) log.debug "Skipping colorTemperature event creation because light not in ct mode"
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
                  if (logEnable == true) log.debug "Skipping colorMode and color name event creation because light not in hs mode"
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
 * (for V2 API)
 * Iterates over Hue light state states in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromMapV2(Map data) {
   if (logEnable == true) log.debug "createEventsFromMapV2($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean hasCT = data.color_temperature?.mirek != null
   data.each { String key, value ->
      switch (key) {
         case "on":  // appears equivalent to any_on in group (which is already CoCoHue behavior, so good)
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
         case "color": 
            if (!hasCT) {
               if (logEnable == true) log.debug "color received (presuming xy, no CT)"
               // no point in doing this yet--but maybe if can convert XY/HS some day:
               //parent.refreshBridgeWithDealay()
            }
            else {
               if (logEnable == true) log.debug "color received but also have CT, so assume CT parsing"
            }
            break
         case "color_temperature":
            if (!hasCT) {
               if (logEnable == true) "ignoring color_temperature because mirek null"
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
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
            break
         default:
            if (logEnable == true) "not handling: $key: $value"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the either command map provided
 * @param commandMap Groovy Map (will be converted to JSON) of Hue API commands to send, e.g., [on: true]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if ["on": true] in map)
 */
void sendBridgeCommandV1(Map commandMap, Boolean createHubEvents=true) {
   if (logEnable == true) log.debug "sendBridgeCommandV1($commandMap)"
   if (commandMap == null || commandMap == [:]) {
      if (logEnable == true) log.debug "Commands not sent to Bridge because command map null or empty"
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups/${getHueDeviceIdV1()}/action",
      contentType: 'application/json',
      body: commandMap,
      timeout: 15
   ]
   asynchttpPut("parseSendCommandResponseV1", params, createHubEvents ? commandMap : null)
   if (logEnable == true) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommandV1. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponseV1(AsyncResponse resp, Map data) {
   if (logEnable == true) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (logEnable == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMapV1(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateBulbs"]) {
         parent.updateMemberBulbStatesFromGroup(data, state.memberBulbs, device.getDeviceNetworkId().endsWith('/0'))
      }
      if (data["on"] == false && settings["updateScenes"] != false) {
         parent.updateSceneStateToOffForGroup(getHueDeviceIdV1())
      }
   }
   else {
      if (logEnable == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
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
 *  Sets state.groupedLightId to the Hue API V2 ID of the grouped_light service that owns this room or zone
 */ 
void setGroupedLightId(String id) {
   state.groupedLightId = id
}

/**
 * Sets all group attribute values to something, intended to be called when device initially created to avoid
 * missing attribute values (may cause problems with GH integration, etc. otherwise). Default values are
 * approximately warm white and off.
 */
private void setDefaultAttributeValues() {
   if (logEnable == true) log.debug "Setting group device states to sensibile default values..."
   Map defaultValues = [any_on: false, bri: 254, hue: 8593, sat: 121, ct: 370 ]
   createEventsFromMapV1(defaultValues)
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Common_Lib ~~~
// Version 1.0.3
// For use with CoCoHue drivers (not app)

/**
 * 1.0.4 - Add common bridgeAsyncGetV2() method (goal to reduce individual driver code)
 * 1.0.3 - Add APIV1 and APIV2 "constants"
 * 1.0.2  - HTTP error handling tweaks
 */

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("logEnable", [value:"false", type:"bool"])
}

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @param resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) {
   if (logEnable == true) log.debug "Checking if valid HTTP response/data from Bridge..."
   Boolean isOK = true
   if (resp.status < 400) {
      if (resp.json == null) {
         isOK = false
         if (resp.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge"
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})"
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeOnlineStatus(false)
      }
      else if (resp.json) {
         if ((resp.json instanceof List) && resp.json.getAt(0).error) {
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
         // TODO: Update for mDNS if/when switch:
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeOnlineStatus(false)
      }
      if (isOK == true) parent.setBridgeOnlineStatus(true)
   }
   else {
      log.warn "Error communicating with Hue Bridge: HTTP ${resp?.status}"
      isOK = false
   }
   return isOK
}

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) {
   //if (logEnable == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (settings.txtEnable == true) log.info(descriptionText)
   if (eventUnit) {
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
   }
}

// HTTP methods (might be better to split into separate library if not needed for some?)

/** Performs asynchttpGet() to Bridge using data retrieved from parent app or as passed in
  * @param callbackMethod Callback method
  * @param clipV2Path The Hue V2 API path (without '/clip/v2', automatically prepended), e.g. '/resource' or '/resource/light'
  * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null
  * @param data Extra data to pass as optional third (data) parameter to asynchtttpGet() method
  */
void bridgeAsyncGetV2(String callbackMethod, String clipV2Path, Map<String,String> bridgeData = null, Map data = null) {
   if (bridgeData == null) {
      bridgeData = parent.getBridgeData()
   }
   Map params = [
      uri: "https://${bridgeData.ip}",
      path: "/clip/v2${clipV2Path}",
      headers: ["hue-application-key": bridgeData.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet(callbackMethod, params, data)
}



// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Constants_Lib ~~~
// Version 1.0.0

// --------------------------------------
// APP AND DRIVER NAMESPACE AND NAMES:
// --------------------------------------
@Field static final String NAMESPACE                  = "RMoRobert"
@Field static final String DRIVER_NAME_BRIDGE         = "CoCoHue Bridge"
@Field static final String DRIVER_NAME_BUTTON         = "CoCoHue Button"
@Field static final String DRIVER_NAME_CT_BULB        = "CoCoHue CT Bulb"
@Field static final String DRIVER_NAME_DIMMABLE_BULB  = "CoCoHue Dimmable Bulb"
@Field static final String DRIVER_NAME_GROUP          = "CoCoHue Group"
@Field static final String DRIVER_NAME_MOTION         = "CoCoHue Motion Sensor"
@Field static final String DRIVER_NAME_PLUG           = "CoCoHue Plug"
@Field static final String DRIVER_NAME_RGBW_BULB      = "CoCoHue RGBW Bulb"
@Field static final String DRIVER_NAME_RGB_BULB       = "CoCoHue RGB Bulb"
@Field static final String DRIVER_NAME_SCENE          = "CoCoHue Scene"

// --------------------------------------
// DNI PREFIX for child devices:
// --------------------------------------
@Field static final String DNI_PREFIX = "CCH"

// --------------------------------------
// OTHER:
// --------------------------------------
// Used in app and Bridge driver, may eventually find use in more:
@Field static final String APIV1 = "V1"
@Field static final String APIV2 = "V2"

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Bri_Lib ~~~
// Version 1.0.4

// 1.0.4  - accept String for setLevel() level also 
// 1.0.3  - levelhandling tweaks

// "SwitchLevel" commands:

void startLevelChange(String direction) {
   if (logEnable == true) log.debug "startLevelChange($direction)..."
   Map cmd = ["bri": (direction == "up" ? 254 : 1),
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))]
   sendBridgeCommandV1(cmd, false) 
}

void stopLevelChange() {
   if (logEnable == true) log.debug "stopLevelChange()..."
   Map cmd = ["bri_inc": 0]
   sendBridgeCommandV1(cmd, false) 
}

void setLevel(value) {
   if (logEnable == true) log.debug "setLevel($value)"
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000)
}

void setLevel(Number value, Number rate) {
   if (logEnable == true) log.debug "setLevel($value, $rate)"
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
   sendBridgeCommandV1(bridgeCmd)
}

void setLevel(value, rate) {
   if (logEnable == true) log.debug "setLevel(Object $value, Object $rate)"
   Float floatLevel = Float.parseFloat(value.toString())
   Integer intLevel = Math.round(floatLevel)
   Float floatRate = Float.parseFloat(rate.toString())
   setLevel(intLevel, floatRate)
}

/**
 * Reads device preference for on() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOnTransitionTime() {
   Integer scaledRate = null
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else {
      scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100)
   }
   return scaledRate
}


/**
 * Reads device preference for off() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOffTransitionTime() {
   Integer scaledRate = null
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) {
      scaledRate = getScaledOnTransitionTime()
   }
   else {
      scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100)
   }
   return scaledRate
}

// Internal methods for scaling


/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100)
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on)
 */
Number scaleBriToBridge(Number hubitatLevel, String apiVersion="1") {
   if (apiVersion != "2") {
      Integer scaledLevel
      scaledLevel = Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254)
      return Math.round(scaledLevel) as Integer
   }
   else {
      BigDecimal scaledLevel
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future)
      scaledLevel = hubitatLevel == 1 ? 0.0 : hubitatLevel.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP)
      return scaledLevel
   }
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 (or 0-100)
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on)
 */
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion="1") {
   Integer scaledLevel
   if (apiVersion != "2") {
      scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
      if (scaledLevel < 1) scaledLevel = 1
   }
   else {
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future)
      scaledLevel = Math.round(bridgeLevel <= 1.49 && bridgeLevel > 0.001 ? 1 : bridgeLevel)
   }
   return scaledLevel
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_CT_Lib ~~~
// Version 1.0.1

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) {
   if (logEnable == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)"
   state.lastKnownColorMode = "CT"
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
   sendBridgeCommandV1(bridgeCmd)
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

/**
 * Reads device preference for CT transition time, or provides default if not available; device
 * can use input(name: ctTransitionTime, ...) to provide this
 */
Integer getScaledCTTransitionTime() {
   Integer scaledRate = null
   if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) {
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : (defaultTransitionTime != null ? defaultTransitionTime : 250)
   }
   else {
      scaledRate = Math.round(settings.ctTransitionTime.toFloat() / 100)
   }
   return scaledRate
}

void setGenericTempName(temp) {
   if (!temp) return
   String genericName = convertTemperatureToGenericColorName(temp)
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName)
}


// ~~~ IMPORTED FROM RMoRobert.CoCoHue_HueSat_Lib ~~~
// Version 1.0.2

void setColor(Map value) {
   if (logEnable == true) log.debug "setColor($value)"
   state.lastKnownColorMode = "RGB"
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") {
      if (logEnable == true) log.debug "Exiting setColor because no hue and/or saturation set"
      return
   }
   Map bridgeCmd 
   Integer newHue = scaleHueToBridge(value.hue)
   Integer newSat = scaleSatToBridge(value.saturation)
   Integer newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null
   Integer scaledRate = value.rate != null ? Math.round(value.rate * 10).toInteger() : getScaledRGBTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat]
   }
   else {
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat, "transitiontime": scaledRate]
   }
   if (newBri) bridgeCmd << ["bri": newBri]
   sendBridgeCommandV1(bridgeCmd)
}

void setHue(value) {
   if (logEnable == true) log.debug "setHue($value)"
   state.lastKnownColorMode = "RGB"
   Integer newHue = scaleHueToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger()
   Map bridgeCmd = ["on": true, "hue": newHue, "transitiontime": scaledRate]
   sendBridgeCommandV1(bridgeCmd)
}

void setSaturation(value) {
   if (logEnable == true) log.debug "setSaturation($value)"
   state.lastKnownColorMode = "RGB"
   Integer newSat = scaleSatToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
   Map bridgeCmd = ["on": true, "sat": newSat, "transitiontime": scaledRate]
   sendBridgeCommandV1(bridgeCmd)
}

Integer scaleHueToBridge(hubitatHue) {
   Integer scaledHue = Math.round(hubitatHue.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535)
   if (scaledHue < 0) scaledHue = 0
   else if (scaledHue > 65535) scaledHue = 65535
   return scaledHue
}

Integer scaleHueFromBridge(bridgeLevel) {
   Integer scaledHue = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100))
   if (scaledHue < 0) scaledHue = 0
   else if (scaledHue > 360) scaledHue = 360
   else if (scaledHue > 100 && !hiRezHue) scaledHue = 100
   return scaledHue
}

Integer scaleSatToBridge(hubitatSat) {
   Integer scaledSat = Math.round(hubitatSat.toBigDecimal() / 100 * 254)
   if (scaledSat < 0) scaledSat = 0
   else if (scaledSat > 254) scaledSat = 254
   return scaledSat
}

Integer scaleSatFromBridge(bridgeSat) {
   Integer scaledSat = Math.round(bridgeSat.toBigDecimal() / 254 * 100)
   if (scaledSat < 0) scaledSat = 0
   else if (scaledSat > 100) scaledSat = 100
   return scaledSat
}


/**
 * Reads device preference for setColor/RGB transition time, or provides default if not available; device
 * can use input(name: rgbTransitionTime, ...) to provide this
 */
Integer getScaledRGBTransitionTime() {
   Integer scaledRate = null
   if (settings.rgbTransitionTime == null || settings.rgbTransitionTime == "-2" || settings.rgbTransitionTime == -2) {
      // keep null; will result in not specifying with command
   }
   else if (settings.rgbTransitionTime == "-1" || settings.rgbTransitionTime == -1) {
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : defaultTransitionTime
   }
   else {
      scaledRate = Math.round(settings.rgbTransitionTime.toFloat() / 100)
   }
}

// Hubiat-provided color/name mappings
void setGenericName(hue) {
   String colorName
   hue = hue.toInteger()
   if (hiRezHue) hue = (hue / 3.6)
   colorName = convertHueToGenericColorName(hue, device.currentSaturation ?: 100)
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName)
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Flash_Lib ~~~
// Version 1.0.0

void flash() {
   if (logEnable == true) log.debug "flash()"
   if (settings.txtEnable == true) log.info("${device.displayName} started 15-cycle flash")
   Map<String,String> cmd = ["alert": "lselect"]
   sendBridgeCommandV1(cmd, false) 
}

void flashOnce() {
   if (logEnable == true) log.debug "flashOnce()"
   if (settings.txtEnable == true) log.info("${device.displayName} started 1-cycle flash")
   Map<String,String> cmd = ["alert": "select"]
   sendBridgeCommandV1(cmd, false) 
}

void flashOff() {
   if (logEnable == true) log.debug "flashOff()"
   if (settings.txtEnable == true) log.info("${device.displayName} was sent command to stop flash")
   Map<String,String> cmd = ["alert": "none"]
   sendBridgeCommandV1(cmd, false) 
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Effect_Lib ~~~
// Version 1.0.1

void setEffect(String effect) {
   if (logEnable == true) log.debug "setEffect($effect)"
   def id = lightEffects.find { it.value == effect }
   if (id != null) setEffect(id.key)
}

void setEffect(Number id) {
   if (logEnable == true) log.debug "setEffect($id)"
   // Looks like should be possible with prism effect in V2 when get here, too:
   sendBridgeCommandV1(["effect": (id == 1 ? "colorloop" : "none"), "on": true])
}

void setNextEffect() {
   if (logEnable == true) log.debug"setNextEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect++
   if (currentEffect > maxEffectNumber) currentEffect = 0
   setEffect(currentEffect)
}

void setPreviousEffect() {
   if (logEnable == true) log.debug "setPreviousEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect--
   if (currentEffect < 0) currentEffect = 1
   setEffect(currentEffect)
}


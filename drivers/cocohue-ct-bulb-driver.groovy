/*
 * =============================  CoCoHue CT Bulb (Driver) ===============================
 *
 *  Copyright 2019-2025 Robert Morris
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
 *  Last modified: 2025-09-07
 *
 *  Changelog:
 *  v5.3.4  - Changes to accommodate HTTPS by default
 *  v5.3.1 - Implement async HTTP call queueing from child drivers through parent app
 *  v5.3.0  - Use V2 for most commands, parse 0 mired as 0 K
 *  v5.2.8  - Add reachable attribute to V2 API parsing; ignore 0 CT values
 *  v5.2.7  - Use level 0 in color or CT commands as off()
 *  v5.2.2  - Populate initial states from V2 cache if available
 *  v5.0.1  - Fix for missing V1 IDs after device creation or upgrade
 *  v5.0    - Use API v2 by default, remove deprecated features
 *  v4.2    - Library updates, prep for more v2 API
 *  v4.1.8  - Fix for division by zero for unexpected colorTemperature values
 *  v4.1.7  - Fix for unexpected Hubitat event creation when v2 API reports level of 0
 *  v4.1.5  - Improved v2 brightness parsing
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0.2  - Fix to avoid unepected "off" transition time
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
@Field static final BigDecimal defaultLevelTransitionTime = 400

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
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
      input name: "ctTransitionTime", type: "enum", description: "", title: "Color temperature transition time", options:
         [[(-2): "Hue default/do not specify"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when bulb state changes (applicable only if not using V2 API/eventstream)",
         defaultValue: false
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   if (device.currentValue("switch") == null) {
      // Populate initial device data (if V2 available; V1 users would need manual refresh)
      List bridgeCacheData = parent.getBridgeCacheV2()?.data ?: []
      Map devCache = bridgeCacheData.find { it.type == "light" && it.id == device.deviceNetworkId.split("/").last() }
      if (devCache == null) devCache == bridgeCacheData.find { it.type == "light" && it.id_v1 == device.deviceNetworkId.split("/").last() }
      if (devCache != null) {
         log.warn devCache.id
         createEventsFromMapV2(devCache)
      }
   }
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
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
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

void on(Number transitionTime = null) {
   if (logEnable == true) log.debug "on()"
   if (getHasV2DNI() == false) {
      onV1(transitionTime)
      return
   }
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 1000).toInteger() : getScaledOnTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": ["on": true]]
   }
   else {
      bridgeCmd = ["on": ["on": true], "dynamics": ["duration": scaledRate]]
   }
   sendBridgeCommandV2(bridgeCmd)
}

void onV1(Number transitionTime = null) {
   if (logEnable == true) log.debug "onV1()"
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : getScaledOnTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": true]
   }
   else {
      bridgeCmd = ["on": true, "transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (logEnable == true) log.debug "off()"
   if (getHasV2DNI() == false) {
      offV1(transitionTime)
      return
   }
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 1000).toInteger() : getScaledOnTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": ["on": false]]
   }
   else {
      bridgeCmd = ["on": ["on": false], "dynamics": ["duration": scaledRate]]
   }
   sendBridgeCommandV2(bridgeCmd)
}

void offV1(Number transitionTime = null) {
   if (logEnable == true) log.debug "offV1()"
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : null
   if (scaledRate == null) {
      bridgeCmd = ["on": false]
   }
   else {
      bridgeCmd = ["on": false, "transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void refresh() {
   log.warn "Refresh Hue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called when commands are sent
 * to Bridge or to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; TODO: check if this is still needed now that pseudo-prestaging removed
 */
void createEventsFromMapV1(Map bridgeCommandMap, Boolean isFromBridge = false, Set<String> keysToIgnoreIfSSEEnabledAndNotFromBridge=listKeysToIgnoreIfSSEEnabledAndNotFromBridge) {
   if (!bridgeCommandMap) {
      if (logEnable == true) log.debug "createEventsFromMapV1 called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (logEnable == true) log.debug "createEventsFromMapV1(): Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   if (!isFromBridge && keysToIgnoreIfSSEEnabledAndNotFromBridge && parent.getEventStreamOpenStatus() == true) {
      bridgeMap.keySet().removeAll(keysToIgnoreIfSSEEnabledAndNotFromBridge)
      if (logEnable == true) log.debug "Map after ignored keys removed: ${bridgeMap}"
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
            if (it.value == 0) break // skip invalid value if ever gets reported...
            eventName = "level"
            eventValue = scaleBriFromBridge(it.value, APIV1)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "ct":
            eventName = "colorTemperature"
            if (it.value == 0) break // skip invalid value that sometimes appears
            eventValue = scaleCTFromBridge(it.value)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue && eventValue != 0) {
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
         case "on":
            eventName = "switch"
            eventValue = value.on ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "dimming":
            if (value.brightness == 0) break // skip invalid value if ever gets reported...
            eventName = "level"
            eventValue = scaleBriFromBridge(value.brightness, APIV2)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue && eventValue > 0) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "color_temperature":
            if (!hasCT) {
               if (logEnable == true) "ignoring color_temperature because mirek null"
               return
            }
            if (value.mirek == 0) break // skip invalid if V2 ever reports this like V1 sometimes does...
            eventName = "colorTemperature"
            eventValue = scaleCTFromBridge(value.mirek)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            setGenericTempName(eventValue)
            break
         case "status":
            if (data.type == "zigbee_connectivity") { // not sure if any other types use this key, but just in case
               eventName = "reachable"
               if (value == "disconnected" || value == "connectivity_issue") {
                  eventValue = "true"
               }
               else {
                  eventValue = false
               }
               eventUnit = null
               if (device.currentValue(eventName) != eventValue) {
                  doSendEvent(eventName, eventValue, eventUnit)
               }
            }
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
      path: "/api/${data.username}/lights/${getHueDeviceIdV1()}/state",
      contentType: 'application/json',
      body: commandMap,
      ignoreSSLIssues: true,
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
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceIdV1())
      }
   }
   else {
      if (logEnable == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommandV2. Can optionally use V1-inspired
  * logic to update device states if `data` map provided.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponseV2(AsyncResponse resp, Map data) {
   if (logEnable == true) log.debug "parseSendCommandResponseV2(): Response status from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (logEnable == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMapV2(data)
      if ((data.containsKey("on") || data.containsKey("dimming")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceIdV2())
      }
   }
   else {
      if (logEnable == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

/**
 * Sends HTTP PUT to Bridge using the V1-format map data provided
 * @param commandMap Groovy Map (will be converted to JSON) of Hue V1 API commands to send, e.g., [on: true]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if ["on": true] in map)
 */
void sendBridgeCommandV2(Map commandMap, Boolean createHubEvents=false) {
   if (logEnable == true) log.debug "sendBridgeCommandV2($commandMap)"
   if (commandMap == null || commandMap == [:]) {
      if (logEnable == true) log.debug "Commands not sent to Bridge because command map null or empty"
      return
   }
   parent.bridgeAsyncPutV2("parseSendCommandResponseV2", this.device, "/resource/light/${getHueDeviceIdV2()}",
                           commandMap, createHubEvents ? commandMap : null)
   if (logEnable == true) log.debug "-- Command sent to Bridge! --"
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Common_Lib ~~~
// Version 1.0.6
// For use with CoCoHue drivers (not app)

/**
 * 1.0.6 - Remove common bridgeAsyncPutV2() method (now call from parent app instead of driver)
 * 1.0.5 - Add common bridgeAsyncPutV2() method for asyncHttpPut (goal to reduce individual driver code)
 * 1.0.4 - Add common bridgeAsyncGetV2() method asyncHttpGet (goal to reduce individual driver code)
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
  * @param clipV2Path The Hue V2 API path ('/clip/v2' is automatically prepended), e.g. '/resource' or '/resource/light'
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

// REMOVED, now call from parent app instead of driver:
// /** Performs asynchttpPut() to Bridge using data retrieved from parent app or as passed in
//   * @param callbackMethod Callback method
//   * @param clipV2Path The Hue V2 API path ('/clip/v2' is automatically prepended), e.g. '/resource' or '/resource/light'
//   * @param body Body data, a Groovy Map representing JSON for the Hue V2 API command, e.g., [on: [on: true]]
//   * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null
//   * @param data Extra data to pass as optional third (data) parameter to asynchtttpPut() method
//   */
// void bridgeAsyncPutV2(String callbackMethod, String clipV2Path, Map body, Map<String,String> bridgeData = null, Map data = null) {
//    if (bridgeData == null) {
//       bridgeData = parent.getBridgeData()
//    }
//    Map params = [
//       uri: "https://${bridgeData.ip}",
//       path: "/clip/v2${clipV2Path}",
//       headers: ["hue-application-key": bridgeData.username],
//       contentType: "application/json",
//       body: body,
//       timeout: 15,
//       ignoreSSLIssues: true
//    ]
//    asynchttpPut(callbackMethod, params, data)
//    if (logEnable == true) log.debug "Command sent to Bridge: $body at ${clipV2Path}"
//    pauseExecution(200) // see if helps HTTP 429 errors?
// }


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
@Field static final String DRIVER_NAME_CONTACT        = "CoCoHue Contact Sensor"
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
// Version 1.0.5

// 1.0.5  - allow V2 for all commands
// 1.0.4  - accept String for setLevel() level also 
// 1.0.3  - levelhandling tweaks

// "SwitchLevel" commands:

void startLevelChange(String direction) {
   if (logEnable == true) log.debug "startLevelChange($direction)..."
   if (getHasV2DNI() == true) {
      Map cmd = [
            "dimming_delta": ["brightness_delta": 100, "action": (direction == "up" ? "up" : "down")],
             "dynamics":  ["duration": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                    3000 : (settings["levelChangeRate"] == "slow" ? 6000 : 4500))]]
      sendBridgeCommandV2(cmd, false) 
   }
   else {
      Map cmd = ["bri": (direction == "up" ? 254 : 1),
               "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                    30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))]
      sendBridgeCommandV1(cmd, false) 
   }
}

void stopLevelChange() {
   if (logEnable == true) log.debug "stopLevelChange()..."
   if (getHasV2DNI() == true) {
      Map cmd = ["dimming_delta": ["action": "stop"]]
      sendBridgeCommandV2(cmd, false) 
   }
   else {
      Map cmd = ["bri_inc": 0]
      sendBridgeCommandV1(cmd, false)
   }
}

void setLevel(value) {
   if (logEnable == true) log.debug "setLevel($value)"
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000)
}

void setLevel(value, rate) {
   if (logEnable == true) log.debug "setLevel(Object $value, Object $rate)"
   Float floatLevel = Float.parseFloat(value.toString())
   Integer intLevel = Math.round(floatLevel)
   Float floatRate = Float.parseFloat(rate.toString())
   setLevel(intLevel, floatRate)
}

void setLevel(Number value, Number rate) {
   if (logEnable == true) log.debug "setLevel(Number $value, Number $rate)"
   if (getHasV2DNI() == false) {
      setLevelV1(value, rate)
      return
   }
   if (value < 0) value = 0.01
   else if (value > 100) value = 100
   else if (value == 0) {
      off(rate)
      return
   }
   Integer newLevel = scaleBriToBridge(value, APIV2)
   Integer scaledRate = (rate * 1000).toInteger()
   Map bridgeCmd = [
         "on": ["on": true],
         "dimming": ["brightness": scaleBriToBridge(value, APIV2)],
         "dynamics": ["duration": scaledRate]
   ]
   sendBridgeCommandV2(bridgeCmd)
}

void setLevelV1(Number value, Number rate) {
   if (logEnable == true) log.debug "setLevel($value, $rate)"
   if (value < 0) value = 1
   else if (value > 100) value = 100
   else if (value == 0) {
      off(rate)
      return
   }
   Integer newLevel = scaleBriToBridge(value, APIV1)
   Integer scaledRate = (rate * 10).toInteger()
   Map bridgeCmd = [
      "on": true,
      "bri": newLevel,
      "transitiontime": scaledRate
   ]
   sendBridgeCommandV1(bridgeCmd)
}

/**
 * Reads device preference for on() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOnTransitionTime(String apiVersion=APIV1) {
   Integer scaledRate = null
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else {
      if (apiVersion == APIV1) scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100)
      else scaledRate = settings.onTransitionTime.toInteger()
   }
   return scaledRate
}


/**
 * Reads device preference for off() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOffTransitionTime(String apiVersion=APIV1) {
   Integer scaledRate = null
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) {
      scaledRate = getScaledOnTransitionTime()
   }
   else {
      if (apiVersion == APIV1) scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100)
      else scaledRate = settings.offTransitionTime.toInteger()
   }
   return scaledRate
}

// Internal methods for scaling


/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100)
 * @param apiVersion: Use APIV1/"V1" (default) for classic, 1-254 API values; use APIV2 for v2/SSE 0.0-100.0 values (note: 0.0 is on)
 */
Number scaleBriToBridge(Number hubitatLevel, String apiVersion=APIV1) {
   if (apiVersion == APIV1) {
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
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion=APIV1) {
   Integer scaledLevel
   if (apiVersion == APIV1) {
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
// Version 1.0.6

void setColorTemperature(String colorTemperature, level=null, transitionTime=null) {
   if (logEnable == true) log.debug "setColorTemperature(Object $colorTemperature, $level, $transitionTime)"
   setColorTemperature(colorTemperature.toInteger(),
      level != null ? level.toBigDecimal() : null,
      transitionTime != null ? transitionTime.toBigDecimal() : null
   )
}

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) {
   if (logEnable == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)"
   if (level == 0) {
      off()
      return
   }
   state.lastKnownColorMode = "CT"
   if (getHasV2DNI() == true) {
      Integer newCT = scaleCTToBridge(colorTemperature)
      Integer scaledRate = getScaledCTTransitionTime(APIV2)
      if (transitionTime != null) {
         scaledRate = (transitionTime * 1000) as Integer
      }
      Map bridgeCmd = ["on": ["on": true], "color_temperature": ["mirek": newCT]]
      if (scaledRate != null) bridgeCmd << ["dynamics": ["duration": scaledRate]]
      if (level) {
         bridgeCmd << ["dimming": ["brightness": scaleBriToBridge(level, APIV2)]]
      }
      sendBridgeCommandV2(bridgeCmd, false)
   }
   else {
      setColorTemperatureV1(colorTemperature, level, transitionTime)
   }
}

void setColorTemperatureV1(Number colorTemperature, Number level = null, Number transitionTime = null) {
   if (logEnable == true) log.debug "setColorTemperatureV1($colorTemperature, $level, $transitionTime)"
   Integer newCT = scaleCTToBridge(colorTemperature)
   Integer scaledRate = getScaledCTTransitionTime(APIV1)
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
   }
   Map bridgeCmd = ["on": true, "ct": newCT]
   if (scaledRate != null) bridgeCmd << ["transitiontime": scaledRate]
   if (level) {
      bridgeCmd << ["bri": scaleBriToBridge(level, APIV1)]
   }
   sendBridgeCommandV1(bridgeCmd)
}

/**
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units)
 */
Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) {
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
Integer scaleCTFromBridge(Number mireds) {
   if (mireds == 0) return 0
   Integer kelvin = Math.round(1000000/mireds) as Integer
   return kelvin
}

/**
 * Reads device preference for CT transition time, or provides default if not available; device
 * can use input(name: ctTransitionTime, ...) to provide this
 */
Integer getScaledCTTransitionTime(String apiVersion = APIV1) {
   Integer scaledRate = null
   if (settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) {
      String levelTT = settings.transitionTime
      if (levelTT != null) {
         scaledRate = Math.round(levelTT.toFloat())
      }
      else {
         scaledRate = (defaultLevelTransitionTime != null) ? defaultLevelTransitionTime : 400
      }
   }
   else {
      scaledRate = Math.round(settings.ctTransitionTime.toFloat())
   }
   if (apiVersion == APIV1 && scaledRate) {
      scaledRate = scaledRate / 100
   }
   return scaledRate
}

void setGenericTempName(temp) {
   if (!temp) return
   String genericName = convertTemperatureToGenericColorName(temp)
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName)
}


// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Flash_Lib ~~~
// Version 1.0.2

void flash() {
   if (logEnable == true) log.debug "flash()"
   if (getHasV2DNI() == true) {
      if (settings.txtEnable == true) log.info("${device.displayName} started ~18-hr flash cycle")
      Map<String,String> cmd = ["signaling": ["signal": "on_off", "duration": 65534000]]
      // Possible alternative, likely more similar to V1 behavior if needed:
      //Map<String,String> cmd = ["alert": ["action": "breathe"]]
      sendBridgeCommandV2(cmd, false)
   }
   else {
      if (settings.txtEnable == true) log.info("${device.displayName} started 15-cycle flash")
      Map<String,String> cmd = ["alert": "lselect"]
      sendBridgeCommandV1(cmd, false)
   }
}

void flashOnce() {
   if (logEnable == true) log.debug "flashOnce()"
   if (settings.txtEnable == true) log.info("${device.displayName} started 1-cycle flash")
   if (getHasV2DNI() == true) {
      Map cmd
      // Approximation for groups since don't support 'identify':
      if (device.deviceNetworkId.tokenize("/")[-2] == "Group") cmd = ["signaling": ["signal": "on_off", "duration": 1500]]
      // Otherwise, use normal method (API docs suggest this could change and suggest already doesn't only do single, but always has for me?):
      else cmd = ["identify": ["action": "identify"]]
      sendBridgeCommandV2(cmd, false)
   }
   else {
      Map<String,String> cmd = ["alert": "select"]
      sendBridgeCommandV1(cmd, false) 
   }
}

void flashOff() {
   if (logEnable == true) log.debug "flashOff()"
   if (settings.txtEnable == true) log.info("${device.displayName} was sent command to stop flash")
   if (getHasV2DNI() == true) {
      Map<String,String> cmd = ["signaling": ["signal": "no_signal", "duration": 0]]
      sendBridgeCommandV2(cmd, false)
   }
   else {
      Map<String,String> cmd = ["alert": "none"]
      sendBridgeCommandV1(cmd, false) 
   }
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_V2_DNI_Tools_Lib ~~~
// Version 1.0.0


/**
 * Parses V2 Hue Bridge device ID out of Hubitat DNI for use with Hue V2 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scene/HueDeviceID", so just
 * looks for string after last "/" character
 */
String getHueDeviceIdV2() {
   if (getHasV2DNI() == true) {
      return device.deviceNetworkId.split("/").last()
   }
   else {
      log.error "DNI not in V2 format but attempeting to fetch API V2 ID. Cannot continue."
   }
}

Boolean getHasV2DNI() {
   String id = device.deviceNetworkId.split("/").last()
   if (id.length() > 32) {  // max length of Hue V1 ID per regex in V2 API docs
      return true
   }
   else {
      return false
   }
}
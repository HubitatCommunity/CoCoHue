/*
 * =============================  CoCoHue Scene ===============================
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
 *  Last modified: 2024-09-18
 *
 *  Changelog:
 *  v5.1    - Remove Switch capability and associated preferences for groups and scenes
 *  v5.0.3  - Use V2 API for scene activation/recall
 *  v5.0.2  - Fetch V2 grouped_light ID owner for room/zone owners of V2 scenes
 *  v5.0.1  - Fetch additional info to avoid missing V1 IDs
 *  v5.0    - Use API v2 by default, remove deprecated features
 *  v4.2    - Add support for parsing on/off events from v2 API state; library improvements; prep for mre v2 API use
 *  v4.1.5  - Fix typos
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Refactoring to match other CoCoHue drivers
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes);
 *            Remove capability "Light" from scene driver (better chance of Alexa seeing as switch and not light)
 *  v3.5    - Minor code cleanup, removal of custom "push" command now that is standard capability command
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Improved HTTP error handling 
 *  v2.1    - Reduced info logging when not state change; code cleanup and more static typing
 *  v2.0    - Improved HTTP error handling; attribute events now generated only after hearing back from Bridge;
 *            Bridge online/offline status improvements; bug fix for off() with light- or group-device-less scenes
 *            Added options for scene "switch" attribute (on/off) behavior
 *            Added options for optional Bridge refresh on scene on/off or push (activation) commands 
 *  v1.9    - Added off() functionality
 *  v1.7    - Added configure() per Capability requirement
 *  v1.5b   - Initial public release
 */ 


import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Scene", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy") {
      capability "Actuator"
      capability "Momentary"
      capability "PushableButton"
      capability "Configuration"

      command "fetchSceneData"
   }

   preferences {
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deactivation: when this scene is activated or deactivated by a Hubitat command...  (suggested only if depend on status of these devices and not using Hue V2 API)",
         options: [["none": "Do not refresh Bridge"],
                   ["1000": "Refresh Bridge device in 1s"],
                   ["5000": "Refresh Bridge device in 5s"]],
         defaultValue: "none"
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   setDefaultAttributeValues()
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   log.debug "initialize()"
   sendEvent(name: "numberOfButtons", value: 1)
   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
   if (!hasV2DNI) fetchSceneData() // Get scene data for V1 devices
}

void configure() {
   log.debug "configure()"
   setDefaultAttributeValues()
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses V1 Hue Bridge scene ID number out of Hubitat DNI for use with Hue V1 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scene/HueDeviceID", so just
 * looks for number after last "/" character; or try state if DNI is V2 format (avoid if posssible,
 *  as Hue is likely to deprecate V1 ID data in future)
 */
String getHueDeviceIdV1() {
   String id = device.deviceNetworkId.split("/").last()
   if (hasV2DNI == true) {
      id = state.id_v1?.split("/")?.last()
      if (state.id_v1 == null) {
         log.warn "Attempting to retrieve V1 ID but not in DNI or state."
      }
   }
   return id
}

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

// these were called on() and onV1() previously:
void activate() {
   if (logEnable) log.debug "activate()"
   if (hasV2DNI == true) {
      if (logEnable) log.debug "activation will use V2 API"
      Map cmd = [recall: [action: "active"]]
      bridgeAsyncPutV2("parseSendCommandResponseV2", "/resource/scene/${getHueDeviceIdV2()}", cmd)
   }
   else {
      activateV1()
   }
}

void activateV1() {
   Map<String,String> data = parent.getBridgeData()
   Map cmd = ["scene": getHueDeviceIdV1()]
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups/0/action",
      contentType: 'application/json',
      body: cmd,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponseV1", param /*, [attribute: 'switch', value: 'on']*/)
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
   }
   if (logEnable) log.debug "Command sent to Bridge: $cmd"
}

// remove if do not re-add Switch capability

// void off() {
//    if (logEnable) log.debug "off()"
//    if (hasV2DNI == true) {
//       if (logEnable) log.debug "off() will use V2 API"
//       if (state.ownerGroupId != null) {
//          List<String> dniParts = device.deviceNetworkId.split("/")
//          String dni = "${dniParts[0]}/${dniParts[1]}/Group/${state.ownerGroupId}"
//          com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
//          if (dev != null) {
//             if (logEnable) log.debug "Hubitat device for group ${state.group} found; turning off"
//             dev.off()
//             doSendEvent("switch", "off", null) // may not need with V2 API but can't hurt?
//          }
//          else {
//             Map cmd = [on: [on: false]]
//             bridgeAsyncPutV2("parseSendCommandResponseV2", "/resource/grouped_light/${state.ownerGroupId}", cmd, null, [attribute: "switch", value: "off"])
//          }
//       }
//       else if (state.group != null) {
//          if (logEnable) log.debug "Cannot find V2 group ID to perform off() action; attepmting V1..."
//          offV1()
//       }
//       else {
//          if (logEnable) log.debug "No group information available to perform off() action. Try running Fetch Scene Data command to fix, or turn off group or lights directly instead of scene device."
//       }
//    }
//    else {
//       offV1()
//    }
// }

// void offV1() {
//    if (logEnable) log.debug "offV1()"
//    if (state.type == "GroupScene") {
//       if (logEnable) log.debug "Scene is GroupScene; turning off group $state.group"
//       List<String> dniParts = device.deviceNetworkId.split("/")
//       String dni = "${dniParts[0]}/${dniParts[1]}/Group/${state.group}"
//       com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
//       if (dev) {
//          if (logEnable) log.debug "Hubitat device for group ${state.group} found; turning off"
//          dev.off()
//          doSendEvent("switch", "off", null) // optimistic here; group device will catch if problem
//       }
//       else {
//          if (logEnable) log.debug "Device not found; sending V1 command directly to turn off Hue group"
//          Map<String,String> data = parent.getBridgeData()
//          Map cmd = ["on": false]
//          Map params = [
//                uri: data.fullHost,
//                path: "/api/${data.username}/groups/${state.group}/action",
//                contentType: 'application/json',
//                body: cmd,
//                timeout: 15
//          ]
//          asynchttpPut("parseSendCommandResponseV1", params, [attribute: 'switch', value: 'off'])
//          if (logEnable) log.debug "Command sent to Bridge: $cmd"
//       }
//    } else if (state.type == "LightScene") {
//       doSendEvent("switch", "off", null) // optimistic here (would be difficult to determine and aggregate individual light responses and should be rare anyway)
//       if (logEnable) log.debug "Scene is LightScene; turning off lights $state.lights"
//       state.lights.each {
//          List<String> dniParts = device.deviceNetworkId.split("/")
//          String dni = "${dniParts[0]}/${dniParts[1]}/Light/${it}"
//          com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
//          if (dev) {
//             if (logEnable) log.debug "Hubitat device for light ${it} found; turning off"
//             dev.off()
//          }
//          else {
//             if (logEnable) log.debug "Device not found; sending command directly to turn off Hue light"
//             Map<String,String> data = parent.getBridgeData()
//             Map cmd = ["on": false]
//             Map params = [
//                uri: data.fullHost,
//                path: "/api/${data.username}/lights/${it}/state",
//                contentType: 'application/json',
//                body: cmd,
//                timeout: 15
//             ]
//             asynchttpPut("parseSendCommandResponseV1", params)
//             if (logEnable) log.debug "Command sent to Bridge: $cmd"
//          }
//       }
//       if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
//          parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
//       }
//    }
//    else {
//       log.warn "No off() action available for scene $device.displayName"
//    }
// }

/**
 * Iterates over Hue scene state state data in Hue API v2 (SSE) format and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromMapV2(Map data) {
   if (logEnable == true) log.debug "createEventsFromMapV2($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   data.each { String key, value ->
      //log.trace "$key = $value"
      switch (key) {
         // case "status":
         //    eventName = "switch"
         //    eventValue = (value.active == "inactive" || value.active == null) ? "off" : "on"
         //    eventUnit = null
         //    if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
         //    break
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
            break
         default:
            if (logEnable == true) "not handling: $key: $value"
      }
   }
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommandV1 or similar command. Updates device
  * device state if appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponseV1(AsyncResponse resp, Map data) {
   if (logEnable) log.debug "Response from Bridge: ${resp.status}; custom data = $data"
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (logEnable) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
   }
   else {
      if (logEnable) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

/** 
  * Parses response from Bridge (or not) after bridgeAsyncPutV2().
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'] -- generally
  *             only useful as last-ditch effort when using V2 API to get scene device state correct if needed).
  */
void parseSendCommandResponseV2(AsyncResponse resp, Map data) {
   if (logEnable) log.debug "Response from Bridge: ${resp.status}; custom data = $data"
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (logEnable) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)
   }
   else {
      if (logEnable) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(btnNum) {
   if (logEnable) log.debug "push($btnNum)"
   activate()
   doSendEvent("pushed", btnNum.toInteger(), null, true)
}

/** Gets data about scene from Bridge; does not update bulb/group status */
void fetchSceneData() {
   if (logEnable) log.debug "refresh()"
   Map<String,String> data = parent.getBridgeData()
   if (data.apiVersion == APIV1 || data.apiVersion == null) {
      Map sceneParams = [
         uri: data.fullHost,
         path: "/api/${data.username}/scenes/${getHueDeviceIdV1()}",
         contentType: 'application/json',
         timeout: 15
         ]
      asynchttpGet("fetchSceneDataResponseV1", sceneParams)
   }
   else {
      bridgeAsyncGetV2("fetchSceneDataResponseV2", "/resource/scene/${getHueDeviceIdV2()}", data)
   }
}

/**
 * Parses data returned when getting scene data from Bridge for V1 API
 */
void fetchSceneDataResponseV1(resp, data) {
   if (logEnable) log.debug "fetchSceneDataResponseV1 response from Bridge: $resp.status"
   Map sceneAttributes
   try {
      sceneAttributes = resp.json
   } catch (ex) {
      log.error("Could not parse scene data: ${ex}")
      return
   }
   if (sceneAttributes["type"] == "GroupScene") {
      state.type = "GroupScene"
      state.group = sceneAttributes["group"]
      state.remove("lights")
   }
   else if (sceneAttributes["type"] == "LightScene") {
      state.type = "LightScene"
      state.lights = sceneAttributes["lights"]
      state.remove("group")
   }
   else {
      log.warn "Unknown scene type; off() commands will not work"
      state.remove("group")
      state.remove("lights")
      state.remove("type")
   }
}

/**
 * Parses data returned when getting scene data from Bridge for V2 API
 */
void fetchSceneDataResponseV2(resp, data) {
   if (logEnable) log.debug "fetchSceneDataResponseV2 response from Bridge: $resp.status"
   Map scData = resp.json.data.first()
   def ownerId = scData.group.rid
   def ownerType = scData.group.rtype
   if (ownerType == "room" || ownerType == "zone") {
      bridgeAsyncGetV2("fetchRoomOrZoneGroupIdResponseV2", "/resource/${ownerType}/${ownerId}")
   }
   else if (ownerType == "grouped_light") {
      setOwnerGroupIDV2(ownerId)
   }
}

/**
 * Parses grouped_light ID out of room or zone data when fetched after fetching scene owner data (above)
 */
void fetchRoomOrZoneGroupIdResponseV2(resp, data) {
   if (logEnable) log.debug "fetchRoomOrZoneGroupIdResponseV2 response from Bridge: $resp.status"
   Map groupedLightSvc = resp.json.data.first().services.find { it.rtype == "grouped_light" }
   if (groupedLightSvc) setOwnerGroupIDV2(groupedLightSvc.rid)
   else if (logEnable) log.debug "Unable to fetch ID for owner"
}

/**
 * Sets all group attribute values to something, intended to be called when device initially created to avoid
 * missing attribute values (may cause problems with GH integration, etc. otherwise). Default values are
 * approximately warm white and off.
 */
private void setDefaultAttributeValues() {
   if (logEnable) log.debug "Setting scene device states to sensibile default values..."
   //event = sendEvent(name: "switch", value: "off", isStateChange: false)
   event = sendEvent(name: "pushed", value: 1, isStateChange: false)
}

// void autoOffHandler() {
//    doSendEvent("switch", "off") 
// }

/**
 * Returns Hue group ID (as String, since it is likely to be used in DNI check or API call).
 * May return null (if is not GroupScene). Used by parent app.
 */
String getGroupID() {
   return state.group
}

/**
 * Sets state.owner to specified ID (ID of grouped_light service from owner room or zone); used only for V2 API
 */
String setOwnerGroupIDV2(String id) {
   state.ownerGroupId = id
}

// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Common_Lib ~~~
// Version 1.0.5
// For use with CoCoHue drivers (not app)

/**
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

/** Performs asynchttpPut() to Bridge using data retrieved from parent app or as passed in
  * @param callbackMethod Callback method
  * @param clipV2Path The Hue V2 API path ('/clip/v2' is automatically prepended), e.g. '/resource' or '/resource/light'
  * @param body Body data, a Groovy Map representing JSON for the Hue V2 API command, e.g., [on: [on: true]]
  * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null
  * @param data Extra data to pass as optional third (data) parameter to asynchtttpPut() method
  */
void bridgeAsyncPutV2(String callbackMethod, String clipV2Path, Map body, Map<String,String> bridgeData = null, Map data = null) {
   if (bridgeData == null) {
      bridgeData = parent.getBridgeData()
   }
   Map params = [
      uri: "https://${bridgeData.ip}",
      path: "/clip/v2${clipV2Path}",
      headers: ["hue-application-key": bridgeData.username],
      contentType: "application/json",
      body: body,
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpPut(callbackMethod, params, data)
   if (logEnable == true) log.debug "Command sent to Bridge: $body at ${clipV2Path}"
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
/*
 * =============================  CoCoHue Scene (Driver) ===============================
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
 *  Last modified: 2024-07-28
 * 
 *  Changelog:
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
      capability "Refresh"
      capability "Switch"
      capability "PushableButton"
      capability "Configuration"
   }

   preferences {
      input name: "onPropagation", type: "enum", title: "Scene \"on\"/\"off\" behavior: when this scene is activated...",
         options: [["none": "Do not manipulate other scene device states"],
                   ["groupScenesOff": "Mark other scenes for this group as off (if GroupScene)"],
                   ["allScenesOff": "Mark all other CoCoHue scenes as off"],
                   ["autoOff": "Automatically mark as off in 5 seconds"]],
         defaultValue: "groupScenesOff"
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deactivation: when this scene is activated or deactivated by a Hubitat command...",
         options: [["none": "Do not refresh Bridge"],
                   ["1000": "Refresh Bridge device in 1s"],
                   ["5000": "Refresh Bridge device in 5s"]],
         defaultValue: "none"
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
   refresh() // Get scene data
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
 * Parses Hue Bridge scene ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scenes/HueSceneID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on() {
   Map<String,String> data = parent.getBridgeData()
   Map cmd = ["scene": getHueDeviceNumber()]
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups/0/action",
      contentType: 'application/json',
      body: cmd,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponse", params, [attribute: 'switch', value: 'on'])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
   }
   if (enableDebug) log.debug "Command sent to Bridge: $cmd"
}

void off() {
   if (enableDebug) log.debug "off()"
   if (state.type == "GroupScene") {
      if (enableDebug) log.debug "Scene is GroupScene; turning off group $state.group"
      List<String> dniParts = device.deviceNetworkId.split("/")
      String dni = "${dniParts[0]}/${dniParts[1]}/Group/${state.group}"
      com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
      if (dev) {
         if (enableDebug) log.debug "Hubitat device for group ${state.group} found; turning off"
         dev.off()
         doSendEvent("switch", "off", null) // optimistic here; group device will catch if problem
      }
      else {
         if (enableDebug) log.debug "Device not found; sending command directly to turn off Hue group"
         Map<String,String> data = parent.getBridgeData()
         Map cmd = ["on": false]
         Map params = [
               uri: data.fullHost,
               path: "/api/${data.username}/groups/${state.group}/action",
               contentType: 'application/json',
               body: cmd,
               timeout: 15
         ]
         asynchttpPut("parseSendCommandResponse", params, [attribute: 'switch', value: 'off'])
         if (enableDebug) log.debug "Command sent to Bridge: $cmd"
      }
   } else if (state.type == "LightScene") {
      doSendEvent("switch", "off", null) // optimistic here (would be difficult to determine and aggregate individual light responses and should be rare anyway)
      if (enableDebug) log.debug "Scene is LightScene; turning off lights $state.lights"
      state.lights.each {
         List<String> dniParts = device.deviceNetworkId.split("/")
         String dni = "${dniParts[0]}/${dniParts[1]}/Light/${it}"
         com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
         if (dev) {
            if (enableDebug) log.debug "Hubitat device for light ${it} found; turning off"
            dev.off()
         }
         else {
            if (enableDebug) log.debug "Device not found; sending command directly to turn off Hue light"
            Map<String,String> data = parent.getBridgeData()
            Map cmd = ["on": false]
            Map params = [
               uri: data.fullHost,
               path: "/api/${data.username}/lights/${it}/state",
               contentType: 'application/json',
               body: cmd,
               timeout: 15
            ]
            asynchttpPut("parseSendCommandResponse", params)
            if (enableDebug) log.debug "Command sent to Bridge: $cmd"
         }
      }
      if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
         parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
      }
   }
   else {
      log.warn "No off() action available for scene $device.displayName"
   }
}

/**
 * Iterates over Hue scene state state data in Hue API v2 (SSE) format and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromSSE(Map data) {
   if (enableDebug == true) log.debug "createEventsFromSSE($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean hasCT = data.color_temperature?.mirek != null
   data.each { String key, value ->
      //log.trace "$key = $value"
      switch (key) {
         case "status":
            eventName = "switch"
            eventValue = (value.active == "inactive" || value.active == null) ? "off" : "on"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
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
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug) log.debug "Response from Bridge: ${resp.status}; data from app = $data"
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (enableDebug) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
      if (data.attribute == "switch" && data.value == "on") {
         if (settings["onPropagation"] == "groupScenesOff") {
            parent.updateSceneStateToOffForGroup(state.group ?: "0", device.deviceNetworkId)
         }
         else if (settings["onPropagation"] == "allScenesOff") {
            parent.updateSceneStateToOffForGroup("0", device.deviceNetworkId)
         }
         else if (settings["onPropagation"] == "autoOff") {
            runIn(5, autoOffHandler)
         }
         else {
            if (enableDebug) log.debug "No scene onPropagation configured; leaving other scene states as-is"
         }
      }
   }
   else {
      if (enableDebug) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(Number btnNum) {
   if (enableDebug) log.debug "push($btnNum)"
   on()
   doSendEvent("pushed", 1, null, true)
}

/** Gets data about scene from Bridge; does not update bulb/group status */
void refresh() {
   if (enableDebug) log.debug "refresh()"
   Map<String,String> data = parent.getBridgeData()
   Map sceneParams = [
      uri: data.fullHost,
      path: "/api/${data.username}/scenes/${getHueDeviceNumber()}",
      contentType: 'application/json',
      timeout: 15
      ]
   asynchttpGet("parseSceneAttributeResponse", sceneParams)  
}

/**
 * Parses data returned when getting scene data from Bridge
 */
void parseSceneAttributeResponse(resp, data) {
   if (enableDebug) log.debug "parseSceneAttributeResponse response from Bridge: $resp.status"
   Map sceneAttributes
   try {
      sceneAttributes = resp.json
   } catch (ex) {
      log.error("Could not parse scene data: ${resp.errorMessage ?: ex}")
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
 * Sets all group attribute values to something, intended to be called when device initially created to avoid
 * missing attribute values (may cause problems with GH integration, etc. otherwise). Default values are
 * approximately warm white and off.
 */
private void setDefaultAttributeValues() {
   if (enableDebug) log.debug "Setting scene device states to sensibile default values..."
   event = sendEvent(name: "switch", value: "off", isStateChange: false)
   event = sendEvent(name: "pushed", value: 1, isStateChange: false)
}

void autoOffHandler() {
   doSendEvent("switch", "off") 
}

/**
 * Returns Hue group ID (as String, since it is likely to be used in DNI check or API call).
 * May return null (if is not GroupScene)
 */
String getGroupID() {
   return state.group
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

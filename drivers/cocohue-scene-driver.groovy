/*
 * =============================  CoCoHue Scene (Driver) ===============================
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

metadata {
   definition (name: "CoCoHue Scene", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "Light"
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
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deacivation: when this scene is activated or deactivated by a Hubitat command...",
         options: [["none": "Do not refresh Bridge"],
                   ["1000": "Refresh Bridge device in 1s"],
                   ["5000": "Refrehs Bridge device in 5s"]],
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
   Integer disableMinutes = 30
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableMinutes} minutes"
      runIn(disableMinutes*60, debugOff)
   }
   refresh() // Get scene data
}

void configure() {
   log.debug "configure()"
   setDefaultAttributeValues()
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
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponse(resp, data) {
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

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @param resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(resp) {
   if (enableDebug) log.debug "Checking if valid HTTP response/data from Bridge..."
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

void push(Number btnNum) {
   if (enableDebug) log.debug "push($btnNum)"
   on()
   doSendEvent("pushed", 1, null, true)
}

void doSendEvent(String eventName, eventValue, String eventUnit=null, forceStateChange=false) {
   //if (enableDebug) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (enableDesc) log.info descriptionText
   // TODO: Map-ify these parameters to make cleaner and less verbose?
   if (eventUnit) {
      if (forceStateChange) {
         sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true) 
      }
      else {
         sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)          
      }
   } else {
      if (forceStateChange) {
         sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  
      }
      else {
         sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  
      }
   }
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
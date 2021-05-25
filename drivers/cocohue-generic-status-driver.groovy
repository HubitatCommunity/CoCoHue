/*
 * =============================  CoCoHue Generic Status Device (Driver) ===============================
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
 *  v3.0    - Initial release
 */
 
metadata {
   definition (name: "CoCoHue Generic Status Device", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-generic-status-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "PushableButton"
   }
       
   preferences {
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deacivation: when this device is activated or deactivated by a Hubitat command...",
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
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/SensorRL/HueDeviceID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on() {
   if (enableDebug) log.debug "on()"
   sendBridgeCommand(["status": 1])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(new Integer(settings["onRefresh"]), "refreshBridge")
   }
}

void off() {
   if (enableDebug) log.debug "off()"
   sendBridgeCommand(["status": 0])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(new Integer(settings["onRefresh"]), "refreshBridge")
   }
}

void push(btnNum) {
   if (enableDebug) log.debug "push($btnNum)"
   on()
   doSendEvent("pushed", 1, null, true)
}

/**
 * Iterates over Hue device commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge (or if pre-staged attribute is changed and "real" command not yet able to be sent, but
 * this isn't supported for sensors, so this driver's methods are a bit different)
 * @param stateMap Map of JSON device state as received from Bridge
 */
void createEventsFromMap(Map stateMap) {
   if (!stateMap) {
      if (enableDebug) log.debug "createEventsFromMap called but state map empty; exiting"
      return
   }
   if (enableDebug) log.debug "Preparing to create events from map: ${stateMap}"
   String eventName, eventUnit, descriptionText
   String eventValue // should only be string here; could be String or number with lights/groups
   stateMap.each {
      switch (it.key) {
         case "status":
            eventName = "switch"
            eventValue = ((it.value as Integer) != 0) ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the command map (auto-converted to JSON)
 * @param bridgeCmds Map of Bridge command to send, e.g., ["state": 1]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for sensor's "switch" if contains "state": 1)
 */
void sendBridgeCommand(Map bridgeCmds = [:], Boolean createHubEvents=true) {
   if (enableDebug) log.debug "Sending command to Bridge: ${bridgeCmds}"
   if (!bridgeCmds) {
      log.debug("Commands not sent to Bridge because command map empty")
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/sensors/${getHueDeviceNumber()}/state",
      contentType: 'application/json',
      body: bridgeCmds,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponse", params, createHubEvents ? bridgeCmds : null)
   if (enableDebug) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponse(resp, data) {
   if (enableDebug) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (enableDebug) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
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

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) {
   //if (enableDebug) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (settings.enableDesc == true) log.info(descriptionText)
   if (eventUnit) {
      if (forceStateChange) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      if (forceStateChange) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true) 
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
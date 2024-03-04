/*
 * =============================  CoCoHue Generic Status Device (Driver) ===============================
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
 *  Last modified: 2024-03-02
 * 
 *  Changelog:
 *  v4.1.5  - Fix typos
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup, removal of custom "push" command now that is standard capability command
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Initial release
 */

// This driver is used for Hue Labs activator devices



import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Generic Status Device", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-generic-status-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "PushableButton"
   }
       
   preferences {
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deactivation: when this device is activated or deactivated by a Hubitat command...",
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
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
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
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (enableDebug) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
   }
   else {
      if (enableDebug) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
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

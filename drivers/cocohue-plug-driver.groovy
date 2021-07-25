/*
 * =============================  CoCoHue On/Off Plug/Light (Driver) ===============================
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
 *  Last modified: 2021-07-24
 * 
 *  Changelog:
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Addded "reachable" attribte from Bridge to bulb and group drivers (thanks to @jtp10181 for original implementation)
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Fix so events no created until Bridge response received (as was done for other drivers in 2.0); improved HTTP error handling
 *  v2.1    - Minor code cleanup; more static typing
 *  v2.0    - Improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.8    - Added ability to disable plug->group state propagation;
 *            Removed ["alert:" "none"] from on() command, now possible explicitly with flashOff()
 *  v1.7    - Initial Release  
 */
 
 // can comment out if don't need commands; see also definition() below

metadata {
   definition(name: "CoCoHue On/Off Plug", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "Light"
      
      // Not supported on (most?) plugs; can uncomment if you are using for lights that support this:
      //command "flash" 
      //command "flashOnce" 
      //command "flashOff"

      attribute "reachable", "string"
   }
       
   preferences {
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when plug state changes", defaultValue: false
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

void on() {
   if (enableDebug == true) log.debug "on()"
   Map bridgeCmd = ["on": true]
   sendBridgeCommand(bridgeCmd)
}

void off() {
   if (enableDebug == true) log.debug "off()"
   Map bridgeCmd = ["on": false]
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
   String eventValue // only String for on/off devices (could be number with others)
   bridgeMap.each {
      switch (it.key) {
         case "on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
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
// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Common_Lib, line 1
library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 2
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 3
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 4
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 5
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 6
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 7
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 8
) // library marker RMoRobert.CoCoHue_Common_Lib, line 9

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 11
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 12
   device.updateSetting("enableDebug", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 13
} // library marker RMoRobert.CoCoHue_Common_Lib, line 14

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 16
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 17
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 18
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 19
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 20
private Boolean checkIfValidResponse(resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 21
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 22
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 23
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 24
      if (resp?.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 25
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 26
         if (resp?.headers == null) log.error "Error: HTTP ${resp?.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 27
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 28
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 29
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 30
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 31
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 32
         if (resp.json[0]?.error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 33
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 34
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 35
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 36
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 37
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 38
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 39
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 40
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 41
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 42
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 43
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 44
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 45
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 46
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 47
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 48
      if (isOK == true) parent.setBridgeStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 49
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 50
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 51
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 52
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 53
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 54
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 55
} // library marker RMoRobert.CoCoHue_Common_Lib, line 56

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 58
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 59
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 60
   if (settings.enableDesc == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 61
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 62
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 63
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 64
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 65
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 66
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 67
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 68
} // library marker RMoRobert.CoCoHue_Common_Lib, line 69

// ~~~~~ end include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~

// ~~~~~ start include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Flash_Lib, line 1
library ( // library marker RMoRobert.CoCoHue_Flash_Lib, line 2
   base: "driver", // library marker RMoRobert.CoCoHue_Flash_Lib, line 3
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Flash_Lib, line 4
   category: "Convenience", // library marker RMoRobert.CoCoHue_Flash_Lib, line 5
   description: "For internal CoCoHue use only. Not intended for external use. Contains flash-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Flash_Lib, line 6
   name: "CoCoHue_Flash_Lib", // library marker RMoRobert.CoCoHue_Flash_Lib, line 7
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Flash_Lib, line 8
) // library marker RMoRobert.CoCoHue_Flash_Lib, line 9

void flash() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 11
   if (enableDebug == true) log.debug "flash()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 12
   if (settings.enableDesc == true) log.info("${device.displayName} started 15-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 13
   Map<String,String> cmd = ["alert": "lselect"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 14
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 15
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 16

void flashOnce() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 18
   if (enableDebug == true) log.debug "flashOnce()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 19
   if (settings.enableDesc == true) log.info("${device.displayName} started 1-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 20
   Map<String,String> cmd = ["alert": "select"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 21
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 22
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 23

void flashOff() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 25
   if (enableDebug == true) log.debug "flashOff()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 26
   if (settings.enableDesc == true) log.info("${device.displayName} was sent command to stop flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 27
   Map<String,String> cmd = ["alert": "none"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 28
   sendBridgeCommand(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 29
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 30

// ~~~~~ end include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~

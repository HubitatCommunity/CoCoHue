/*
 * =============================  CoCoHue On/Off Plug/Light (Driver) ===============================
 *
 *  Copyright 2019-2020 Robert Morris
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
 *  Last modified: 2020-09-16
 *  Version: 2.0.0-preview.5
 * 
 *  Changelog:
 * 
 *  v2.0    - Improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.8    - Added ability to disable plug->group state propagation;
 *            Removed ["alert:" "none"] from on() command, now possible explicitly with flashOff()
 *  v1.7    - Initial Release  
 */
 
metadata {
   definition (name: "CoCoHue On/Off Plug", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "Light"
      
      // Not supported on (most?) plugs; can uncomment if you are using for lights that support this:
      //command "flash" 
      //command "flashOnce" 
      //command "flashOff" 
   }
       
   preferences {
      input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
      input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
      input(name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when plug state changes", defaultValue: false)
   }
}

def installed(){
   log.debug "Installed..."
   initialize()
}

def updated(){
   log.debug "Updated..."
   initialize()
}

def initialize() {
   log.debug "Initializing"
   int disableTime = 1800
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
      runIn(disableTime, debugOff)
   }
}

def refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

def debugOff() {
   log.warn("Disabling debug logging")
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

// Probably won't happen but...
def parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
 * looks for number after third "/" character
 */
def getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

def on() {    
   logDebug("Turning on...")
   addToNextBridgeCommand(["on": true], true)
   sendBridgeCommand()
}

def off() {    
   logDebug("Turning off...")
   addToNextBridgeCommand(["on": false], true)
   sendBridgeCommand()
}

def flash() {
   logDesc("${device.displayName} started 15-cycle flash")
   def cmd = ["alert": "lselect"]
   sendBridgeCommand(cmd, false) 
}

def flashOnce() {
   logDesc("${device.displayName} started 1-cycle flash")
   def cmd = ["alert": "select"]
   sendBridgeCommand(cmd, false) 
}

def flashOff() {
   logDesc("${device.displayName} was sent command to stop flash")
   def cmd = ["alert": "none"]
   sendBridgeCommand(cmd, false) 
}

/**
 * Used to build body of (future) HTTP PUT to Bridge; useful to build up
 * parts a command in multiple places/methods and then send to Bridge as one
 * command (e.g., if "on" with lots of prestaging enabled and set) to maximize
 * efficiency and provide one transition instead of multiple.
 * @param cmdToAdd Map of Bridge commands to place in next command to be sent--example: ["on": true]
 * @param clearFirst If true (optional; default is false), will clear pending command map first
 */
def addToNextBridgeCommand(Map cmdToAdd, boolean clearFirst=false) {
    if (clearFirst || !state.nextCmd) state.nextCmd = [:]
    state.nextCmd << cmdToAdd
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge or if pre-staged attribute is changed and "real" command not yet able to be sent, or
 * to parse/update light states based on data received from Bridge
 * @param bridgeCmd Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge; defaults to the  state attribute created by addToNextBridgeCommand if not provided
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; if true, will ignore differences for prestaged attributes if switch state is off
 */
def createEventsFromMap(Map bridgeCmd = state.nextCmd, boolean isFromBridge = false) {
   if (!bridgeCmd) {
      logDebug("createEventsFromMap called but map command empty; exiting")
      return
   }
   logDebug("Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeCmd}")
   def eventName, eventValue, eventUnit, descriptionText
   Boolean isOn
   bridgeCmd.each {
      switch (it.key) {
         case "on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
            eventUnit = null                
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            isOn = it.value
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
 * Sends HTTP PUT to Bridge using the either command map previously built
 * with one or more calls to addToNextBridgeCommand and clears
 * that map (this is normally the case) or sends custom map without changing this
 * map (useful for one-off Hubitat commands like start/stopLevelChange)
 * @param customMap If provided, uses this map instead of the one previously built =
 *        with addToNextBridgeCommand; if not provided, uses and then clears the
 *        previously built map
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if map contains "on": true)
 */
def sendBridgeCommand(Map customMap = null, boolean createHubEvents=true) {    
   logDebug("Sending command to Bridge: ${customMap ?: state.nextCmd}")
   Map cmd = [:]
   if (customMap != null) {
      cmd = customMap
   } else {
      cmd = state.nextCmd
      state.remove("nextCmd")
   }
   if (!cmd) {
      log.debug("Commands not sent to Bridge because command map empty")
      return
   }
   if (createHubEvents) createEventsFromMap(cmd)
   def data = parent.getBridgeData()
   def params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights/${getHueDeviceNumber()}/state",
      contentType: 'application/json',
      body: cmd,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponse", params, createHubEvents ? cmd : null)
   logDebug("-- Command sent to Bridge!" --)
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponse(resp, data) {
   logDebug("Response from Bridge: ${resp.status}")
   if (checkIfValidResponse(resp) && data) {
      logDebug("  Bridge response valid; creating events from data map")          
      createEventsFromMap(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceNumber())
      }
   }
   else {
      logDebug("  Not creating events from map because not specified to do or Bridge response invalid")
   }
}

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @param resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(resp) {
   logDebug("Checking if valid HTTP response/data from Bridge...")
   Boolean isOK = true
   if (resp?.json == null) {
      isOK = false
      if (resp?.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge"
      else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})"
      parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
      parent.setBridgeStatus(false)
   }
   else if (resp.status < 400 && resp.json) {
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
   if (isOK) parent.setBridgeStatus(true)
   return isOK
}

def doSendEvent(eventName, eventValue, eventUnit=null) {
   logDebug("Creating event for $eventName...")
   def descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   logDesc(descriptionText)
   def event
   if (eventUnit) {
      event = sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      event = sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
   }
   return event
}

def logDebug(str) {
   if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
   if (settings.enableDesc) log.info(str)
}
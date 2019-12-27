/*
 * =============================  CoCoHue On/Off Plug/Light (Driver) ===============================
 *
 *  Copyright 2019 Robert Morris
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
 *  Last modified: 2019-12-27
 * 
 *  Changelog:
 * 
 *  v1.7 Initial Release
 */ 

metadata {
    definition (name: "CoCoHue On/Off Plug", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Light"
        
        // Not supported on (most?) plugs; can uncomment if you are using for lights that support this:
        //command "flash" 
        //command "flashOnce"  
    }
       
   preferences {
        input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
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

def configure() {
    // Do I need to do anything here?
    log.warn "configure()"
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
    addToNextBridgeCommand(["on": true, "alert": "none"], true)
    sendBridgeCommand()
}

def off() {    
    logDebug("Turning off...")
    addToNextBridgeCommand(["on": false], true)
    sendBridgeCommand()
}

def flash() {
    logDebug("Starting flash (note: not supported on plugs; bulbs will stop automatically after 15 cycles)...")
    def cmd = ["alert": "lselect"]
    sendBridgeCommand(cmd, false) 
}

def flashOnce() {
    logDebug("Running flashOnce... (note: not supported on plugs)")
    def cmd = ["alert": "select"]
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
    boolean isOn
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
        body: cmd
        ]
    asynchttpPut("parseBridgeResponse", params)
    if (cmd.containsKey("on") || cmd.containsKey("bri")) {
        parent.updateGroupStatesFromBulb(cmd, getHueDeviceNumber()) 
    }
    logDebug("-- Command sent to Bridge!" --)
}

def doSendEvent(eventName, eventValue, eventUnit) {
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

/**
 * Generic callback for async Bridge calls when we don't care about
 * the response (but can log it if debug enabled)
 */
def parseBridgeResponse(resp, data) {
    logDebug("Response from Bridge: $resp.status")
}

def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}
/*
 *  Dimmable Only Bulb Driver developed from the RGBW bulb driver by Ryan780
 * =============================  CoCoHue RGBW Bulb (Driver) ===============================
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
 *  Last modified: 2019-12-29
 * 
 *  Changelog:
 * 
 *  v1.0 - Initial Release
 *  v1.1 - Added flash commands
 *  v1.5 - Added additional custom commands and more consistency with effect behavior
 *  v1.6 - Eliminated duplicate color/CT events on refresh
 *  v1.6b - Changed bri_inc to match Hubitat behavior
 *  v1.7 - Bulb switch/level states now propgate to groups w/o polling (TODO: add option to disable both?)
 *  v1.7b - Modified startLevelChange behavior to avoid possible problems with third-party devices
 */ 

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static Map lightEffects = [0: "None", 1:"Color Loop"]

metadata {
    definition (name: "CoCoHue Dimmable Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-dimmable-bulb-driver.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "ChangeLevel"
        capability "Light"

        command "flash"
        command "flashOnce"
           
    }
       
   preferences {
        input(name: "transitionTime", type: "enum", description: "", title: "Transition time", options: [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400)
        input(name: "levelStaging", type: "bool", description: "", title: "Enable level pseudo-prestaging", defaultValue: false)
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
    /* TODO: Add setting for "agressive" vs. normal prestaging (?), and for regular pre-staging,
     check if current level is different from lastXYZ value, in which case it was probably
     changed outside of Hubitat and we should not set the pre-staged value(s)--Hue does not
     support "true" prestaging, so any prestaging is a Hubitat-only workaround */
    // Disables lselect alert if in progress to be consistent with other drivers that stop flash with on()
    addToNextBridgeCommand(["on": true], !(levelStaging))
    sendBridgeCommand()
    state.remove("lastLevel")
}

def off() {    
    logDebug("Turning off...")
    state.remove("lastLevel")
    addToNextBridgeCommand(["on": false], true)
    sendBridgeCommand()
}


def startLevelChange(direction) {
    logDebug("Running startLevelChange($direction)...")
    def cmd = ["bri": (direction == "up" ? 254 : 1), "transitiontime": 30]
    sendBridgeCommand(cmd, false) 
}

def stopLevelChange() {
    logDebug("Running stopLevelChange...")
    def cmd = ["bri_inc": 0]
    sendBridgeCommand(cmd, false) 
}

def setLevel(value) {
    setLevel(value, ((transitionTime != null ? transitionTime.toBigDecimal() : 1000)) / 1000)
}

def setLevel(value, rate) {
    logDebug("Setting level to ${value}% over ${rate}s...")
    state.remove("lastLevel")
    if (value < 0) value = 1
    else if (value > 100) value = 100
    else if (value == 0) {
        off()
        logDebug("Level is 0 so turning off instead")
        return
    }
    def newLevel = scaleBriToBridge(value)
    def scaledRate = (rate * 10).toInteger()
    addToNextBridgeCommand(["bri": newLevel, "transitiontime": scaledRate], !levelStaging)
    def isOn = device.currentValue("switch") == "on"    
    if (!levelStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        sendBridgeCommand()
    } else {
        state["lastLevel"] = device.currentValue("level")
        createEventsFromMap()
    }
}



def flash() {
    logDebug("Starting flash (note: Hue will automatically stop flashing after 15 cycles; this is not indefinite)...")
    def cmd = ["alert": "lselect"]
    sendBridgeCommand(cmd, false) 
}

def flashOnce() {
    logDebug("Running flashOnce...")
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
            case "bri":
                eventName = "level"
                eventValue = scaleBriFromBridge(it.value)
                eventUnit = "%"
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && levelStaging && state.nextCmd?.get("bri")) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)                    
                }
                break        
			case "transitiontime":
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
    // Remove color effect if present and user setting hue or CT.
    // Hue apparently needs this as a separate command first, or it will just restore
    // to what it was before (not what else is passed along with)
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

def refresh() {
    log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

def configure() {
    // Do I need to do anything here?
    log.warn "configure()"
}

/**
 * Generic callback for async Bridge calls when we don't care about
 * the response (but can log it if debug enabled)
 */
def parseBridgeResponse(resp, data) {
    logDebug("Response from Bridge: $resp.status")
}

/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254
 */
private scaleBriToBridge(hubitatLevel) {
    def scaledLevel =  hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254
    return Math.round(scaledLevel)
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100
 */
private scaleBriFromBridge(bridgeLevel) {
    def scaledLevel = bridgeLevel.toBigDecimal() / 254 * 100
    if (scaledLevel < 1) scaledLevel = 1
    return Math.round(scaledLevel)
}


def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}
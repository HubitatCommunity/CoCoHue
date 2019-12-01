/*
 * =============================  CoCoHue Scene (Driver) ===============================
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
 *  Last modified: 2019-12-01
 * 
 *  Changelog:
 * 
 *  v1.5 - Initial public release
 *
 */ 

metadata {
    definition (name: "CoCoHue Scene", namespace: "RMoRobert", author: "Robert Morris", importURL: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgbw-bulb-driver.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Light"
        capability "PushableButton"
        
        command "push",	[[name:"NUMBER", type: "NUMBER", description: "Button number" ]]
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
    sendEvent(name: "numberOfButtons", value: 1)				
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
 * Parses Hue Bridge scene ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scenes/HueSceneID", so just
 * looks for number after third "/" character
 */
def getHueDeviceNumber() {
    return device.deviceNetworkId.split("/")[3]
}

def on() {    
    logDebug("Turning on scene...")
    def data = parent.getBridgeData()
    def cmd = ["scene": getHueDeviceNumber()]
    def params = [
        uri: data.fullHost,
        path: "/api/${data.username}/groups/0/action",
        contentType: 'application/json',
        body: cmd
        ]
    asynchttpPut("parseBridgeResponse", params)
    logDebug("Command sent to Bridge: $cmd")
    doSendEvent("switch", "on", null)
    logDebug("----------------------------------------------")
}

def off() {
    logDebug("Turing off scene device (note: has no effect on Hue devices)")
    doSendEvent("switch", "off", null)
}

def push(btnNum) {
    on()
    doSendEvent("pushed", "1", null)
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

def configure() {}

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

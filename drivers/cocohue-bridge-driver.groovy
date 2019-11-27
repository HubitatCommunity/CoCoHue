/**
 * =============================  CoCoHue Bridge (Driver) ===============================
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
 *  Last modified: 2019-11-26
 *
 *  Changelog:
 * 
 *  v1.0 - Initial Release
 *
 */ 

//import groovy.json.JsonBuilder

metadata {
    definition (name: "CoCoHue Bridge", namespace: "RMoRobert", author: "Robert Morris", importURL: "https://raw.githubusercontent.com/RMoRobert/CoCoHue/master/drivers/cocohue-bridge-driver.groovy") {
        capability "Refresh"
        attribute "status", "string"   // TODO: This is not yet updated
    }
    
    preferences() {
        section("") {
            input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
            input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
        }
    }   
}

def debugOff() {
    log.warn("Disabling debug logging")
    device.updateSetting("enableDebug", [value:"false", type:"bool"])
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
    log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
    if (enableDebug) runIn(disableTime, debugOff)    
}

// Probably won't happen but...
def parse(String description) {
    logDebug("Running parse for: '${description}'")
}

def refresh() {
    logDebug("Refresh...")
    def data = parent.getBridgeData()
    def lightParams = [
        uri: data.fullHost,
        path: "/api/${data.username}/lights",
        contentType: 'application/json',
        body: cmds
        ]
    def groupParams = [
        uri: data.fullHost,
        path: "/api/${data.username}/groups",
        contentType: 'application/json',
        body: cmds
        ]
    asynchttpGet("parseLightStates", lightParams)    
    asynchttpGet("parseGroupStates", groupParams)
}

// ------------ BULBS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in parent app state when finished. Intended to be called
 *  during bulb discovery in app.
 */
def getAllBulbs() {
    logDebug("Getting bulb list from Bridge...")
    //clearBulbsCache()
    def data = parent.getBridgeData()
    def params = [
        uri: data.fullHost,
        path: "/api/${data.username}/lights",
        contentType: "application/json",
        ]
    asynchttpGet("parseGetAllBulbsResponse", params)
}

private parseGetAllBulbsResponse(resp, data) {
    logDebug("Parsing in parseGetAllBulbsResponse") 
    def body = resp?.json
    def bulbs = [:]
    body?.each { key, val ->
        bulbs[key] = [name: val.name, type: val.type]
    }
    state.allBulbs = bulbs
}


/** Intended to be called from parent Bridge Child app to retrive previously
 *  requested list of bulbs
 */
def getAllBulbsCache() {
    return state.allBulbs 
}

/** Clears cache of bulb IDs/names/types; useful for parent app to call if trying to ensure
 * not working with old data
 */
def clearBulbsCache() {
    logDebug("Running clearBulbsCache...")
    state.allBulbs = [:]
}

/** Callback method that handles updating attributes on child light
 *  devices when Bridge refreshed
 */
def parseLightStates(resp, data) { 
    logDebug("Parsing light states from Bridge...")
    def lightStates
    try {
        lightStates = resp.json
    } catch (ex) {
        log.error("Error requesting light data: ${resp.errorMessage ?: ex}")
        return        
    }    
    lightStates.each { id, val ->
        def device = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
        if (device) {
            device.createEventsFromMap(val.state, true)
        }
    }
}

// ------------ GROUPS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in parent app state when finished. Intended to be called
 *  during bulb discovery in app.
 */
def getAllGroups() {
    logDebug("Getting group list from Bridge...")
    //clearGroupsCache()
    def data = parent.getBridgeData()
    def params = [
        uri: data.fullHost,
        path: "/api/${data.username}/groups",
        contentType: "application/json",
        ]
    asynchttpGet("parseGetAllGroupsResponse", params)
}

private parseGetAllGroupsResponse(resp, data) {
    logDebug("Parsing in parseGetAllGroupsResponse") 
    def body = resp?.json
    def groups = [:]
    body?.each { key, val ->
        groups[key] = [name: val.name, type: val.type]
    }
    state.allGroups = groups
}


/** Intended to be called from parent Bridge Child app to retrive previously
 *  requested list of groups
 */
def getAllGroupsCache() {
    return state.allGroups
}

/** Clears cache of group IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
def clearGroupsCache() {
    logDebug("Running clearGroupsCache...")
    state.allGroups = [:]
}

/** Callback method that handles updating attributes on child group
 *  devices when Bridge refreshed
 */
def parseGroupStates(resp, data) {
    logDebug("Parsing group states from Bridge...")
    def groupStates
    try {
        groupStates = resp.json
    } catch (ex) {
        log.error("Error requesting light data: ${resp.errorMessage ?: ex}")
        return        
    }    
    groupStates.each { id, val ->
        def device = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
        if (device) {
            device.createEventsFromMap(val.action, true)
            device.createEventsFromMap(val.state, true)
        }
    }
}

def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}
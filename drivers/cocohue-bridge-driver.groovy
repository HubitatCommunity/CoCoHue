/**
 * =============================  CoCoHue Bridge (Driver) ===============================
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
 *  Last modified: 2020-11-25
 *
 *  Changelog:
 *  v2.1    - Minor code cleanup and more static typing
 *  v2.0    - Added Actuator capability; Bridge and HTTP error handling improvements; added specific HTTP timeout
 *  v1.5    - Additional methods to support scenes and improved group behavior
 *  v1.0    - Initial Release
 */ 

metadata {
   definition (name: "CoCoHue Bridge", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      attribute "status", "string"
   }
   
   preferences() {
      input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
      input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
   }   
}

void debugOff() {
   log.warn("Disabling debug logging")
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

void installed(){
   log.debug "Installed..."
   initialize()
}

void updated(){
   log.debug "Updated..."
   initialize()
}

void initialize() {
   log.debug "Initializing"
   int disableTime = 1800
   log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
   if (enableDebug) runIn(disableTime, debugOff)    
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Ignoring parse() for: '${description}'")
}

void refresh() {
   logDebug("Refresh...")
   Map<String,String> data = parent.getBridgeData()
   Map lightParams = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights",
      contentType: 'application/json',
      timeout: 15
      ]
   Map groupParams = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups",
      contentType: 'application/json',
      timeout: 15
      ]
   try {
      asynchttpGet("parseLightStates", lightParams)
      asynchttpGet("parseGroupStates", groupParams)
   } catch (Exception ex) {
      log.error "Error in refresh: $ex"
   }
}

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @params resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(resp) {
   logDebug("Checking if valid HTTP response/data from Bridge...")
   Boolean isOK = true
   if (resp?.hasError()) {
      log.warn "Error in Bridge response. HTTP ${resp.status}."
      isOK = false
   }
   else if (resp?.json == null) {
      isOK = false
      if (resp?.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge"
      else log.error "No JSON data found in response. (HTTP ${resp.status}; headers = ${resp.headers})"
      parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
   }
   else if (resp.status < 400 && resp.json) {
      if (resp.json[0]?.error) {
         // Bridge (not HTTP) error (bad username, bad command formatting, etc.):
         isOK = false
         log.warn "Error from Hue Bridge: ${resp.json[0].error}"
      }
      // Otherwise: probably OK (not changing anything because isOK = true already)
   }
   else {
      isOK = false
      log.warn("HTTP status code ${resp.status} from Bridge")
      if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
   }
   if (device.currentValue("status") != (isOK ? "Online" : "Offline")) doSendEvent("status", (isOK ? "Online" : "Offline"))
   logDebug("Reponse ${isOK ? 'valid' : 'invalid'}")
   return isOK
}


// ------------ BULBS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllBulbs() {
   logDebug("Getting bulb list from Bridge...")
   //clearBulbsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllBulbsResponse", params)
}

private void parseGetAllBulbsResponse(resp, data) {
   logDebug("Parsing in parseGetAllBulbsResponse") 
   if (checkIfValidResponse(resp)) {
      try {
         Map bulbs = [:]
         resp.json.each { key, val ->
            bulbs[key] = [name: val.name, type: val.type]
         }
         state.allBulbs = bulbs
         logDebug("  All bulbs received from Bridge: $bulbs")
      }
      catch (Exception ex) {
         log.error "Error parsing all bulbs response: $ex"
      }
   }
}


/** Intended to be called from parent Bridge Child app to retrive previously
 *  requested list of bulbs
 */
Map getAllBulbsCache() {
   return state.allBulbs 
}

/** Clears cache of bulb IDs/names/types; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearBulbsCache() {
   logDebug("Running clearBulbsCache...")
   state.remove('allBulbs')
}

/** Callback method that handles updating attributes on child light
 *  devices when Bridge refreshed
 */
private void parseLightStates(resp, data) { 
   logDebug("Parsing light states from Bridge...")
   if (checkIfValidResponse(resp)) {
      try {
         resp.json.each { id, val ->
            def device = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
            if (device) {
               device.createEventsFromMap(val.state, true)
            }
         }
         if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
      } catch (Exception ex) {
         log.error "Error parsing light states: ${ex}"           
      }
   }
}

// ------------ GROUPS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllGroups() {
   logDebug("Getting group list from Bridge...")
   //clearGroupsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllGroupsResponse", params)
}

private void parseGetAllGroupsResponse(resp, data) {
   logDebug("Parsing in parseGetAllGroupsResponse")
   if (checkIfValidResponse(resp)) {
      try {
         Map groups = [:]
         resp.json.each { key, val ->
            groups[key] = [name: val.name, type: val.type]
         }
         groups[0] = [name: "All Hue Lights", type:  "LightGroup"] // add "all Hue lights" group, ID 0
         state.allGroups = groups
         logDebug("  All groups received from Bridge: $groups")
      }
      catch (Exception ex) {
         log.error "Error parsing all groups response: $ex"
      }
   }
}

/** Intended to be called from parent Bridge Child app to retrive previously
 *  requested list of groups
 */
Map getAllGroupsCache() {
   return state.allGroups
}

/** Clears cache of group IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearGroupsCache() {
    logDebug("Running clearGroupsCache...")
    state.remove('allGroups')
}

/** Callback method that handles updating attributes on child group
 *  devices when Bridge refreshed
 */
private void parseGroupStates(resp, data) {
   logDebug("Parsing group states from Bridge...")    
   if (checkIfValidResponse(resp)) {
      try {
         resp.json.each { id, val ->
               com.hubitat.app.DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
               if (dev) {
                  dev.createEventsFromMap(val.action, true)
                  dev.createEventsFromMap(val.state, true)
                  dev.setMemberBulbIDs(val.lights)
               }
         }
         Boolean anyOn = resp.json.any { it.value?.state?.any_on == false }
         com.hubitat.app.DeviceWrapper allLightsDev = parent.getChildDevice("${device.deviceNetworkId}/Group/0")
         if (allLightsDev) {
                  allLightsDev.createEventsFromMap(['any_on': anyOn], true)
         }
         
      }
      catch (Exception ex) {
         log.error "Error parsing group states: ${ex}"   
      }
   }
}

// ------------ SCENES ------------

/** Requests list of all scenes from Hue Bridge; updates
 *  allScenes in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllScenes() {
   logDebug("Getting scene list from Bridge...")
   getAllGroups() // so can get room names, etc.
   //clearScenesCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/scenes",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllScenesResponse", params)
}

private void parseGetAllScenesResponse(resp, data) {
   logDebug("Parsing all scenes response...")    
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.each { key, val ->
            scenes[key] = ["name": val.name]
            if (val.group) scenes[key] << ["group": val.group]
         }
         state.allScenes = scenes
         logDebug("  All scenes received from Bridge: $scenes")
      }
      catch (Exception ex) {
         log.error "Error parsing all scenes response: ${ex}"   
      }
   }
}


/** Intended to be called from parent Bridge Child app to retrive previously
 *  requested list of scenes
 */
Map getAllScenesCache() {
   return state.allScenes
}

/** Clears cache of scene IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearScenesCache() {
   logDebug("Running clearScenesCache...")
   state.remove('allScenes')
}

private void doSendEvent(String eventName, eventValue) {
   //logDebug("Creating event for $eventName...")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   logDesc(descriptionText)
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)
}

void logDebug(str) {
   if (settings.enableDebug) log.debug(str)
}

void logDesc(str) {
   if (settings.enableDesc) log.info(str)
}
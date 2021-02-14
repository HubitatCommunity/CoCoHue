/**
 * =============================  CoCoHue Bridge (Driver) ===============================
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
 *  Last modified: 2021-02-13
 *
 *  Changelog:
 *  v3.0    - Added support for sensors (Hue Motion sensors with motion/temp/lux) and Hue Labs effects (looks for resoucelinks with 1 sensor link)
 *          - Revamped refresh/sync to fetch all Bridge data instead of indiviudal /lights, /groups, etc. APIs (goal: reduce to one HTTP call and response total)
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

void installed() {
   log.debug "Installed..."
   initialize()
}

void updated() {
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
   logDebug("refresh()")
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/",
      contentType: 'application/json',
      timeout: 15
   ]
   try {
      asynchttpGet("parseStates", params)
   } catch (Exception ex) {
      log.error "Error in refresh: $ex"
   }
}

/** Callback method that handles full Bridge refresh. Eventually delegated to individual
 *  methods below.
 */
private void parseStates(resp, data) { 
   logDebug("parseStates: States from Bridge received. Now parsing...")
   if (checkIfValidResponse(resp)) {
      parseLightStates(resp.json.lights)
      parseGroupStates(resp.json.groups)
      parseSensorStates(resp.json.sensors)
      parseLabsSensorStates(resp.json.sensors)
   }
}

private void parseLightStates(Map lightsJson) { 
   logDebug("Parsing light states from Bridge...")
   try {
      lightsJson.each { id, val ->
         com.hubitat.app.DeviceWrapper device = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
         if (device) {
            device.createEventsFromMap(val.state, true)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing light states: ${ex}"
   }
}

private void parseGroupStates(Map groupsJson) {
   logDebug("Parsing group states from Bridge...")
   try {
      groupsJson.each { id, val ->
         com.hubitat.app.DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev) {
            dev.createEventsFromMap(val.action, true)
            dev.createEventsFromMap(val.state, true)
            dev.setMemberBulbIDs(val.lights)
         }
      }
      Boolean anyOn = groupsJson.any { it.value?.state?.any_on == false }
      com.hubitat.app.DeviceWrapper allLightsDev = parent.getChildDevice("${device.deviceNetworkId}/Group/0")
      if (allLightsDev) {
         allLightsDev.createEventsFromMap(['any_on': anyOn], true)
      }
      
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"   
   }
}

private void parseSensorStates(Map sensorsJson) {
   logDebug("Parsing sensor states from Bridge...")
   try {
      Map allSensors = [:]
      sensorsJson.each { key, val ->
         if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature") {
            String mac = val?.uniqueid?.substring(0,23)
            if (mac != null) {
               com.hubitat.app.DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${mac}")
               if (dev != null) {
                  dev.createEventsFromMap(val.state)
                  // All entries have config.battery, so just picking one to parse here to avoid redundancy:
                  if (val.type == "ZLLPresence") dev.createEventsFromMap(["battery": val.config.battery])
               }
            }
         }
      }
   }
   catch (Exception ex) {
      log.error "Error parsing sensor states: ${ex}"   
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
      if (resp?.headers == null) log.error "Error: HTTP ${resp?.status} when attempting to communicate with Bridge"
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

/** Intended to be called from parent app to retrive previously
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

/** Intended to be called from parent app to retrive previously
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

/** Intended to be called from parent app to retrive previously
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

// ------------ SENSORS (Motion/etc.) ------------

/** Requests list of all sensors from Hue Bridge; updates
 *  allSensors in state when finished. (Filters down to only Hue
 *  Motion sensors.) Intended to be called during sensor discovery in app.
 */
void getAllSensors() {
   logDebug("Getting sensor list from Bridge...")
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/sensors",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllSensorsResponse", params)
}

private void parseGetAllSensorsResponse(resp, data) {
   logDebug("Parsing all sensors response...")    
   if (checkIfValidResponse(resp)) {
      try {
         Map allSensors = [:]
         resp.json.each { key, val ->
            if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature") {
               String mac = val?.uniqueid?.substring(0,23)
               if (mac != null) {
                  if (!(allSensors[mac])) allSensors[mac] = [:]
                  if (allSensors[mac]?.ids) allSensors[mac].ids.add(key)
                  else allSensors[mac].ids = [key]
               }
               if (allSensors[mac].name) {
                  // The ZLLPresence endpoint appears to be the one carrying the user-defined name
                  if (val.type == "ZLLPresence") allSensors[mac].name = val.name
               }
               else {
                  //...but get the other names if none has been set, just in case
                  allSensors[mac].name = val.name
               }
            }
         }
         Map hueMotionSensors = [:]
         allSensors.each { key, value ->
            // Hue  Motion sensors should have all three types, so just further filtering:
            if (value.ids?.size >= 3) hueMotionSensors << [(key): value]
         }
         state.allSensors = hueMotionSensors
         logDebug("  All sensors received from Bridge: $hueMotionSensors")
      }
      catch (Exception ex) {
         log.error "Error parsing all sensors response: ${ex}"   
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of sensors
 */
Map getAllSensorsCache() {
   return state.allSensors
}

/** Clears cache of sensor IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearSensorsCache() {
   logDebug("Running clearSensorsCache...")
   state.remove('allSensors')
}

// ------------ HUE LABS SENSORS ------------

/** Requests list of all Hue Bridge state; callback will parse resourcelinks and sensors
 */
void getAllLabsDevices() {
   logDebug("Getting resourcelink list from Bridge...")
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllLabsDevicesResponse", params)
}

private void parseGetAllLabsDevicesResponse(resp, data) {
   logDebug("Parsing all Labs devices response...")    
   if (checkIfValidResponse(resp)) {
      try {
         Map names = [:]
         Map activatorSensors = resp.json.sensors.findAll { key, val ->
            val["type"]  == "CLIPGenericStatus" && val["modelid"] == "HUELABSVTOGGLE"
         }
         activatorSensors.each { key, val ->
            resp.json.resourcelinks.each { rlId, rlVal ->
               if (rlVal.links?.any { it == "/sensors/${key}" }) {
                  names[(key)] = rlVal.name
               } 
            }
            //val["name"] = resp.json.resourcelinks.find { rlid, rlval -> rlval.links.find { idx, dev -> dev == "/sensors/${key}"} }
         }
         names.each { id, name ->
            activatorSensors[id].name = name
         }
         state.labsSensors = activatorSensors
      }
      catch (Exception ex) {
         log.error "Error parsing all Labs sensors response: ${ex}"   
      }
      logDebug("  All Labs sensors received from Bridge: $activatorSensors")
   }
}

/** Callback method that handles updating attributes on child sensor
 *  devices when Bridge refreshed
 */
private void parseLabsSensorStates(sensorJson) {
   logDebug("Parsing Labs sensor states...")
   //logDebug("states: $sensorJson")
   try {
      sensorJson.each { id, val ->
         com.hubitat.app.DeviceWrapper device = parent.getChildDevice("${device.deviceNetworkId}/SensorRL/${id}")
         if (device) {
            device.createEventsFromMap(val.state)
         }
      }
   }
   catch (Exception ex) {
      log.error "Error parsing Labs sensor states: ${ex}"   
   }
}


/** Intended to be called from parent app to retrive previously
 *  requested list of Labs actiavtor devices
 */
Map getAllLabsSensorsCache() {
   return state.labsSensors 
}

/** Clears cache of Labs activator devices; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearLabsSensorsCache() {
   logDebug("Running clearLabsSensorsCache...")
   state.remove("labsSensors")
}

private void doSendEvent(String eventName, eventValue) {
   //logDebug("doSendEvent($eventName, $eventValue, $eventUnit)")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   logDesc(descriptionText)
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)
}

void logDebug(str) {
   if (settings.enableDebug == true) log.debug(str)
}

void logDesc(str) {
   if (settings.enableDesc == true) log.info(str)
}
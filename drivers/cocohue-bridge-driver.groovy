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
 *  Last modified: 2021-05-23
 * 
 *  Changelog:
 *  v3.5    - Minor code cleanup
 *  v3.1    - Improved error handling and debug logging
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
      attribute "status", "STRING"
   }
   
   preferences() {
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Ignoring parse() for: '${description}'")
}

void refresh() {
   if (enableDebug) log.debug "refresh()"
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
   if (enableDebug) log.debug "parseStates: States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      parseLightStates(resp.json.lights)
      parseGroupStates(resp.json.groups)
      parseSensorStates(resp.json.sensors)
      parseLabsSensorStates(resp.json.sensors)
   }
}

private void parseLightStates(Map lightsJson) { 
   if (enableDebug) log.debug "Parsing light states from Bridge..."
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "lightsJson = $lightsJson"
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
   if (enableDebug) log.debug "Parsing group states from Bridge..."
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "groupsJson = $groupsJson"
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
   if (enableDebug) log.debug "Parsing sensor states from Bridge..."
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "sensorsJson = $sensorsJson"
   try {
      sensorsJson.each { key, val ->
         if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature" ||
             val.type == "ZHAPresence" || val.type == "ZHALightLevel" || val.type == "ZHATemperature") {
            String mac = val?.uniqueid?.substring(0,23)
            if (mac != null) {
               com.hubitat.app.DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${mac}")
               if (dev != null) {
                  dev.createEventsFromMap(val.state)
                  // All entries have config.battery, so just picking one to parse here to avoid redundancy:
                  if (val.type == "ZLLPresence" || val.type == "ZHAPresence") dev.createEventsFromMap(["battery": val.config.battery])
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
      if (isOK) parent.setBridgeStatus(true)
   }
   else {
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}"
      isOK = false
   }
   return isOK
}


// ------------ BULBS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllBulbs() {
   if (enableDebug) log.debug "Getting bulb list from Bridge..."
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
   if (enableDebug) log.debug "Parsing in parseGetAllBulbsResponse"
   if (checkIfValidResponse(resp)) {
      try {
         Map bulbs = [:]
         resp.json.each { key, val ->
            bulbs[key] = [name: val.name, type: val.type]
         }
         state.allBulbs = bulbs
         if (enableDebug) log.debug "  All bulbs received from Bridge: $bulbs"
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
   if (enableDebug) log.debug "Running clearBulbsCache..."
   state.remove('allBulbs')
}

// ------------ GROUPS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllGroups() {
   if (enableDebug) log.debug "Getting group list from Bridge..."
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
   if (enableDebug) log.debug "Parsing in parseGetAllGroupsResponse"
   if (checkIfValidResponse(resp)) {
      try {
         Map groups = [:]
         resp.json.each { key, val ->
            groups[key] = [name: val.name, type: val.type]
         }
         groups[0] = [name: "All Hue Lights", type:  "LightGroup"] // add "all Hue lights" group, ID 0
         state.allGroups = groups
         if (enableDebug) log.debug "  All groups received from Bridge: $groups"
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
    if (enableDebug) log.debug "Running clearGroupsCache..."
    state.remove('allGroups')
}

// ------------ SCENES ------------

/** Requests list of all scenes from Hue Bridge; updates
 *  allScenes in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllScenes() {
   if (enableDebug) log.debug "Getting scene list from Bridge..."
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
   if (enableDebug) log.debug "Parsing all scenes response..."
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.each { key, val ->
            scenes[key] = ["name": val.name]
            if (val.group) scenes[key] << ["group": val.group]
         }
         state.allScenes = scenes
         if (enableDebug) log.debug "  All scenes received from Bridge: $scenes"
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
   if (enableDebug) log.debug "Running clearScenesCache..."
   state.remove('allScenes')
}

// ------------ SENSORS (Motion/etc.) ------------

/** Requests list of all sensors from Hue Bridge; updates
 *  allSensors in state when finished. (Filters down to only Hue
 *  Motion sensors.) Intended to be called during sensor discovery in app.
 */
void getAllSensors() {
   if (enableDebug) log.debug "Getting sensor list from Bridge..."
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
   if (enableDebug) log.debug "Parsing all sensors response..."
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
         if (enableDebug) log.debug "  All sensors received from Bridge: $hueMotionSensors"
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
   if (enableDebug) log.debug "Running clearSensorsCache..."
   state.remove('allSensors')
}

// ------------ HUE LABS SENSORS ------------

/** Requests list of all Hue Bridge state; callback will parse resourcelinks and sensors
 */
void getAllLabsDevices() {
   if (enableDebug) log.debug "Getting resourcelink list from Bridge..."
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
   if (enableDebug) log.debug "Parsing all Labs devices response..."
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
      if (enableDebug) log.debug "  All Labs sensors received from Bridge: $activatorSensors"
   }
}

/** Callback method that handles updating attributes on child sensor
 *  devices when Bridge refreshed
 */
private void parseLabsSensorStates(sensorJson) {
   if (enableDebug) log.debug "Parsing Labs sensor states..."
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "sensorJson = $sensorJson"
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
   if (enableDebug) log.debug "Running clearLabsSensorsCache..."
   state.remove("labsSensors")
}

private void doSendEvent(String eventName, eventValue) {
   //if (enableDebug) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   if (settings.enableDesc == true) log.info(descriptionText)
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)
}
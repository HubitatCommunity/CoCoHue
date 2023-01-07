/**
 * =============================  CoCoHue Bridge (Driver) ===============================
 *
 *  Copyright 2019-2023 Robert Morris
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
 *  Last modified: 2023-01-06
 * 
 *  Changelog:
 *  v4.1.3  - Improved eventstream data handling (when multiple devices included in same payload, thanks to @Modem-Tones)
 *  v4.1.2  - Additional button enhancements (relative_rotary -- Hue Tap Dial, etc.)
 *  v4.1    - Add button device support (with v2 API only)
 *  v4.0.2  - Fix to avoid unepected "off" transition time
 *  v4.0.1  - Fix for "on" state of "All Hue Lights" group (if used)
 *  v4.0.1  - Minor sensor cache updates
 *  v4.0    - EventStream support for real-time updates
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Added support for sensors (Hue Motion sensors with motion/temp/lux) and Hue Labs effects (looks for resoucelinks with 1 sensor link)
 *          - Revamped refresh/sync to fetch all Bridge data instead of indiviudal /lights, /groups, etc. APIs (goal: reduce to one HTTP call and response total)
 *  v2.1    - Minor code cleanup and more static typing
 *  v2.0    - Added Actuator capability; Bridge and HTTP error handling improvements; added specific HTTP timeout
 *  v1.5    - Additional methods to support scenes and improved group behavior
 *  v1.0    - Initial Release
 */ 



import groovy.json.JsonSlurper
import hubitat.scheduling.AsyncResponse
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

// Number of seconds to wait after Bridge EventStream (SSE) is disconnected before consider it so on Hubitat
// Seems to be helpful at the moment because get spurious disconnects when SSE is working fine, shortly followed
// by a reconnect (~6 sec for me, so 7 should cover most)
@Field static final Integer eventStreamDisconnectGracePeriod = 8

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Bridge", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Initialize"
      command "connectEventStream"
      command "disconnectEventStream"
      attribute "status", "STRING"
      attribute "eventStreamStatus", "STRING"
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
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
   if (parent.getEventStremEnabledSetting()) connectEventStream()
}

void connectEventStream() {
   if (enableDebug) log.debug "connectEventStream()"
   if (parent.getEventStremEnabledSetting() != true) {
      log.warn "CoCoHue app is configured not to use EventStream. To reliably use this interface, it is recommended to enable this option in the app."
   }
   Map<String,String> data = parent.getBridgeData()
   if (enableDebug) {
      log.debug "Connecting to event stream at 'https://${data.ip}/eventstream/clip/v2' with key '${data.username}'"
   }
   interfaces.eventStream.connect(
      "https://${data.ip}/eventstream/clip/v2", [
      headers: ["Accept": "text/event-stream", "hue-application-key": data.username],
      rawData: true,
      pingInterval: 10,
      readTimeout: 3600,
      ignoreSSLIssues: true
   ])
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
   if (enableDebug) log.debug "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)"
   if (device.currentValue("eventStreamStatus") == "connected" && notIfAlreadyConnected) {
      if (enableDebug) log.debug "already connected; skipping reconnection"
   }   
   else if (parent.getEventStremEnabledSetting() != true) {
      if (enableDebug) log.debug "skipping reconnection because (parent) app configured not to use EventStream"
   }
   else {
      connectEventStream()
   }
}

void disconnectEventStream() {
   interfaces.eventStream.close()
}

void eventStreamStatus(String message) {
   if (enableDebug) log.debug "eventStreamStatus: $message"
   if (message.startsWith("START:")) {
      setEventStreamStatusToConnected()
   }
   else {
      runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
   }
}

private void setEventStreamStatusToConnected() {
   parent.setEventStreamOpenStatus(true) // notify app
   unschedule("setEventStreamStatusToDisconnected")
   if (device.currentValue("eventStreamStatus") == "disconnected") doSendEvent("eventStreamStatus", "connected")
   state.connectionRetryTime = 3
}

private void setEventStreamStatusToDisconnected() {
   parent.setEventStreamOpenStatus(false) // notify app
   doSendEvent("eventStreamStatus", "disconnected")
   if (state.connectionRetryTime) {
      state.connectionRetryTime *= 2
      if (state.connectionRetryTime > 900) {
         state.connectionRetryTime = 900 // cap retry time at 15 minutes
      }
   }
   else {
      state.connectionRetryTime = 5
   }
   if (enableDebug) log.debug "reconnecting SSE in ${state.connectionRetryTime}"
   runIn(state.connectionRetryTime, "reconnectEventStream")
}

// For EventStream:
void parse(String description) {
   if (enableDebug) log.debug "parse: $description"
   List<String> messages = description.split("\n\n")
   setEventStreamStatusToConnected() // should help avoid spurious disconnect messages?
   messages.each { String message -> 
      List<String> lines = description.split("\n")
      StringBuilder sbData = new StringBuilder()
      lines.each { String line ->
         if (line.startsWith("data: ")) {
            sbData << line.substring(6)
         }
         else {
            if (enableDebug) log.debug "ignoring line: $line"
         }
      }
      if (sbData) {
         List dataList = new JsonSlurper().parseText(sbData.toString())
         dataList.each { dataEntryMap ->
            //log.trace "--> DATA = ${dataEntryMap}"
            if (dataEntryMap.type == "update") {
               dataEntryMap.data?.each { updateEntryMap ->
                  //log.trace "--> map = ${updateEntryMap}"
                  String fullId = updateEntryMap.id_v1
                  if (fullId != null) {
                     switch (fullId) {
                        case { it.startsWith("/lights/") }:
                           String hueId = fullId.split("/")[-1]
                           DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${hueId}")
                           if (dev != null) dev.createEventsFromSSE(updateEntryMap)
                           break
                        case { it.startsWith("/groups/") }:
                           String hueId = fullId.split("/")[-1]
                           DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${hueId}")
                           if (dev != null) dev.createEventsFromSSE(updateEntryMap)
                           break
                        case { it.startsWith("/sensors/") }:
                           String hueId = fullId.split("/")[-1]
                           DeviceWrapper dev = parent.getChildDevices().find { DeviceWrapper dev ->
                              hueId in dev.deviceNetworkId.tokenize('/')[-1].tokenize('|') &&
                              dev.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/")  // shouldn't be necessary but gave me a Light ID once in testing for a sensor, so?!
                           }
                           if (dev != null) {
                              dev.createEventsFromSSE(updateEntryMap)
                           }
                           else {
                              // try button; should eventually switch to v2 for all of this...
                              if (updateEntryMap.owner?.rid) dev = parent.getChildDevice("${device.deviceNetworkId}/Button/${updateEntryMap.owner.rid}")
                              if (dev != null) {
                                 dev.createEventsFromSSE(updateEntryMap)
                              }
                           }
                           break
                        default:
                           if (enableDebug) log.debug "skipping Hue v1 ID: $hueId"
                     }
                  }
               }
            }
            else {
               if (enableDebug) log.debug "skip: $dataEntryMap"
            }
         }
      }
      else {
         if (enableDebug) log.trace "no data parsed from message: $message"
      }
   }
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

void scheduleRefresh() {
   if (enableDebug) log.debug "scheduleRefresh()"
   
}

// For HTTP API-based parsing/refreshes:

/** Callback method that handles full Bridge refresh. Eventually delegated to individual
 *  methods below.
 */
private void parseStates(AsyncResponse resp, Map data) { 
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
         DeviceWrapper device = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
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
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev) {
            dev.createEventsFromMap(val.action, true)
            dev.createEventsFromMap(val.state, true)
            dev.setMemberBulbIDs(val.lights)
         }
      }
      Boolean anyOn = groupsJson.any { it.value?.state?.any_on == true }
      DeviceWrapper allLightsDev = parent.getChildDevice("${device.deviceNetworkId}/Group/0")
      if (allLightsDev != null) {
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
   try {
      sensorsJson.each { key, val ->
         if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature" ||
          val.type == "ZHAPresence" || val.type == "ZHALightLevel" || val.type == "ZHATemperature") {
            DeviceWrapper sensorDev = parent.getChildDevices.findAll { DeviceWrapper it ->
               it.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/") &&
               (key as String) in it.deviceNetworkId.tokenize('/')[3].tokenize('|')
            }[0]
            if (sensorDev != null) {
               sensorDev.createEventsFromMap(val.state)
               // All entries have config.battery, so just picking one to parse here to avoid redundancy:
               if (val.type == "ZLLPresence" || val.type == "ZHAPresence") sensorDev.createEventsFromMap(["battery": val.config.battery])
            }
         }
      }
   }
   catch (Exception ex) {
      log.error "Error parsing sensor states: ${ex}"
   }
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
         Map<String,Map> hueMotionSensors = [:]
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
Map<String,Map> getAllSensorsCache() {
   return state.allSensors
}

/** Clears cache of sensor IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearSensorsCache() {
   if (enableDebug) log.debug "Running clearSensorsCache..."
   state.remove('allSensors')
}

// ------------ BUTTONS ------------

/** Requests list of all button devices from Hue Bridge; updates
 *  allButtons in state when finished. Intended to be called
 *  during buttoon discovery in app.
 */
void getAllButtons() {
   if (enableDebug) log.debug "Getting button list from Bridge..."
   //clearButtonsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/device",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet("parseGetAllButtonsResponse", params)
}

private void parseGetAllButtonsResponse(resp, data) {
   if (enableDebug) log.debug "Parsing in parseGetAllButtonsResponse"
   if (checkIfValidResponse(resp)) {
      try {
         Map buttons = [:]
         // Get specific /button devices first....
         Map<String,String> bridgeData = parent.getBridgeData()
         // TODO: Consider making this async, but should be pretty safe considerng we just heard from Bridge...
         Map params = [
            uri: "https://${bridgeData.ip}",
            path: "/clip/v2/resource/button",
            headers: ["hue-application-key": bridgeData.username],
            contentType: "application/json",
            timeout: 10,
            ignoreSSLIssues: true
         ]
         httpGet(params,
            { response ->
                  response.data.data.each {
                     if (buttons[it.owner.rid] == null) buttons[it.owner.rid] = [buttons: [:]]
                     buttons[it.owner.rid].buttons << [(it.id): it.metadata.control_id]
                     
                  }
            }
         )
         // Check for relative_rotary, too (Hue Tap Dial, Lutron Aurora)
         params = [
            uri: "https://${bridgeData.ip}",
            path: "/clip/v2/resource/relative_rotary",
            headers: ["hue-application-key": bridgeData.username],
            contentType: "application/json",
            timeout: 10,
            ignoreSSLIssues: true
         ]
         httpGet(params,
            { response ->
                  response.data.data.each {
                     if (buttons[it.owner.rid] != null) {
                        if (buttons[it.owner.rid].relative_rotary == null) {
                           buttons[it.owner.rid] << [relative_rotary: []]
                        }
                        buttons[it.owner.rid].relative_rotary << it.id
                     }
                     else {
                        // probably won't happen, but skip if no associated button
                     }
                  }
            }
         )
         // But also have to get name from /devices data...
         if (resp?.json?.data) {
            List devicesJson = resp.json.data
            buttons.keySet().each { String id ->
               Map dev = devicesJson.find { dev -> dev.id == id }
               buttons[id].name = dev.metadata.name
               buttons[id].manufacturer_name = dev.product_data.manufacturer_name
               buttons[id].model_id = dev.product_data.model_id
            }
         }
         else {
            log.warn "No data in returned JSON: $data"
         }
         state.allButtons = buttons
         state.allRelativeRotaries = relativeRotaries
         if (enableDebug) log.debug "  All buttons received from Bridge: $buttons"
      }
      catch (Exception ex) {
         log.error "Error parsing all buttons response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of bulbs
 */
Map getAllButtonsCache() {
   return state.allButtons 
}

/** Clears cache of bulb IDs/names/types; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearButtonsCache() {
   if (enableDebug) log.debug "Running clearButtonsCache..."
   state.remove('allButtons')
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
         DeviceWrapper device = parent.getChildDevice("${device.deviceNetworkId}/SensorRL/${id}")
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
// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.1 // library marker RMoRobert.CoCoHue_Common_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 7
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 9
) // library marker RMoRobert.CoCoHue_Common_Lib, line 10

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 12
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 13
   device.updateSetting("enableDebug", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 14
} // library marker RMoRobert.CoCoHue_Common_Lib, line 15

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 17
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 18
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 19
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 20
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 21
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 22
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 23
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 24
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 25
      if (resp?.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 26
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 27
         if (resp?.headers == null) log.error "Error: HTTP ${resp?.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 28
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 29
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 30
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 31
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 32
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 33
         if (resp.json[0]?.error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 34
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 35
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 36
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 37
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 38
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 39
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 40
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 41
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 42
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 43
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 44
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 45
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 46
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 47
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 48
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 49
      if (isOK == true) parent.setBridgeStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 50
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 51
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 52
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 53
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 54
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 55
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 56
} // library marker RMoRobert.CoCoHue_Common_Lib, line 57

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 59
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 60
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 61
   if (settings.enableDesc == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 62
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 63
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 64
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 65
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 66
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 67
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 68
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 69
} // library marker RMoRobert.CoCoHue_Common_Lib, line 70

// ~~~~~ end include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~

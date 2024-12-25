/**
 * =============================  CoCoHue Bridge (Driver) ===============================
 *
 *  Copyright 2019-2024 Robert Morris
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
 *  Last modified: 2024-12-08
 *
 *  Changelog:
 *  v5.2.2  - Minor typo fix for SSE reconnection debug log
 *  v5.2.1  - Keep cache of /resources data in V2 API to reduce HTTP calls and facilite service and owner ID matching
 *  v5.2.0  - (possible) fix for battery events on sensors
 *  v5.0.3  - Add zigbee_connectivity parsing for lights
 *  v5.0.2  - Fetch V2 grouped_light ID owner for room/zone owners of V2 scenes
 *  v5.0.1  - Fix for missing V1 IDs after device creation or upgrade
 *  v5.0    - Use API v2 by default for device info, remove deprecated features
 *  v4.2.1  - Add scene on/off state reporting with v2 API
 *  v4.2    - Improved eventstream reconnection logic
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.1.3  - Improved eventstream data handling (when multiple devices included in same payload, thanks to @Modem-Tones)
 *  v4.1.2  - Additional button enhancements (relative_rotary -- Hue Tap Dial, etc.)
 *  v4.1    - Add button device support (with v2 API only)
 *  v4.0.2  - Fix to avoid unexpected "off" transition time
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
// For readTimeout value in eventstream connection:
@Field static final Integer eventStreamReadTimeout = 3600

@Field static final Integer debugAutoDisableMinutes = 30

@Field static final Object eventStreamStatusLock = new Object()
@Field static Map<Long,Boolean> eventStreamOpenStatus = [:]  // key = bridge device idAsLong, value = true if connected

@Field static final Object bridgeCacheLock = new Object()
@Field static Map<Long, Map> bridgeCache = [:] // key = bridge device idAsLong, value = `/resources` JSON/map from V2 API
@Field static final Integer CACHE_STALE_THRESHOLD = 8_000  // 8 seconds; default time before which will not be fetched again if try; passing does not *cause* fetch

// TODO: Make bulb, sensor, etc. caches similar fields instead of state? (But they are cleared when main app page is loaded, so impact should normally be low...)

metadata {
   definition(
      name: "CoCoHue Bridge",
      namespace:"RMoRobert",
      author: "Robert Morris",
      singleThreaded: true,
      importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy"
   ) {
      capability "Actuator"
      capability "Refresh"
      capability "Initialize"
      
      command "connectEventStream"
      command "disconnectEventStream"
      command "refreshV1" // can be used to force V1 API refresh even if using V2 otherwise

      attribute "status", "STRING"
      attribute "eventStreamStatus", "STRING"
   }
   
   preferences() {
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
   disconnectEventStream()
   doSendEvent("eventStreamStatus", "disconnected")
   if (parent.getEventStreamEnabledSetting()) runIn(3, "connectEventStream")

}

void connectEventStream() {
   if (logEnable) log.debug "connectEventStream()"
   if (parent.getEventStreamEnabledSetting() != true) {
      log.warn "Parent app is configured not to use EventStream. To reliably use this interface, enable this option in the app."
   }
   Map<String,String> data = parent.getBridgeData()
   if (logEnable) {
      log.debug "Connecting to event stream at 'https://${data.ip}/eventstream/clip/v2' with Hue API key '${data.username}'"
   }
   interfaces.eventStream.close()
   interfaces.eventStream.connect(
      "https://${data.ip}/eventstream/clip/v2", [
      headers: ["Accept": "text/event-stream", "hue-application-key": data.username],
      rawData: true,
      pingInterval: 10,
      readTimeout: eventStreamReadTimeout,
      ignoreSSLIssues: true
   ])
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
   if (logEnable) log.debug "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)"
   if (device.currentValue("eventStreamStatus") == "connected" && notIfAlreadyConnected) {
      if (logEnable) log.debug "already connected; skipping reconnection"
   }   
   else if (parent.getEventStreamEnabledSetting() != true) {
      if (logEnable) log.debug "skipping reconnection because (parent) app configured not to use EventStream"
   }
   else {
      connectEventStream()
   }
}

void disconnectEventStream() {
   interfaces.eventStream.close()
}

void eventStreamStatus(String message) {
   if (logEnable) log.debug "eventStreamStatus: $message"
   if (message.startsWith("START:")) {
      setEventStreamStatusToConnected()
   }
   else if (message.startsWith("STOP:")) {
      runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
   }
   else {
      if (logEnable) log.debug "Unhandled eventStreamStatus message: $message"
   }
}

void setEventStreamStatusToConnected() {
   synchronized(eventStreamStatusLock) {
      if (logEnable) "set eventStreamOpenStatus[${device.idAsLong}] to true (connected)"
      eventStreamOpenStatus[device.idAsLong] = true
   }
   unschedule("setEventStreamStatusToDisconnected")
   if (device.currentValue("eventStreamStatus") != "connected") doSendEvent("eventStreamStatus", "connected")
   state.connectionRetryTime = 4
}

void setEventStreamStatusToDisconnected() {
   synchronized(eventStreamStatusLock) {
      if (logEnable) "set eventStreamOpenStatus[${device.idAsLong}] to false (disconnected)"
      eventStreamOpenStatus[device.idAsLong] = false
   }
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
   if (logEnable) log.debug "Will attempt eventstream reconnection in ${state.connectionRetryTime} seconds"
   runIn(state.connectionRetryTime, "reconnectEventStream")
}

/** Returns true if evenstream currently connected, false if not (or unknown), intended to be used by parent
  * app if needed
*/
Boolean getEventStreamOpenStatus() {
   if (logEnable) "getEventStreamOpenStatus()"
   synchronized(eventStreamStatusLock) {
      return eventStreamOpenStatus[device.idAsLong] == true
   }
}

// For Eventstream:
void parse(String description) {
   if (logEnable) log.debug "parse: $description"
   List<String> messages = description.split("\n\n")
   setEventStreamStatusToConnected() // should help avoid spurious disconnect messages?
   if (logEnable) log.debug "messages (${messages.size()} total): $messages"
   messages.each { String message -> 
      List<String> lines = description.split("\n")
      StringBuilder sbData = new StringBuilder()
      lines.each { String line ->
         if (line.startsWith("data: ")) {
            sbData << line.substring(6)
         }
         else {
            if (logEnable) log.debug "ignoring line: $line"
         }
      }
      if (sbData) {
         List dataList = new JsonSlurper().parseText(sbData.toString())
         dataList.each { dataEntryMap ->
            //log.trace "--> DATA = ${dataEntryMap}"
            if (dataEntryMap.type == "update") {
               dataEntryMap.data?.each { updateEntryMap ->
                  //log.trace "--> map = ${updateEntryMap}"
                  String idV1
                  if (updateEntryMap.id_v1 != null) idV1 = updateEntryMap.id_v1.split("/").last()
                  String idV2 = updateEntryMap.id
                  String idV1Num
                  DeviceWrapper dev
                  if (idV2 != null) {
                     switch (updateEntryMap.type) {
                        case "light":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${idV2}")
                           if (dev == null) dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${idV1}")
                           break
                        case "grouped_light":  // does this one actually happpen?
                        case "room":
                        case "zone":
                        case "bridge_home":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${idV2}")
                           if (dev == null)  dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${idV1}")
                           break
                        case "scene":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${idV2}")
                           if (dev == null)  dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${idV1}")
                           break
                        case "motion":
                        case "contact":
                        case "temperature":
                        case "light_level": //todo: test or check is correct?
                           String ownerId = updateEntryMap.owner?.rid
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${ownerId}") // motion
                           if (dev == null) dev = parent.getChildDevice("${device.deviceNetworkId}/Contact/${ownerId}") // contact
                           if (dev == null && idV1 != null) {
                              // or for now also check V1 motion sensor ID
                              dev = parent.getChildDevices().find { DeviceWrapper d ->
                                 idV1 in d.deviceNetworkId.tokenize('/').last().tokenize('|') &&
                                 d.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/")  // shouldn't be necessary but gave me a Light ID once in testing for a sensor, so?!
                              }
                           }
                           break
                        case "button":
                        case "relative_rotary":
                           String ownerId = updateEntryMap.owner?.rid
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Button/${ownerId}")
                           break
                        case "device_power": // could be motion sensor or button
                           String ownerId = updateEntryMap.owner?.rid
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${ownerId}")
                           if (dev == null) parent.getChildDevice("${device.deviceNetworkId}/Button/${ownerId}")
                           break
                        case "zigbee_connectivity":
                           String ownerId = updateEntryMap.owner?.rid // find owner for zigbee_connectivity service, then...
                           String lightId = getBridgeCacheV2().find { Map devData -> // use it to match up with the light ID
                              devData.type == "light" &&
                              devData.owner?.rid == ownerId
                           }
                           if (lightId != null) dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${lightId}")
                           // not doing for sensors or buttons but could if wanted to with minor adjustments above
                        default:
                           if (logEnable) log.debug "skipping Hue v1 ID: $idV1"
                     }
                     // If child device found, send map to it for parsing:
                     if (dev != null) dev.createEventsFromMapV2(updateEntryMap)
                  }
               }
            }
            else {
               if (logEnable) log.debug "skip: $dataEntryMap"
            }
         }
      }
      else {
         if (logEnable) log.trace "no data parsed from message: $message"
      }
   }
}

void refresh() {
   if (logEnable) log.debug "refresh()"
   Map<String,String> data = parent.getBridgeData()
   try {
      if (data.apiVersion == APIV1 || data.apiVersion == null) {
         refreshV1()
      }
      else {
         bridgeAsyncGetV2("parseStatesV2", "/resource", data)
      }
   }
   catch (Exception ex) {
      log.error "Error in refresh(): $ex"
   }
}

void refreshV1() {
   if (logEnable) log.debug "refreshV1()"
   Map<String,String> data = parent.getBridgeData()
   try {
      Map params = [
         uri: data.fullHost,
         path: "/api/${data.username}/",
         contentType: 'application/json',
         timeout: 15
      ]
      asynchttpGet("parseStatesV1", params)
   }
   catch (Exception ex) {
      log.error "Error in refreshV1(): $ex"
   }
}

void scheduleRefresh() {
   if (logEnable) log.debug "scheduleRefresh()"
   
}

/** Returns V1-format bulb/light type (e.g., "extended color light") based on information
  * found in V2 API light service data (e.g., presence or absence of color_temperature service, color, etc.)
*/
String determineLightType(Map data) {
   if (data.color && data.color_temperature) return "extended color light"
   else if (data.color_temperature) return "color temperature light"
   else if (data.color) return "color light"
   else if (data.dimming) return "dimmable light"
   else if (data.on) return "on/off light"
   else return "UNKNOWN"
}

/** Callback method that handles full Bridge refresh for V2 API. Eventually delegated to
 *  individual methods below.
 */
void parseStatesV2(AsyncResponse resp, Map data) { 
   if (logEnable) log.debug "parseStatesV2: States from Bridge received. Now parsing..."
   // Might as well update cache since have the same data now anyway:
   handleUpdateBridgeCacheResponseV2(resp, data)
   if (checkIfValidResponse(resp)) {
      //TODO: Check that all are updated for v2 (in progress!)
      // Lights:
      List<Map> lightsData = resp.json.data.findAll { it.type == "light" }
      // Groups (and Rooms and Zones):
      List<Map> roomsData = resp.json.data.findAll { it.type == "room" }  // probably needed? check if needed here...
      List<Map> zonesData = resp.json.data.findAll { it.type == "zone" }  // probably needed? check if needed here...
      List<Map> groupsData = resp.json.data.findAll { it.type == "grouped_light" }
      // Scenes:
      List<Map> scenesData = resp.json.data.findAll { it.type == "scene" }
      // Contact sensors:
      List<Map> contactData = resp.json.data.findAll { it.type == "contact_report" }
      // Motion sensors (motion, temperature, lux, battery):
      List<Map> motionData = resp.json.data.findAll { it.type == "motion" }
      //log.trace "motion = $motionData"
      List<Map> temperatureData = resp.json.data.findAll { it.type == "temperature" }
      //log.trace  "ill = $illuminanceData"
      List<Map> illuminanceData = resp.json.data.findAll { it.type == "light_level" }
      //log.trace "ll = $illuminanceData"
      List<Map> batteryData = resp.json.data.findAll { it.type == "device_power" }
      //log.trace "batt = $batteryData"
      // TODO: batteryData could also be useful for buttons/remotes?
      // Probably does not make sense to parse other button events now (only in real time)
      // Check if anything else?
      parseLightStatesV2(lightsData)
      parseGroupStatesV2(groupsData)
      parseSceneStatesV2(scenesData)
      // TODO: see if can combine this data into one instead of calling 4x total:
      parseMotionSensorStatesV2(motionData)
      parseMotionSensorStatesV2(temperatureData)
      parseMotionSensorStatesV2(illuminanceData)
      parseMotionSensorStatesV2(batteryData)
      // TODO: see if can combine this data into one instead of calling 2x, similar to above:
      parseContactSensorStatesV2(contactData)
      parseContactSensorStatesV2(batteryData)
   }
}

/** Callback method that handles full Bridge refresh for V1 API. Eventually delegated to
 *  individual methods below. For Hue V1 API.
 */
void parseStatesV1(AsyncResponse resp, Map data) { 
   if (logEnable) log.debug "parseStatesV1() - States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      parseLightStatesV1(resp.json.lights)
      parseGroupStatesV1(resp.json.groups)
      parseMotionSensorStatesV1(resp.json.sensors)
   }
}

void parseLightStatesV2(List lightsJson) {
   if (logEnable) log.debug "parseLightStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "lightsJson = $lightsJson"
   try {
      lightsJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/lights/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format. Try selecting \"Done\" in the parent app to retry conversion. Found and using V1 device for now."
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing light states: ${ex}"
   }
}

void parseLightStatesV1(Map lightsJson) {
   if (logEnable) log.debug "parseLightStatesV1()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "lightsJson = $lightsJson"
   try {
      lightsJson.each { id, val ->
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
         if (dev == null) dev = parent.getChildDevices().find {
            it.deviceNetworkId.startsWith("${device.deviceNetworkId}/Light/") && it.getHueDeviceIdV1() == id
         }
         if (dev) {
            dev.createEventsFromMapV1(val.state, true)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing light states: ${ex}"
   }
}

void parseGroupStatesV2(List groupsJson) {
   if (logEnable) log.debug "parseGroupStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "groupsJson = $groupsJson"
   try {
      groupsJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/groups/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format. Try selecting \"Done\" in the parent app to retry conversion. Found and using V1 device for now."
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseGroupStatesV1(Map groupsJson) {
   if (logEnable) log.debug "parseGroupStatesV1()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "groupsJson = $groupsJson"
   try {
      groupsJson.each { id, val ->
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev == null) dev = parent.getChildDevices().find {
            it.deviceNetworkId.startsWith("${device.deviceNetworkId}/Group/") && it.getHueDeviceIdV1() == id
         }
         if (dev != null) {
            dev.createEventsFromMapV1(val.action, true)
            dev.createEventsFromMapV1(val.state, true)
            dev.setMemberBulbIDs(val.lights)
         }
      }
      Boolean anyOn = groupsJson.any { it.value?.state?.any_on == true }
      DeviceWrapper allLightsDev = parent.getChildDevice("${device.deviceNetworkId}/Group/0")
      if (allLightsDev != null) {
         allLightsDev.createEventsFromMapV1(['any_on': anyOn], true)
      }
      
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseSceneStatesV2(List scenesJson) {
   if (logEnable) log.debug "parseSceneStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "scenesJson = $scenesJson"
   try {
      scenesJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/scene/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format. Try selecting \"Done\" in the parent app to retry conversion. Found and using V1 device for now."
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseContactSensorStatesV2(List sensorJson) {
   if (logEnable) log.debug "parseContactSensorStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "sensorJson = $sensorJson"
   try {
      sensorJson.each { Map data ->
         String id = data.owner.rid // use owner ID for sensor to keep same physical devices together more easily 
         //String id_v1 = data.id_v1 - "/sensors/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Contact/${id}")
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      //if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing contact sensor states: ${ex}"
   }
}

void parseMotionSensorStatesV2(List sensorJson) {
   if (logEnable) log.debug "parseMotionSensorStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "sensorJson = $sensorJson"
   try {
      sensorJson.each { Map data ->
         String id = data.owner.rid // use owner ID for sensor to keep same physical devices together more easily 
         String id_v1 = data.id_v1 - "/sensors/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${id}")
         if (dev == null) {
               dev = parent.getChildDevices().find { DeviceWrapper d ->
                  d.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/") &&
                  id_v1 in d.deviceNetworkId.tokenize('/')[-1].tokenize('|')
               }
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format. Try selecting \"Done\" in the parent app to retry conversion. Found and using V1 device for now."
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      //if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing motion sensor states: ${ex}"
   }
}

// Not used for new installs, but existing may have V1 IDs for sensors from CoCoHue 3.x/4.x, so keeping this V1
// method around for now just in case:
void parseMotionSensorStatesV1(Map sensorsJson) {
   if (logEnable) log.debug "Parsing sensor states from Bridge..."
   // Uncomment this line if asked to for debugging (or you're curious):
   // log.trace "sensorsJson = ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(sensorsJson))}"
   try {
      sensorsJson.each { key, val ->
         if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature" ||
          val.type == "ZHAPresence" || val.type == "ZHALightLevel" || val.type == "ZHATemperature") {
            DeviceWrapper sensorDev = parent.getChildDevices().findAll { DeviceWrapper it ->
               it.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/") &&
               (key as String) in it.deviceNetworkId.tokenize('/')[-1].tokenize('|')
            }[0]
            if (sensorDev != null) {
               sensorDev.createEventsFromMapV1(val.state)
               // All entries have config.battery, so just picking one to parse here to avoid redundancy:
               if (val.type == "ZLLPresence" || val.type == "ZHAPresence") sensorDev.createEventsFromMapV1(["battery": val.config.battery])
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
void getAllBulbsV1() {
   if (logEnable) log.debug "getAllBulbsV1()"
   //clearBulbsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllBulbsResponseV1", params)
}

void parseGetAllBulbsResponseV1(resp, data) {
   if (logEnable) log.debug "parseGetAllBulbsResponseV1()"
   if (checkIfValidResponse(resp)) {
      try {
         Map bulbs = [:]
         resp.json.each { key, val ->
            bulbs[key] = [name: val.name, type: val.type]
         }
         state.allBulbs = bulbs
         if (logEnable) log.debug "  All bulbs received from Bridge: $bulbs"
      }
      catch (Exception ex) {
         log.error "Error parsing all bulbs response: $ex"
      }
   }
}

void getAllBulbsV2() {
   if (logEnable) log.debug "getAllBulbsV2()"
   updateBridgeCacheV2(additionalCallback: "parseGetAllBulbsResponseV2")
}

void parseGetAllBulbsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllBulbsResponseV2()"
   if (checkIfValidResponse(resp)) {
    try {
         Map bulbs = [:]
         resp.json.data.findAll { it.type == "light" }?.each { Map bulbData ->
            bulbs[bulbData.id] = [name: bulbData.metadata.name, type: determineLightType(bulbData), id_v1: bulbData.id_v1]
         }
         state.allBulbs = bulbs
         if (logEnable) log.debug "  All bulbs received from Bridge: $bulbs"
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
   if (logEnable) log.debug "Running clearBulbsCache..."
   state.remove('allBulbs')
}

// ------------ GROUPS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllGroupsV1() {
   if (logEnable) log.debug "getAllGroupsV1()"
   //clearGroupsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups",
      contentType: "application/json",
      timeout: 15
   ]
   asynchttpGet("parseGetAllGroupsResponseV1", params)
}

void parseGetAllGroupsResponseV1(resp, data) {
   if (logEnable) log.debug "parseGetAllGroupsResponseV1()"
   if (checkIfValidResponse(resp)) {
      try {
         Map groups = [:]
         resp.json.each { key, val ->
            groups[key] = [name: val.name, type: val.type]
         }
         groups[0] = [name: "All Hue Lights", type:  "LightGroup"] // add "all Hue lights" group, ID 0
         state.allGroups = groups
         if (logEnable) log.debug "  All groups received from Bridge: $groups"
      }
      catch (Exception ex) {
         log.error "Error in parseGetAllGroupsResponseV1(): $ex"
      }
   }
}

void getAllGroupsV2() {
   if (logEnable) log.debug "getAllGroupsV2()"
   updateBridgeCacheV2(additionalCallback: "parseGetAllGroupsResponseV2")
}

void parseGetAllGroupsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllGroupsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         Map roomsOrZones = [:]
         // fetch rooms and zones:
         ["room", "zone"]. each { String roomOrZone ->
            resp.json.data.findAll { it.type == roomOrZone }?.each { Map roomOrZoneData ->
               String groupedLightId = roomOrZoneData.services.find({ svc -> svc.rtype == "grouped_light" })?.rid
               if (groupedLightId != null) {
                  roomsOrZones[groupedLightId] = [name: roomOrZoneData.metadata.name, type: roomOrZoneData.type, 
                     "${roomOrZone}Id": roomOrZoneData.id, id_v1: roomOrZoneData.id_v1]
               }
               else {
                  if (logEnable) log.debug "No grouped_light service found for {roomOrZone} ID ${roomOrZoneData.id}"
               }
            }
            if (roomOrZone== "room") state.allRooms = roomsOrZones
            else state.allZones = roomsOrZones
            if (logEnable) log.debug "  All ${roomOrZone}s received from Bridge: $roomsOrZones"
         }
         // fetched the one grouped_light that should be like groups/0 in V1 API, i.e., "All Hue Lights":
         Map allHueLightsGroup = resp.json.data.find { it.type == "grouped_light" && it.owner?.rtype == "bridge_home" }
         if (allHueLightsGroup != null) {
            state.allGroups = [:]
            state.allGroups[allHueLightsGroup.id] = [name: "All Hue Lights", type: "grouped_light"]
         }
      }
      catch (Exception ex) {
         log.error "Error in parseGetAllGroupsResponseV2(): $ex"
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
   if (logEnable) log.debug "Running clearGroupsCache..."
   state.remove("allGroups")
}

/** Intended to be called from parent app to retrive previously
 *  requested list of rooms
 */
Map getAllRoomsCache() {
   return state.allRooms ?: [:]
}

/** Clears cache of room IDs/names (and group light data inside); useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearRoomsCache() {
   if (logEnable) log.debug "Running clearRoomsCache..."
   state.remove("allRooms")
}

/** Intended to be called from parent app to retrive previously
 *  requested list of zones
 */
Map getAllZonesCache() {
   return state.allZones ?: [:]
}

/** Clears cache of zone IDs/names (and group light data inside); useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearZonesCache() {
    if (logEnable) log.debug "Running clearZonesCache..."
    state.remove("allZones")
}

// ------------ SCENES ------------

/** Requests list of all scenes from Hue Bridge; updates
 *  allScenes in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllScenesV1() {
   if (logEnable) log.debug "getAllScenesV1()"
   getAllGroupsV1() // so can get room names, etc.
   //clearScenesCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/scenes",
      contentType: "application/json",
      timeout: 15
   ]
   asynchttpGet("parseGetAllScenesResponseV1", params)
}

void parseGetAllScenesResponseV1(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllScenesResponseV1()"
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.each { key, val ->
            scenes[key] = ["name": val.name]
            if (val.group) scenes[key] << ["group": val.group]
         }
         state.allScenes = scenes
         if (logEnable) log.debug "  All scenes received from Bridge: $scenes"
      }
      catch (Exception ex) {
         log.error "Error in parseGetAllScenesResponseV1(): ${ex}"
      }
   }
}

void getAllScenesV2() {
   if (logEnable) log.debug "getAllScenesV2()"
   updateBridgeCacheV2(additionalCallback: "parseGetAllScenesResponseV2")
}

void parseGetAllScenesResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllScenesResponseV2()"
   // do group parsing first so can get room/zone names
   parseGetAllGroupsResponseV2(resp)
   // now on to the actual scenes...
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.data.findAll { it.type == "scene"}?.each { Map sceneData ->
            // TODO: originally wanted grouped_light service ID, not room/zone ID, but using so either should work and can look up f/ cache if need in future?
            scenes[sceneData.id] = [name: sceneData.metadata.name, group: sceneData.group?.rid, id_v1: sceneData.id_v1]
         }
         state.allScenes = scenes
         if (logEnable) log.debug "  All scenes received from Bridge: $scenes"
      }
      catch (Exception ex) {
         log.error "Error in parseGetAllScenesResponseV2(): $ex"
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
   if (logEnable) log.debug "Running clearScenesCache..."
   state.remove("allScenes")
}

// ------------ MOTION SENSORS ------------
// No V1 for these

void getAllMotionSensorsV2() {
   if (logEnable) log.debug "getAllMotionSensorsV2()"
   updateBridgeCacheV2(additionalCallback: "parseGetAllMotionSensorsResponseV2")
}

void parseGetAllMotionSensorsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllMotionSensorsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         List<Map> motionDevs = resp.json?.data?.findAll { Map devData ->
            devData.type == "device" &&
            devData.services.any { svc -> svc.rtype == "motion"}
         }
         Map sensors = [:]
         motionDevs.each { Map devData ->
            sensors[devData.id] = devData.metadata.name
         }
         state.allMotionSensors = sensors
         if (logEnable) log.debug "  All sensors received from Bridge: $sensors"
      }
      catch (Exception ex) {
         log.error "Error parsing all sensors response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of sensors
 */
Map<String,Map> getAllMotionSensorsCache() {
   return state.allMotionSensors
}

/** Clears cache of sensor IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearMotionSensorsCache() {
   if (logEnable) log.debug "Running clearMotionSensorsCache..."
   state.remove("allMotionSensors")
}

// ------------ CONTACT SENSORS ------------
// No V1 for these

void getAllContactSensorsV2() {
   if (logEnable) log.debug "getAllContactSensorsV2()"
   updateBridgeCacheV2(additionalCallback: "parseGetAllContactSensorsResponseV2")
}

void parseGetAllContactSensorsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllContactSensorsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         List<Map> contactDevs = resp.json?.data?.findAll { Map devData ->
            devData.type == "device" &&
            devData.services.any { svc -> svc.rtype == "contact"}
         }
         Map sensors = [:]
         contactDevs.each { Map devData ->
            sensors[devData.id] = devData.metadata.name
         }
         state.allContactSensors = sensors
         if (logEnable) log.debug "  All sensors received from Bridge: $sensors"
      }
      catch (Exception ex) {
         log.error "Error parsing all sensors response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of sensors
 */
Map<String,Map> getAllContactSensorsCache() {
   return state.allContactSensors
}

/** Clears cache of sensor IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearContactSensorsCache() {
   if (logEnable) log.debug "Running clearContactSensorsCache..."
   state.remove("allContactSensors")
}

// ------------ BUTTONS ------------
// no V1 for these

/** Requests list of all button devices from Hue Bridge; updates
 *  allButtons in state when finished. Intended to be called
 *  during buttoon discovery in app.
 */
void getAllButtonsV2() {
   if (logEnable) log.debug "getAllButtonsV2()"
   //clearButtonsCache()
   updateBridgeCacheV2(additionalCallback: "parseGetAllButtonsResponseV2")
}

void parseGetAllButtonsResponseV2(resp, data) {
   if (logEnable) log.debug "parseGetAllButtonsResponseV2()"
   if (checkIfValidResponse(resp)) {
      Map buttons = [:]
      // button resources:
      List<Map> buttonDevs = resp.json?.data?.findAll { Map devData -> devData.type == "button" }
      buttonDevs?.each { Map devData ->
         log.warn it
         if (buttons[devData.owner.rid] == null) buttons[devData.owner.rid] = [buttons: [:]]
         buttons[devData.owner.rid].buttons << [(devData.id): devData.metadata.control_id]
      }
      // relative_rotary resoruces (Hue Tap Dial, Lutron Aurora, etc.):
      buttonDevs = resp.json?.data?.findAll { Map devData -> devData.type == "relative_rotary" }
      buttonDevs?.each { Map devData ->
         if (buttons[devData.owner.rid] != null) {
            if (buttons[devData.owner.rid].relative_rotary == null) {
               buttons[devData.owner.rid] << [relative_rotary: []]
            }
            buttons[devData.owner.rid].relative_rotary << devData.id
         }
         else {
            // probably won't happen, but skip if no associated button
         }
      }
      // also get device name:
      List devicesJson = resp.json?.data?.findAll { Map devData -> devData.type == "device" }
      buttons.keySet().each { String id ->
         Map dev = devicesJson.find { dev -> dev.id == id }
         buttons[id].name = dev.metadata.name
         buttons[id].manufacturer_name = dev.product_data.manufacturer_name
         buttons[id].model_id = dev.product_data.model_id
      }
      state.allButtons = buttons
      //state.allRelativeRotaries = relativeRotaries
      if (logEnable) log.debug "  All buttons received from Bridge: $buttons"
   }
}

// void parseGetAllButtonsResponseV2OLD(resp, data) {
//    if (logEnable) log.debug "parseGetAllButtonsResponseV2()"
//    if (checkIfValidResponse(resp)) {
//       try {
//          Map buttons = [:]
//          // Get specific /button devices first....
//          Map<String,String> bridgeData = parent.getBridgeData()
//          // TODO: Consider making this async, but should be pretty safe considerng we just heard from Bridge...
//          Map params = [
//             uri: "https://${bridgeData.ip}",
//             path: "/clip/v2/resource/button",
//             headers: ["hue-application-key": bridgeData.username],
//             contentType: "application/json",
//             timeout: 10,
//             ignoreSSLIssues: true
//          ]
//          httpGet(params,
//             { response ->
//                   response.data.data.each {
//                      if (buttons[it.owner.rid] == null) buttons[it.owner.rid] = [buttons: [:]]
//                      buttons[it.owner.rid].buttons << [(it.id): it.metadata.control_id]
//                   }
//             }
//          )
//          pauseExecution(500)
//          // Check for relative_rotary, too (Hue Tap Dial, Lutron Aurora)
//          params = [
//             uri: "https://${bridgeData.ip}",
//             path: "/clip/v2/resource/relative_rotary",
//             headers: ["hue-application-key": bridgeData.username],
//             contentType: "application/json",
//             timeout: 10,
//             ignoreSSLIssues: true
//          ]
//          httpGet(params,
//             { response ->
//                   response.data.data.each {
//                      if (buttons[it.owner.rid] != null) {
//                         if (buttons[it.owner.rid].relative_rotary == null) {
//                            buttons[it.owner.rid] << [relative_rotary: []]
//                         }
//                         buttons[it.owner.rid].relative_rotary << it.id
//                      }
//                      else {
//                         // probably won't happen, but skip if no associated button
//                      }
//                   }
//             }
//          )
//          // But also have to get name from /devices data...
//          if (resp?.json?.data) {
//             List devicesJson = resp.json.data
//             buttons.keySet().each { String id ->
//                Map dev = devicesJson.find { dev -> dev.id == id }
//                buttons[id].name = dev.metadata.name
//                buttons[id].manufacturer_name = dev.product_data.manufacturer_name
//                buttons[id].model_id = dev.product_data.model_id
//             }
//          }
//          else {
//             log.warn "No data in returned JSON: $data"
//          }
//          state.allButtons = buttons
//          //state.allRelativeRotaries = relativeRotaries
//          if (logEnable) log.debug "  All buttons received from Bridge: $buttons"
//       }
//       catch (Exception ex) {
//          log.error "Error parsing all buttons response: $ex"
//       }
//    }
// }

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
   if (logEnable) log.debug "Running clearButtonsCache..."
   state.remove('allButtons')
}


// --------- BRIDGE/General ---------
/** Updates V2 API cache of entire /resources endpoint, useful for matching service (e.g., light or motion) and
 * owner (device) IDs, reducing individual endpoint calls, etc.
 * @param options Map of additional options:
 *  - `additionalCallback`: if provided, will run this method when fetch is complete (useful if using as part of bulb, sensor, etc. fetch); handler to update cache object always runs
 *  - `force`: If true, will reload regardless of last cache time; if false, will refresh only if not refreshed in last CACHE_STALE_THRESHOLD milliseconds
 */
void updateBridgeCacheV2(Map options) {
   if (logEnable) log.debug "updateBridgeCacheV2()"
   if ((now() - (state.lastBridgeCache ?: 0) > CACHE_STALE_THRESHOLD) || options?.force) {
      Map<String,String> extraData = options?.additionalCallback ? [additionalCallback: options.additionalCallback] : null
      bridgeAsyncGetV2("handleUpdateBridgeCacheResponseV2", "/resource", null, extraData)
   }
   else {
      if (logEnable) log.debug "Skipping cache update because newer than ${CACHE_STALE_THRESHOLD} ms"
   }
}

void handleUpdateBridgeCacheResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "handleUpdateBridgeCacheResponseV2()"
   if (checkIfValidResponse(resp)) {
      synchronized(bridgeCacheLock) {
         bridgeCache[device.idAsLong] = resp.json
      }
      state.lastBridgeCache = now()
      if (data?.additionalCallback) {
         this."${data.additionalCallback}"(resp, data)
      }
   }
}

Map getBridgeCacheV2() {
   return bridgeCache[device.idAsLong]
}


// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Common_Lib ~~~
// Version 1.0.5
// For use with CoCoHue drivers (not app)

/**
 * 1.0.5 - Add common bridgeAsyncPutV2() method for asyncHttpPut (goal to reduce individual driver code)
 * 1.0.4 - Add common bridgeAsyncGetV2() method asyncHttpGet (goal to reduce individual driver code)
 * 1.0.3 - Add APIV1 and APIV2 "constants"
 * 1.0.2  - HTTP error handling tweaks
 */

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("logEnable", [value:"false", type:"bool"])
}

/** Performs basic check on data returned from HTTP response to determine if should be
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and
  * returns false if not
  * @param resp The async HTTP response object to examine
  */
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) {
   if (logEnable == true) log.debug "Checking if valid HTTP response/data from Bridge..."
   Boolean isOK = true
   if (resp.status < 400) {
      if (resp.json == null) {
         isOK = false
         if (resp.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge"
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})"
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeOnlineStatus(false)
      }
      else if (resp.json) {
         if ((resp.json instanceof List) && resp.json.getAt(0).error) {
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
         // TODO: Update for mDNS if/when switch:
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery 
         parent.setBridgeOnlineStatus(false)
      }
      if (isOK == true) parent.setBridgeOnlineStatus(true)
   }
   else {
      log.warn "Error communicating with Hue Bridge: HTTP ${resp?.status}"
      isOK = false
   }
   return isOK
}

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) {
   //if (logEnable == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (settings.txtEnable == true) log.info(descriptionText)
   if (eventUnit) {
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true) 
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
   }
}

// HTTP methods (might be better to split into separate library if not needed for some?)

/** Performs asynchttpGet() to Bridge using data retrieved from parent app or as passed in
  * @param callbackMethod Callback method
  * @param clipV2Path The Hue V2 API path ('/clip/v2' is automatically prepended), e.g. '/resource' or '/resource/light'
  * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null
  * @param data Extra data to pass as optional third (data) parameter to asynchtttpGet() method
  */
void bridgeAsyncGetV2(String callbackMethod, String clipV2Path, Map<String,String> bridgeData = null, Map data = null) {
   if (bridgeData == null) {
      bridgeData = parent.getBridgeData()
   }
   Map params = [
      uri: "https://${bridgeData.ip}",
      path: "/clip/v2${clipV2Path}",
      headers: ["hue-application-key": bridgeData.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet(callbackMethod, params, data)
}

/** Performs asynchttpPut() to Bridge using data retrieved from parent app or as passed in
  * @param callbackMethod Callback method
  * @param clipV2Path The Hue V2 API path ('/clip/v2' is automatically prepended), e.g. '/resource' or '/resource/light'
  * @param body Body data, a Groovy Map representing JSON for the Hue V2 API command, e.g., [on: [on: true]]
  * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null
  * @param data Extra data to pass as optional third (data) parameter to asynchtttpPut() method
  */
void bridgeAsyncPutV2(String callbackMethod, String clipV2Path, Map body, Map<String,String> bridgeData = null, Map data = null) {
   if (bridgeData == null) {
      bridgeData = parent.getBridgeData()
   }
   Map params = [
      uri: "https://${bridgeData.ip}",
      path: "/clip/v2${clipV2Path}",
      headers: ["hue-application-key": bridgeData.username],
      contentType: "application/json",
      body: body,
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpPut(callbackMethod, params, data)
   if (logEnable == true) log.debug "Command sent to Bridge: $body at ${clipV2Path}"
}


// ~~~ IMPORTED FROM RMoRobert.CoCoHue_Constants_Lib ~~~
// Version 1.0.0

// --------------------------------------
// APP AND DRIVER NAMESPACE AND NAMES:
// --------------------------------------
@Field static final String NAMESPACE                  = "RMoRobert"
@Field static final String DRIVER_NAME_BRIDGE         = "CoCoHue Bridge"
@Field static final String DRIVER_NAME_BUTTON         = "CoCoHue Button"
@Field static final String DRIVER_NAME_CT_BULB        = "CoCoHue CT Bulb"
@Field static final String DRIVER_NAME_DIMMABLE_BULB  = "CoCoHue Dimmable Bulb"
@Field static final String DRIVER_NAME_GROUP          = "CoCoHue Group"
@Field static final String DRIVER_NAME_MOTION         = "CoCoHue Motion Sensor"
@Field static final String DRIVER_NAME_CONTACT        = "CoCoHue Contact Sensor"
@Field static final String DRIVER_NAME_PLUG           = "CoCoHue Plug"
@Field static final String DRIVER_NAME_RGBW_BULB      = "CoCoHue RGBW Bulb"
@Field static final String DRIVER_NAME_RGB_BULB       = "CoCoHue RGB Bulb"
@Field static final String DRIVER_NAME_SCENE          = "CoCoHue Scene"

// --------------------------------------
// DNI PREFIX for child devices:
// --------------------------------------
@Field static final String DNI_PREFIX = "CCH"

// --------------------------------------
// OTHER:
// --------------------------------------
// Used in app and Bridge driver, may eventually find use in more:
@Field static final String APIV1 = "V1"
@Field static final String APIV2 = "V2"
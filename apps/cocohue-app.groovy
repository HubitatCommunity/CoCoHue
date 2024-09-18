/**
 * ===========================  CoCoHue - Hue Bridge Integration =========================
 *
 *  Copyright 2019-2024 Robert Morris
 *
 *  DESCRIPTION:
 *  Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
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
 *  Last modified: 2024-09-17
 *  Changelog:
 *  v5.0.3 - Upgrade old log settings; use V2 for scene activation when possible
 *  v5.0.2 - Fetch V2 grouped_light ID owner for room/zone owners of V2 scenes
 *  v5.0.1 - Fix for missing V1 IDs after device creation or upgrade
 *  v5.0   - Use API v2 by default for device info, remove deprecated features, add RGB-only driver
 *  v4.1.9 - Add note that Hue Labs features are now deprecated
 *  v4.1.2 - Additional button enhancements (relative_rotary -- Hue Tap Dial, etc.)
 *  v4.1   - Add support for button devices (with v2 API only)
 *  v4.0.3 - Immediately disconnect eventstream when option un-selected (and saved)
 *  v4.0.2 - Fix for error when adding new sensors
 *  v4.0.1 - Fix for app not sensing bridge authorization on initial setup
 *  v4.0   - Changes for EventStream-based information pushing; new DNI format (CCH/appId..., not CCH/BridgeMACAbbrev...)
 *           After upgrading, open CoCoHue app and push "Done." NOTE: Downgrading (without hub restore) is not possible after this.
 *  v3.5.1 - Improved username sanitization; removed logging for SSDP if debug logging disabled
 *  v3.5   - Minor code cleanup (and lots of driver changes)
 *  v3.1   - Driver updates (logging, error handling)
 *  v3.0   - Added support for Hue motion sensors (also temp/illuminance) and Hue Labs activators; added custom port options and
 *           other changes to enhance compatibility with DeCONZ and similar third-party APIs
 *  v2.2   - Added support for illumiance/temp/motion readings from Hue Motion sensors from Bridge
 *  v2.1   - Reduced group and scene "info" logging if no state change/event; other GroupScenes now also report "off" if received from
 *           poll from Bridge instead of (only) command from Hubitat; more static typing
 *  v2.0   - New non-parent/child structure and name change; Bridge discovery; Bridge linking improvements (fewer pages);
 *           added documentation links; likely performance improvements (less dynamic typing); ability to use dicovery but
 *           unsubscribe from SSDP/discovery after addition; Hue vs. Hubitat name comparisons added; scene device improvments
 *           Additiononal device features; child devices now (optionally) deleted when app uninstalled
 *  v1.9   - Added CT and dimmable bulb types
 *  v1.7   - Addition of new child device types, updating groups from member bulbs
 *  v1.6   - Added options for bulb and group deivce naming
 *  v1.5   - Added scene integration
 *  v1.1   - Added more polling intervals
 *  v1.0   - Initial Public Release
 */ 

import groovy.transform.Field
import hubitat.scheduling.AsyncResponse
import com.hubitat.app.DeviceWrapper


@Field static final Integer debugAutoDisableMinutes = 30

// Current "schema" of app settings, state, DNI format, and related features that occasionally change -- used by some methods to check
// if need to run once-post-upgrade conversions, etc. Increase iff such modifications become necessary:
@Field static final Integer currentSchemaVersion = 5

String getDriverNameForDeviceType(String deviceType) {
   switch (deviceType) {
      case "extended color light":
         return DRIVER_NAME_RGBW_BULB
         break
      case "color light":
         return DRIVER_NAME_RGB_BULB
         break
      case "color temperature light":
         return DRIVER_NAME_CT_BULB
         break
      case "dimmable light":
         return DRIVER_NAME_DIMMABLE_BULB
         break
      case "on/off light":
      case "on/off plug-in unit":
         return DRIVER_NAME_PLUG
         break
      default:
         return DRIVER_NAME_RGBW_BULB  // reasonable enough?
         break
   }
}

@Field static final Integer minPossibleV2SwVersion = 1948086000 // minimum swversion on Bridge needed for Hue V2 API
@Field static final Integer minV2SwVersion = 1955082050         // ... but 1955082050 recommended for production use

definition (
   name: "CoCoHue - Hue Bridge Integration",
   namespace: "RMoRobert",
   author: "Robert Morris",
   description: "Community-created Philips Hue integration for Hue Bridge lights and other Hue devices and features",
   category: "Convenience",
   installOnOpen: true,
   documentationLink: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978",
   iconUrl: "",
   iconX2Url: "",
   iconX3Url: ""
)

preferences {
   page name: "pageFirstPage"
   page name: "pageIncomplete"
   page name: "pageAddBridge"
   page name: "pageReAddBridge"
   page name: "pageLinkBridge"
   page name: "pageManageBridge"
   page name: "pageSelectLights"
   page name: "pageSelectGroups"
   page name: "pageSelectScenes"
   page name: "pageSelectMotionSensors"
   page name: "pageSelectButtons"
   page name: "pageSupportOptions"
}

void installed() {
   log.debug "installed()"
   app.updateSetting("logEnable", true)
   initialize()
}

void uninstalled() {
   log.debug "uninstalled()"
}

void updated() {
   log.debug "updated()"
   if (logEnable == true) log.debug "Updated with settings: ${settings}"
   initialize()
   // Upgrade pre-CoCoHue-5.0 DNIs to match new DNI format (will only change if using V2 and hasn't been done yet)
   runIn(3, "upgradeCCHv1DNIsToV2")
   if (settings.enableDebug != null) { // name in 4.x and earlier, using as proxy to determine if log settings updated to 5.x standard for all
      Boolean currentAppDebugSetting = settings.enableDebug == true ? true : false
      app.removeSetting("enableDebug")
      app.updateSetting("enableDebug", [type: "bool", value: currentAppDebugSetting])
      getChildDevices.each() { DeviceWrapper cd ->
         Boolean dbgLog = cd.getSetting("enableDebug")
         if (dbgLog != null) {
            cd.removeSetting("enableDebug")
            cd.updateSetting("logEnable", [type: "bool", value: dbgLog])
         }
         Boolean infoLog = cd.getSetting("enableDesc")
         if (infoLog != null) {
            cd.removeSetting("enableDesc")
            cd.updateSetting("txtEnable", [type: "bool", value: infoLog])
         }
      }
   }
}

/** Upgrades pre-CoCoHue-5.0 DNIs from V1 API to match 5.x/V2 API format (changes V1 Hue IDs to V2)
  * Should do ONLY if know Bridge is capable of supporting v2 API
*/
void upgradeCCHv1DNIsToV2(Boolean secondAttempt=false) {
   if (logEnable == true) log.debug "upgradeCCHv1DNIsToV2()"
   state.remove("pendingUpgradeCCHv1DNIsToV2Retry")
   if (state.useV2 && !(state.useV2UpgradeCompleted)) {
      Map<String,String> bridgeData = getBridgeData()
      Map params = [
         uri: "https://${bridgeData.ip}",
         path: "/clip/v2/resource",
         headers: ["hue-application-key": bridgeData.username],
         contentType: "application/json",
         timeout: 15,
         ignoreSSLIssues: true
      ]
      if (logEnable == true) log.debug "Starting Hue V2 API DNI updates. Sending V2 API call to Bridge /resources endpoint..."
      asynchttpGet("upgradeCCHv1DNIsToV2ResponseHandler", params)
   }
   else if (!(state.useV2) && settings.useEventStream && secondAttempt != true) {
      // Try again in a few seconds in case state.useV2 wasn't set yet because didn't have Bridge info yet
      // But only try once more (secondAttempt parameter avoids endless chain)
      if (logEnable == true) log.debug "Set to use V2 if available but state not currently set for V2 usage; retrying once more in a few seconds..."
      state.pendingUpgradeCCHv1DNIsToV2Retry = true
      runIn(6, "retryUpgradeCCHv1DNIsToV2")
   }
   else if (logEnable == true && !(state.useV2 || settings.useEventStream)) {
      log.debug "Not configured to use Hue V2 API, so not upgrading DNIs to V2 API format."
   }
}

void retryUpgradeCCHv1DNIsToV2() {
   if (logEnable) log.debug "retryUpgradeCCHv1DNIsToV2()"
   upgradeCCHv1DNIsToV2(true)
   state.remove("pendingUpgradeCCHv1DNIsToV2Retry")
}

void upgradeCCHv1DNIsToV2ResponseHandler(AsyncResponse resp, data=null) {
   if (logEnable == true) log.debug "upgradeCCHv1DNIsToV2ResponseHandler()"
   if (resp.status == 200 && !(resp.error) && resp.json?.data) {
      if (logEnable == true) log.debug "Parsing data from Bridge /resources endpoint..."
      
      // -- Get all relevant device data from API response: --
      List<Map> lightsData = resp.json.data.findAll { it.type == "light" } ?: [[:]] // lights
      List<Map> roomsData = resp.json.data.findAll { it.type == "room" } ?: [[:]]  // rooms (groups)
      List<Map> zonesData = resp.json.data.findAll { it.type == "zone" } ?: [[:]]    // zones (groups)
      List<Map> groupsData = resp.json.data.findAll { it.type == "grouped_light" } ?: [[:]]  // "pure" groups (groups)
      List<Map> scenesData = resp.json.data.findAll { it.type == "scene" } ?: [[:]]  // scenes
      List<Map> motionData = resp.json.data.findAll { it.type == "motion" } ?: [[:]]  // motion for motion sensorsor motion sensors
      List<Map> temperatureData = resp.json.data.findAll { it.type == "temperature" } ?: [[:]]  // temperature for motion sensors
      List<Map> illuminanceData = resp.json.data.findAll { it.type == "light_level" } ?: [[:]]  // lux for motion sensors (all three of above have separate V1 IDs)
      // Don't need this for motion or button since was coupled with other data in V1:
      //List<Map> batteryData = resp.json.data.findAll { it.type == "device_power" }
      // Not doing buttons because have always been created using only V2 ID

      // -- Now, look up each Hubitat device and perform DNI conversion if found on Hue --
      // converting lights, groups, and scens are all pretty similar (TODO: see if can refactor and reuse more code among all 3?):
      lightsData.each { Map hueData ->
         String id = hueData.id 
         String id_v1 = hueData.id_v1
         if (id_v1 != null) {
            id_v1 = id_v1 - "/lights/"
            DeviceWrapper dev = getChildDevice("${DNI_PREFIX}/${app.id}/Light/${id_v1}")
            if (dev != null) {
               String newDNI = dev.deviceNetworkId.replace("/Light/${id_v1}", "/Light/${id}")
               if (logEnable == true) log.debug "Found Hubitat device ${dev.displayName} for Hue light with V1 ID ${id_v1}. Changing DNI from ${dev.deviceNetworkId} to ${newDNI}..."
               dev.setDeviceNetworkId(newDNI)
               dev.createEventsFromMapV2([id_v1: "/lights/${id_v1}"])
            }
            else {
               if (logEnable == true) log.debug "No Hubitat device found for Hue light ${hueData.metadata?.name} with ID V1 ${id_v1} and ID V2 ${id}; skipping."
            }
         }
         else {
            if (logEnable == true) log.debug "Unable to convert light ${hueData.metadata?.name} with V2 id $id because no V1 ID found in Hue Bridge response"
         }
      }
      groupsData.each { Map hueData ->
         String id = hueData.id 
         String id_v1 = hueData.id_v1
         if (id_v1 != null) {
            id_v1 = id_v1 - "/groups/"
            DeviceWrapper dev = getChildDevice("${DNI_PREFIX}/${app.id}/Group/${id_v1}")
            if (dev != null) {
               String newDNI = dev.deviceNetworkId.replace("/Group/${id_v1}", "/Group/${id}")
               if (logEnable == true) log.debug "Found Hubitat device ${dev.displayName} for Hue group with V1 ID ${id_v1}. Changing DNI from ${dev.deviceNetworkId} to ${newDNI}..."
               dev.setDeviceNetworkId(newDNI)
               dev.createEventsFromMapV2([id_v1: "/groups/${id_v1}"])
            }
            else {
               if (logEnable == true) log.debug "No Hubitat device found for Hue group ${hueData.metadata?.name ?: ''} with ID V1 ${id_v1} and ID V2 ${id}; skipping."
            }
         }
         else {
            if (logEnable == true) log.debug "Unable to convert group ${hueData.metadata?.name} with V2 id $id because no V1 ID found in Hue Bridge response"
         }
      }
      scenesData.each { Map hueData ->
         String id = hueData.id 
         String id_v1 = hueData.id_v1
         if (id_v1 != null) {
            id_v1 = id_v1 - "/scenes/"
            DeviceWrapper dev = getChildDevice("${DNI_PREFIX}/${app.id}/Scene/${id_v1}")
            if (dev != null) {
               String newDNI = dev.deviceNetworkId.replace("/Scene/${id_v1}", "/Scene/${id}")
               if (logEnable == true) log.debug "Found Hubitat device ${dev.displayName} for Hue scene with V1 ID ${id_v1}. Changing DNI from ${dev.deviceNetworkId} to ${newDNI}..."
               dev.setDeviceNetworkId(newDNI)
               dev.createEventsFromMapV2([id_v1: "/scenes/${id_v1}"])
               String groupedLightId
               Map infoMap = roomsData.find { rd -> rd.id == hueData.group?.rid }
               if (infoMap == null) infoMap = zonesData.find { zd -> zd.id == hueData.group?.rid }
               if (infoMap != null) {
                  groupedLightId = infoMap.services?.find { svc -> svc.rtype == "grouped_light" }?.rid
               }
               if (groupedLightId) dev.setOwnerGroupIDV2(groupedLightId)
            }
            else {
               if (logEnable == true) log.debug "No Hubitat device found for Hue scene ${hueData.metadata?.name} with ID V1 ${id_v1} and ID V2 ${id}; skipping."
            }
         }
         else {
            if (logEnable == true) log.debug "Unable to convert scene ${hueData.metadata?.name} with V2 id $id because no V1 ID found in Hue Bridge response"
         }
      }
      // converting sensors is a bit different (old DNis had three Hue V1 IDs separated by '|' character, e.g., CCH/123/Sensor/31|32|33)
      motionData.each { Map hueData ->
         String id = hueData.id 
         String id_v1 = hueData.id_v1
         if (id_v1 != null) {
            id_v1 = id_v1 - "/sensors/"
               DeviceWrapper dev = getChildDevices().find { DeviceWrapper d ->
                  d.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Sensor/") &&
                  id_v1 in d.deviceNetworkId.tokenize('/')[-1].tokenize('|')
               }
            if (dev != null) {
               String lastPart = dev.deviceNetworkId.tokenize('/')[-1]
               String newDNI = dev.deviceNetworkId.replace("/Sensor/${lastPart}", "/Sensor/${id}")
               if (logEnable == true) log.debug "Found Hubitat device ${dev.displayName} for Hue sensor with V1 ID ${id_v1}. Changing DNI from ${dev.deviceNetworkId} to ${newDNI}..."
               dev.setDeviceNetworkId(newDNI)
            }
            else {
               if (logEnable == true) log.debug "No Hubitat device found for Hue sensor with ID V1 ${id_v1} and ID V2 ${id}; skipping."
            }
         }
         else {
            if (logEnable == true) log.debug "Unable to convert sensor with V2 id $id because no V1 ID found in Hue Bridge response"
         }
      }
      state.useV2UpgradeCompleted = true // indicate conversion complete so does not try every time
   }
   else {
      log.warn "Unable to upgrade to V2 DNIs. HTTP ${resp.status}. Error(s): ${resp.error}. Data: ${resp.data}"
   }
}

// Used to be saved in state but removed because not necessary--can retrieve easily:
/** Returns last 6 of MAC address, similar to built-in integration **/
String getBridgeId() {
   if (state.bridgeMAC) {
      return state.bridgeMAC.drop(6) // return last 6 (drop first 6) of MAC
   }
   else {
      log.warn "getBridgeId() called but no MAC saved; cannot retrieve ID"
   }
}

void initialize() {
   log.debug "initialize()"
   unschedule()
   state.remove("discoveredBridges")
   if (settings.useSSDP == true || settings["useSSDP"] == null) {
      if (settings["keepSSDP"] != false) {
         if (logEnable == true) log.debug "Subscribing to SSDP..."
         subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", "ssdpHandler")
         schedule("${Math.round(Math.random() * 59)} ${Math.round(Math.random() * 59)} 6 ? * * *",
               "periodicSendDiscovery")
      }
      else {
         if (logEnable == true) log.debug "Not subscribing to SSDP..."
         unsubscribe("ssdpHandler")
         unschedule("periodicSendDiscovery")
      }
      subscribe(location, "systemStart", "hubRestartHandler")
      if (state.bridgeAuthorized) {
         // Do discovery if user clicks "Done" (but wait a bit in case other data also being fetched...)
         runIn(7, "sendBridgeDiscoveryCommandIfSSDPEnabled")
      }
   }
   else {
      unsubscribe("ssdpHandler")
      unschedule("periodicSendDiscovery")
   }

   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }

   if (settings.useEventStream == true) {
      DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
      if (bridge != null) {
         if (state.useV2 == true) bridge.connectEventStream()
         String bridgeSwVersion = bridge.getDataValue("swversion")
         if (bridgeSwVersion == null) {
            sendBridgeInfoRequest()
            // TODO: Re-time this and retryUpgradeCCHV1... method or figure out some way to avoid unschedule!
            runIn(15, "initialize") // re-check version after has time to fetch in case upgrading from old version without this value
         }
         else if (bridgeSwVersion.isInteger() && Integer.parseInt(bridgeSwVersion) >= minV2SwVersion) {
            state.useV2 = true
         }
      }
   }
   else {
      // probably not necessary right now but could be if ever re-allow "downgrade" to V1 API:
      bridge?.disconnectEventStream()
   }

   scheduleRefresh()
}

void scheduleRefresh() {
   if (logEnable == true) log.debug "scheduleRefresh()"
   Integer pollInt = (settings.pollInterval instanceof Number) ? (settings.pollInterval as Integer) : (Integer.parseInt(settings.pollInterval ?: "0"))
   // If change polling options in UI, may need to modify some of these cases:
   switch (pollInt) {
      case 0:
         if (logEnable == true) log.debug "Polling disabled; not scheduling"
         break
      case 1..59:
         if (logEnable == true) log.debug "Scheduling polling every ${pollInt} seconds"
         schedule("${Math.round(Math.random() * pollInt)}/${pollInt} * * ? * * *", "refreshBridge")
         break
      case 60..119:
         if (logEnable == true) log.debug "Scheduling polling every 1 minute"
         runEvery1Minute("refreshBridge")
         break
      case 120..179:
         if (logEnable == true) log.debug "Scheduling polling every 2 minutes"
         schedule("${Math.round(Math.random() * 59)} */2 * ? * * *", "refreshBridge")
         runEvery2Minutes("refreshBridge")
         break
      case 180..299:
         if (logEnable == true) log.debug "Scheduling polling every 3 minutes"
         schedule("${Math.round(Math.random() * 59)} */3 * ? * * *", "refreshBridge")
         break
      case 300..1799:
         if (logEnable == true) log.debug "Scheduling polling every 5 minutes"
         runEvery5Minutes("refreshBridge")
         break
      case 1800..3599:
         if (logEnable == true) log.debug "Scheduling polling every 30 minutes"
         runEvery30Minutes("refreshBridge")
         break
      default:
         if (logEnable == true) log.debug "Scheduling polling every hour"
         runEvery1Hour("refreshBridge")
   }
}

void sendBridgeDiscoveryCommand() {
    sendHubCommand(new hubitat.device.HubAction("lan discovery ssdpTerm.urn:schemas-upnp-org:device:basic:1",
                   hubitat.device.Protocol.LAN))
    state.lastDiscoCommand = now()
}

/** Sends SSDP discovery command, optionally checking if was done in last few minutes and ignoring if so;
    intended to be called by child devices (e.g., Bridge) if notice problems suggesting Bridge IP may have
    changed
*/
void sendBridgeDiscoveryCommandIfSSDPEnabled(Boolean checkIfRecent=true) {
   if (logEnable == true) log.debug("sendBridgeDiscoveryCommandIfSSDPEnabled($checkIfRecent)")
   if (settings.useSSDP != false) {
      if (checkIfRecent == true) {
         Long lastDiscoThreshold = 300000 // Start with 5 minutes
         if (state.failedDiscos >= 3 && state.failedDiscos < 4) lastDiscoThreshold = 600000 // start trying every 5 min
         else if (state.failedDiscos >= 4 && state.failedDiscos < 6) lastDiscoThreshold = 1200000 // gradually increase interval if keeps failing...
         else if (state.failedDiscos >= 6 && state.failedDiscos < 18) lastDiscoThreshold = 3600000 // 1 hour now
         else lastDiscoThreshold =  7200000 // cap at 2 hr if been more than ~12 hr without Bridge
         if (!(state.lastDiscoCommand) || (now() - state.lastDiscoCommand >= lastDiscoThreshold)) {
            sendBridgeDiscoveryCommand()
         }
      }
      else {
         sendBridgeDiscoveryCommand()
      }
   }
}

void hubRestartHandler(evt) {
   runIn(20, "sendBridgeDiscoveryCommandIfSSDPEnabled")
}

// Scheduled job handler; if using SSDP, schedules to run once a day just in case
void periodicSendDiscovery(evt) {
   sendBridgeDiscoveryCommandIfSSDPEnabled(true)
}

void debugOff() {
   log.warn "Disabling debug logging"
   app.updateSetting("logEnable", [value:"false", type:"bool"])
}

def pageFirstPage() {
   state.authRefreshInterval = 5
   state.discoTryCount = 0
   state.authTryCount = 0
   // Shouldn't happen with installOnOpen: true, but just in case:
   if (app.getInstallationState() == "INCOMPLETE") {
      dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
         section() {
            paragraph "Please select \"Done\" to finish installation.<br>Then, re-open to set up your Hue Bridge."
         }
      }
   }
   // Show "real" pages otherwise, depending on state:
   else {
      if (state.bridgeLinked) {
         return pageManageBridge()
      }
      else {
         return pageAddBridge()
      }
   }
}

def pageAddBridge() {
   if (logEnable == true) log.debug "pageAddBridge()..."
   Integer discoMaxTries = 60
   if (settings.boolReauthorize) {
      state.remove("bridgeAuthorized")
      app.removeSetting("boolReauthorize")
   }
   if (settings.useSSDP != false && state.discoTryCount < 5) {
      if (logEnable == true) log.debug "Subscribing to and sending SSDP discovery..."
      subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
      sendBridgeDiscoveryCommand()
   }  
   dynamicPage(name: "pageAddBridge", uninstall: true, install: false,
               refreshInterval: ((settings.useSSDP == false || selectedDiscoveredBridge) ? null : state.authRefreshInterval),
               nextPage: "pageLinkBridge") {
      section("Add Hue Bridge") {
         // TODO: Switch to mDNS, leave SSDP as legacy option for v1 bridges if needed; consider making separate setting?
         input name: "useSSDP", type: "bool", title: "Discover Hue Bridges automatically", defaultValue: true, submitOnChange: true
         if (settings.useSSDP != false) {
            if (!(state.discoveredBridges)) {
               paragraph "Please wait while Hue Bridges are discovered..."
               paragraph "Your Hue devices must already be configured on your Hue Bridge (for example, using the Hue mobile app)."
               paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
            }
            else {
               // TODO: show on separate page since checked devices will uncheck on page refresh
               input name: "selectedDiscoveredBridge", type: "enum", title: "Discovered bridges:", options: state.discoveredBridges,
                     multiple: false, submitOnChange: true
               if (!(settings.selectedDiscoveredBridge)) {
                  if (!(state.discoveredBridges)) paragraph("Please wait while we discover Hue Bridges on your network...")
                  else paragraph("Select a Hue Bridge above to begin adding it to your Hubitat Elevation hub.")
               }
               else {
                  if (/*!state.bridgeLinked ||*/ !state.bridgeAuthorized)
                        paragraph("<strong>Press the button on your Bridge</strong> and then select \"Next\" to continue.")
                  else
                        paragraph("Select \"Next\" to continue.")
                  }
            }
            input name: "btnDiscoBridgeRefresh", type: "button", title: "Refresh Bridge List"
            if (state.discoTryCount > discoMaxTries && !(state.discoveredBridges)) {
               state.remove('authRefreshInterval')
               paragraph "No bridges have been found. Please go back and try again, or consider using manual setup."
            }
            paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
         }
         else { 
               unsubscribe() // remove or modify if ever subscribe to more than SSDP above
               input name: "bridgeIP", type: "string", title: "Hue Bridge IP address:", required: false, defaultValue: null, submitOnChange: true
               input name: "customPort", type: "number", title: "Custom port? (Blank for default)"
               if (settings.bridgeIP && !state.bridgeLinked || !state.bridgeAuthorized) {
                  paragraph "<strong>Press the button on your Hue Bridge,</strong> and then select \"Next\" to continue."
               }
         }
         // Hack-y way to hide/show Next button if still waiting:
         if ((settings.useSSDP != false && settings.selectedDiscoveredBridge) || settings.bridgeIP) {
            paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
         }
         else {
            paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
         }
      }
   }
}

def pageReAddBridge() {
   if (logEnable == true) log.debug "pageReAddBridge()..."
   state.authRefreshInterval = 5
   state.discoTryCount = 0
   state.authTryCount = 0
   if (settings.useSSDP == true || settings.useSSDP == null && state.discoTryCount < 5) {
      if (logEnable == true) log.debug "Subscribing to and sending SSDP discovery..."
      subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", "ssdpHandler")
      sendBridgeDiscoveryCommand()
   }
   state.bridgeLinked = false
   dynamicPage(name: "pageReAddBridge", uninstall: true, install: false, nextPage: pageAddBridge) {  
      section("Options") {
         paragraph("You have chosen to edit the Bridge IP address (if automatic discovery is not selected) or " +
               "re-discover the Bridge (if discovery is enabled; usually happens automatically). The Bridge you choose " +
               "must be the same as the one with which the app and devices were originally configured. To switch to " +
               "a completely different Bridge, install a new instance of the app instead.")
         paragraph("If you see \"unauthorized user\" errors, try enabling the option below. In most cases, you can " +
               "continue without this option. In all cases, an existing Bridge device will be either updated to match " +
               "your selection (on the next page) or re-created if it does not exist.")
         input name: "boolReauthorize", type: "bool", title: "Request new Bridge username (re-authorize)", defaultValue: false
         paragraph "<strong>Select \"Next\" to continue.</strong>"
      }
   }
}

def pageLinkBridge() {
   if (logEnable == true) log.debug "Beginning bridge link process..."
   String ipAddress = (settings.useSSDP != false) ? settings.selectedDiscoveredBridge : settings.bridgeIP
   state.ipAddress = ipAddress
   if (logEnable == true) log.debug "  IP address = ${state.ipAddress}"
   Integer authMaxTries = 35
   if (!(settings.useSSDP == false)) {
      if (!(settings.selectedDiscoveredBridge)) {
         dynamicPage(name: "pageLinkBridge", uninstall: true, install: false, nextPage: "pageAddBridge") {
            section() {
               paragraph 'No Bridge selected. Select "Next" to return to the bridge selection page, and try again.'
            }
         }
      }
   }
   dynamicPage(name: "pageLinkBridge", refreshInterval: state.authRefreshInterval, uninstall: true, install: false,
               nextPage: "pageFirstPage") {
      section("Linking Hue Bridge") {
         if (!(state.bridgeAuthorized)) {
               log.debug "Attempting Hue Bridge authorization; attempt number ${state.authTryCount+1}"
               if (settings.useSSDP) sendUsernameRequest()
               else sendUsernameRequest("http", settings["customPort"] as Integer ?: 80)
               state.authTryCount += 1
               paragraph "Waiting for Bridge to authorize. This page will automatically refresh."
               if (state.authTryCount > 5 && state.authTryCount < authMaxTries) {
                  String strParagraph = "Still waiting for authorization. Please make sure you pressed " +
                     "the button on the Hue Bridge."
                  if (state.authTryCount > 10) {
                     if (!settings.useSSDP) strParagraph + " Also, verify that your Bridge IP address is correct: ${state.ipAddress}"
                  }
                  paragraph(strParagraph)
               }
               if (state.authTryCount >= authMaxTries) {
                  state.remove('authRefreshInterval')
                  paragraph("<b>Authorization timed out.<b> Select \"Next\" to return to the beginning, " + 
                           "check your settings, and try again.")
               }
         }
         else {
               if (getChildDevice("${DNI_PREFIX}/${app.id}")) {
                  state.bridgeLinked = true
               }
               if (!state.bridgeLinked || !getChildDevice("${DNI_PREFIX}/${app.id}")) {
                  log.debug "Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat..."
                  paragraph "Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat..."
                  if (settings["useSSDP"]) sendBridgeInfoRequest(createBridge: true)
                  else sendBridgeInfoRequest(createBridge: true, ip: settings.bridgeIP ?: state.ipAddress, port: settings.customPort as Integer ?: null)
               }
               else {
                  if (logEnable == true) log.debug("Bridge already linked; skipping Bridge device creation")
                  if (state.bridgeLinked && state.bridgeAuthorized) {
                     state.remove('discoveredBridges')
                     state.remove('authRefreshInterval')
                     //app.clearSetting('selectedDiscoveredBridge')
                     paragraph "<b>Your Hue Bridge has been linked!</b> Select \"Next\" to begin adding lights " +
                                 "and other devices from the Hue Bridge to your hub."
                  }
                  else {
                     paragraph "There was a problem authorizing or linking your Hue Bridge. Please start over and try again."
                  }
               }
         }
         // Hack-y way to hide/show Next button if still waiting:
         if ((state.authTryCount >= authMaxTries) || (state.bridgeAuthorized && getChildDevice("${DNI_PREFIX}/${app.id}"))) {
            paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
         }
         else {
            paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
         }
      }
   }
}

def pageSupportOptions() {
   dynamicPage(name: "pageSupportOptions", uninstall: true, install: false, nextPage: "pageManageBridge") {
      // section("Temporary Fixes") {
      //    paragraph "Fix V2 group (room/zone) device DNIs from older beta of new app version (this option will be removed in next release):"
      //    input name: "btnFixGroupDNIsTEMP", type: "button", title: "Fix Group DNIs from Old Beta"
      // }
      section("Debugging Information") {
         paragraph "Enable debug logging on Bridge child device (will remain enabled until disabled on device):"
         input name: "btnEnableBridgeLogging", type: "button", title: "Enable Debug Logging on Bridge"

         paragraph 'Writes information about child devices (created from integration on hub) to <a href="/logs">Logs</a>:'
         input name: "btnLogChildDeviceInfo", type: "button", title: "Log Child Device Info"

         paragraph 'The following options fetch data (list of lights, groups, scenes, etc.) from the Bridge ' +
            'and write a summary of that data to <a href="/logs">Logs</a>.  Suggested use is to run the fetch (first ' +
            'button in pair), wait at least a few seconds, then run the log of the fetched, cached data (second button in pair).'
         paragraph "Lights:", width: 3
         input name: "btnFetchLightsInfo", type: "button", title: "Fetch Lights Info", width: 4
         input name: "btnLogLightsInfo", type: "button", title: "Log Lights Cache", width: 5
         paragraph "Groups:", width: 3
         input name: "btnFetchGroupsInfo", type: "button", title: "Fetch Groups Info", width: 4
         input name: "btnLogGroupsInfo", type: "button", title: "Log Groups Cache", width: 5
         paragraph "Scenes:", width: 3
         input name: "btnFetchScenesInfo", type: "button", title: "Fetch Scenes Info", width: 4
         input name: "btnLogScenesInfo", type: "button", title: "Log Scenes Cache", width: 5
         if (state.useV2) {
            paragraph "Sensors:", width: 3
            input name: "btnFetchSensorsInfo", type: "button", title: "Fetch Motion Sensors Info", width: 4
            input name: "btnLogSensorsInfo", type: "button", title: "Log Motion Sensors Cache", width: 5
            paragraph "Buttons:", width: 3
            input name: "btnFetchButtonsInfo", type: "button", title: "Fetch Buttons Info", width: 4
            input name: "btnLogButtonsInfo", type: "button", title: "Log Buttons Cache", width: 5
         }
      }

      section("Advanced Tools") {
         String introText = "Use these options only with guidance from the developer or community.hubitat.com. See: https://docs2.hubitat.com/en/apps/hue-bridge-integration"         
         paragraph introText

         paragraph "Retry enabling V2 API (server-sent events/SSE/eventstream) option:"
         input name: "btnRetryV2APIEnable", type: "button", title: "Retry Hue V2 API Migration", submitOnChange: true
      }
   }
}

def pageManageBridge() {
   if (settings["newBulbs"]) {
      if (logEnable == true) log.debug "New bulbs selected. Creating..."
      createNewSelectedBulbDevices()
   }
   if (settings["newGroups"]) {
      if (logEnable == true) log.debug "New groups selected. Creating..."
      createNewSelectedGroupDevices()
   }
   if (settings["newScenes"]) {
      if (logEnable == true) log.debug "New scenes selected. Creating..."
      createNewSelectedSceneDevices()
   }
   if (settings["newSensors"]) {
      if (logEnable == true) log.debug "New sensors selected. Creating..."
      createNewSelectedSensorDevices()
   }
   if (settings["newButtons"]) {
      if (logEnable == true) log.debug "New button devices selected. Creating..."
      createNewSelectedButtonDevices()
   }
   // General cleanup in case left over from discovery:
   state.remove("authTryCount")
   state.remove("discoTryCount")
   // More cleanup...
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge != null) {
      bridge.clearBulbsCache()
      bridge.clearGroupsCache()
      bridge.clearRoomsCache()
      bridge.clearZonesCache()
      bridge.clearScenesCache()
      bridge.clearSensorsCache()
      bridge.clearButtonsCache()
   }
   else {
      log.warn "Bridge device not found!"
   }
   state.remove("sceneFullNames")
   state.remove("addedBulbs")
   state.remove("addedGroups")
   state.remove("addedScenes")
   state.remove("addedSensors")
   state.remove("addedButtons")

   dynamicPage(name: "pageManageBridge", uninstall: true, install: true) {  
      section("Manage Hue Bridge Devices:") {
         href(name: "hrefSelectLights", title: "Select Lights",
               description: "", page: "pageSelectLights")
         href(name: "hrefSelectGroups", title: "Select Groups",
               description: "", page: "pageSelectGroups")
         href(name: "hrefSelectScenes", title: "Select Scenes",
               description: "", page: "pageSelectScenes")
         if (state.useV2) {
            href(name: "hrefSelectMotionSensors", title: "Select Motion Sensors",
                  description: "", page: "pageSelectMotionSensors")
            href(name: "hrefSelectButtons", title: "Select Button Devices",
                  description: "", page: "pageSelectButtons")
         }
      }       
      section("Other Options:") {
         if (!(state.useV2)) {
            input name: "useEventStream", type: "bool", title: "Prefer V2 Hue API (EventStream/Server-Sent Events) if available. NOTE: Cannot be disabled once enabled.", defaultValue: true
            paragraph "<small>The V2 Hue API allows instant status updates from devices with no polling required. It is available on V2 bridges with firmware starting around late 2021 (though current firmware is recommended) and " +
               "is necessary to use for sensor and button devices. This setting cannot be disabled once enabled (without restoring a hub backup or if you have not yet selected a Hue Bridge to link with).</small>"
         }
         else {
            paragraph "NOTE: Hue API V2 use is enabled. (This setting cannot be reverted once enabled.)"
            input name: "useV1Polling", type: "bool", title: "Use V1 API for polling on Bridge (if polling enabled), even if V2 API is enabled (recommended)", defaultValue: true
         }
         input name: "pollInterval", type: "enum", title: "Poll bridge every...",
            options: [0:"Disabled", 15:"15 seconds", 20:"20 seconds", 30:"30 seconds", 45:"45 seconds", 60:"1 minute (default)", 120:"2 minutes",
                      180:"3 minutes", 300:"5 minutes", 420:"7 minutes", 6000:"10 minutes", 1800:"30 minutes", 3600:"1 hour", 7200:"2 hours", 18000:"5 hours"],
                      defaultValue: 60
         paragraph "<small>NOTE: Polling is recommended if using the V1 Hue API, as changes made outside Hubitat will not be reflected without this feature enabled. " +
            "Polling is still recommmended if using the V2 API, but you may consider increasing the polling interval if the instant status updates for most device attributes " +
            "meet your needs. Consult the documentation (help icon in top right) for additional details."
         input name: "boolCustomLabel", type: "bool", title: "Customize the name of this app", defaultValue: false, submitOnChange: true
         if (settings.boolCustomLabel) label title: "Custom name for this app", required: false
         input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      }
      section("Advanced Options", hideable: true, hidden: true) {
         href(name: "hrefReAddBridge", title: "Edit Bridge IP, re-authorize, or re-discover...",
               description: "", page: "pageReAddBridge")
         // Consider hiding this page in UI if becomes problem?
         href(name: "hrefSupportOptions", title: "Advanced Debug Options...",
               description: "", page: "pageSupportOptions")
         if (settings.useSSDP != false) {
            input name: "keepSSDP", type: "bool", title: "Remain subscribed to Bridge discovery requests (recommended to keep enabled if Bridge has dynamic IP address)",
               defaultValue: true
         }
         if (!state.useV2) input name: "showAllScenes", type: "bool", title: "Allow adding scenes not associated with rooms/zones" // only has effect in when using V1 API (V2 only fetches rooms and zones to start)
      }
   }
}

def pageSelectLights() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   state.useV2 ? bridge.getAllBulbsV2() : bridge.getAllBulbsV1()
   List arrNewBulbs = []
   Map bulbCache = bridge.getAllBulbsCache()
   List<DeviceWrapper> unclaimedBulbs = getChildDevices().findAll { it.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Light/") }
   dynamicPage(name: "pageSelectLights", refreshInterval: bulbCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedBulbs = [:]  // To be populated with lights user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (bulbCache) {
         bulbCache.each { cachedBulb ->
            DeviceWrapper bulbChild = unclaimedBulbs.find { b -> b.deviceNetworkId == "${DNI_PREFIX}/${app.id}/Light/${cachedBulb.key}" }
            if (bulbChild) {
               addedBulbs.put(cachedBulb.key, [hubitatName: bulbChild.displayName, hubitatId: bulbChild.id, hueName: cachedBulb.value?.name])
               unclaimedBulbs.removeElement(bulbChild)
            } else {
               Map newBulb = [:]
               newBulb << [(cachedBulb.key): (cachedBulb.value.name)]
               arrNewBulbs << newBulb
            }
         }
         arrNewBulbs = arrNewBulbs.sort { a, b ->
            // Sort by bulb name (default would be hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedBulbs = addedBulbs.sort { it.value.hubitatName }
      }
      if (!bulbCache) {
         section("Discovering bulbs/lights. Please wait...") {
            paragraph "Select \"Refresh\" if you see this message for an extended period of time and do not see devices you have added to Hue."
            input name: "btnBulbRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Lights") {
            input name: "newBulbs", type: "enum", title: "Select Hue lights to add:",
                  multiple: true, options: arrNewBulbs
            input name: "boolAppendBulb", type: "bool", title: "Append \"(Hue Light)\" to Hubitat device name"
            paragraph ""
            paragraph "Previously added lights${addedBulbs ? ' <span style=\"font-style: italic\">(Hue Bridge device name in parentheses)</span>' : ''}:"
            if (addedBulbs) {
               StringBuilder bulbText = new StringBuilder()
               bulbText << "<ul>"
               addedBulbs.each {
                  bulbText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  bulbText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_Light_ID", type: "button", title: "Remove", width: 3)
               }
               bulbText << "</ul>"
               paragraph(bulbText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added lights found</span>"
            }
            if (unclaimedBulbs) {
               paragraph "Hubitat light devices not found on Hue:"
               StringBuilder bulbText = new StringBuilder()
               bulbText << "<ul>"
               unclaimedBulbs.each {
                  bulbText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               bulbText << "</ul>"
               paragraph(bulbText.toString())
            }
         }
         section("Rediscover Bulbs") {
               paragraph("If you added new lights to the Hue Bridge and do not see them above, select the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnBulbRefresh", type: "button", title: "Refresh Bulb List", submitOnChange: true
         }
      }
   }
}

def pageSelectGroups() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   state.useV2 ? bridge.getAllGroupsV2() : bridge.getAllGroupsV1()
   List arrNewGroups = []
   Map groupCache = bridge.getAllGroupsCache() ?: [:] // has everything for V1, only All Hue Lights group for V2
   if (state.useV2) {  // add room and zones for V2:
      groupCache += (bridge.getAllRoomsCache() ?: [:]) + (bridge.getAllZonesCache() ?: [:])
   }
   List<DeviceWrapper> unclaimedGroups = getChildDevices().findAll { it.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Group/") }
   dynamicPage(name: "pageSelectGroups", refreshInterval: groupCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedGroups = [:]  // To be populated with groups user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (groupCache) {
         groupCache.each { cachedGroup ->
            DeviceWrapper groupChild = unclaimedGroups.find { grp -> grp.deviceNetworkId == "${DNI_PREFIX}/${app.id}/Group/${cachedGroup.key}" }
            if (groupChild) {
               addedGroups.put(cachedGroup.key, [hubitatName: groupChild.displayName, hubitatId: groupChild.id, hueName: cachedGroup.value?.name])
               unclaimedGroups.removeElement(groupChild)
            }
            else {
               Map newGroup = [:]
               newGroup << [(cachedGroup.key): (cachedGroup.value.name)]
               arrNewGroups << newGroup
            }
         }
         arrNewGroups = arrNewGroups.sort {a, b ->
               // Sort by group name (default would be Hue ID)
               a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
               }
         addedGroups = addedGroups.sort { it.value.hubitatName }
      }
      if (!groupCache) { 
         section("Discovering groups. Please wait...") {
               paragraph "Select \"Refresh\" if you see this message for an extended period of time and do not see devices you have added to Hue."
               input name: "btnGroupRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Groups") {
            input name: "newGroups", type: "enum", title: "Select Hue groups to add:",
                  multiple: true, options: arrNewGroups
            input name: "boolAppendGroup", type: "bool", title: "Append \"(Hue Group)\" to Hubitat device name"
            paragraph ""
            paragraph "Previously added groups${addedGroups ? ' <span style=\"font-style: italic\">(Hue Bridge group name in parentheses)</span>' : ''}:"
               if (addedGroups) {
                  StringBuilder grpText = new StringBuilder()
                  grpText << "<ul>"
                  addedGroups.each {
                     grpText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                     grpText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                     //input(name: "btnRemove_Group_ID", type: "button", title: "Remove", width: 3)
                  }
                  grpText << "</ul>"
                  paragraph(grpText.toString())
               }
               else {
                  paragraph "<span style=\"font-style: italic\">No added groups found</span>"
               }
               if (unclaimedGroups) {
                  paragraph "Hubitat group devices not found on Hue:"
                  StringBuilder grpText = new StringBuilder()
                  grpText << "<ul>"
                  unclaimedGroups.each {
                     grpText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
                  }
                  grpText << "</ul>"
                  paragraph(grpText.toString())
               }
         }
         section("Rediscover Groups") {
            paragraph("If you added new groups (rooms or zones) to the Hue Bridge and do not see them above or see groups on " +
               "your hub that were not found on Hue (but you believe should be), select the button " +
               "below to retrieve new information from the Bridge.")
            input name: "btnGroupRefresh", type: "button", title: "Refresh Group List", submitOnChange: true
         }
      }
   }    
}

def pageSelectScenes() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   state.useV2 ? bridge.getAllScenesV2() : bridge.getAllScenesV1()
   List arrNewScenes = []
   Map sceneCache = bridge.getAllScenesCache()
   Map groupCache 
   if (state.useV2 == true) {
      groupCache = (bridge.getAllRoomsCache() ?: [:]) + (bridge.getAllZonesCache() ?: [:])
   }
   else {
      groupCache = bridge.getAllGroupsCache()
   }
   List<DeviceWrapper> unclaimedScenes = getChildDevices().findAll { it.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Scene/") }
   Map grps = [:]
   groupCache?.each { grps << [(it.value.roomId ?: it.value.zoneId ?: it.key) : (it.value.name)] }
   dynamicPage(name: "pageSelectScenes", refreshInterval: sceneCache ? 0 : 7, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedScenes = [:]  // To be populated with scenes user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (sceneCache) {
         state.sceneFullNames = [:]
         sceneCache.each { sc ->
            DeviceWrapper sceneChild = unclaimedScenes.find { scn -> scn.deviceNetworkId == "${DNI_PREFIX}/${app.id}/Scene/${sc.key}" }
            if (sceneChild) {
               addedScenes.put(sc.key, [hubitatName: sceneChild.displayName, hubitatId: sceneChild.id, hueName: sc.value?.name])
               unclaimedScenes.removeElement(sceneChild)
            }
            else {
               Map newScene = [:]
               String sceneName = sc.value.name
               if (sc.value.group) {
                  grps.each { g ->
                     def k = g.key
                     if (k && k == sc.value.group) {
                        def v = g.value
                        // "Group Name - Scene Name" naming convention:
                        if (v) sceneName = "$v - $sceneName"
                     }
                  }
               }
               if (sc.value?.group || settings["showAllScenes"]) {
                  state.sceneFullNames.put(sc.key, sceneName)
                  newScene << [(sc.key): (sceneName)]
                  arrNewScenes << newScene
               }
            }
         }
         arrNewScenes = arrNewScenes.sort {a, b ->
            // Sort by group name (default would be Hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedScenes = addedScenes.sort { it.value.hubitatName }
      }

      if (!sceneCache) {
         section("Discovering scenes. Please wait...") {
            paragraph "Select \"Refresh\" if you see this message for an extended period of time and do not see devices you have added to Hue."
            input name: "btnSceneRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Scenes") {
            input name: "newScenes", type: "enum", title: "Select Hue scenes to add:",
                  multiple: true, options: arrNewScenes
            paragraph ""
            paragraph "Previously added groups${addedScenes ? ' <span style=\"font-style: italic\">(Hue scene name [without room/zone] in parentheses)</span>' : ''}:"
            if (addedScenes) {
               StringBuilder scenesText = new StringBuilder()
               scenesText << "<ul>"
               addedScenes.each {
                  scenesText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  scenesText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_Group_ID", type: "button", title: "Remove", width: 3)
               }
               scenesText << "</ul>"
               paragraph(scenesText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added scenes found</span>"
            }
            if (unclaimedScenes) {
               paragraph "Hubitat scene devices not found on Hue:"
               StringBuilder scenesText = new StringBuilder()
               scenesText << "<ul>"
               unclaimedScenes.each {
                  scenesText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               scenesText << "</ul>"
               paragraph(scenesText.toString())
            }
         }
         section("Rediscover Scenes") {
            paragraph "If you added new scenes to the Hue Bridge and do not see them above or if room/zone names are " +
                     "missing from scenes (and assigned to a room or zone in the Hue app), " +
                     "select the button below to retrieve new information from the Bridge."
            input name: "btnSceneRefresh", type: "button", title: "Refresh Scene List", submitOnChange: true
         }
      }
   }
}

def pageSelectMotionSensors() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (!state.useV2) log.warn "Attempting to retrieve sensor list, but app not configured to use V2 API. Verify this setting."
   bridge.getAllSensorsV2()
   List arrNewSensors = []
   Map sensorCache = bridge.getAllSensorsCache()
   List<DeviceWrapper> unclaimedSensors = getChildDevices().findAll { it.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Sensor/") }
   dynamicPage(name: "pageSelectMotionSensors", refreshInterval: sensorCache ? null : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedSensors = [:]  // To be populated with sensors user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (sensorCache) {
         sensorCache.sort { it.value }
         sensorCache.each { cachedSensor -> // key = id, value = name
            DeviceWrapper sensorChild = unclaimedSensors.find { s -> s.deviceNetworkId == "${DNI_PREFIX}/${app.id}/Sensor/${cachedSensor.key}" }
            if (sensorChild) {
               addedSensors.put(cachedSensor.key, [hubitatName: sensorChild.displayName, hubitatId: sensorChild.id, hueName: cachedSensor.value])
               unclaimedSensors.removeElement(sensorChild)
            } else {
               Map newSensor = [:]
               // eventually becomes input for setting/dropdown; Map format is [id: displayName]
               newSensor << [(cachedSensor.key): (cachedSensor.value)]
               arrNewSensors << newSensor
            }
         }
         // arrNewSensors = arrNewSensors.sort { a, b ->
         //    // Sort by sensor name (default would be hue ID)
         //    a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         // }
         addedSensors = addedSensors.sort { it.value.hubitatName }
      }
      if (!sensorCache) {
         section("Discovering sensors. Please wait...") {
            paragraph "Select \"Refresh\" if you see this message for an extended period of time and do not see devices you have added to Hue."
            input name: "btnSensorRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Sensors") {
            input name: "newSensors", type: "enum", title: "Select Hue motion sensors to add:",
                  multiple: true, options: arrNewSensors
            paragraph ""
            paragraph "Previously added sensors${addedSensors ? ' <span style=\"font-style: italic\">(Hue Bridge device name in parentheses)</span>' : ''}:"
            if (addedSensors) {
               StringBuilder sensorText = new StringBuilder()
               sensorText << "<ul>"
               addedSensors.each {
                  sensorText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  sensorText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_Sensor_ID", type: "button", title: "Remove", width: 3)
               }
               sensorText << "</ul>"
               paragraph(sensorText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added sensors found</span>"
            }
            if (unclaimedSensors) {
               paragraph "Hubitat sensor devices not found on Hue:"
               StringBuilder sensorText = new StringBuilder()
               sensorText << "<ul>"
               unclaimedSensors.each {
                  sensorText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               sensorText << "</ul>"
               paragraph(sensorText.toString())
            }
         }
         section("Rediscover Sensors") {
               paragraph("If you added new sensors to the Hue Bridge and do not see them above, select the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnSensorRefresh", type: "button", title: "Refresh Sensor List", submitOnChange: true
         }
      }
   }
}

def pageSelectButtons() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   bridge.getAllButtonsV2()
   List arrNewButtons = []
   Map buttonCache = bridge.getAllButtonsCache()
   List<DeviceWrapper> unclaimedButtons = getChildDevices().findAll { it.deviceNetworkId.startsWith("${DNI_PREFIX}/${app.id}/Button/") }
   dynamicPage(name: "pageSelectButtons", refreshInterval: buttonCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedButtons = [:]  // To be populated with buttons user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (buttonCache) {
         buttonCache.each { cachedButton ->
            DeviceWrapper buttonChild = unclaimedButtons.find { s -> s.deviceNetworkId == "${DNI_PREFIX}/${app.id}/Button/${cachedButton.key}" }
            if (buttonChild) {
               addedButtons.put(cachedButton.key, [hubitatName: buttonChild.displayName, hubitatId: buttonChild.id, hueName: cachedButton.value?.name])
               unclaimedButtons.removeElement(buttonChild)
            } else {
               Map newButton = [:]
               // eventually becomes input for setting/dropdown; Map format is [MAC: DisplayName]
               newButton << [(cachedButton.key): (cachedButton.value.name)]
               arrNewButtons << newButton
            }
         }
         arrNewButtons = arrNewButtons.sort { a, b ->
            // Sort by display name (default would be hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedButtons = addedButtons.sort { it.value.hubitatName }
      }
      if (!buttonCache) {
         section("Discovering buttons. Please wait...") {
            paragraph "Select \"Refresh\" if you see this message for an extended period of time and do not see devices you have added to Hue."
            input name: "btnButtonRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Button Devices") {
            if (!(state.useV2)) {
               paragraph "NOTE: You have not enabled the preference to use the Hue V2 API. Button devices will not work with this integration without this option enabled."
            }
            input name: "newButtons", type: "enum", title: "Select Hue button devices to add:",
                  multiple: true, options: arrNewButtons
            paragraph ""
            paragraph "Previously added buttons${addedButtons ? ' <span style=\"font-style: italic\">(Hue Bridge device name in parentheses)</span>' : ''}:"
            if (addedButtons) {
               StringBuilder buttonsText = new StringBuilder() 
               buttonsText << "<ul>"
               addedButtons.each {
                  buttonsText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  buttonsText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_Button_ID", type: "button", title: "Remove", width: 3)
               }
               buttonsText << "</ul>"
               paragraph(buttonsText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added button devices found</span>"
            }
            if (unclaimedButtons) {
               paragraph "Hubitat button devices not found on Hue:"
               StringBuilder buttonsText = new StringBuilder()
               buttonsText << "<ul>"
               unclaimedButtons.each {
                  buttonsText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               buttonsText << "</ul>"
               paragraph(buttonsText.toString())
            }
         }
         section("Rediscover Buttons") {
               paragraph("If you added new button devices to the Hue Bridge and do not see them above, select the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnButtonRefresh", type: "button", title: "Refresh Button List", submitOnChange: true
         }
      }
   }
}

/** Creates new Hubitat devices for new user-selected bulbs on lights-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedBulbDevices() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map bulbCache = bridge?.getAllBulbsCache()
   settings["newBulbs"].each {
      Map b = bulbCache.get(it)
      if (b) {
         try {
            if (logEnable == true) log.debug "Creating new device for Hue light ${it} (${b.name})"
            String devDriver = getDriverNameForDeviceType(b.type.toLowerCase())
            String devDNI = "${DNI_PREFIX}/${app.id}/Light/${it}"
            Map devProps = [name: (settings["boolAppendBulb"] ? b.name + " (Hue Bulb)" : b.name)]
            DeviceWrapper bulbDev = addChildDevice(NAMESPACE, devDriver, devDNI, devProps)
            if (bulbDev != null && state.useV2) bulbDev.createEventsFromMapV2([id_v1: b.id_v1])

         } catch (Exception ex) {
            log.error("Unable to create new device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for bulb $it: ID not found on Hue Bridge")
      }
   }
   bridge.clearBulbsCache()
   state.useV2 ? bridge.getAllBulbsV2() : bridge.getAllBulbsV1()
   app.removeSetting("newBulbs")
}

/** Creates new Hubitat devices for new user-selected groups on groups-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedGroupDevices() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map groupCache = bridge.getAllGroupsCache()
   if (state.useV2) groupCache += (bridge.getAllRoomsCache() ?: [:]) + (bridge.getAllZonesCache() ?: [:])
   settings["newGroups"].each {
      Map g = groupCache.get(it)
      if (g) {
         try {
            if (logEnable == true) log.debug("Creating new device for Hue group ${it} (${g.name})")
            String devDNI = "${DNI_PREFIX}/${app.id}/Group/${it}"
            Map devProps = [name: (settings["boolAppendGroup"] ? g.name + " (Hue Group)" : g.name)]
            DeviceWrapper grpDev = addChildDevice(NAMESPACE, DRIVER_NAME_GROUP, devDNI, devProps)
            if (grpDev != null && state.useV2) {
               if (g.groupedLightId != null) grpDev.setGroupedLightId(g.groupedLightId)
               grpDev.createEventsFromMapV2([id_v1: g.id_v1])
            }
         }
         catch (Exception ex) {
            log.error("Unable to create new group device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for group $it: ID not found on Hue Bridge")
      }
   }    
   bridge.clearGroupsCache()
   state.useV2 ? bridge.getAllGroupsV2() : bridge.getAllGroupsV1()
   if (useV1Polling != false) bridge.refreshV1()
   else bridge.refresh()
   app.removeSetting("newGroups")
}

/** Creates new Hubitat devices for new user-selected scenes on scene-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedSceneDevices() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (!bridge) log.error("Unable to find Bridge device")
   Map sceneCache = bridge?.getAllScenesCache()
   Map groupCache
   if (state.useV2) {  // get rooms and zones (which own scenes and are owned by a grouped_light service whose ID we need)
      groupCache = (bridge.getAllRoomsCache() ?: [:]) + (bridge.getAllZonesCache() ?: [:])
   }
   settings["newScenes"].each {
      Map sc = sceneCache.get(it)
      if (sc) {
         try {
               if (logEnable == true) log.debug "Creating new device for Hue group ${it} (state.sceneFullNames?.get(it) ?: sc.name)"
               String devDNI = "${DNI_PREFIX}/${app.id}/Scene/${it}"
               Map devProps = [name: (state.sceneFullNames?.get(it) ?: sc.name)]
               DeviceWrapper scDev = addChildDevice(NAMESPACE, DRIVER_NAME_SCENE, devDNI, devProps)
               if (scDev != null && state.useV2) {
                  scDev.createEventsFromMapV2([id_v1: sc.id_v1])
                  String groupedLightId = groupCache.find { it.value.roomId == sc.group || it.value.zoneId == sc.group}?.key
                  if (groupedLightId) scDev.setOwnerGroupIDV2(groupedLightId)
               }
         } catch (Exception ex) {
               log.error "Unable to create new scene device for $it: $ex"
         }
      } else {
         log.error "Unable to create new scene for scene $it: ID not found on Hue Bridge"
      }
   }
   bridge.clearScenesCache()
   //bridge.getAllScenesV1()
   app.removeSetting("newScenes")
   state.remove("sceneFullNames")
}

/** Creates new Hubitat devices for new user-selected sensors on sensor-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedSensorDevices() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map<String,String> sensorCache = bridge?.getAllSensorsCache()
   //log.trace "sensorCache = $sensorCache"
   settings.newSensors.each { String id ->
      String name = sensorCache.get(id)
      if (name) {
         //log.trace "name = $name"
         try {
            //log.trace "id = $id"
            if (logEnable == true) log.debug "Creating new device for Hue sensor ${id}: (${name})"
            String devDNI = "${DNI_PREFIX}/${app.id}/Sensor/${id}"
            Map devProps = [name: name]
            addChildDevice(NAMESPACE, DRIVER_NAME_MOTION, devDNI, devProps)
         }
         catch (Exception ex) {
            log.error "Unable to create new sensor device for $id: $ex"
         }
      } else {
         log.error "Unable to create new device for sensor $id: ID not found in Hue Bridge cache"
      }
   }
   bridge.clearSensorsCache()
   // do we need to do this?
   //bridge.getAllSensorsV2()
   //bridge.refresh()
   app.removeSetting("newSensors")
}

/** Creates new Hubitat devices for new user-selected buttons on button-device-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedButtonDevices() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map buttonCache = bridge?.getAllButtonsCache()
   settings["newButtons"].each {
      Map b = buttonCache.get(it)
      if (b) {
         try {
            if (logEnable == true) log.debug "Creating new device for Hue button device ${it} (${b.name})"
            String devDNI = "${DNI_PREFIX}/${app.id}/Button/${it}"
            Map devProps = [name: b.name]
            DeviceWrapper d = addChildDevice(NAMESPACE, DRIVER_NAME_BUTTON, devDNI, devProps)
            if (d) {
               d.updateDataValue("manufacturer_name", b.manufacturer_name)
               d.updateDataValue("model_id", b.model_id)
               d.setButtons(b.buttons, b.relative_rotary)
            }
         } catch (Exception ex) {
            log.error("Unable to create new device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for button device $it: ID not found on Hue Bridge")
      }
   }
   bridge.clearButtonsCache()
   //bridge.getAllButtonsV2()
   app.removeSetting("newButtons")
}

/** Sends request for username creation to Bridge API. Intended to be called after user
 *  presses link button on Bridge
 */
void sendUsernameRequest(String protocol="http", Integer port=null) {
   if (logEnable == true) log.debug "sendUsernameRequest()... (IP = ${state.ipAddress})"
   String locationNameNormalized = location.name?.replaceAll("\\P{InBasic_Latin}", "_").take(16) // Cap at first 16 characters (possible 30-char total limit?)
   String userDesc = locationNameNormalized ? "Hubitat ${DNI_PREFIX}#${locationNameNormalized}" : "Hubitat ${DNI_PREFIX}"
   String ip = state.ipAddress
   Map params = [
      uri:  ip ? """${protocol}://${ip}${port ? ":$port" : ''}""" : getBridgeData().fullHost,
      requestContentType: "application/json",
      contentType: "application/json",
      path: "/api",
      body: [devicetype: userDesc],
      contentType: 'text/xml',
      timeout: 15
   ]
   asynchttpPost("parseUsernameResponse", params, null)
}


/** Callback for sendUsernameRequest. Saves username (API key) in app state if Bridge
 *  is successfully authorized, or logs error if unable to do so.
 */
void parseUsernameResponse(resp, data) {
   def body = resp.json
   if (logEnable == true) log.debug "Attempting to request Hue Bridge API key/username; result = ${body}"
   if (body.success != null) {
      if (body.success[0] != null) {
         if (body.success[0].username) {
               state.username = body.success[0].username
               state.bridgeAuthorized = true
               if (logEnable == true) log.debug "Bridge authorized!"
         }
      }
   }
   else {
      if (body.error != null) {
         log.warn "  Error from Bridge: ${body.error}"
      }
      else {
         log.error "  Unknown error attempting to authorize Hue Bridge API key"
      }
   }
}

/** Requests Bridge info from /api/0/config endpoint) to verify that device is a
 *  Hue Bridge and to retrieve information necessary to either create the Bridge device
 *  (when parsed in parseBridgeInfoResponse if createBridge == true) or to add to the list
 *  of discovered Bridge devices (when createBridge == false). protocol, ip, and port are optional
 *  and will default to getBridgeData() values if not specified
 *  @param options Possible values: createBridge (default true), protocol (default "https"), ip, port
 */
void sendBridgeInfoRequest(Map options) {
   if (logEnable == true) log.debug "sendBridgeInfoRequest()"
   String fullHost
   if (options?.port) {
      fullHost = "${options.protocol ?: 'https'}://${options.ip ?: state.ipAddress}:${options.port}"
   }  
   else {
      fullHost = "${options?.protocol ?: 'https'}://${options?.ip ?: state.ipAddress}" 
   }
   Map params = [
      uri: fullHost,
      path: "/api/0/config",  // does not require authentication (API key/username)
      contentType: "text/xml",
      ignoreSSLIssues: true,
      timeout: 15
   ]
   asynchttpGet("parseBridgeInfoResponse", params, options)
}

// Example response:
/*
{
   "name": "Smartbridge 1",
   "swversion": "1947054040",
   "apiversion": "1.46.0",
   "mac": "00:17:88:25:b8:f8",
   "bridgeid": "001788FFFE25B8F8",
   "factorynew": false,
   "replacesbridgeid": null,
   "modelid": "BSB002",
}
*/
/** Parses response from GET of /api/0/config endpoint on the Bridge;
 *  verifies that device is a Hue Bridge (modelName contains "Philips Hue Bridge")
 * and obtains MAC address for use in creating Bridge DNI and device name
 */
void parseBridgeInfoResponse(resp, Map data) {
   //resp?.properties.each { log.trace it }
   if (logEnable == true) log.debug "parseBridgeInfoResponse(resp?.data = ${resp?.data}, data = $data)"
   String ipAddress = (data?.ip == null) ? state.ipAddress : data.ip
   Map body
   try {
      body = resp.json
   }
   catch (Exception ex) {
      if (logEnable == true) log.debug "  Responding device likely not a Hue Bridge: $ex"
      return
      // if (!(data.haveAttemptedV1)) {
      //    // try again with V1 API in case is V1 Bridge or old firmware on V2:
      //    if (logEnable == true) log.debug "  Retrying with V1 API"
      //    data.haveAttemptedV1 = true
      //    sendBridgeInfoRequest(data)
      // }
   }

   String friendlyBridgeName = body.name ?: "Unknown Bridge"
   String bridgeMAC = body.mac.replaceAll(':', '').toUpperCase()
   // Not using "full" bridge ID for this to retain V1 compatibility, but could(?):
   String bridgeID = bridgeMAC.drop(6)   // last (12-6=) 6 of MAC serial
   String swVersion = body.swversion // version 1948086000 means v2 API is available
   DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")

   if (data?.createBridge) {
      if (logEnable == true) log.debug "    Attempting to create Hue Bridge device for $bridgeMAC"
      if (!bridgeMAC) {
         log.error "    Unable to retrieve MAC address for Bridge. Exiting before creation attempt."
         return
      }
      state.bridgeMAC = bridgeMAC
      try {
         if (!bridgeDevice) bridgeDevice = addChildDevice(NAMESPACE, DRIVER_NAME_BRIDGE, "${DNI_PREFIX}/${app.id}", null,
                              [label: """${DRIVER_NAME_BRIDGE} ${getBridgeId()}${friendlyBridgeName ? " ($friendlyBridgeName)" : ""}""", name: DRIVER_NAME_BRIDGE])
         if (!bridgeDevice) {
            log.error "    Bridge device unable to be created or found. Check that driver is installed and no existing device exists for this Bridge." 
         }
         else {
            state.bridgeLinked = true
            if (swVersion) {
               bridgeDevice.updateDataValue("swversion", swVersion)
               try {
                  Integer intVer = Integer.parseInt(swVersion)
                  if (settings.useSSDP && intVer >= minV2SwVersion) {
                     state.useV2 = true
                  }
               }
               catch (Exception ex) {
                  log.error "Error converting bridge swVersion to Integer: $ex"
               }
            }
         }
         if (!(settings.boolCustomLabel)) {
               app.updateLabel("""CoCoHue - Hue Bridge Integration (${getBridgeId()}${friendlyBridgeName ? " - $friendlyBridgeName)" : ")"}""")
         }
      }
      catch (IllegalArgumentException e) { // could be bad DNI if already exists
         log.error "   Error creating Bridge device. IllegalArgumentException: $e"
      }
      catch (Exception e) {
         log.error "    Error creating Bridge device: $e"
      }
   }
   else { // createBridge = false, so either in discovery (so add to list instead) or received as part of regular app operation (so check if IP address changed if using Bridge discovery)
      if (!(state.bridgeLinked)) { // so in discovery
         if (logEnable == true) log.debug "  Adding Bridge with MAC $bridgeMAC ($friendlyBridgeName) to list of discovered Bridges"
         if (!state.discoveredBridges) state.discoveredBridges = []
         if (!(state.discoveredBridges.any { it.containsKey(ipAddress) })) {
            state.discoveredBridges.add([(ipAddress): "${friendlyBridgeName} - ${bridgeMAC}"])
         }
      }
      else { // Bridge already added, so likely added with discovery; check if IP changed
         if (logEnable == true) log.debug "  Bridge already added; seaching if Bridge matches MAC $bridgeMAC"
         if (bridgeMAC == state.bridgeMAC && bridgeMAC != null) { // found a match for this Bridge, so update IP:
            if (data?.ip && settings.useSSDP) {
               state.ipAddress = data.ip
               if (logEnable == true) log.debug "  Bridge MAC matched. Setting IP as ${state.ipAddress}"
            }
            String dataSwversion = bridgeDevice.getDataValue("swversion")
            if (dataSwversion != swVersion && swVersion) bridgeDevice.updateDataValue("swversion", swVersion)
            state.remove("failedDiscos")
         }
         else {
            state.failedDiscos= state.failedDiscos ? state.failedDiscos += 1 : 1
            if (logEnable == true) log.debug "  No matching Bridge MAC found for ${state.bridgeMAC}. failedDiscos = ${state.failedDiscos}"
         }
      }
   }
}

/** Handles response from SSDP (sent to discover Bridge) */
void ssdpHandler(evt) {
   Map parsedMap = parseLanMessage(evt?.description)
   if (parsedMap) {
      String ip = "${convertHexToIP(parsedMap?.networkAddress)}"
      String ssdpPath = parsedMap.ssdpPath
      if (ip) {
         if (logEnable == true) log.debug "Device at $ip responded to SSDP; sending info request to see if is Hue Bridge"
         sendBridgeInfoRequest(ip: ip)
      }
      else {
         if (logEnable == true) log.debug "In ssdpHandler() but unable to obtain IP address from device response: $parsedMap"
      }
   }
   else {
      if (logEnable == true) log.debug "In ssdpHandler() but unable to parse LAN message from event: $evt?.description"
   }
   //log.trace parsedMap
}

private String convertHexToIP(hex) {
   [hubitat.helper.HexUtils.hexStringToInt(hex[0..1]),
    hubitat.helper.HexUtils.hexStringToInt(hex[2..3]),
    hubitat.helper.HexUtils.hexStringToInt(hex[4..5]),
    hubitat.helper.HexUtils.hexStringToInt(hex[6..7])].join(".")
}

/**
 * Returns map containing Bridge username, IP, and full HTTP post/port, intended to be
 * called by child devices so they can send commands to the Hue Bridge API using info
 */
Map<String,String> getBridgeData(String protocol="http", Integer port=null) {
   if (logEnable == true) log.debug "getBridgeData()"
   if (!state.ipAddress && settings.bridgeIP && !(settings.useSSDP)) state.ipAddress = settings.bridgeIP // seamless upgrade from v1.x
   if (!state.username || !state.ipAddress) log.error "Missing username or IP address from Bridge"
   Integer thePort = port
   if (thePort == null) {
      if (!settings.useSSDP && settings.customPort) {
         thePort = settings.customPort as Integer
      }
      else {
         thePort = (protocol == "https") ? 443 : 80
      }
   }
   String apiVer = state.useV2 ? APIV2 : APIV1
   Map<String,String> map = [username: state.username, ip: "${state.ipAddress}", fullHost: "${protocol}://${state.ipAddress}:${thePort}", apiVersion: apiVer]
   return map
}

/**
 * Calls refresh() method on Bridge child, intended to be called at user-specified
 * polling interval (if enabled)
 * @param reschedule If true (default), re-schedules/resets next poll; not intended to be used if was scheduled
 * refresh but could be used if user- or app-initiated (e.g., if SSE-based refresh to handle odd cases)
 */
private void refreshBridge(Map<String,Boolean> options = [reschedule: false]) {
   if (logEnable == true) log.debug "refreshBridge(reschedule = $reschedule)"
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (!bridge) {
      log.error "No Bridge device found; could not refresh/poll"
      return
   }
   if (useV1Polling != false) bridge.refreshV1()
   else bridge.refresh()
   if (options.reschedule == true) scheduleRefresh()
}

/**
 * Calls refresh() method on Bridge child, not yet used but planned to be with SSE xy color received to poll for HS
 * Will wait 1s (to avoid cluster of refreshes if multiple devices change at same time), and will also
 * re-schedule next periodic refresh (if enabled) to extend polling interval so this "counts" as such
*/
private void refreshBridgeWithDealay() {
   if (logEnable == true) log.debug "refreshBridgeWithDealay"
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (!bridge) {
      log.error "No Bridge device found; could not refresh"
      return
   }
   // Using 3-second delay; 1s seemed too fast in testing, so this might be good for most cases:
   runIn(3, "refreshBridge", [reschedule: true])
}

/**
 * Sets "status" attribute on Bridge child device (intended to be called from child light/group scene devices with
 * successful or unsuccessful commands to Bridge as needed)
 * @param setToOnline Sets status to "Online" if true, else to "Offline"
 */
void setBridgeOnlineStatus(setToOnline=true) {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   if (bridge == null) {
      log.error "No Bridge device found; could not set Bridge status"
      return
   }
   String value = setToOnline ? 'Online' : 'Offline'
   if (logEnable == true) log.debug("  Setting Bridge status to ${value}...")
   if (bridge.currentValue("status") != value) bridge.doSendEvent("status", value)
}

/**
 *  Intended to be called by group child device when state is manipulated in a way that would affect
 *  all member bulbs. Updates member bulb states (so doesn't need to wait for next poll to update). 
 *  For Hue v1 device IDs only
 *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
 *  @param ids Hue IDs of member bulbs to update
 *  @param isAllGroup Set to true if is "All Hue Lights" group (group 0); ids (ignored) can be null in this case. Defaults to false.
 */
 void updateMemberBulbStatesFromGroup(Map states, List ids, Boolean isAllGroup=false) {
   if (logEnable == true) log.debug "Updating member bulb states after group device change... (ids = $ids, isAllGroup = $isAllGroup)"
   if (!isAllGroup) {
      ids?.each {
         DeviceWrapper dev = getChildDevice("${DNI_PREFIX}/${app.id}/Light/${it}")
         dev?.createEventsFromMapV1(states, false)
      }
   } else {
      List<DeviceWrapper> devList = getChildDevices().findAll { it.getDeviceNetworkId().startsWith("${DNI_PREFIX}/${app.id}/Light/") }
      // Update other gropus even though they aren't "bulbs":
      devList += getChildDevices().findAll { it.getDeviceNetworkId().startsWith("${DNI_PREFIX}/${app.id}/Group/") && !(it.getDeviceNetworkId() == "${DNI_PREFIX}/${app.id}/Group/0") }
      //if (logEnable == true) log.debug("Updating states for: $devList")
      devList.each { it.createEventsFromMapV1(states, false) }
   }    
 }

 /**
  *  Intended to be called by bulb child device when state is manipulated in a way that would affect
  *  group and user has enabled this option. Updates group device states if this bulb ID is found as a
  *  member of that group (so doesn't need to wait for next poll to update).
  *  For Hue v1 device IDs only
  *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
  *  @param id Hue bulb ID to search all groups for (will update group if bulb found in group)
  */
 void updateGroupStatesFromBulb(Map states, id) {
   if (logEnable == true) log.debug "Searching for group devices containing bulb $id to update group state after bulb state change..."
   List matchingGroupDevs = []
   getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("${DNI_PREFIX}/${app.id}/Group/")})?.each {
      if (it.getMemberBulbIDs()?.contains(id) || it.getDeviceNetworkId() == "${DNI_PREFIX}/${app.id}/Group/0") {
         if (logEnable == true) log.debug("Bulb $id found in group ${it.toString()}. Updating states.")
         matchingGroupDevs.add(it)
      }
   }
   matchingGroupDevs.each { groupDev ->
      // Hue app reports "on" if any members on but takes last color/level/etc. from most recent
      // change, so emulate that behavior here (or does Hue average level of all? this is at least close...)
      Boolean onState = getIsAnyGroupMemberBulbOn(groupDev)
      groupDev.createEventsFromMapV1(states << ["on": onState], false)
   }
 }

/**
 *  Intended to be called by scene child device if GroupScene so other scenes belonging to same
 *  group can be set to "off" if user has this preference configured for scene.
 *  For Hue v1 API only
 *  @param groupID Hue group ID (will search for GroupScenes belonging to this group), or use 0 for all CoCoHue scenes
  * @param excludeDNI Intended to be DNI of the calling scene; will exclude this from search since should remain on
 */
void updateSceneStateToOffForGroup(String groupID, String excludeDNI=null) {
   if (logEnable == true) log.debug "Searching for scene devices matching group $groupID and excluding DNI $excludeDNI"
   List<DeviceWrapper> sceneDevs = []
   if (groupID == "0") {
      sceneDevs = getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("${DNI_PREFIX}/${app.id}/Scene/") &&
                                 it.getDeviceNetworkId() != excludeDNI})
   }
   else {
      sceneDevs = getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("${DNI_PREFIX}/${app.id}/Scene/") &&
                                 it.getDeviceNetworkId() != excludeDNI &&
                                 it.getGroupID() == groupID})
   }
   if (logEnable == true) log.debug "updateSceneStateToOffForGroup matching scenes: $sceneDevs"
   sceneDevs.each { sc ->
		   if (sc.currentValue("switch") != "off") sc.doSendEvent("switch", "off")
   }
}

 /**
 * Finds Hubitat devices for member bulbs of group and returns true if any (that are found) are on; returns false
 * if all off or no member bulb devices found.
 * For Hue v1 API only
 * @param Instance of child Group device on which to check member bulb states
 */
Boolean getIsAnyGroupMemberBulbOn(groupDevice) {
   if (logEnable == true) log.debug "Determining whether any group member bulbs on for group $groupDevice"
   Boolean retVal = false
   if (groupDevice) {
      List<DeviceWrapper> memberBulbDevs = []
      if (groupDevice.getDeviceNetworkId() == "${DNI_PREFIX}/${app.id}/Group/0") {
         memberBulbDevs = getChildDevices().findAll { it.getDeviceNetworkId().startsWith("${DNI_PREFIX}/${app.id}/Light/") }
      }
      else {
         groupDevice.getMemberBulbIDs().each { bulbId ->
               DeviceWrapper bulbDev = getChildDevice("${DNI_PREFIX}/${app.id}/Light/${bulbId}")
               if (bulbDev) memberBulbDevs.add(bulbDev)
         }
      }
      Boolean anyOn = memberBulbDevs.any { it.currentValue('switch') == 'on' }
      if (logEnable == true) log.debug "Determined if any group member bulb on: $anyOn"
      return anyOn
   }
}

/**
 *  Returns true if app configured to use EventStream/SSE, else false (proxy since cannot directly access settings from child)
 */
Boolean getEventStreamEnabledSetting() {
   // This was the old way of checking, now check if really using instead?:
   //return (settings.useEventStream == true) ? true : false
   return state.useV2
}

/**
 *  Gets EventStream status (returns true if connected, otherwise false); retrieved from Bridge
 *  device with app in middle because normally to be called by light/group/etc. devices when need to know.
 */
Boolean getEventStreamOpenStatus() {
   DeviceWrapper bridge = getChildDevice("${DNI_PREFIX}/${app.id}")
   Boolean open = bridge.getEventStreamOpenStatus()
   if (logEnable == true) "getEventStreamOpenStatus (returning ${open})"
   return (state.eventStreamOpenStatus == true) ? true : false
}

void appButtonHandler(btn) {
   switch(btn) {
      // "Refresh" buttons on Select Lights, Groups, Scenes, bridge discovery, etc. pages:
      case "btnBulbRefresh":
      case "btnGroupRefresh":
      case "btnSceneRefresh":
      case "btnSensorRefresh":
      case "btnButtonRefresh":
         // Just want to resubmit page, so nothing
         break
      case "btnDiscoBridgeRefresh":
         sendBridgeDiscoveryCommand()
         break
      // Temporary option on Advanced Debugging Options/support page--remove eventually:
      // case "btnFixGroupDNIsTEMP":
      //    DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
      //    if (state.useV2 == true) {
      //       bridgeDevice.getAllGroupsV2()
      //       pauseExecution(5000)
      //       bridgeDevice.getAllRoomsCache()?.each { grpId, roomData ->
      //          DeviceWrapper d = getChildDevice("${DNI_PREFIX}/${app.id}/Group/${roomData.roomId}")
      //          if (d != null) {
      //             d.setDeviceNetworkId("${DNI_PREFIX}/${app.id}/Group/${grpId}")
      //          }
      //       }
      //       bridgeDevice.getAllZonesCache()?.each { grpId, zoneData ->
      //          DeviceWrapper d = getChildDevice("${DNI_PREFIX}/${app.id}/Group/${zoneData.zoneId}")
      //          if (d != null) {
      //             d.setDeviceNetworkId("${DNI_PREFIX}/${app.id}/Group/${grpId}")
      //          }
      //       }
      //    }
      //    else {
      //       log.warn "Not using V2 API; exiting"
      //    }
      // Options on Advanced Debugging Options/support page:
      case "btnEnableBridgeLogging":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         bridgeDevice.updateSetting("logEnable", [type: "bool", value: true])
         break
      case "btnLogChildDeviceInfo":
         getChildDevices().sort { it.deviceNetworkId }.each { DeviceWrapper dev ->
            log.debug "${dev.deviceNetworkId} = ${dev.displayName}"
         }
         break
      case "btnFetchLightsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         state.useV2 ? bridgeDevice.getAllBulbsV2() : bridgeDevice.getAllBulbsV1()
         break
      case "btnLogLightsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         log.debug bridgeDevice.getAllBulbsCache() ?: "EMPTY LIGHTS CACHE ON BRIDGE"
         break
      case "btnFetchGroupsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         state.useV2 ? bridgeDevice.getAllGroupsV2() : bridgeDevice.getAllGroupsV1()
         break
      case "btnLogGroupsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         log.debug bridgeDevice.getAllGroupsCache() ?: "EMPTY GROUPS CACHE ON BRIDGE"
         if (state.useV2) {
            log.debug bridgeDevice.getAllRoomsCache() ?: "EMPTY ROOMS CACHE ON BRIDGE"
            log.debug bridgeDevice.getAllZonesCache() ?: "EMPTY ZONES CACHE ON BRIDGE"
         }
         break
      case "btnFetchScenesInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         state.useV2 ? bridgeDevice.getAllScenesV2() : bridgeDevice.getAllScenesV1()
         break
      case "btnLogScenesInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         log.debug bridgeDevice.getAllScenesCache() ?: "EMPTY SCENES CACHE ON BRIDGE"
         break
      case "btnFetchSensorsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         bridgeDevice.getAllSensorsV2()
         break
      case "btnLogSensorsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         log.debug bridgeDevice.getAllSensorsCache() ?: "EMPTY SENSORS CACHE ON BRIDGE"
         break
      case "btnFetchButtonsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         bridgeDevice.getAllButtonsV2()
         break
      case "btnLogButtonsInfo":
         DeviceWrapper bridgeDevice = getChildDevice("${DNI_PREFIX}/${app.id}")
         log.debug bridgeDevice.getAllButtonsCache() ?: "EMPTY BUTTONS CACHE ON BRIDGE"
         break
      case "btnRetryV2APIEnable":
         app.updateSetting("logEnable", true)
         state.remove("useV2UpgradeCompleted")
         upgradeCCHv1DNIsToV2()
         break
      // Other
      default:
         log.warn "Unhandled app button press: $btn"
   }
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
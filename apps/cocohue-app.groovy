/**
 * ===========================  CoCoHue - Hue Bridge Integration =========================
 *
 *  Copyright 2019-2023 Robert Morris
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum or README.MD file in GitHub repo
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
 *  Last modified: 2022-09-04
 * 
 *  Changelog:
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

@Field static final String childNamespace = "RMoRobert" // namespace of child device drivers
@Field static final Map driverMap = [
   "extended color light":     "CoCoHue RGBW Bulb",
   "color light":              "CoCoHue RGBW Bulb",  // eventually should make this one RGB
   "color temperature light":  "CoCoHue CT Bulb",
   "dimmable light":           "CoCoHue Dimmable Bulb",
   "on/off light":             "CoCoHue On/Off Plug",
   "on/off plug-in unit":      "CoCoHue On/Off Plug",
   "DEFAULT":                  "CoCoHue RGBW Bulb"
]

definition (
   name: "CoCoHue - Hue Bridge Integration",
   namespace: "RMoRobert",
   author: "Robert Morris",
   description: "Community-created Philips Hue integration for Hue Bridge lights, groups, and scenes",
   category: "Convenience",
   installOnOpen: true,
   documentationLink: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978",
   iconUrl: "",
   iconX2Url: "",
   iconX3Url: "",
   //Uncomment the following line if upgrading from existing CoCoHue 1.x installation:
   //parent: "RMoRobert:CoCoHue (Parent App)",
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
   page name: "pageSelectLabsActivators"
}

void installed() {
   log.info("Installed with settings: ${settings}")
   initialize()
}

void uninstalled() {
   log.info("Uninstalling")
   if (!(settings['deleteDevicesOnUninstall'] == false)) {
      logDebug("Deleting child devices of this CoCoHue instance...")
      List DNIs = getChildDevices().collect { it.deviceNetworkId }
      logDebug("  Preparing to delete devices with DNIs: $DNIs")
      DNIs.each {
         deleteChildDevice(it)
      }
   }
}

void updated() {
   log.info("Updated with settings: ${settings}")
   initialize()
   // Upgrade pre-CoCoHue-4.0 DNIs to match new DNI format (CCH/AppID, not CCH/BridgeMacAbbrev)
   if (getChildDevices().any { DeviceWrapper dev -> dev.deviceNetworkId.startsWith("CCH/${state.bridgeID}/") }) {
      getChildDevices().each { DeviceWrapper d ->
         if (d.deviceNetworkId.startsWith("CCH/${state.bridgeID}") && !(d.deviceNetworkId.startsWith("CCH/${state.bridgeID}/Sensor"))) {
            String newDNI = d.deviceNetworkId.replace("CCH/${state.bridgeID}", "CCH/${app.getId()}")
            log.debug("Updating ${d.displayName} DNI from ${d.deviceNetworkId} to $newDNI")
            d.setDeviceNetworkId(newDNI)
         }
      }
   }
   // Upgrade pre-CoCoHue-4.0 motion sensor DNIs to match new DNI format (Hue IDs, separated by pipe character; not IEEE/MAC)
   if (getChildDevices().any { DeviceWrapper dev ->
    (dev.deviceNetworkId.startsWith("CCH/${state.bridgeID}/Sensor/") || dev.deviceNetworkId.startsWith("CCH/${app.getId()}/Sensor/")) &&
    dev.deviceNetworkId.tokenize('/')[-1].contains(":")
   }) {
      DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
      if (bridge == null) {
         bridge = getChildDevice("CCH/${state.bridgeID}")
         if (bridge == null) {
            log.warn "Bridge device not found. Try..."
         }
      }
      bridge.getAllSensors()
      pauseExecution(6000) // should work (wait 6s), though there's probably a more reliable way to time (async callback?)...
      Map sensorCache = bridge.getAllSensorsCache()
      sensorCache.each { String mac, Map value ->
         DeviceWrapper dev = getChildDevice("CCH/${app.getId()}/Sensor/${mac}") ?: getChildDevice("CCH/${state.bridgeID}/Sensor/${mac}")
         if (dev != null) {
            log.debug "Updating DNI for ${dev.displayName} to CoCoHue 4.0 format (old DNI = ${dev.deviceNetworkId})"
            List ids = value.ids.sort()
            String newLastPart = ids.join("|")
            dev.setDeviceNetworkId("CCH/${app.getId()}/Sensor/${newLastPart}")
            log.debug "Set DNI for ${dev.displayName} to CCH/${app.getId()}/${newLastPart}"
         }
         log.debug "dev was $dev"
      }
   }
}

void initialize() {
   log.debug("Initializing...")
   unschedule()
   state.remove('discoveredBridges')
   if (settings["useSSDP"] == true || settings["useSSDP"] == null) {
      if (settings["keepSSDP"] != false) {
         log.debug("Subscribing to ssdp...")
         subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", "ssdpHandler")
         schedule("${Math.round(Math.random() * 59)} ${Math.round(Math.random() * 59)} 6 ? * * *",
               "periodicSendDiscovery")
      }
      else {
         log.debug("Not subscribing to ssdp...")
         unsubscribe("ssdpHandler")
         unschedule("periodicSendDiscovery")
      }
      subscribe(location, "systemStart", hubRestartHandler)
      if (state.bridgeAuthorized) sendBridgeDiscoveryCommand() // do discovery if user clicks 'Done'
   }
   else {
      unsubscribe("ssdpHandler")
      unschedule("periodicSendDiscovery")
   }

   Integer disableTime = 1800
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }

   if (settings.useEventStream == true) {
      DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
      bridge?.connectEventStream()
   }
   else {
      bridge?.disconnectEventStream()
   }

   scheduleRefresh()
}

void scheduleRefresh() {
   if (enableDebug) log.debug "scheduleRefresh()"
   Integer pollInt = Integer.parseInt(settings["pollInterval"] ?: "0")
   // If change polling options in UI, may need to modify some of these cases:
   switch (pollInt) {
      case 0:
         logDebug "Polling disabled; not scheduling"
         break
      case 1..59:
         logDebug "Scheduling polling every ${pollInt} seconds"
         schedule("${Math.round(Math.random() * pollInt)}/${pollInt} * * ? * * *", "refreshBridge")
         break
      case 60..119:
         logDebug "Scheduling polling every 1 minute"
         runEvery1Minute("refreshBridge")
         break
      case 120..179:
         logDebug "Scheduling polling every 2 minutes"
         schedule("${Math.round(Math.random() * 59)} */2 * ? * * *", "refreshBridge")
         runEvery2Minutes("refreshBridge")
         break
      case 180..299:
         logDebug "Scheduling polling every 3 minutes"
         schedule("${Math.round(Math.random() * 59)} */3 * ? * * *", "refreshBridge")
         break
      case 300..1799:
         logDebug "Scheduling polling every 5 minutes"
         runEvery5Minutes("refreshBridge")
         break
      case 1800..3599:
         logDebug "Scheduling polling every 30 minutes"
         runEvery30Minutes("refreshBridge")
         break
      default:
         logDebug "Scheduling polling every hour"
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
   logDebug("sendBridgeDiscoveryCommandIfSSDPEnabled($checkIfRecent)")
   if (settings.useSSDP != false) {
      if (checkIfRecent) {
         Long lastDiscoThreshold = 300000 // Start with 5 minutes
         if (state.failedDiscos >= 3 && state.failedDiscos < 4) lastDiscoThreshold = 600000 // start trying every 5 min
         else if (state.failedDiscos >= 4 && state.failedDiscos < 6) lastDiscoThreshold = 1200000 // gradually increase interval if keeps failing...
         else if (state.failedDiscos >= 6 && state.failedDiscos < 18) lastDiscoThreshold = 3600000 // 1 hour now
         else lastDiscoThreshold =  7200000 // cap at 2 hr if been more than ~12 hr without Bridge
         if (!(state.lastDiscoCommand) || (now() -  state.lastDiscoCommand >= lastDiscoThreshold)) {         
            sendBridgeDiscoveryCommand()
         }
      }
      else {
         sendBridgeDiscoveryCommand()
      }
   }
}

void hubRestartHandler(evt) {
   sendBridgeDiscoveryCommandIfSSDPEnabled()
}

// Scheduled job handler; if using SSDP, schedules to run once a day just in case
void periodicSendDiscovery(evt) {
   sendBridgeDiscoveryCommandIfSSDPEnabled(true)
}

void debugOff() {
   log.warn "Disabling debug logging"
   app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
   state.authRefreshInterval = 5
   state.discoTryCount = 0
   state.authTryCount = 0
   if (app.getInstallationState() == "INCOMPLETE") {
      // Shouldn't happen with installOnOpen: true, but just in case...
      dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
      section() {
         paragraph "Please press \"Done\" to install CoCoHue.<br>Then, re-open to set up your Hue Bridge."
      }
   }
   } else {
      if (state.bridgeLinked) {
         return pageManageBridge()
      }
      else {
         return pageAddBridge()
      }
   }
}

def pageAddBridge() {
   logDebug "pageAddBridge()..."
   Integer discoMaxTries = 60
   if (settings.boolReauthorize) {
      state.remove("bridgeAuthorized")
      app.removeSetting("boolReauthorize")
   }
   if (settings.useSSDP != false && state.discoTryCount < 5) {
      logDebug "Subscribing to and sending SSDP discovery..."
      subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
      sendBridgeDiscoveryCommand()
   }  
   dynamicPage(name: "pageAddBridge", uninstall: true, install: false,
               refreshInterval: ((settings.use == false || selectedDiscoveredBridge) ? null : state.authRefreshInterval),
               nextPage: "pageLinkBridge") {
      section("Add Hue Bridge") {
         input name: "useSSDP", type: "bool", title: "Discover Hue Bridges automatically", defaultValue: true, submitOnChange: true
         if (settings.useSSDP != false) {
            if (!(state.discoveredBridges)) {
               paragraph "Please wait while Hue Bridges are discovered..."
               paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
            }
            else {
               input name: "selectedDiscoveredBridge", type: "enum", title: "Discovered bridges:", options: state.discoveredBridges,
                     multiple: false, submitOnChange: true
               if (!(settings.selectedDiscoveredBridge)) {
                  if (!(state.discoveredBridges)) paragraph("Please wait while CoCoHue discovers Hue Bridges on your network...")
                  else paragraph("Select a Hue Bridge above to begin adding it to CoCoHue.")
               }
               else {
                  if (/*!state.bridgeLinked ||*/ !state.bridgeAuthorized)
                        paragraph("<strong>Press the button on your Bridge</strong> and press \"Next\" to continue.")
                  else
                        paragraph("Press \"Next\" to continue.")
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
                  paragraph("<strong>Press the button on your Hue Bridge,</strong> then press \"Next\" to continue.")
               }
         }
         // Hack-y way to hide/show Next button if still waiting:
         if ((settings.useSSDP != false && settings['selectedDiscoveredBridge']) || settings['bridgeIP']) {
            paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
         }
         else {
            paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
         }
      }
   }
}

def pageReAddBridge() {
   logDebug "pageReAddBridge()..."
   state.authRefreshInterval = 5
   state.discoTryCount = 0
   state.authTryCount = 0
   if (settings.useSSDP == true || settings.useSSDP == null && state.discoTryCount < 5) {
      logDebug "Subscribing to and sending SSDP discovery..."
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
         paragraph "<strong>Press \"Next\" to continue.</strong>"
      }
   }
}

def pageLinkBridge() {
   logDebug "Beginning brdige link process..."
   String ipAddress = (settings.useSSDP != false) ? settings.selectedDiscoveredBridge : settings.bridgeIP
   state.ipAddress = ipAddress
   logDebug "  IP address = ${state.ipAddress}"
   Integer authMaxTries = 35
   if (!(settings.useSSDP == false)) {
      if (!(settings.selectedDiscoveredBridge)) {
         dynamicPage(name: "pageLinkBridge", uninstall: true, install: false, nextPage: "pageAddBridge") {
            section() {
               paragraph 'No Bridge selected. Click "Next" to return to the bridge selection page, and try again.'
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
                     if (!settings.useSSDP) strParagraph + "Also, verify that your Bridge IP address is correct: ${state.ipAddress}"
                  }
                  paragraph(strParagraph)
               }
               if (state.authTryCount >= authMaxTries) {
                  state.remove('authRefreshInterval')
                  paragraph("<b>Authorization timed out.<b> Click/tap \"Next\" to return to the beginning, " + 
                           "check your settings, and try again.")
               }
         }
         else {
               if (!state.bridgeLinked) {
                  log.debug "Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat..."
                  paragraph "Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat..."
                  if (settings["useSSDP"]) sendBridgeInfoRequest(true)
                  else sendBridgeInfoRequest(true, null, settings["bridgeIP"] ?: state.ipAddress, settings["customPort"] as Integer ?: 80)
               }
               else {
                  logDebug("Bridge already linked; skipping Bridge device creation")
                  if (state.bridgeLinked && state.bridgeAuthorized) {
                     state.remove('discoveredBridges')
                     state.remove('authRefreshInterval')
                     //app.clearSetting('selectedDiscoveredBridge')
                     paragraph("<b>Your Hue Bridge has been linked!</b> Press \"Next\" to begin adding lights, groups, " +
                                 "or scenes.")
                  }
                  else {
                     paragraph("There was a problem authorizing or linking your Hue Bridge. Please start over and try again.")
                  }
               }
         }
         // Hack-y way to hide/show Next button if still waiting:
         if ((state.authTryCount >= authMaxTries) || (state.bridgeLinked && state.bridgeAuthorized)) {
            paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
         }
         else {
            paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
         }
      }
   }
}
def pageManageBridge() {
   if (settings["newBulbs"]) {
      logDebug "New bulbs selected. Creating..."
      createNewSelectedBulbDevices()
   }
   if (settings["newGroups"]) {
      logDebug "New groups selected. Creating..."
      createNewSelectedGroupDevices()
   }
   if (settings["newScenes"]) {
      logDebug "New scenes selected. Creating..."
      createNewSelectedSceneDevices()
   }
   if (settings["newSensors"]) {
      logDebug "New sensors selected. Creating..."
      createNewSelectedSensorDevices()
   }
   if (settings["newButtons"]) {
      logDebug "New button devices selected. Creating..."
      createNewSelectedButtonDevices()
   }
   if (settings["newLabsDevs"]) {
      logDebug "New Labs devices selected. Creating..."
      createNewSelectedLabsDevices()
   }
   // General cleanup in case left over from discovery:
   state.remove("authTryCount")
   state.remove("discoTryCount")
   // More cleanup...
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge != null) {
      bridge.clearBulbsCache()
      bridge.clearGroupsCache()
      bridge.clearScenesCache()
      bridge.clearSensorsCache()
      bridge.clearLabsSensorsCache()
   }
   else {
      log.warn "Bridge device not found!"
   }
   state.remove("sceneFullNames")
   state.remove("addedBulbs")
   state.remove("addedGroups")
   state.remove("addedScenes")
   state.remove("addedSensors")
   state.remove("addedLabsDevs")

   dynamicPage(name: "pageManageBridge", uninstall: true, install: true) {  
      section("Manage Hue Bridge Devices:") {
         href(name: "hrefSelectLights", title: "Select Lights",
               description: "", page: "pageSelectLights")
         href(name: "hrefSelectGroups", title: "Select Groups",
               description: "", page: "pageSelectGroups")
         href(name: "hrefSelectScenes", title: "Select Scenes",
               description: "", page: "pageSelectScenes")
         href(name: "hrefSelectMotionSensors", title: "Select Motion Sensors",
               description: "", page: "pageSelectMotionSensors")
         href(name: "hrefSelectButtons", title: "Select Button Devices (experimental)",
               description: "", page: "pageSelectButtons")
         href(name: "hrefSelectLabsActivators", title: "Select Hue Labs Activators",
               description: "", page: "pageSelectLabsActivators")
      }
      section("Advanced Options", hideable: true, hidden: true) {
         href(name: "hrefReAddBridge", title: "Edit Bridge IP, re-authorize, or re-discover...",
               description: "", page: "pageReAddBridge")
         if (settings.useSSDP != false) {
            input name: "keepSSDP", type: "bool", title: "Remain subscribed to Bridge discovery requests (recommended to keep enabled if Bridge has dynamic IP address)",
               defaultValue: true
         }
         input name: "showAllScenes", type: "bool", title: "Allow adding scenes not associated with rooms/zones"
         input name: "deleteDevicesOnUninstall", type: "bool", title: "Delete devices created by app (Bridge, light, group, and scene) if uninstalled", defaultValue: true
      }        
      section("Other Options:") {
         input name: "useEventStream", type: "bool", title: "Enable \"push\" updates (Server-Sent Events/EventStream) from Bridge (experimental; requires Bridge v2 and Hubitat 2.2.9 or later)"
         input name: "pollInterval", type: "enum", title: "Poll bridge every...",
            options: [0:"Disabled", 10:"10 seconds", 15:"15 seconds", 20:"20 seconds", 30:"30 seconds", 45:"45 seconds", 60:"1 minute (default)",
                        120:"2 minutes", 180:"3 minutes", 300:"5 minutes", 1800:"30 minutes", 3600:"1 hour"],
                        defaultValue: 60
         input name: "boolCustomLabel", type: "bool", title: "Customize the name of this CoCoHue app instance", defaultValue: false, submitOnChange: true
         if (settings.boolCustomLabel) label title: "Custom name for this app", required: false
         input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      }
   }
}

def pageSelectLights() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllBulbs()
   List arrNewBulbs = []
   Map bulbCache = bridge.getAllBulbsCache()
   List<DeviceWrapper> unclaimedBulbs = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/Light/") }
   dynamicPage(name: "pageSelectLights", refreshInterval: bulbCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedBulbs = [:]  // To be populated with lights user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (bulbCache) {
         bulbCache.each { cachedBulb ->
            DeviceWrapper bulbChild = unclaimedBulbs.find { b -> b.deviceNetworkId == "CCH/${app.getId()}/Light/${cachedBulb.key}" }
            if (bulbChild) {
               addedBulbs.put(cachedBulb.key, [hubitatName: bulbChild.name, hubitatId: bulbChild.id, hueName: cachedBulb.value?.name])
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
            paragraph "Press \"Refresh\" if you see this message for an extended period of time"
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
               paragraph("If you added new lights to the Hue Bridge and do not see them above, click/tap the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnBulbRefresh", type: "button", title: "Refresh Bulb List", submitOnChange: true
         }
      }
   }
}

def pageSelectGroups() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllGroups()
   List arrNewGroups = []
   Map groupCache = bridge.getAllGroupsCache()
   List<DeviceWrapper> unclaimedGroups = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/Group/") }
   dynamicPage(name: "pageSelectGroups", refreshInterval: groupCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedGroups = [:]  // To be populated with groups user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (groupCache) {
         groupCache.each { cachedGroup ->
            DeviceWrapper groupChild = unclaimedGroups.find { grp -> grp.deviceNetworkId == "CCH/${app.getId()}/Group/${cachedGroup.key}" }
            if (groupChild) {
               addedGroups.put(cachedGroup.key, [hubitatName: groupChild.name, hubitatId: groupChild.id, hueName: cachedGroup.value?.name])
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
               paragraph "Press \"Refresh\" if you see this message for an extended period of time"
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
            paragraph("If you added new groups to the Hue Bridge and do not see them above, click/tap the button " +
                     "below to retrieve new information from the Bridge.")
            input name: "btnGroupRefresh", type: "button", title: "Refresh Group List", submitOnChange: true
         }
      }
   }    
}

def pageSelectScenes() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllScenes()
   List arrNewScenes = []
   Map sceneCache = bridge.getAllScenesCache()
   Map groupCache = bridge.getAllGroupsCache()
   List<DeviceWrapper> unclaimedScenes = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/Scene/") }
   Map grps = [:]
   groupCache?.each { grps << [(it.key) : (it.value.name)] }
   dynamicPage(name: "pageSelectScenes", refreshInterval: sceneCache ? 0 : 7, uninstall: true, install: false, nextPage: "pageManageBridge") {  
      Map addedScenes = [:]  // To be populated with scenes user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (sceneCache) {
         state.sceneFullNames = [:]
         sceneCache.each { sc ->
            DeviceWrapper sceneChild = unclaimedScenes.find { scn -> scn.deviceNetworkId == "CCH/${app.getId()}/Scene/${sc.key}" }
            if (sceneChild) {
               addedScenes.put(sc.key, [hubitatName: sceneChild.name, hubitatId: sceneChild.id, hueName: sc.value?.name])
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
            paragraph "Press \"Refresh\" if you see this message for an extended period of time"
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
            paragraph("If you added new scenes to the Hue Bridge and do not see them above, if room/zone names are " +
                     "missing from scenes (if assigned to one), or if you changed the \"Allow adding scenes not associated with rooms/zones...\" setting, " +
                     "click/tap the button below to retrieve new information from the Bridge.")
            input name: "btnSceneRefresh", type: "button", title: "Refresh Scene List", submitOnChange: true
         }
      }
   }
}

def pageSelectMotionSensors() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllSensors()
   List arrNewSensors = []
   Map sensorCache = bridge.getAllSensorsCache()
   List<DeviceWrapper> unclaimedSensors = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/Sensor/") }
   dynamicPage(name: "pageSelectMotionSensors", refreshInterval: sensorCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedSensors = [:]  // To be populated with sensors user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (sensorCache) {
         sensorCache.each { cachedSensor ->
            List ids = cachedSensor.value.ids?.sort() // sort numerically in case aren't (though usually retrieved from Bridge as such)
            String lastPart = ids.join("|") // DNI format is like CCH/BridgeID/Sensor/1|2|3, where 1, 2, and 3 are the Hue IDs for various components of this sensor
            DeviceWrapper sensorChild = unclaimedSensors.find { s -> s.deviceNetworkId == "CCH/${app.getId()}/Sensor/${lastPart}" }
            if (sensorChild) {
               addedSensors.put(lastPart, [hubitatName: sensorChild.name, hubitatId: sensorChild.id, hueName: cachedSensor.value?.name])
               unclaimedSensors.removeElement(sensorChild)
            } else {
               Map newSensor = [:]
               // eventually becomes input for setting/dropdown; Map format is [MAC: DisplayName]
               newSensor << [(cachedSensor.key): (cachedSensor.value.name)]
               arrNewSensors << newSensor
            }
         }
         arrNewSensors = arrNewSensors.sort { a, b ->
            // Sort by sensor name (default would be hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedSensors = addedSensors.sort { it.value.hubitatName }
      }
      if (!sensorCache) {
         section("Discovering sensors. Please wait...") {
            paragraph "Press \"Refresh\" if you see this message for an extended period of time"
            input name: "btnSensorRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Sensors") {
            if (!(settings.useEventStream)) {
               paragraph "NOTE: Without \"push\" updates (EventStream/SSE) enabled, motion sensor changes are updated only when the Bridge is polled, per your CoCoHue configuration options (or a manual \"Refresh\" on the Bridge device). <b>It is not recommended to rely on Hue motion sensors for time-sensitve motion-based automations on Hubitat in this configuration</b> when used via the Hue Bridge. For example, some motion events may be missed entirely if the duration of activity lasts less than the polling interval, but there will be a delay before activity in any case."
            }
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
               paragraph("If you added new sensors to the Hue Bridge and do not see them above, click/tap the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnSensorRefresh", type: "button", title: "Refresh Sensor List", submitOnChange: true
         }
      }
   }
}

def pageSelectButtons() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllButtons()
   List arrNewButtons = []
   Map buttonCache = bridge.getAllButtonsCache()
   List<DeviceWrapper> unclaimedButtons = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/Button/") }
   dynamicPage(name: "pageSelectButtons", refreshInterval: buttonCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedButtons = [:]  // To be populated with buttons user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (buttonCache) {
         buttonCache.each { cachedButton ->
            DeviceWrapper buttonChild = unclaimedButtons.find { s -> s.deviceNetworkId == "CCH/${app.getId()}/Button/${cachedButton.key}" }
            if (buttonChild) {
               addedButtons.put(cachedButton.key, [hubitatName: buttonChild.name, hubitatId: buttonChild.id, hueName: cachedButton.value?.name])
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
            paragraph "Press \"Refresh\" if you see this message for an extended period of time"
            input name: "btnButtonRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Button Devices") {
            if (!(settings.useEventStream)) {
               paragraph "NOTE: The \"push\" (EventStream/SSE) option is not enabled. Button devices will not function in CoCoHue without this option enabled."
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
               paragraph("If you added new button devices to the Hue Bridge and do not see them above, click/tap the button " +
                        "below to retrieve new information from the Bridge.")
               input name: "btnButtonRefresh", type: "button", title: "Refresh Button List", submitOnChange: true
         }
      }
   }
}

def pageSelectLabsActivators() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   bridge.getAllLabsDevices()
   List arrNewLabsDevs = []
   Map labsCache = bridge.getAllLabsSensorsCache()
   List<DeviceWrapper> unclaimedLabsDevs = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${app.getId()}/SensorRL/") }
   dynamicPage(name: "pageSelectLabsActivators", refreshInterval: labsCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedLabsDevs = [:]  // To be populated with lights user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (labsCache) {
         labsCache.each { cachedLabDev ->
            DeviceWrapper labsChild = unclaimedLabsDevs.find { d -> d.deviceNetworkId == "CCH/${app.getId()}/SensorRL/${cachedLabDev.key}" }
            if (labsChild) {
               addedLabsDevs.put(cachedLabDev.key, [hubitatName: labsChild.name, hubitatId: labsChild.id, hueName: cachedLabDev.value?.name])
               unclaimedLabsDevs.removeElement(labsChild)
            } else {
               Map newLabsDev = [:]
               newLabsDev << [(cachedLabDev.key): (cachedLabDev.value.name)]
               arrNewLabsDevs << newLabsDev
            }
         }
         arrNewLabsDevs = arrNewLabsDevs.sort { a, b ->
            // Sort by device name (default would be Hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedLabsDevs = addedLabsDevs.sort { it.value.hubitatName }
      }
      if (!labsCache) {
         section("Discovering Hue Labs activators. Please wait...") {
            paragraph "Press \"Refresh\" if you see this message for an extended period of time"
            input name: "btnLabsRefresh", type: "button", title: "Refresh", submitOnChange: true
         }
      }
      else {
         section("Manage Hue Labs Formula Activators") {
            input name: "newLabsDevs", type: "enum", title: "Select Hue Labs formula acvivators to add:",
                  multiple: true, options: arrNewLabsDevs
            input name: "boolAppendLabs", type: "bool", title: "Append \"(Hue Labs Formula)\" to Hubitat device name"
            paragraph ""
            paragraph "Previously added devices${addedLabsDevs ? ' <span style=\"font-style: italic\">(Hue Labs formula name on Bridge in parentheses)</span>' : ''}:"
            if (addedLabsDevs) {
               StringBuilder labDevsText = new StringBuilder()
               labDevsText << "<ul>"
               addedLabsDevs.each {
                  labDevsText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  labDevsText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_LabsDev_ID", type: "button", title: "Remove", width: 3)
               }
               labDevsText << "</ul>"
               paragraph(labDevsText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added Hue Labs Forumla devices found</span>"
            }
            if (unclaimedLabsDevs) {
               paragraph "Hubitat devices not found on Hue:"
               StringBuilder labDevsText = new StringBuilder()
               labDevsText << "<ul>"
               unclaimedLabsDevs.each {
                  labDevsText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               labDevsText << "</ul>"
               paragraph(labDevsText.toString())
            }
         }
         section("Rediscover Labs Devices") {
               paragraph "If you added new Labs formulas to the Hue Bridge and do not see them above, click/tap the button " +
                        "below to retrieve new information from the Bridge."
               input name: "btnLabsRefresh", type: "button", title: "Refresh Labs Formula List", submitOnChange: true
         }
      }
   }
}

/** Creates new Hubitat devices for new user-selected bulbs on lights-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedBulbDevices() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map bulbCache = bridge?.getAllBulbsCache()
   settings["newBulbs"].each {
      Map b = bulbCache.get(it)
      if (b) {
         try {
            logDebug "Creating new device for Hue light ${it} (${b.name})"
            String devDriver = driverMap[b.type.toLowerCase()] ?: driverMap["DEFAULT"]
            String devDNI = "CCH/${app.getId()}/Light/${it}"
            Map devProps = [name: (settings["boolAppendBulb"] ? b.name + " (Hue Bulb)" : b.name)]
            addChildDevice(childNamespace, devDriver, devDNI, devProps)

         } catch (Exception ex) {
            log.error("Unable to create new device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for bulb $it: ID not found on Hue Bridge")
      }
   }
   bridge.clearBulbsCache()
   bridge.getAllBulbs()
   app.removeSetting("newBulbs")
}

/** Creates new Hubitat devices for new user-selected groups on groups-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedGroupDevices() {
   String driverName = "CoCoHue Group"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map groupCache = bridge?.getAllGroupsCache()
   settings["newGroups"].each {
      def g = groupCache.get(it)
      if (g) {
         try {
            logDebug("Creating new device for Hue group ${it} (${g.name})")
            String devDNI = "CCH/${app.getId()}/Group/${it}"
            Map devProps = [name: (settings["boolAppendGroup"] ? g.name + " (Hue Group)" : g.name)]
            addChildDevice(childNamespace, driverName, devDNI, devProps)

         }
         catch (Exception ex) {
            log.error("Unable to create new group device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for group $it: ID not found on Hue Bridge")
      }
   }    
   bridge.clearGroupsCache()
   bridge.getAllGroups()
   bridge.refresh()
   app.removeSetting("newGroups")
}

/** Creates new Hubitat devices for new user-selected scenes on scene-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedSceneDevices() {
   String driverName = "CoCoHue Scene"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (!bridge) log.error("Unable to find Bridge device")
   Map sceneCache = bridge?.getAllScenesCache()
   settings["newScenes"].each {
      Map sc = sceneCache.get(it)
      if (sc) {
         try {
               logDebug "Creating new device for Hue group ${it} (state.sceneFullNames?.get(it) ?: sc.name)"
               String devDNI = "CCH/${app.getId()}/Scene/${it}"
               Map devProps = [name: (state.sceneFullNames?.get(it) ?: sc.name)]
               addChildDevice(childNamespace, driverName, devDNI, devProps)
         } catch (Exception ex) {
               log.error "Unable to create new scene device for $it: $ex"
         }
      } else {
         log.error "Unable to create new scene for scene $it: ID not found on Hue Bridge"
      }
   }
   bridge.clearScenesCache()
   //bridge.getAllScenes()
   app.removeSetting("newScenes")
   state.remove("sceneFullNames")
}

/** Creates new Hubitat devices for new user-selected sensors on sensor-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedSensorDevices() {
   String driverName = "CoCoHue Motion Sensor"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map<String,Map> sensorCache = bridge?.getAllSensorsCache()
   settings["newSensors"].each { String mac ->
      Map cachedSensor = sensorCache.get(mac)
      if (cachedSensor) {
         //try {
            logDebug "Creating new device for Hue sensor ${mac}: (${cachedSensor})"
            List ids = cachedSensor.ids?.sort() // sort numerically in case aren't (though usually retrieved from Bridge as such)
            String lastPart = ids.join("|") // DNI format is like CCH/BridgeID/Sensor/1|2|3, where 1, 2, and 3 are the Hue IDs for various components of this sensor
            String devDNI = "CCH/${app.getId()}/Sensor/${lastPart}"
            Map devProps = [name: cachedSensor.name]
            addChildDevice(childNamespace, driverName, devDNI, devProps)

         //}
         //catch (Exception ex) {
           // log.error "Unable to create new sensor device for $mac: $ex"
         //}
      } else {
         log.error "Unable to create new device for sensor $mac: MAC not found in Hue Bridge cache"
      }
   }    
   bridge.clearSensorsCache()
   bridge.getAllSensors()
   bridge.refresh()
   app.removeSetting("newSensors")
}

/** Creates new Hubitat devices for new user-selected buttons on button-device-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedButtonDevices() {
   String devDriver = "CoCoHue Button"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map buttonCache = bridge?.getAllButtonsCache()
   settings["newButtons"].each {
      Map b = buttonCache.get(it)
      if (b) {
         try {
            logDebug "Creating new device for Hue button device ${it} (${b.name})"
            String devDNI = "CCH/${app.getId()}/Button/${it}"
            Map devProps = [name: b.name]
            DeviceWrapper d = addChildDevice(childNamespace, devDriver, devDNI, devProps)
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
   //bridge.getAllButtons()
   app.removeSetting("newButtons")
}

/** Creates new Hubitat devices for new user-selected Labs devices on Labs device-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedLabsDevices() {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) log.error("Unable to find Bridge device")
   Map labsCache = bridge?.getAllLabsSensorsCache()
   settings["newLabsDevs"].each {
      Map d = labsCache.get(it)
      if (d) {
         try {
            logDebug "Creating new device for Hue Labs sensor device ${it} (${d.name})"
            String devDriver = "CoCoHue Generic Status Device"
            String devDNI = "CCH/${app.getId()}/SensorRL/${it}"
            Map devProps = [name: (settings["boolAppendLabs"] ? d.name + " (Hue Labs Formula)" : d.name)]
            DeviceWrapper dev = addChildDevice(childNamespace, devDriver, devDNI, devProps)
            dev?.updateDataValue("type", "CLIPGenericStatus")
         } catch (Exception ex) {
            log.error "Unable to create new device for $it: $ex"
         }
      } else {
         log.error "Unable to create new device for Labs device $it: ID not found on Hue Bridge"
      }
   }
   bridge.clearLabsSensorsCache()
   app.removeSetting("newLabsDevs")
}

/** Sends request for username creation to Bridge API. Intended to be called after user
 *  presses link button on Bridge
 */
void sendUsernameRequest(String protocol="http", Integer port=null) {
   logDebug "sendUsernameRequest()... (IP = ${state.ipAddress})"
   String locationNameNormalized = location.name?.replaceAll("\\P{InBasic_Latin}", "_").take(13) // Cap at first 13 characters (possible 30-char total limit?)
   String userDesc = locationNameNormalized ? "Hubitat CoCoHue#${locationNameNormalized}" : "Hubitat CoCoHue"
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
   log.warn params = params
   asynchttpPost("parseUsernameResponse", params, null)
}


/** Callback for sendUsernameRequest. Saves username in app state if Bridge is
 * successfully authorized, or logs error if unable to do so.
 */
void parseUsernameResponse(resp, data) {
   def body = resp.json
   logDebug "Attempting to request Hue Bridge username; result = ${body}"
   if (body.success != null) {
      if (body.success[0] != null) {
         if (body.success[0].username) {
               state.username = body.success[0].username
               state.bridgeAuthorized = true
               logDebug "Bridge authorized!"
         }
      }
   }
   else {
      if (body.error != null) {
         log.warn "  Error from Bridge: ${body.error}"
      }
      else {
         log.error "  Unknown error attempting to authorize Hue Bridge username"
      }
   }
}

/** Requests Bridge info (/description.xml by default) to verify that device is a
 *  Hue Bridge and to retrieve information necessary to either create the Bridge device
 *  (when parsed in parseBridgeInfoResponse if createBridge == true) or to add to the list
 *  of discovered Bridge devices (when createBridge == false). protocol, ip, and port are optional
 *  and will default to getBridgeData() values if not specified
 *  // TODO: When move away from SSDP, check /api/config endpoint instead (check also swversion for v2 API availability)
 */
void sendBridgeInfoRequest(Boolean createBridge=true, String protocol="http", String ip = null, Integer port=null,
                           String ssdpPath="/description.xml") {
   logDebug "Sending request for Bridge information"
   String fullHost = ip ? """${protocol ?: "http"}://${ip}${port ? ":$port" : ''}""" : getBridgeData().fullHost
   Map params = [
      uri: fullHost,
      path: ssdpPath,
      contentType: 'text/xml',
      timeout: 15
   ]
   asynchttpGet("parseBridgeInfoResponse", params, [createBridge: createBridge, protocol: protocol ?: "http",
                                                    port: port, ip: (ip ?: state.ipAddress)])
}

/** Parses response from GET of description.xml on the Bridge;
 *  verifies that device is a Hue Bridge (modelName contains "Philips Hue Bridge")
 * and obtains MAC address for use in creating Bridge DNI and device name
 */
private void parseBridgeInfoResponse(resp, data) {
   logDebug("Parsing response from Bridge information request (resp = $resp, data = $data)")
   groovy.util.slurpersupport.GPathResult body
   try {
      body = resp.xml
   }
   catch (Exception ex) {
      logDebug("  Responding device likely not a Hue Bridge: $ex")
      body = null
   }
   if (body?.device?.modelName?.text()?.contains("Philips hue bridge")) {
      String friendlyBridgeName
      String serial = body?.device?.serialNumber?.text().toUpperCase()
      if (serial) {
         logDebug "  Hue Bridge serial parsed as ${serial}; getting additional device info..."
         friendlyBridgeName = body?.device?.friendlyName
         if (friendlyBridgeName) friendlyBridgeName = friendlyBridgeName.substring(0,friendlyBridgeName.lastIndexOf(' ('-1)) // strip out parenthetical IP address
         DeviceWrapper bridgeDevice
         if (data?.createBridge) {
            log.debug "    Creating CoCoHue Bridge device for Brige with MAC $serial"
            state.bridgeID = serial.drop(6) // last (12-6=) 6 of MAC
            state.bridgeMAC = serial // full MAC
            try {
               bridgeDevice = getChildDevice("CCH/${app.getId()}") ?: getChildDevice("CCH/${state.bridgeID}")
               if (!bridgeDevice) bridgeDevice = addChildDevice(childNamespace, "CoCoHue Bridge", "CCH/${app.getId()}", null,
                                    [label: """CoCoHue Bridge ${state.bridgeID}${friendlyBridgeName ? " ($friendlyBridgeName)" : ""}""", name: "CoCoHue Bridge"])
               if (!bridgeDevice) {
                  log.error "    Bridge device unable to be created or found. Check that driver is installed and no existing device exists for this Bridge." 
               }
               if (bridgeDevice) state.bridgeLinked = true
               if (!(settings['boolCustomLabel'])) {
                  app.updateLabel("""CoCoHue - Hue Bridge Integration (${state.bridgeID}${friendlyBridgeName ? " - $friendlyBridgeName)" : ")"}""")
               }
            }
            catch (IllegalArgumentException e) { // could be bad DNI if already exists
               bridgeDevice = getChildDevice("CCH/${app.getId()}")
               if (bridgeDevice) {
                  log.error "   Error creating Bridge device: $e"
               }
               else {
                  log.error "    Error creating Bridge device. Ensure another device does not already exist for this Bridge. Error: $e"
               }
            }
            catch (Exception e) {
               log.error "    Error creating Bridge device: $e"
            }
            if (!state.bridgeLinked) log.error "    Unable to create Bridge device. Make sure driver installed and no Bridge device for this MAC already exists."
         }
         else { // createBridge = false, so either in discovery (so add to list instead) or received as part of regular app operation (check if IP address changed if used Bridge discovery)
            if (!(state.bridgeLinked)) { // so in discovery
               logDebug "  Adding Bridge with MAC $serial ($friendlyBridgeName) to list of discovered Bridges"
               if (!state.discoveredBridges) state.discoveredBridges = []
               if (!(state.discoveredBridges.any { it.containsKey(data?.ip) } )) {
                  state.discoveredBridges.add([(data.ip): """${(body?.device?.friendlyName) ?: "Hue Bridge"} - ${serial.toUpperCase()}"""])
               }
            }
            else { // Bridge already added, so likely added with discovery; check if IP changed
               logDebug "  Bridge already added; seaching if Bridge matches $serial"
               if (serial == state.bridgeMAC && serial != null) { // found a match for this Bridge, so update IP:
                  if (data.ip && settings['useSSDP']) {
                     state.ipAddress = data.ip
                     logDebug "  Bridge serial matched. Setting IP as ${state.ipAddress}"
                  }
                  state.remove('failedDiscos')
               }
               else {
                  state.failedDiscos= state.failedDiscos ? state.failedDiscos += 1 : 1
                  logDebug "  No matching Bridge serial found for ${state.bridgeMAC}. failedDiscos = ${state.failedDiscos}"
               }
            }
         }
      } else {
         log.error "Unexpected response received from Hue Bridge (no serial)"
      }
   } else {
      if (data?.createBridge) log.error("No Hue Bridge found at IP address")
      else logDebug "No Hue Bridge found at IP address"
   }
}

/** Handles response from SSDP (sent to discover Bridge) */
void ssdpHandler(evt) {
   Map parsedMap = parseLanMessage(evt?.description)
   if (parsedMap) {
      String ip = "${convertHexToIP(parsedMap?.networkAddress)}"
      String ssdpPath = parsedMap.ssdpPath
      if (ip) {
         logDebug "Device at $ip responded to SSDP; sending info request to see if is Hue Bridge"
         sendBridgeInfoRequest(false, "http", ip, null, ssdpPath ?: "/description.xml")
      }
      else {
         logDebug "In ssdpHandler but unable to obtain IP address from device response: $parsedMap"
      }
   }
   else {
      logDebug "In ssdpHandler but unable to parse LAN message from event: $evt?.description"
   }
   //log.warn parsedMap
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
   logDebug "Running getBridgeData()..."
   if (!state.ipAddress && settings['bridgeIP'] && !(settings['useSSDP'])) state.ipAddress = settings['bridgeIP'] // seamless upgrade from v1.x
   if (!state.username || !state.ipAddress) log.error "Missing username or IP address from Bridge"
   port = port ?: ((!(settings["useSSDP"]) && settings["customPort"]) ? settings["customPort"] as Integer : 80)
   Map map = [username: state.username, ip: "${state.ipAddress}", fullHost: "${protocol}://${state.ipAddress}:${port}"]
   return map
}

/**
 * Calls refresh() method on Bridge child, intended to be called at user-specified
 * polling interval
 * @param reschedule If true (default), re-schedules/resets next poll; not intended to be used if was scheduled
 * refresh but could be used if user- or app-initiated (e.g., if SSE-based refresh to handle odd cases)
 */
private void refreshBridge(Map<String,Boolean> options = [reschedule: false]) {
   logDebug "refreshBridge(reschedule = $reschedule)"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (!bridge) {
      log.error "No Bridge device found; could not refresh/poll"
      return
   }
   bridge.refresh()
   if (options.reschedule == true) scheduleRefresh()
}

/**
 * Calls refresh() method on Bridge child, not yet used but planned to be with SSE xy color received to poll for HS
 * Will wait 1s (to avoid cluster of refreshes if multiple devices change at same time), and will also
 * re-schedule next periodic refresh (if enabled) to extend polling interval so this "counts" as such
*/
private void refreshBridgeWithDealay() {
   logDebug "refreshBridgeWithDealay"
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (!bridge) {
      log.error "No Bridge device found; could not refresh"
      return
   }
   // Using 3-second delay; 1s seemed too fast in testing, so this might be good for most cases:
   runIn(3, "refreshBridge", [reschedule: true])
}

/**
 * Sets "status" attribute on Bridge child device (intended to be called from child light/group scene devices with
 * successful or unsuccessful commands to Bridge as needed
 * @param setToOnline Sets status to "Online" if true, else to "Offline"
 */
void setBridgeStatus(setToOnline=true) {
   DeviceWrapper bridge = getChildDevice("CCH/${app.getId()}")
   if (bridge == null) {
      log.error "No Bridge device found; could not set Bridge status"
      return
   }
   String value = setToOnline ? 'Online' : 'Offline'
   logDebug("  Setting Bridge status to ${value}...")
   if (bridge.currentValue("status") != value) bridge.doSendEvent("status", value)
}

/**
 *  Intended to be called by group child device when state is manipulated in a way that would affect
 *  all member bulbs. Updates member bulb states (so doesn't need to wait for next poll to update)
 *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
 *  @param ids Hue IDs of member bulbs to update
 *  @param isAllGroup Set to true if is "All Hue Lights" group (group 0); ids (ignored) can be null in this case. Defaults to false.
 */
 void updateMemberBulbStatesFromGroup(Map states, List ids, Boolean isAllGroup=false) {
   logDebug "Updating member bulb states after group device change... (ids = $ids, isAllGroup = $isAllGroup)"
   if (!isAllGroup) {
      ids?.each {
         DeviceWrapper dev = getChildDevice("CCH/${app.getId()}/Light/${it}")
         dev?.createEventsFromMap(states, false)
      }
   } else {
      List<DeviceWrapper> devList = getChildDevices().findAll { it.getDeviceNetworkId().startsWith("CCH/${app.getId()}/Light/") }
      // Update other gropus even though they aren't "bulbs":
      devList += getChildDevices().findAll { it.getDeviceNetworkId().startsWith("CCH/${app.getId()}/Group/") && !(it.getDeviceNetworkId() == "CCH/${app.getId()}/Group/0") }
      //logDebug("Updating states for: $devList")
      devList.each { it.createEventsFromMap(states, false) }
   }    
 }

 /**
  *  Intended to be called by bulb child device when state is manipulated in a way that would affect
  *  group and user has enabled this option. Updates group device states if this bulb ID is found as a
  *  member of that group (so doesn't need to wait for next poll to update)
  *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
  *  @param id Hue bulb ID to search all groups for (will update group if bulb found in group)
  */
 void updateGroupStatesFromBulb(Map states, id) {
   logDebug "Searching for group devices containing bulb $id to update group state after bulb state change..."
   List matchingGroupDevs = []
   getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("CCH/${app.getId()}/Group/")})?.each {
      if (it.getMemberBulbIDs()?.contains(id) || it.getDeviceNetworkId() == "CCH/${app.getId()}/Group/0") {
         logDebug("Bulb $id found in group ${it.toString()}. Updating states.")
         matchingGroupDevs.add(it)
      }
   }
   matchingGroupDevs.each { groupDev ->
      // Hue app reports "on" if any members on but takes last color/level/etc. from most recent
      // change, so emulate that behavior here (or does Hue average level of all? this is at least close...)
      Boolean onState = getIsAnyGroupMemberBulbOn(groupDev)
      groupDev.createEventsFromMap(states << ["on": onState], false)
   }
 }

/**
 *  Intended to be called by scene child device if GroupScene so other scenes belonging to same
 *  group can be set to "off" if user has this preference configured for scene.
 *  @param groupID Hue group ID (will search for GroupScenes belonging to this group), or use 0 for all CoCoHue scenes
  * @param excludeDNI Intended to be DNI of the calling scene; will exclude this from search since should remain on
 */
void updateSceneStateToOffForGroup(String groupID, String excludeDNI=null) {
   logDebug "Searching for scene devices matching group $groupID and excluding DNI $excludeDNI"
   List<DeviceWrapper> sceneDevs = []
   if (groupID == "0") {
      sceneDevs = getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("CCH/${app.getId()}/Scene/") &&
                                 it.getDeviceNetworkId() != excludeDNI})
   }
   else {
      sceneDevs = getChildDevices()?.findAll({it.getDeviceNetworkId()?.startsWith("CCH/${app.getId()}/Scene/") &&
                                 it.getDeviceNetworkId() != excludeDNI &&
                                 it.getGroupID() == groupID})
   }
   logDebug "updateSceneStateToOffForGroup matching scenes: $sceneDevs"
   sceneDevs.each { sc ->
		   if (sc.currentValue("switch") != "off") sc.doSendEvent("switch", "off")
   }
}

 /**
 * Finds Hubitat devices for member bulbs of group and returns true if any (that are found) are on; returns false
 * if all off or no member bulb devices found
 * @param Instance of CoCoHue Group device on which to check member bulb states
 */
Boolean getIsAnyGroupMemberBulbOn(groupDevice) {
   logDebug "Determining whether any group member bulbs on for group $groupDevice"
   Boolean retVal = false
   if (groupDevice) {
      List<DeviceWrapper> memberBulbDevs = []
      if (groupDevice.getDeviceNetworkId() == "CCH/${app.getId()}/Group/0") {
         memberBulbDevs = getChildDevices().findAll { it.getDeviceNetworkId().startsWith("CCH/${app.getId()}/Light/") }
      }
      else {
         groupDevice.getMemberBulbIDs().each { bulbId ->
               DeviceWrapper bulbDev = getChildDevice("CCH/${app.getId()}/Light/${bulbId}")
               if (bulbDev) memberBulbDevs.add(bulbDev)
         }
      }
      Boolean anyOn = memberBulbDevs.any { it.currentValue('switch') == 'on' }
      logDebug "Determined if any group member bulb on: $anyOn"
      return anyOn
   }
}

/**
 *  Stores EventStream status (online = true if connected, otherwise false); used to cache so
 *  do not have to ask Bridge each time want to know. Intended to be called by Bridge device in response
 *  to changes.
 */
void setEventStreamOpenStatus(Boolean isOnline) {
   logDebug "setEventStreamOpenStatus($isOnline)"
   state.eventStreamOpenStatus = isOnline
}

/**
 *  Returns true if app configured to use EventStream/SSE, else false (proxy since cannot directly access settings from child)
 */
Boolean getEventStremEnabledSetting() {
   return (settings.useEventStream == true) ? true : false
}

/**
 *  Gets EventStream status (returns true if connected, otherwise false); gets from cache (see storeEventStreamOpenStatus())
 *  so do not have to ask Bridge each time want to know. Intended to be called by light/group/etc. devices when need to know.
 */
Boolean getEventStreamOpenStatus() {
   logDebug "getEventStreamOpenStatus (returning ${state.eventStreamOpenStatus})"
   return (state.eventStreamOpenStatus == true) ? true : false
}

void appButtonHandler(btn) {
   switch(btn) {
      case "btnBulbRefresh":
      case "btnGroupRefresh":
      case "btnSceneRefresh":
      case "btnSensorRefresh":
      case "btnLabsRefresh":
      case "btnButtonRefresh":
         // Just want to resubmit page, so nothing
         break
      case "btnDiscoBridgeRefresh":
         sendBridgeDiscoveryCommand()
         break
      default:
         log.warn "Unhandled app button press: $btn"
   }
}

private void logDebug(str) {
   if (!(settings.enableDebug == false)) log.debug(str)
}
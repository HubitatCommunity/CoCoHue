/**
 * ===========================  CoCoHue - Hue Bridge Integration =========================
 *
 *  Copyright 2019-2020 Robert Morris
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum.
 *
 *  Copyright 2019-2020 Robert Morris
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
 *  Last modified: 2020-05-05
 *  Version: 2.0.0-preview.1
 * 
 *  Changelog:
 * // TODO:  reduce no. of child->parent calls?
 *  v2.0   - New non-parent/child structure and name change; Bridge discovery; Bridge linking improvements (fewer pages);
 *           added documentation links
 *           Additiononal device features; child devices now (optionally) deleted when app uninstalled
 *  v1.9   - Added CT and dimmable bulb types
 *  v1.7   - Addition of new child device types, updating groups from member bulbs
 *  v1.6   - Added options for bulb and group deivce naming
 *  v1.5   - Added scene integration
 *  v1.1   - Added more polling intervals
 *  v1.0   - Initial Public Release
 */ 

import groovy.transform.Field

@Field static String childNamespace = "RMoRobert" // namespace of child device drivers
@Field static Map driverMap = [ "extended color light":     "CoCoHue RGBW Bulb",
                                "color light":              "CoCoHue RGBW Bulb",            
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
    documentationLink: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    //Uncomment the following line if upgrading from existing CoCoHue 1.x installation:
    //parent: "RMoRobert:CoCoHue (Parent App)",
)

preferences {
    page(name: "pageFirstPage", content: "pageFirstPage")
    page(name: "pageIncomplete", content: "pageIncomplete")
    page(name: "pageAddBridge", content: "pageAddBridge")
    page(name: "pageReAddBridge", content: "pageReAddBridge")
    page(name: "pageLinkBridge", content: "pageLinkBridge")
    page(name: "pageManageBridge", content: "pageManageBridge")
    page(name: "pageSelectLights", content: "pageSelectLights")
    page(name: "pageSelectGroups", content: "pageSelectGroups")
    page(name: "pageSelectScenes", content: "pageSelectScenes")
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
}

void initialize() {
    log.debug("Initializing...")
    unschedule()
    state.remove('discoveredBridges')
    if (settings["useSSDP"] == true || settings["useSSDP"] == null) {
        log.debug("Subscribing to ssdp...")
        subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
        subscribe(location, "systemStart", hubRestartHandler)
        schedule("${Math.round(Math.random() * 60)} ${Math.round(Math.random() * 60)} 6 ? * * *",
                  periodicSendDiscovery)
        if (state.bridgeAuthorized) sendBridgeDiscoveryCommand() // do discovery if user clicks 'Done'
    }
    else {
        unsubscribe() // remove or modify if ever subscribe to more than SSDP
    }

    int disableTime = 1800
    if (enableDebug) {
        log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
        runIn(disableTime, debugOff)
    }

    def pollInt = settings["pollInterval"]?.toInteger()
    // If change polling options in UI, may need to modify some of these cases:
    switch (pollInt ?: 0) {
        case 0:
            logDebug("Polling disabled; not scheduling")
            break
        case 1..59:
            logDebug("Scheduling polling every ${pollInt} seconds")
            schedule("${Math.round(Math.random() * pollInt)}/${pollInt} * * ? * * *", "refreshBridge")
            break
        case 60..259:
            logDebug("Scheduling polling every 1 minute")
            runEvery1Minute("refreshBridge")
            break
        case 300..1800:
            logDebug("Schedulig polling every 5 minutes")
            runEvery5Minutes("refreshBridge")
            break
        default:
            logDebug("Scheduling polling every hour")
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
    if (settings["useSSDP"] == true || settings["useSSDP"] == null) {
        // If not in the last 5 minutes:
        if (!state.lastDiscoCommand && (now() -  state.lastDiscoCommand >= 300000)) {
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
    log.warn("Disabling debug logging")
    app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
    state.authRefreshInterval = 5
    state.discoTryCount = 0
    state.authTryCount = 0
    if (app.getInstallationState() == "INCOMPLETE") {
        dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
        section() {
            paragraph("Please press \"Done\" to install CoCoHue.<br>Then, re-open to set up your Hue Bridge.")
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
    logDebug("pageAddBridge()...")
    Integer discoMaxTries = 60
    if (settings['boolReauthorize']) {
        state.remove('bridgeAuthorized')
        app.removeSetting('boolReauthorize')
    }
    if (settings["useSSDP"] == true || settings["useSSDP"] == null && state.discoTryCount < 5) {
        logDebug("Subscribing to and sending SSDP discovery...")
        subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
        sendBridgeDiscoveryCommand()
    }
    String nextPageName = ((settings["useSSDP"] != false && settings["selectedDiscoveredBridge"]) || settings['bridgeIP']) ?
                          "pageLinkBridge" : "pageAddBridge"
    dynamicPage(name: "pageAddBridge", uninstall: true, install: false,
                refreshInterval: (selectedDiscoveredBridge ? null : state.authRefreshInterval), nextPage: nextPageName) {
        section("Add Hue Bridge") {
            input(name: "useSSDP", type: "bool", title: "Discover Hue Bridges automatically", defaultValue: true, submitOnChange: true)
            if (settings["useSSDP"] != false) {
                if (!(state.discoveredBridges)) {
                    paragraph("Please wait while Hue Bridges are discovered...")
                }
                else {
                    input(name: "selectedDiscoveredBridge", type: "enum", title: "Discovered bridges:", options: state.discoveredBridges,
                          multiple: false, submitOnChange: true)
                    input(name: "btnDiscoBridgeRefresh", type: "button", title: "Refresh Bridge List")
                    if (!(settings['selectedDiscoveredBridge'])) {
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
                if (state.discoTryCount > discoMaxTries && !(state.discoveredBridges)) {
                    state.remove('authRefreshInterval')
                    paragraph("No bridges have been found. Please go back and try again, or consider using manual setup.")
                }
            } else { 
                unsubscribe() // remove or modify if ever subscribe to more than SSDP above
                input(name: "bridgeIP", type: "string", title: "Hue Bridge IP address:", required: false, defaultValue: null, submitOnChange: true)            
                if (settings['bridgeIP'] && !state.bridgeLinked || !state.bridgeAuthorized) {
                    paragraph("<strong>Press the button on your Hue Bridge,</strong> then press \"Next\" to continue.")
                }
            }
        }
    }
}

def pageReAddBridge() {
    logDebug("pageReAddBridge()...")
    state.authRefreshInterval = 5
    state.discoTryCount = 0
    state.authTryCount = 0
    if (settings["useSSDP"] == true || settings["useSSDP"] == null && state.discoTryCount < 5) {
        logDebug("Subscribing to and sending SSDP discovery...")
        subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
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
            input(name: "boolReauthorize", type: "bool", title: "Request new Bridge username (re-authorize)", defaultValue: false)
            paragraph("<strong>Press \"Next\" to continue.</strong>")
        }
    }
}

def pageLinkBridge() {
    logDebug("Beginning brdige link process...")
    String ipAddress = (settings['useSSDP'] != false) ? settings['selectedDiscoveredBridge'] : settings['bridgeIP']
    state.ipAddress = ipAddress
    logDebug("  IP address = ${state.ipAddress}")
    Integer authMaxTries = 35
    if (!(settings['useSSDP'] == false)) {
        if (!(settings['selectedDiscoveredBridge'])) {
            dynamicPage(name: "pageLinkBridge", uninstall: true, install: false, nextPage: "pageAddBridge") {
                section() {
                    paragraph('No Bridge selected. Click "Next" to return to the bridge selection page, and try again.')
                }
            }
        }
    }
    String nextPageName = (state.authTryCount >= authMaxTries) || (state.bridgeLinked && state.bridgeAuthorized) ?
                            "pageFirstPage" : "pageLinkBridge"
    dynamicPage(name: "pageLinkBridge", refreshInterval: state.authRefreshInterval, uninstall: true, install: false,
                nextPage: nextPageName) {  
        section("Linking Hue Bridge") {
            if (!(state["bridgeAuthorized"])) {
                log.debug("Attempting Hue Bridge authorization; attempt number ${state.authTryCount+1}")
                sendUsernameRequest()
                state.authTryCount += 1
                paragraph("Waiting for Bridge to authorize. This page will automatically refresh.")
                if (state.authTryCount > 5 && state.authTryCount < authMaxTries) {
                    def strParagraph = "Still waiting for authorization. Please make sure you pressed " +
                        "the button on the Hue Bridge."
                    if (state.authTryCount > 10) {
                        if (!settings['useSSDP']) strParagraph + "Also, verify that your Bridge IP address is correct: ${state.ipAddress}"
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
                    log.debug("Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat...")
                    paragraph("Bridge authorized. Requesting information from Bridge and creating Hue Bridge device on Hubitat...")
                    sendBridgeInfoRequest(true)
                } else {
                    logDebug("Bridge already linked; skipping Bridge device creation")
                    if (state.bridgeLinked && state.bridgeAuthorized) {
                        state.remove('discoveredBridges')
                        state.remove('authRefreshInterval')
                        app.clearSetting('selectedDiscoveredBridge')
                        paragraph("<b>Your Hue Bridge has been linked!</b> Press \"Next\" to begin adding lights, groups, " +
                                    "or scenes.")
                    }
                    else {
                        paragraph("There was a problem authorizing or linking your Hue Bridge. Please start over and try again.")
                    }
                }
            }
        }
    }
}
def pageManageBridge() {
    if (settings["newBulbs"]) {
        logDebug("New bulbs selected. Creating...")
        createNewSelectedBulbDevices()
    }
    if (settings["newGroups"]) {
        logDebug("New groups selected. Creating...")
        createNewSelectedGroupDevices()
    }
    if (settings["newScenes"]) {
        logDebug("New scenes selected. Creating...")
        createNewSelectedSceneDevices()
    }
    // General cleanup in case left over from discovery:
    state.remove('authTryCount')
    state.remove('discoTryCount')
    // More cleanup...
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    bridge.clearBulbsCache()
    bridge.clearGroupsCache()
    bridge.clearScenesCache()
    state.remove('sceneFullNames')
    state.remove('addedBulbs')
    state.remove('addedGroups')
    state.remove('addedScenes')

    dynamicPage(name: "pageManageBridge", uninstall: true, install: true) {  
        section("Manage Hue Bridge Devices:") {
            href(name: "hrefSelectLights", title: "Select Lights",
                description: "", page: "pageSelectLights")
            href(name: "hrefSelectGroups", title: "Select Groups",
                description: "", page: "pageSelectGroups")
            href(name: "hrefSelectScenes", title: "Select Scenes",
                description: "", page: "pageSelectScenes")
        }
        section("Advanced Options", hideable: true, hidden: true) {
            href(name: "hrefReAddBridge", title: "Edit Bridge IP, re-authorize, or re-discover...",
                 description: "", page: "pageReAddBridge")
            input(name: "showAllScenes", type: "bool", title: "Allow adding scenes not associated with rooms/zones (not recommended; devices will not support \"off\" command)")
            input(name: "deleteDevicesOnUninstall", type: "bool", title: "Delete devices created by app (Bridge, light, group, and scene) if uninstalled", defaultValue: true)
        }        
        section("Other Options:") {
            input(name: "pollInterval", type: "enum", title: "Poll bridge every...",
               options: [0:"Disabled", 10:"10 seconds", 15:"15 seconds", 20:"20 seconds", 30:"30 seconds", 45:"45 seconds", 60:"1 minute (recommended)",
                         300:"5 minutes", 3600:"1 hour"], defaultValue:60)
            input(name: "boolCustomLabel", type: "bool", title: "Customize the name of this CoCoHue app instance", defaultValue: false, submitOnChange: true)
            if (settings['boolCustomLabel']) label(title: "Custom name for this app", required: false)
            input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        }
    }
}

def pageSelectLights() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    bridge.getAllBulbs()
    def arrNewBulbs = []
    def bulbCache = bridge.getAllBulbsCache()
    log.warn "pageSelectLights refreshInterval: ${bulbCache ? 0 : 6}"
    dynamicPage(name: "pageSelectLights", refreshInterval: bulbCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
        state.addedBulbs = [:]  // To be populated with lights user has added, matched by Hue ID
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        if (bulbCache) {
            bulbCache.each {
                def bulbChild = getChildDevice("CCH/${state.bridgeID}/Light/${it.key}")
                if (bulbChild) {
                    state.addedBulbs.put(it.key, bulbChild.name)
                } else {
                    def newBulb = [:]
                    newBulb << [(it.key): (it.value.name)]
                    arrNewBulbs << newBulb
                }
            }
            arrNewBulbs = arrNewBulbs.sort { a, b ->
                // Sort by bulb name (default would be hue ID)
                a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
            }
            state.addedBulbs = state.addedBulbs.sort { it.value }
        }
        if (!bulbCache) {
            section("Discovering bulbs/lights. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnBulbRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Lights") {
                input(name: "newBulbs", type: "enum", title: "Select Hue lights to add:",
                      multiple: true, options: arrNewBulbs)
                input(name: "boolAppendBulb", type: "bool", title: "Append \"(Hue Light)\" to Hubitat device name")
            }
            section("Previously added lights") {
                if (state.addedBulbs) {
                    state.addedBulbs.each {
                        paragraph(it.value, /*width: 6*/)
                        //input(name: "btnRemove_Light_ID", type: "button", title: "Remove", width: 3)
                        //input(name: "btnRename_Light_ID", type: "button", title: "Rename", width: 3)
                    }
                }
                else {
                    paragraph("No bulbs added")
                }
            }
            section("Rediscover Bulbs") {
                paragraph("If you added new lights to the Hue Bridge and do not see them above, click/tap the button " +
                          "below to retrieve new information from the Bridge.")
                input(name: "btnBulbRefresh", type: "button", title: "Refresh Bulb List", submitOnChange: true)
            }
        }
    }
}

def pageSelectGroups() {        
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    bridge.getAllGroups()
    def arrNewGroups = []
    def groupCache = bridge.getAllGroupsCache()
    dynamicPage(name: "pageSelectGroups", refreshInterval: groupCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
        state.addedGroups = [:]  // To be populated with groups user has added, matched by Hue ID

        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        if (groupCache) {
            groupCache.each {
                def groupChild = getChildDevice("CCH/${state.bridgeID}/Group/${it.key}")
                if (groupChild) {
                    state.addedGroups.put(it.key, groupChild.name)
                } else {
                    def newGroup = [:]
                    newGroup << [(it.key): (it.value.name)]
                    arrNewGroups << newGroup
                }
            }
            arrNewGroups = arrNewGroups.sort {a, b ->
                // Sort by group name (default would be Hue ID)
                a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
                }
            state.addedGroups = state.addedGroups.sort { it.value }
        }

        if (!groupCache) { 
            section("Discovering groups. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnGroupRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Groups") {
                input(name: "newGroups", type: "enum", title: "Select Hue groups to add:",
                      multiple: true, options: arrNewGroups)
                input(name: "boolAppendGroup", type: "bool", title: "Append \"(Hue Group)\" to Hubitat device name")
            }
            section("Previously added groups") {
                if (state.addedGroups) {
                    state.addedGroups.each {
                        paragraph(it.value)
                    }
                }
                else {
                    paragraph("No groups added")
                }
            }
            section("Rediscover Groups") {
                paragraph("If you added new groups to the Hue Bridge and do not see them above, click/tap the button " +
                          "below to retrieve new information from the Bridge.")
                input(name: "btnGroupRefresh", type: "button", title: "Refresh Group List", submitOnChange: true)
            }
        }
    }    
}

def pageSelectScenes() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    bridge.getAllScenes()
    def arrNewScenes = []
    def sceneCache = bridge.getAllScenesCache()

    def groupCache = bridge.getAllGroupsCache()
    def grps = [:]
    groupCache?.each { grps << [(it.key) : (it.value.name)] }
    dynamicPage(name: "pageSelectScenes", refreshInterval: sceneCache ? 0 : 7, uninstall: true, install: false, nextPage: "pageManageBridge") {  
        state.addedScenes = [:]  // To be populated with scenes user has added, matched by Hue ID
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        if (sceneCache) {
            state.sceneFullNames = [:]
            sceneCache.each { sc ->
                def sceneChild = getChildDevice("CCH/${state.bridgeID}/Scene/${sc.key}")
                if (sceneChild) {
                    state.addedScenes.put(sc.key, sceneChild.name)
                } else {
                    def newScene = [:]
                    def sceneName = sc.value.name
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
            state.addedScenes = state.addedScenes.sort { it.value }
        }

        if (!sceneCache) {
            section("Discovering scenes. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnSceneRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Scenes") {
                input(name: "newScenes", type: "enum", title: "Select Hue scenes to add:",
                      multiple: true, options: arrNewScenes)
            }
            section("Previously added scenes") {
                if (state.addedScenes) {
                    state.addedScenes.each {
                        paragraph(it.value)
                    }
                }
                else {
                    paragraph("No scenes added")
                }
            }
            section("Rediscover Scenes") {
                paragraph("If you added new scenes to the Hue Bridge and do not see them above, if room/zone names are " +
                          "missing from scenes (if assigned to one), or if you changed the \"Allow adding scenes not associated with rooms/zones...\" setting, " +
                          "click/tap the button below to retrieve new information from the Bridge.")
                input(name: "btnSceneRefresh", type: "button", title: "Refresh Scene List", submitOnChange: true)
            }
        }
    }
}

/** Creates new Hubitat devices for new user-selected bulbs on lights-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedBulbDevices() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def bulbCache = bridge?.getAllBulbsCache()
    settings["newBulbs"].each {
        def b = bulbCache.get(it)
        if (b) {
            try {
                logDebug("Creating new device for Hue light ${it} (${b.name})")
                def devDriver = driverMap[b.type.toLowerCase()] ?: driverMap["DEFAULT"]
                def devDNI = "CCH/${state.bridgeID}/Light/${it}"
                def devProps = [name: (settings["boolAppendBulb"] ? b.name + " (Hue Bulb)" : b.name)]
                addChildDevice(childNamespace, devDriver, devDNI, null, devProps)

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
    def driverName = "CoCoHue Group"
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def groupCache = bridge?.getAllGroupsCache()
    settings["newGroups"].each {
        def g = groupCache.get(it)
        if (g) {
            try {
                logDebug("Creating new device for Hue group ${it} (${g.name})")
                def devDNI = "CCH/${state.bridgeID}/Group/${it}"
                def devProps = [name: (settings["boolAppendGroup"] ? g.name + " (Hue Group)" : g.name)]
                addChildDevice(childNamespace, driverName, devDNI, null, devProps)

            } catch (Exception ex) {
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
def createNewSelectedSceneDevices() {
    def driverName = "CoCoHue Scene"
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def sceneCache = bridge?.getAllScenesCache()
    settings["newScenes"].each {
        def sc = sceneCache.get(it)
        if (sc) {
            try {
                logDebug("Creating new device for Hue group ${it}" +
                         " (state.sceneFullNames?.get(it) ?: sc.name)")
                def devDNI = "CCH/${state.bridgeID}/Scene/${it}"
                def devProps = [name: (state.sceneFullNames?.get(it) ?: sc.name)]
                def dev = addChildDevice(childNamespace, driverName, devDNI, null, devProps)
            } catch (Exception ex) {
                log.error("Unable to create new scene device for $it: $ex")
            }
        } else {
            log.error("Unable to create new scene for scene $it: ID not found on Hue Bridge")
        }
    }
    bridge.clearScenesCache()
    //bridge.getAllScenes()
    app.removeSetting("newScenes")
    state.remove("sceneFullNames")
}

/** Sends request for username creation to Bridge API. Intended to be called after user
 *  presses link button on Bridge
 */
void sendUsernameRequest() {
    logDebug("sendUsernameRequest()... (IP = ${state.ipAddress})")
    def userDesc = location.name ? "Hubitat CoCoHue#${location.name}" : "Hubitat CoCoHue"
    def host = "${state.ipAddress}:80"
    sendHubCommand(new hubitat.device.HubAction([
        method: "POST",
        path: "/api",
        headers: [HOST: host],
        body: [devicetype: userDesc]
        ], null, [callback: "parseUsernameResponse"])
    )
}

/** Callback for sendUsernameRequest. Saves username in app state if Bridge is
 * successfully authorized, or logs error if unable to do so.
 */
void parseUsernameResponse(hubitat.device.HubResponse resp) {
    def body = resp.json
    logDebug("Attempting to request Hue Bridge username; result = ${body}")    
    if (body.success != null) {
        if (body.success[0] != null) {
            if (body.success[0].username) {
                state.username = body.success[0].username
                state.bridgeAuthorized = true
            }
        }
    }
    else {
        if (body.error != null) {
            log.warn("  Error from Bridge: ${body.error}")
        }
        else {
            log.error("  Unknown error attempting to authorize Hue Bridge username")
        }
    }
}

/** Requests Bridge info (/description.xml by default) to verify that device is a
 *  Hue Bridge and to retrieve information necessary to either create the Bridge device
 *  (when parsed in parseBridgeInfoResponse if createBridge == true) or to add to the list
 *  of discovered Bridge devices (when createBridge == false). protocol, ip, and port are optional
 *  and will default to getBridgeData() values if not specified
 */
void sendBridgeInfoRequest(Boolean createBridge=true, String protocol="http", String ip = null, Integer port=80,
                           String ssdpPath="/description.xml") {
    log.debug("Sending request for Bridge information")
    def fullHost = ip ? "${protocol}://${ip}:${port}" : getBridgeData().fullHost
    def params = [
        uri: fullHost,
        path: ssdpPath,
        contentType: 'text/xml',
        timeout: 15
        ]
    asynchttpGet("parseBridgeInfoResponse", params, [createBridge: createBridge, protocol: protocol,
                                                     port: port, ip: (ip ?: state.ipAddress)])
}

/** Parses response from GET of description.xml on the Bridge;
 *  verifies that device is a Hue Bridge (modelName contains "Philips Hue Bridge")
 * and obtains MAC address for use in creating Bridge DNI and device name
 */
private parseBridgeInfoResponse(resp, data) {
    log.debug("Parsing response from Bridge information request (resp = $resp, data = $data)")
    def body = resp.xml
    if (body?.device?.modelName?.text().contains("Philips hue bridge")) {
        String friendlyBridgeName
        String serial = body?.device?.serialNumber?.text().toUpperCase()
        if (serial) {
            log.debug("  Hue Bridge serial parsed as ${serial}; getting additional device info...")
            friendlyBridgeName = body?.device?.friendlyName
            if (friendlyBridgeName) friendlyBridgeName = friendlyBridgeName.substring(0,friendlyBridgeName.lastIndexOf(' ('-1)) // strip out parenthetical IP address
            def bridgeDevice           
            if (data?.createBridge) {
                log.debug("    Creating CoCoHue Bridge device for Brige with MAC $serial")
                state.bridgeID = serial.drop(6) // last (12-6=) 6 of MAC
                state.bridgeMAC = serial // full MAC
                try {
                    bridgeDevice = getChildDevice("CCH/${state.bridgeID}")
                    if (!bridgeDevice) bridgeDevice = addChildDevice(childNamespace, "CoCoHue Bridge", "CCH/${state.bridgeID}", null,
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
                    bridgeDevice = getChildDevice("CCH/${state.bridgeID}")
                    if (bridgeDevice) {
                        
                    }
                    else {                        
                        log.error("    Error creating Bridge device. Ensure another device does not already exist for this Bridge. Error: $e")
                    }                                
                }
                catch (Exception e) {
                    log.error("    Error creating Bridge device: $e")
                }
                if (!state.bridgeLinked) log.error("    Unable to create Bridge device. Make sure driver installed and no Bridge device for this MAC already exists.")
            }
            else { // createBridge = false, so either in discovery (so add to list instead) or received as part of regular app operation (check if IP address changed if used Bridge discovery)
                if (!(state.bridgeLinked)) { // so in discovery
                    logDebug("  Adding Bridge with MAC $serial ($friendlyBridgeName) to list of discovered Bridges")
                    if (!state.discoveredBridges) state.discoveredBridges = []
                    if (!(state.discoveredBridges.any { it.containsKey(data?.ip) } )) {
                        state.discoveredBridges.add([(data.ip): """${(body?.device?.friendlyName) ?: "Hue Bridge"} - ${serial.toUpperCase()}"""])
                    }
                }
                else { // Bridge already added, so likely added with discovery; check if IP changed
                    logDebug("  Bridge already added; seaching if Bridge matches $serial")
                    if (serial == state.bridgeMAC && serial != null) { // found a match for this Bridge, so update IP:
                        if (data.ip && settings['useSSDP']) state.ipAddress = data.ip   
                    }
                }
            }
        } else {
            log.error("Unexpected response received from Hue Bridge (no serial)")
        }
    } else {
        if (data?.createBridge) log.error("No Hue Bridge found at IP address")
        else logDebug("No Hue Bridge found at IP address")
    }
}

/** Handles response from SSDP (sent to discover Bridge) */
void ssdpHandler(evt) {
    def parsedMap = parseLanMessage(evt?.description)
    if (parsedMap) {
        def ip = convertHexToIP(parsedMap?.networkAddress)
        def ssdpPath = parsedMap.ssdpPath
        if (ip) {
            logDebug("Device at $ip responded to SSDP; sending info request to see if is Hue Bridge")
            sendBridgeInfoRequest(false, "http", ip, 80, ssdpPath ?: "/description.xml")
        }
        else {
            logDebug("In ssdpHandler but unable to obtain IP address from device response: $parsedMap")
        }
    }
    else {
        logDebug("In ssdpHandler but unable to parse LAN message from event: $evt?.description")
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
Map getBridgeData(String protocol="http", Integer port=80) {
    logDebug("Running getBridgeData()...")
    if (!state.ipAddress && settings['bridgeIP'] && !(settings['useSSDP'])) state.ipAddress = settings['bridgeIP'] // seamless upgrade from v1.x
    if (!state["username"] || !state.ipAddress) log.error "Missing username or IP address from Bridge"
    def map = [username: state.username, ip: "${state.ipAddress}", fullHost: "${protocol}://${state.ipAddress}:${port}"]
    return map
}

/**
 * Calls refresh() method on Bridge child, intended to be called at user-specified
 * polling interval
 */
private void refreshBridge() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) {
            log.error "No Bridge device found; could not refresh/poll"
            return
    }
    logDebug("Polling Bridge...")
    bridge.refresh()
}

/**
 * Sets "status" attribute on Bridge child device (intended to be called from child light/group scene devices with
 * successful or unsuccessful commands to Bridge as needed
 * @param setToOnline Sets status to "Online" if true, else to "Offline"
 */
void setBridgeStatus(setToOnline=true) {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) {
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
 */
 void updateMemberBulbStatesFromGroup(Map states, List ids) {
    logDebug("Updating member bulb $ids states after group device change...")
    ids?.each {
        def device = getChildDevice("CCH/${state.bridgeID}/Light/${it}")
        device?.createEventsFromMap(states, false)
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
    logDebug("Searching for group devices containing bulb $id to update group state after bulb state change...")
    //TODO: There is a better, Groovier way to do this search...
    def matchingGroups = []
    getChildDevices()?.each {
        if (it.getDeviceNetworkId()?.startsWith("CCH/${state.bridgeID}/Group/")) {
            if (it.getMemberBulbIDs()?.contains(id)) {
                logDebug("Bulb $id found in group. Updating states.")
                matchingGroups.add(it)
            }
        }
    }
    matchingGroups.each {
        // Hue app reports "on" if any members on but takes last color/level/etc. from most recent
        // change, so emulate that behavior here
        def onState = getIsAnyGroupMemberBulbOn(it)
        it.createEventsFromMap(states << ["on": onState], false)
    }
 }

 /**
 * Finds Hubitat devices for member bulbs of group and returns true if any (that are found) are on; returns false
 * if all off or no member bulb devices found
 * @param Instance of CoCoHue Group device on which to check member bulb states
 */
Boolean getIsAnyGroupMemberBulbOn(groupDevice) {
    logDebug ("Determining whether any group member bulbs on for group $groupID")
    def retVal = false
    def memberDevices = []
    if (groupDevice) {
        groupDevice.getMemberBulbIDs().each {
            if (!retVal) { // no point in continuing to check if already found one on
                def memberLight = getChildDevice("CCH/${state.bridgeID}/Light/${it}")
                if (memberLight?.currentValue("switch") == "on") retVal = true
            }
        }
    } else {
        logDebug "No group device found for group ID $groupID"
    }
    logDebug("Determined if any group member bulb on: $retVal")
    return retVal
 }

def appButtonHandler(btn) {
    switch(btn) {
        case "btnBulbRefresh":
        case "btnGroupRefresh":
        case "btnSceneRefresh":
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
    if (!(settings['enableDebug'] == false)) log.debug(str)
}
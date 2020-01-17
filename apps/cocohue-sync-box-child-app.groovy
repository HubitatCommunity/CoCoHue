/**
 * ==========================  CoCoHue (Sync Box Instance Child App) ==========================
 *
 *  DESCRIPTION:
 *  Community-developed Hue Sync Box integration app for Hubitat
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum.
 *
 *  Copyright 2019-2020 Daniel Terryn
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
 *  Last modified: 2020-01-17
 * 
 *  Changelog:
 * 
 *  v1.0 - Initial Public Release
 */ 

definition (
    name: "CoCoHue (Sync Box Instance Child App)",
    namespace: "RMoRobert",
    author: "Robert Morris",
    description: "Integrate Hue Sync Box into Hubitat (use parent app to create instances)",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    parent: "RMoRobert:CoCoHue - Hue Bridge Integration",
)

preferences {
    page(name: "pageFirstPage", content: "pageFirstPage")
    page(name: "pageAddSyncBox", content: "pageAddSyncBox")
    page(name: "pageLinkSyncBox", content: "pageLinkSyncBox")
    page(name: "pageSyncBoxLinked", content: "pageSyncBoxLinked")
    page(name: "pageManageSyncBox", content: "pageManageSyncBox")
}

/** Namespace to search/use for child device driver creation */
def getChildNamespace() {
    return "RMoRobert"
}

def installed() {
    log.info("Installed with settings: ${settings}")
    initialize()
}

def uninstalled() {
    log.info("Uninstalling")
}

def updated() {
    log.info("Updated with settings: ${settings}")
    initialize()
}

def initialize() {
    log.debug("Initializing...")
    unschedule()
    unsubscribe()    
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
            schedule("${Math.round(Math.random() * pollInt)}/${pollInt} * * ? * * *", "refreshSyncBox")
            break
        case 60..259:
            logDebug("Scheduling polling every 1 minute")
            runEvery1Minute("refreshSyncBox")
            break
        case 300..1800:
            logDebug("Schedulig polling every 5 minutes")
            runEvery5Minutes("refreshSyncBox")
            break
        default:
            logDebug("Scheduling polling every hour")
            runEvery1Hour("refreshSyncBox")                
    }
}

def debugOff() {
    log.warn("Disabling debug logging")
    app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
    return (state.syncBoxLinked ? pageManageSyncBox() : pageAddSyncBox())
}

def pageAddSyncBox() {
    dynamicPage(name: "pageAddSyncBox", uninstall: true, install: false, nextPage: pageLinkSyncBox) {
        state.authRefreshInterval = 10
        state.authTryCount = 0
        section("Add Hue Sync Box") {
            input(name: "syncBoxIP", type: "string", title: "Hue Sync Box IP address:", required: false, defaultValue: null, submitOnChange: true)            
            if (settings["syncBoxIP"] && state.syncBoxLinked) {
                input(name: "boolForceCreateSyncBox", type: "bool", title: "Force recreation of Sync Box child device)", submitOnChange: true)
            }
            if (settings["syncBoxIP"] && !state.syncBoxLinked || settings["boolForceCreateSyncBox"]) {
                paragraph("<strong>Press the button on your Hue Sycn Box for 2 seconds,</strong> then click/tap the \"Next\" button to continue.")
            }
        }
    }
}

def pageLinkSyncBox() {
    def authMaxTries = 20
    if (settings["boolForceCreateSyncBox"]) {
        state.remove("syncBoxAuthorized")
        app.updateSetting("boolForceCreateSyncBox", false)
    }
    
    dynamicPage(name: "pageLinkSyncBox", refreshInterval: state.authRefreshInterval, uninstall: true, install: false, nextPage: "pageSyncBoxLinked") {  
        section("Linking Hue Sync Box") {
            if (!(state["syncBoxAuthorized"])) {
                log.debug("Attempting Hue Sync Box authorization; attempt number ${state.authTryCount+1}")
                sendUsernameRequest()
                state.authTryCount = state.authTryCount + 1
                paragraph("Waiting for Sync Box to authorize. This page will automatically refresh.")
                if (state.authTryCount > 5 && state.authTryCount < authMaxTries) {
                    def strParagraph = "Still waiting for authorization. Please make sure you pressed " +
                        "the button on the Hue Sync Box for 2 seconds."
                    if (state.authTryCount > 10) {
                        strParagraph + "Also, verify that your Sync Box IP address is correct: ${settings["syncBoxIP"]}"
                    }
                    paragraph(strParagraph)
                }
                if (state.authTryCount >= authMaxTries) {
                    paragraph("<b>Authorization timed out. Please go back to the previous page, check your settings, " +
                              "and try again.</b>")
                }                
            }
            else {
                if (!state.syncBoxLinked) {
                    log.debug("Hue Sync Box authorized. Requesting info from Sync Box and creating Sync Box device...")
                    sendSyncBoxInfoRequest()
                } else {
                    logDebug("Sync Box device already exits; skipping creation")
                }
                paragraph("<b>Your Hue Sync Box has been linked!</b> Press \"Next\" to continue.")
            }
        }
    }
}

def pageSyncBoxLinked() {
    dynamicPage(name: "pageSyncBoxLinked", uninstall: true, install: true, nextPage: pageFirstPage) {
        state.authRefreshInterval = 10
        state.authTryCount = 0
        if (state["syncBoxAuthorized"] && state["syncBoxLinked"]) {
            section("Sync Box Linked") {
                paragraph("Your Hue Sync Box has been successfully linked to Hubitat. Press \"Done\" to finish " +
                          "installing the app, then re-open it to discover/add devices.")
            }
        } else {
            section("Sync Box Not Linked") {
                paragraph("There was a problem authorizing or linking your Hue Sync Box. Please start over and try again.")
            }
        }
    }
}         

def pageManageSyncBox() {
    dynamicPage(name: "pageManageSyncBox", uninstall: true, install: true) {  
        section("Sync Box Device Options", hideable: true, hidden: true) {
            input(name: "pollInterval", type: "enum", title: "Poll sync box every...",
               options: [0:"Disabled", 10:"10 seconds", 15:"15 seconds", 20:"20 seconds", 30:"30 seconds", 60:"1 minute (recommended)", 300:"5 minutes", 3600:"1 hour"], defaultValue:60)
            href(name: "hrefAddSyncBox", title: "Edit Sync Box IP or re-authorize",
                 description: "", page: "pageAddSyncBox")            
            label(title: "Name for this Hue Sync Box child app (optional)", required: false)
            input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        }
    }
}

/** Sends request for username creation to Sync Box API. Intended to be called after user
 *  presses link button on Sync Box
 */
def toJson(Map m)
{
    return new groovy.json.JsonBuilder(m).toString()
}

private sendUsernameRequest() {
    def userDesc = location.name ? "Hubitat CoCoHue ${location.name}" : "Hubitat CoCoHue"
    userDesc = "Development"
    def body = [:]
    body.appName = userDesc
    body.instanceName = userDesc
    body.appSecret = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="

    def requestParams =
    [
        uri:  "https://${settings["syncBoxIP"]}/api/v1/registrations",
        contentType: "application/json",
        ignoreSSLIssues:  true,
        body : toJson(body)
    ]
    //asynchttpPost("parseUsernameResponse", requestParams)
    log.debug "${requestParams}"
    try{
        httpPost(requestParams)
        {
            response ->
                if (response?.status == 200)
                {
                    if (response?.data?.accessToken) {
                        state["accessToken"] = response?.data?.accessToken
                        state["syncBoxAuthorized"] = true
                    }
                    else {
                        log.error("Unknown error adding Hue Sync Box ${response?.data}")
                    }
                }
                else
                {
                    log.error "Error in registration Response ${response?.status}"
                }
        }
    } catch (groovyx.net.http.HttpResponseException hre) {
          log.error "Error:${hre.getResponse()?.getData()}"
    } catch (Exception e) { 
          log.error "Something went wrong when posting: ${e}"
    }          
}

/** Callback for sendUsernameRequest. Saves username in app state if Sync Box is
 * successfully authorized, or logs error if unable to do so.
 */
def parseUsernameResponse(response, data) {
    def status = response.status          // => http status code of the response
    if (status.toInteger() != 200) {
        log.error("parseUsernameResponse: invalid status ${status} returned")
        return
    }
    def jsonData = parseJson(response.getData())
    logDebug("Attempting to request Hue Sync Box accessToken; result = ${jsonData}")

    if (jsonData?.accessToken) {
        state["accessToken"] = jsonData?.accessToken
        state["syncBoxAuthorized"] = true
    }
    else {
        log.error("Unknown error adding Hue Sync Box ${jsonData}")
    }
}

/** Requests Sync Box info  to verify that device is a
 *  Hue Sync Box and to retrive (when parsed in parseSyncBoxInfoResponse)
 *  information necessary to create the Sync Box device
 */
private sendSyncBoxInfoRequest() {
    log.debug("Sending request for Sync Box information")
    def requestParams =
    [
        uri:  "https://${settings["syncBoxIP"]}/api/v1",
        contentType: "application/json",
        headers: ["Authorization": "Bearer ${state["accessToken"]}"],
        timeout: 200, 
        ignoreSSLIssues:  true
    ]
    //asynchttpGet("parseSyncBoxInfoResponse", requestParams)
    try {
        httpGet(requestParams)
        {
          response ->
            if (response?.status == 200)
            {
                if (response?.data?.device?.deviceType?.contains("HSB")) {
                    state.serial
                    def serial = response?.data?.device?.uniqueId
                    if (serial) {
                        log.debug("Hue Sync Box serial parsed as ${serial}; creating device")
                        state.syncBoxID = serial.reverse().take(6).reverse().toUpperCase() // last 6 of MAC
                        def syncBoxDevice
                        try {
                            syncBoxDevice = addChildDevice("RMoRobert", "CoCoHue Sync Box", "CCH/${state.syncBoxID}", null,
                                                          [label: "CoCoHue Sync Box (${state.syncBoxID})", name: "CoCoHue Sync Box"])
                            state.syncBoxLinked = true
                        } catch (Exception e) {
                            log.error("Error creating Sync Box device: $e")
                        }
                        if (!state.syncBoxLinked) log.error("Unable to create Sync Box device. Make sure driver installed and no Sync Box device for this MAC already exists.")
                    } else {
                        log.error("Unexpected response received from Hue Sync Box")
                    } 
                } else {
                    log.error("No Hue Sync Box found at IP address")
                }
            }
            else
            {
                log.error "Error in sendSyncBoxInfoRequest Response ${response?.status}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException hre) {
          log.error "Error:${hre.getResponse()?.getData()}"        
    } catch (Exception e){
        log.error e
    }  
}

/** Parses response from GET of device info;
 *  verifies that device is a Hue Sync Box (modelName contains "HSB")
 * and obtains MAC address for use in creating Sync Box DNI and device name
 */
private parseSyncBoxInfoResponse(response, data) {
    def status = response.status          // => http status code of the response
    if (status.toInteger() != 200) {
        log.error("parseSyncBoxInfoResponse: invalid status ${status} returned")
        return
    }
    def jsonData = parseJson(response.getData())
    
    log.debug("Parsing response from Sync Box information request")
    def body = resp.xml
    if (jsonData?.device?.deviceType?.text().contains("HSB")) {
        state.serial
        def serial = body?.device?.uniqueId?.text()
        if (serial) {
            log.debug("Hue Sync Box serial parsed as ${serial}; creating device")
            state.syncBoxID = serial.reverse().take(6).reverse().toUpperCase() // last 6 of MAC
            def syncBoxDevice
            try {
                syncBoxDevice = addChildDevice("RMoRobert", "CoCoHue Sync Box", "CCH/${state.syncBoxID}", null,
                                              [label: "CoCoHue Sync Box (${state.syncBoxID})", name: "CoCoHue Sync Box"])
                state.syncBoxLinked = true
            } catch (Exception e) {
                log.error("Error creating Sync Box device: $e")
            }
            if (!state.syncBoxLinked) log.error("Unable to create Sync Box device. Make sure driver installed and no Sync Box device for this MAC already exists.")
        } else {
            log.error("Unexpected response received from Hue Sync Box")
        } 
    } else {
        log.error("No Hue Sync Box found at IP address")
    }
}

/** Returns map containing Sync Box accessToken, IP, and full HTTP post/port, intended to be
 *  called by child devices so they can send commands to the Hue Sync Box API using info
 */
def getSyncBoxData() {
    logDebug("Running getSyncBoxData()...")
    if (!state["accessToken"] || !settings["syncBoxIP"]) log.error "Missing username or IP address from Sync Box"
    def map = [accessToken: state["accessToken"], host: settings["syncBoxIP"] + ":443", fullHost: "https://${settings['syncBoxIP']}"]
    return map
}

/** Calls refresh() method on Sync Box child, intended to be called at user-specified
 *  polling interval
 */
private refreshSyncBox() {
    def syncBox = getChildDevice("CCH/${state.syncBoxID}")
    if (!syncBox) {
            log.error "No Sync Box device found; could not refresh/poll"
            return
    }
    logDebug("Polling Sync Box...")
    syncBox.refresh()
}


def appButtonHandler(btn) {
    switch(btn) {
        default:
            log.warn "Unhandled app button press: $btn"
    }
}

def logDebug(str) {
    if (enableDebug) log.debug(str)
}
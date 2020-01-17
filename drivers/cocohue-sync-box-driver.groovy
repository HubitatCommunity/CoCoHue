/**
 * =============================  CoCoHue Sync Box (Driver) ===============================
 *
 *  Copyright 2019 Daniel Terryn
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
 *  Last modified: 2020-01-17
 *
 *  Changelog:
 * 
 *  v1.0 - Initial Release
 *
 */ 

metadata {
    definition (name: "CoCoHue Sync Box", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-sync-box-driver.groovy") {
        capability "Refresh"
        capability "Switch"

		command "setMode", [[name:"Set Mode*", type: "ENUM", description: "Set Mode", constraints: ["passthrough", "video", "music", "game"] ] ]
		command "setInput", [[name:"Set Input*", type: "ENUM", description: "Set Input", constraints: ["input1", "input2", "input3", "input3"] ] ]
        command "checkForUpdates"
        
        attribute "brightness", "number"
        attribute "mode", "string"
        attribute "hdmiSource", "string"
        attribute "lastSyncMode", "string"
        attribute "firmwareVersion", "string"
        attribute "lastCheckedUpdate", "string"
        attribute "updateAvailable", "string"
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

def configure() {}

// Probably won't happen but...
def parse(String description) {
    logDebug("Running parse for: '${description}'")
}

def refresh() {
    logDebug("Refresh...")
    try{
        def data = getHttpGetData("")
        def switchState = "off"

        if (data?.execution?.syncActive == true)
            switchState = "on"
        doSendEvent("switch", switchState)
        if (data?.execution?.brightness)
            doSendEvent("brightness", data?.execution?.brightness)
        if (data?.execution?.mode)
            doSendEvent("mode", data?.execution?.mode)
        if (data?.execution?.hdmiSource)
            doSendEvent("hdmiSource", data?.execution?.hdmiSource)
        if (data?.execution?.lastSyncMode)
            doSendEvent("lastSyncMode", data?.execution?.lastSyncMode)
        if (data?.device?.firmwareVersion)
            doSendEvent("firmwareVersion", data?.device?.firmwareVersion)
        if (data?.device?.lastCheckedUpdate)
            doSendEvent("lastCheckedUpdate", data?.device?.lastCheckedUpdate)
        if (data?.device?.updatableFirmwareVersion)
            doSendEvent("updateAvailable", "true")
        else
            doSendEvent("updateAvailable", "false")            
    } catch (Exception e){
        log.info e
    }
}


def on() {
    try{
        if (setMode("video"))
            doSendEvent("switch", "on")
    } catch (Exception e){
        log.info e
    }      
}

def off() {
    try{
        if (setMode("passthrough"))
            doSendEvent("switch", "off")
    } catch (Exception e){
        log.info e
    }      
}

def checkForUpdates()
{
    def result = false
    try{
        result = sendHttpPut("/device", toJson([action: "checkForFirmwareUpdates"]))
    } catch (Exception e){
        log.info e
    }
    return result
}

def setInput(input)
{
    def result = false
    try{
        result = sendHttpPut("/execution", toJson([hdmiSource: input]))
        if (result)
            doSendEvent("hdmiSource", input)        
    } catch (Exception e){
        log.info e
    }
    return result
}

def setMode(mode)
{
    def result = false
    try{
        result = sendHttpPut("/execution", toJson([mode: mode]))
        if (result)
            doSendEvent("mode", mode)        
    } catch (Exception e){
        log.info e
    }
    return result
}

def getHttpGetData(message)
{
    def data = parent.getSyncBoxData()
    def result = null
    def requestParams =
    [
        uri:  "${data.fullHost}/api/v1"+message,
        contentType: "application/json",
        headers: ["Authorization": "Bearer ${data.accessToken}"],
        timeout: 200, 
        ignoreSSLIssues:  true
    ]
    logDebug("getHttpGetData: ${requestParams}")
 
    try{
        httpGet(requestParams)  //change to httpGet for the get test.
        {
          response ->
	        if (response?.status == 200)
	        {
                logDebug("getHttpGetData Response: ${response.data}")
                result = response.data
	        }
	        else
	        {
		        log.warn "${response?.status}"
	        }
        }
    } catch (groovyx.net.http.HttpResponseException hre) {
          log.error "Error:${hre.getResponse()?.getData()}"        
    } catch (Exception e){
        log.info e
    }
    return result
}

def sendHttpPut(message, body)
{
    def data = parent.getSyncBoxData()
    def result = false
    def requestParams =
    [
        uri:  "${data.fullHost}/api/v1"+message,
        contentType: "application/json",
        headers: ["Authorization": "Bearer ${data.accessToken}"],
        timeout: 200, 
        ignoreSSLIssues:  true,
        body : body
    ]
    logDebug("getHttpGetData: ${requestParams}")
 
    try{
        httpGet(requestParams)  //change to httpGet for the get test.
        {
          response ->
	        if (response?.status == 200)
	        {
                logDebug("getHttpGetData Response: ${response.data}")
                result = true
	        }
	        else
	        {
		        log.warn "${response?.status}"
	        }
        }
    } catch (groovyx.net.http.HttpResponseException hre) {
          log.error "Error:${hre.getResponse()?.getData()}"        
    } catch (Exception e){
        log.info e
    }
    return result
}

def doSendEvent(eventName, eventValue) {
    logDebug("Creating event for $eventName...")
    def descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
    logDesc(descriptionText)
    def event = sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
    return event
}

def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}

def toJson(Map m)
{
    return new groovy.json.JsonBuilder(m).toString()
}
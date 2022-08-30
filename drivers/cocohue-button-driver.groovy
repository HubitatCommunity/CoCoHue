/*
 * =============================  CoCoHue Button (Driver) ===============================
 *
 *  Copyright 2022 Robert Morris
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
 *  Last modified: 2022-08-29
 * 
 *  Changelog:
 *  v4.1.1   - Improved button event parsing
 *  v4.1     - Initial release (with CoCoHue app/bridge 4.1)
 */



import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Button", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-button-driver.groovy") {
      capability "Actuator"
      //capability "Refresh"
      capability "PushableButton"
      capability "HoldableButton"
      capability "ReleasableButton"
      //capability "Configuration"
   }

   preferences {
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
}

/*
void configure() {
   log.debug "configure()"
   // nothing? remove capability if not needed...
}
*/

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge scene ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/AppId/Button/v2ApiId", so just
 * looks for string after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug) log.debug "Response from Bridge: ${resp.status}; data from app = $data"
   // TODO: Rethink for buttons...
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (enableDebug) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
   }
   else {
      if (enableDebug) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(Number btnNum) {
   if (enableDebug) log.debug "push($btnNum)"
   doSendEvent("pushed", btnNum, null, true)
}

void hold(Number btnNum) {
   if (enableDebug) log.debug "hold($btnNum)"
   doSendEvent("held", btnNum, null, true)
}

void release(Number btnNum) {
   if (enableDebug) log.debug "release($btnNum)"
   doSendEvent("released", btnNum, null, true)
}

/**
 * Parses through device data/state in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device
 */
void createEventsFromSSE(Map data) {
   if (enableDebug == true) log.debug "createEventsFromSSE($data)"
   String eventName
   Integer eventValue = state.buttons.find({ it.key == data.id})?.value ?: 1
   if (data.type == "button") {
      switch (data.button.last_event) {
         case "initial_press":
            eventName = "pushed"
            break
         case "repeat":
            // prevent sending repeated "held" events
            if (state.lastHueEvent != "repeat") eventName = "held"
            else eventName = null
            break
         case "long_release":
            eventName = "released"
            break
         default:
            if (enableDebug == true) log.debug "No button event created from: ${data.button.last_event}"
            break
      }
      state.lastHueEvent = data.button.last_event
      if (eventName != null) doSendEvent(eventName, eventValue, null, true)
   }
   else if  (data.type == "relative_rotary") {
      if (enableDebug) log.debug "ignoring relative_rotary, likely from Hue Tap Dial or Lutron Aurora; support may be added for these events in the future"
   }
   else {
      if (enableDebug) log.debug "ignoring; data.type = ${data.type}"
   }
}

/**
 * Sets state.button to IDs a Map in format [subButtonId: buttonNumber], used to determine
 * which button number to use for events when it is believed to be one this device "owns"
*/
void setButtons(Map<String,Integer> buttons) {
   if (enableDebug) log.debug "setButtons($buttons)"
   state.buttons = buttons
   doSendEvent("numberOfButtons", buttons.keySet().size())
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

/*
 * =============================  CoCoHue Button (Driver) ===============================
 *
 *  Copyright 2022-2024 Robert Morris
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
 *  Last modified: 2024-09-14
 *
 *  Changelog:
 *  v5.0     - Use API v2 by default, remove deprecated features
 *  v4.2     - Library updates, prep for more v2 API
 *  v4.1.5   - Improve button command compatibility
 *  v4.1.4   - Improved HTTP error handling
 *  v4.1.2   - Add relative_rotary support (Hue Tap Dial, etc.)
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
  * Parses response from Bridge (or not) after sendBridgeCommandV1. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponseV1(AsyncResponse resp, Map data) {
   if (logEnable) log.debug "Response from Bridge: ${resp.status}; data from app = $data"
   // TODO: Rethink for buttons...
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (logEnable) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
   }
   else {
      if (logEnable) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(btnNum) {
   if (logEnable) log.debug "push($btnNum)"
   doSendEvent("pushed", btnNum.toInteger(), null, true)
}

void hold(btnNum) {
   if (logEnable) log.debug "hold($btnNum)"
   doSendEvent("held", btnNum.toInteger(), null, true)
}

void release(btnNum) {
   if (logEnable) log.debug "release($btnNum)"
   doSendEvent("released", btnNum.toInteger(), null, true)
}

/**
 * Parses through device data/state in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device
 */
void createEventsFromMapV2(Map data) {
   if (logEnable == true) log.debug "createEventsFromMapV2($data)"
   String eventName
   if (data.type == "button") {
      Integer eventValue = state.buttons.find({ it.key == data.id})?.value ?: 1
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
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
            break
         default:
            if (logEnable == true) log.debug "No button event created from: ${data.button.last_event}"
            break
      }
      state.lastHueEvent = data.button.last_event
      if (eventName != null) doSendEvent(eventName, eventValue, null, true)
   }
   else if  (data.type == "relative_rotary") {
      Integer eventValue = state.relative_rotary.indexOf(data.id) + state.buttons.size() + 1
      // using counterclockwise = index+1, clockwise = index+2 for rotary devices
      if (data.relative_rotary.last_event.rotation.direction == "clock_wise") eventValue++
      switch (data.relative_rotary.last_event.action) {
         case "start":
            eventName = "pushed"
            break
         case "repeat":
            // prevent sending repeated "held" events
            if (state.lastHueEvent != "repeat") eventName = "held"
            else eventName = null
            break
         default:
            break
      }
      state.lastHueEvent = data.relative_rotary.last_event.action
      if (eventName != null) doSendEvent(eventName, eventValue, null, true)
   }
   else {
      if (logEnable) log.debug "ignoring; data.type = ${data.type}"
   }
}

/**
 * Sets state.button to IDs a Map in format [subButtonId: buttonNumber], used to determine
 * which button number to use for events when it is believed to be one this device "owns". Also
 * accepts List of relative_rotary IDs, optional (will be used as additional button numbers)
 */
void setButtons(Map<String,Integer> buttons, List<String> relativeRotaries=null) {
   if (logEnable) log.debug "setButtons($buttons, $relativeRotaries)"
   state.buttons = buttons
   if (relativeRotaries) state.relative_rotary = relativeRotaries
   Integer numButtons = buttons.keySet().size()
   if (relativeRotaries) numButtons += relativeRotaries.size() * 2 // x2 because clockwise + counterclockwise as separate numbers
   doSendEvent("numberOfButtons", numButtons)
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
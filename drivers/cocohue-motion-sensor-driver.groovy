/*
 * =============================  CoCoHue Motion Sensor (Driver) ===============================
 *
 *  Copyright 2020-2023 Robert Morris
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
 *  Last modified: 2023-01-30
 * 
 *  Changelog:
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup
 *  v3.1.6  - Fixed runtime error when using temperature offset; ensure battery and lux reported as integers, temperature as BigDecimal
 *  v3.1.2  - Added optional offset for temperature sensor
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Initial release
 */
 


import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Motion Sensor", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-motion-sensor-driver.groovy") {
      capability "Sensor"
      capability "Refresh"
      capability "MotionSensor"
      capability "IlluminanceMeasurement"
      capability "TemperatureMeasurement"
      capability "Battery"
   }

   preferences {
      input name: "tempAdjust", type: "number", title: "Adjust temperature reading by this amount", description: "Example: 0.4 or -1.5 (optional)"
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

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups/sensors"
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Returns MAC address portion of "uniqueid" for device (maximimal form is AA:BB:CC:DD:EE:FF:00:11-XX-YYYY,
 * where -XX or -XX-YYYY indicate additional endpoints/sensors on same device), which should be last part
 * of DNI
 */
String getHueDeviceMAC() {
   return device.deviceNetworkId.split("/")[3]
}

/**
 * Iterates over Hue sensor state commands/states in Hue format (e.g., ["lightlevel": 25000]) and does
 * a sendEvent for each relevant attribute; for sensors, intended to be called
 * to parse/update sensor state on Hubitat based on data received from Bridge
 * @param bridgeCmd Map of sensor states from Bridge (for lights, this could be either a command to or response from)
 */
void createEventsFromMap(Map bridgeCmd) {
   if (!bridgeCmd) {
      if (enableDebug) log.debug "createEventsFromMap called but map empty; exiting"
      return
   }
   if (enableDebug) log.debug "Preparing to create events from map: ${bridgeCmd}"
   String eventName, eventUnit, descriptionText
   def eventValue // could be numeric (lux, temp) or boolean (motion)
   bridgeCmd.each {
      switch (it.key) {
         case "presence":
            eventName = "motion"
            eventValue = it.value ? "active" : "inactive"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "lightlevel":
            eventName = "illuminance"
            eventValue = Math.round(10 ** (((it.value as Integer)-1)/10000))
            eventUnit = "lux"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         case "temperature":
            eventName = "temperature"
            if (location.temperatureScale == "C") eventValue = ((it.value as BigDecimal)/100.0).setScale(1, java.math.RoundingMode.HALF_UP)
            else eventValue = celsiusToFahrenheit((it.value as BigDecimal)/100.0).setScale(1, java.math.RoundingMode.HALF_UP)
            if (settings["tempAdjust"]) eventValue = (eventValue as BigDecimal) + (settings["tempAdjust"] as BigDecimal)
            eventUnit = "°${location.temperatureScale}"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as BigDecimal, eventUnit)
            break
         case "battery":
            eventName = "battery"
            eventValue = (it.value != null) ? (it.value as Integer) : 0
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * (for "new"/v2/EventSocket [SSE] API; not documented and subject to change)
 * Iterates over Hue light state states in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromSSE(Map data) {
   if (enableDebug == true) log.debug "createEventsFromSSE($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   data.each { String key, value ->
      switch (key) {
         case "motion":
            eventName = "motion"
            eventValue = value.motion ? "active" : "inactive"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "light":
            eventName = "illuminance"
            eventValue = Math.round(10 ** (((value.light_level as Integer)-1)/10000))
            eventUnit = "lux"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "temperature":
            eventName = "temperature"
            if (location.temperatureScale == "C") eventValue = ((value.temperature as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
            else eventValue = celsiusToFahrenheit((value.temperature  as BigDecimal).setScale(1, java.math.RoundingMode.HALF_UP))
            if (settings["tempAdjust"]) eventValue = (eventValue as BigDecimal) + (settings["tempAdjust"] as BigDecimal)
            eventUnit = "°${location.temperatureScale}"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as BigDecimal, eventUnit)
            break
         default:
            if (enableDebug == true) "not handling: $key: $value"
      }
   }
}

// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.2 // library marker RMoRobert.CoCoHue_Common_Lib, line 1

// 1.0.2  - HTTP error handling tweaks // library marker RMoRobert.CoCoHue_Common_Lib, line 3

library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 5
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 6
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 7
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 8
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 9
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 10
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 11
) // library marker RMoRobert.CoCoHue_Common_Lib, line 12

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 14
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 15
   device.updateSetting("enableDebug", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 16
} // library marker RMoRobert.CoCoHue_Common_Lib, line 17

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 19
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 20
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 21
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 22
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 23
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 24
   if (enableDebug == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 25
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 26
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 27
      if (resp.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 28
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 29
         if (resp.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 30
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 31
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 32
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 33
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 34
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 35
         if (resp.json instanceof List && resp.json[0]?.error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 36
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 37
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 38
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 39
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 40
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 41
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 42
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 43
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 44
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 45
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 46
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 47
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 48
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 49
         parent.setBridgeStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 50
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 51
      if (isOK == true) parent.setBridgeStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 52
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 53
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 54
      log.warn "Error communiating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 55
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 56
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 57
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 58
} // library marker RMoRobert.CoCoHue_Common_Lib, line 59

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 61
   //if (enableDebug == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 62
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 63
   if (settings.enableDesc == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 64
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 65
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 66
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 67
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 68
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 69
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 70
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 71
} // library marker RMoRobert.CoCoHue_Common_Lib, line 72

// ~~~~~ end include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~

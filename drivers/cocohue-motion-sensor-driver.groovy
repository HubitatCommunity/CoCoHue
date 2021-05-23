/*
 * =============================  CoCoHue Motion Sensor (Driver) ===============================
 *
 *  Copyright 2020-2021 Robert Morris
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
 *  Last modified: 2021-05-23
 * 
 *  Changelog:
 *  v3.5    - Minor code cleanup
 *  v3.1.6  - Fixed runtime error when using temperature offset; ensure battery and lux reported as integers, temperature as BigDecimal
 *  v3.1.2  - Added optional offset for temperature sensor
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Initial release
 */
 
metadata {
   definition (name: "CoCoHue Motion Sensor", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-motion-sensor-driver.groovy") {
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
   Integer disableMinutes = 30
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableMinutes} minutes"
      runIn(disableMinutes*60, debugOff)
   }
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups/sensors"
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
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
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue  as Integer, eventUnit)
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

void doSendEvent(String eventName, eventValue, String eventUnit=null) {
   //if (enableDebug) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)"
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
   if (settings.enableDesc == true) log.info(descriptionText)
   if (eventUnit) {
      sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
   } else {
      sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
   }
}
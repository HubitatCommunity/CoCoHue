/*
 * =============================  CoCoHue Dimmable Bulb (Driver) ===============================
 *
 *  Copyright 2019-2022 Robert Morris
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
 *  Last modified: 2022-01-12
 *
 *  Changelog:
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added "reachable" attribte
              from Bridge to bulb and group drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Fix so events no created until Bridge response received (as was done for other drivers in 2.0); improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - Minor code cleanup and more static typing
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Initial release (based on CT bulb driver)
 */

#include RMoRobert.CoCoHue_Common_Lib
#include RMoRobert.CoCoHue_Bri_Lib
#include RMoRobert.CoCoHue_Flash_Lib
#include RMoRobert.CoCoHue_Prestage_Lib

import groovy.transform.Field
import hubitat.scheduling.AsyncResponse


@Field static final Integer debugAutoDisableMinutes = 30

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
// (for dim-only devices, should cover everything...)
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on", "bri"]

metadata {
   definition(name: "CoCoHue Dimmable Bulb", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-dimmable-bulb-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "LevelPreset"
      capability "ChangeLevel"
      capability "Light"

      command "flash"
      command "flashOnce"
      command "flashOff"

      attribute "reachable", "string"
   }
         
   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      if (levelStaging) input name: "levelStaging", type: "bool", description: "DEPRECATED. Please use new presetLevel() command instead. May be removed in future.", title: "Enable level pseudo-prestaging", defaultValue: false
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when bulb state changes",
         defaultValue: false
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

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on(Number transitionTime = null) {
   if (enableDebug == true) log.debug "on()"
   Map bridgeCmd = ["on": true]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (enableDebug == true) log.debug "off()"
   Map bridgeCmd = ["on": false]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   sendBridgeCommand(bridgeCmd)
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge or if pre-staged attribute is changed and "real" command not yet able to be sent, or
 * to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; if true, will ignore differences for prestaged attributes if switch state is off (TODO: how did new prestaging affect this?)
 */
void createEventsFromMap(Map bridgeCommandMap, Boolean isFromBridge = false, Set<String> keysToIgnoreIfSSEEnabledAndNotFromBridge=listKeysToIgnoreIfSSEEnabledAndNotFromBridge) {
   if (!bridgeCommandMap) {
      if (enableDebug == true) log.debug "createEventsFromMap called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (enableDebug == true) log.debug "Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   if (!isFromBridge && keysToIgnoreIfSSEEnabledAndNotFromBridge && parent.getEventStreamOpenStatus() == true) {
      bridgeMap.keySet().removeAll(keysToIgnoreIfSSEEnabledAndNotFromBridge)
      if (enableDebug == true) log.debug "Map after ignored keys removed: ${bridgeMap}"
   }
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean isOn = bridgeMap["on"]
   bridgeMap.each {
      switch (it.key) {
         case "on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "bri":
            eventName = "level"
            eventValue = scaleBriFromBridge(it.value)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "reachable":
            eventName = "reachable"
            eventValue = it.value ? "true" : "false"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "transitiontime":
         case "mode":
         case "alert":
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
         case "on":
            eventName = "switch"
            eventValue = value.on ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "dimming":
            eventName = "level"
            eventValue = scaleBriFromBridge(value.brightness, "2")
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         default:
            if (enableDebug == true) "not handling: $key: $value"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the either command map provided
 * @param commandMap Groovy Map (will be converted to JSON) of Hue API commands to send, e.g., [on: true]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if ["on": true] in map)
 */
void sendBridgeCommand(Map commandMap, Boolean createHubEvents=true) {
   if (enableDebug == true) log.debug "sendBridgeCommand($commandMap)"
   if (commandMap == null || commandMap == [:]) {
      if (enableDebug == true) log.debug "Commands not sent to Bridge because command map null or empty"
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights/${getHueDeviceNumber()}/state",
      contentType: 'application/json',
      body: commandMap,
      timeout: 15
   ]
   asynchttpPut("parseSendCommandResponse", params, createHubEvents ? commandMap : null)
   if (enableDebug == true) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug == true) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (enableDebug == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceNumber())
      }
   }
   else {
      if (enableDebug == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}
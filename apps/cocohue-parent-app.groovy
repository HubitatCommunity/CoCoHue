/**
 * ==========================  CoCoHue (Parent App) ==========================
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum.
 *
 *  Copyright 2019 Robert Morris
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
 *  Last modified: 2019-12-01
 * 
 *  Changelog:
 * 
 *  v1.0 - Initial Public Release
 *  v1.5 - Minor formatting changes, version ugpraded to match most components
 *
 */ 
 
definition(
    name: "CoCoHue - Hue Bridge Integration",
    namespace: "RMoRobert",
    author: "Robert Morris",
    singleInstance: true,
    description: "Integrate Hue Bridge lights, groups, and scenes into Hubitat",
    category: "Convenience",        
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)   

preferences {
    page(name: "mainPage", title: "CoCoHue - Hue Bridge Integration", install: true, uninstall: true) {
        section {      
          if (app.getInstallationState() == "INCOMPLETE") {
              paragraph("<b>Please press \"Done\" to finish installing this app, then re-open it to add your Hue Bridge.</b>")
              return
          }
        app(name: "childApps", appName: "CoCoHue (Bridge Instance Child App)", namespace: "RMoRobert", title: "Add new Hue Bridge...", multiple: true)
        }
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    //unsubscribe()
    initialize()
}

def initialize() {
    log.debug "Initializing; there are ${childApps.size()} child apps installed:"
    childApps.each {child ->
        log.debug "  child app: ${child.label}"
    }
}

/*
 *   DEPRECATED - NOT FOR NEW INSTALLS (use only if upgrading from 1.x)
 *
 *   The CoCoHue Parent App has been deprecated in version 2.0. For new installs, install the "regular" app
 *   directly (it is now the only app you need, but also install the drivers). Existing installations may
 *   continue to use the parent app if desired by updating the parent app to this version and updating the
 *   child app to the new 2.x child.
 *  
 */

/**
 * ==========================  CoCoHue (Parent App) ==========================
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes. (Depcreated; new installs should install the non-deprecated app directly.)
 
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
 * 
 *  Changelog:
 * 
 *  v1.0 - Initial Public Release
 *  v1.5 - Minor formatting changes, version ugpraded to match most components
 *  v2.0 - Deprecated parent app, but you can continue using if it was previously set up this way (see comments or docs for how)
 *
 */ 
 
definition(
    name: "CoCoHue (Parent App)",
    namespace: "RMoRobert",
    author: "Robert Morris",
    singleInstance: true,
    description: "Integrate Hue Bridge lights, groups, and scenes into Hubitat (deprecated; use for existing 1.x-to-2.x upgrades only; new 2.x users should use new app)",
    category: "Convenience",
    documentationLink: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978",    
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)   

preferences {
    page(name: "mainPage", title: "CoCoHue (Parent App)", install: true, uninstall: true) {
        section {      
          if (app.getInstallationState() == "INCOMPLETE") {
              paragraph("<b>Please press \"Done\" to finish installing this app, then re-open it to add your Hue Bridge.</b>")
              return
          }
        app(name: "childApps", appName: "CoCoHue - Hue Bridge Integration", namespace: "RMoRobert", title: "Add new Hue Bridge...", multiple: true)
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
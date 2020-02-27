# CoCoHue
CoCoHue: <b>Co</b>mmunity <b>Co</b>llection of <b>Hue</b> Bridge Apps and Drivers for Hubitat

(Hue Bridge Integration App for Hubitat)

This is a Hue Bridge integration designed to replace (or supplement) Hubitat's buit-in Hue Bridge
integration. It provides several advantages, including:
1. Access to Hue bulb "effects" (color loop, select/alert, etc.)
2. Improved group support ("Change Level" capability--`startLevelChange` and stopLevelChange` commands implemented)
3. Scene support: create switch/button devices that can be used to activate Hue Bridge scenes
4. It's open source! Customize the code to suit your requirements if so desired

For discussion and more information, visit the <a href="https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978">Hubitat Community forum thread</a>.

## To Install
1. Back up your hub and save a local copy before proceeding.

2. Install the parent and child apps from the "apps" folder in this repository into the "Apps Code" section of Hubitat: 
    * Install the parent app code: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/apps/cocohue-parent-app.groovy    
    * Install the child app code: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/apps/cocohue-bridge-child-app.groovy
    * Note: In v2.0, I plan to deprecate this parent/child strucutre. For new 1.x installs, you may wish to install only the
      Bridge Child app and comment out or remove the `parent` line in the `defintion` "section," but I will release a new, to-be-deprecated
      parent app for 2.0 so that 1.x users can upgrade without hassle.

3. Install all necessary drivers from the "drivers" folder in this repository into the "Drivers Code" section of Hubitat. (There aren't very many, so I'd recommend just installing them all, but technically all you need is the Bridge driver plus the driver for any device types you plan to use.)
    * Install the Bridge driver code: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy
    * Install the bulb, group, scene, and plug drivers:
    https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgbw-bulb-driver.groovy,
    https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy,
    https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy,
    https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy
    (more coming soon)

4. Install an instance of app: go to **Apps > Add User App**, choose **CoCoHue**, and follow the prompts. At the moment, your
   Hue Bridge needs a static IP address, as discovery is not supported.

## Feature Documentation
CoCoHue is designed to be a drop-in replacement for Hubitat's existing Hue integration. If any devices behave differently, this
may be considered a bug (except for differences noted below). Please report any such behavior in the Community forum, and feel free
to ask any questions there as well.

This integration is intended to replace the built-in Hue Bridge Integration app, and adds the following features:

1. Prestaging: this integration adds color and level prestaging options to mimic those found in many other native drivers.
This means that if a `setLevel` (for level prestaging) or a `setColorTemperature`, `setColor`, `setHue`, or
`setSaturation` command are received when the bulb is off, it will not turn on. Instead, the next time the bulb is turned on, it will be turned on with those settings. This works well if your bulbs are manipulated entirely from Hubitat. Unfortunately,
Hue itself does not support prestaging on the Bridge (these prestaged settings are "remembered" entirely on the
Hubitat device), so this will *not* work if you prestage in Hubitat and then turn the bulbs on outside of Hubitat.
(Recommendation: manipulate bulbs only from Hubitat if prestaging is enabled.)

2. Color loop effect: to fit in with Hubitat's "Light Effects" capability, Hue's only effect, `colorloop`, is implemented using
this capability. It is effect `1`. It can be activated by calling the standard command, `setEffect(1)`. "None" is implemented
as effect `0`, so the effect can be cancelled by calling `setEffect(0)`. The `nextEffect` and `previousEffect` commands are
pretty boring for this reason, but they are part of the standard Hubitat capability as it is currently documented. Setting
a color, hue, or color temperature will also cancel the effect (this is consistent with the behavior of other bulbs I tested).
Setting a level or saturation will *not* because Hue allows adjustment of these while the effect (which does not manipulate these
values) is in progress.

3. "Select" and "LSelect" alerts: these are basically a one-time flash and a 15-time flash. These are implemented as the
`flashOnce()` command and the pseudo-standard `flash()` command, respectively.

4. Groups: besides "Change Level" (implemnted here), most group changes will propagage to individual bulbs. This is consistent with
Hubitat's stock behavior. CoCoHue adds the reverse, updating group states when individual bulbs are updated. In both cases, unlike
Hubitat's stock integration, CoCoHue considers a group when any (not all) members are on. This is consistent with Hue app behavior and
makes prestaging options make more sense when using both. It also means both bulbs and groups should get updated without polling
when either is mannipulated, though it is recommended to configure some polling interval regardless.

5. Scenes: implemented as button and switch devices. To activate, do a `push(1)` or an `on()` command (<b>the `off()` command
is ignored</b> because Hue does not have a way to "turn off" a scene, but it can be used if desired without harm). If you use
scenes, it is recommended to keep polling enabled (actvating a scene will not update associated Hubitat group or bulb
devices without polling).
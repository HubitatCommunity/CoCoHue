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

## To Install (Manual Method)
1. Back up your hub and save a local copy before proceeding.

2. Install the app  from the "apps" folder in this repository into the "Apps Code" section of Hubitat: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/apps/cocohue-app.groovy
    * **Important for users upgrading from 1.x:** if your installation is set up using the parent/child app strucutre (and you want to keep that installation), then
    follow these steps instead. First, update the parent app: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/deprecated/cocohue-parent-app.groovy. Then,
    install the (child) app as above: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/apps/cocohue-app.groovy. Finally, uncomment the
    line `parent: "RMoRobert:CoCoHue (Parent App)"` (should be around line 50-55) in the "child" app by removing the two slashes, `//`, in front of it.

3. Install all necessary drivers from the "drivers" folder in this repository into the "Drivers Code" section of Hubitat. (There aren't very many, so I'd recommend just installing them all, but technically all you need is the Bridge driver plus the driver for any device types you plan to use.)
    * Install the Bridge driver code: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy
    * Install the bulb, group, scene, and plug drivers:
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgbw-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-ct-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-dimmable-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy
      
4. Install an instance of app: go to **Apps > Add User App**, choose **CoCoHue**, and follow the prompts.

## To Install (Hubitat Package Manager/Automatic Method)

CoCoHue is also available on <a href="https://community.hubitat.com/t/beta-hubitat-package-manager/38016">Hubitat Package
Manager</a>, a community app designed to make installing and updating community apps and drivers easier.

(NOTE: If upgrading, existing 1.x users will still need to pay attention to the notice above; this will apply to the initial
upgrade and every subsequent app upgrade HPM installs.)

## Feature Documentation
CoCoHue is designed to be a drop-in replacement for Hubitat's existing Hue integration. If any devices behave differently, this
may be considered a bug (except for differences noted below). Please report any such behavior in the Community forum, and feel free
to ask any questions there as well.

This integration is intended to replace the built-in Hue Bridge Integration app (but you can use both side-by-side if you want, as you might
if slowly transitioning from one to the other), and adds the following features:

1. Scenes: implemented as button and switch devices. To activate, "Push" button "1" or send an `on()` command. If you use
scenes, it is recommended to keep polling enabled (actvating a scene will not update associated Hubitat group or bulb
devices without polling). The `off()` command on a scene device will turn off the associated group (using the Hubitat device if
available) or (for classic/"non-GroupScene" scenes) the associated lights, but in most cases it would be desirable to manually
turn the group/lights off yourself instead of using the scene device (you'll have more control and know exactly what the outcome
should be instead of CoCoHue inferring one for you). CoCoHue provides options for handling scene device on/off state and can
optionally show other scenes for same room/zone/group as off when another is activated.

2. Groups: the "Change Level" capability is implemented, meaning the "Start Level Change" and "Stop Level Change" commands are
implemented on CoCoHue group devices, not just individual bulbs. Like Hubitat, by default, most group changes will propagage to
individual bulb devices. CoCoHue extends this with an option to also do the reverse, updating group states when individual bulbs are
updated. (Both are optional; the former direction is on by default and the latter off.) In both cases, unlike Hubitat's
stock integration, CoCoHue considers a group when any (not all) members are on. This is consistent with Hue app
behavior and makes prestaging options make more sense when using both. It also means both bulbs and groups should get
updated without polling when either is mannipulated, though it is recommended to configure some polling interval
regardless. Additionally, an "All Hue Lights" group is available to add if desired.

3. Color loop effect: to fit in with Hubitat's "Light Effects" capability, Hue's only effect, `colorloop`, is implemented using
this capability and the command it uses, "Set Effect." Color loop is effect `1`. It can be activated by calling `setEffect(1)`.
"None" (no effect; normal behavior) is implemented as effect `0`, so the effect can be cancelled by calling `setEffect(0)`.
The `nextEffect` and `previousEffect` commands are pretty boring for this reason, but they are implemented to be consistent with
the standard Hubitat capability as it is currently documented. Setting a color, hue, or color temperature will also cancel the effect
(this is consistent with the behavior of other bulbs I tested). Setting a level or saturation will *not* because Hue allows adjustment
of these while the effect (which does not manipulate these values) is in progress.

4. Prestaging: this integration adds color and level prestaging options to mimic those found in many other native drivers.
This means that if a `setLevel` (for level prestaging) or a `setColorTemperature`, `setColor`, `setHue`, or `setSaturation` command
are received when the bulb is off, it will not turn on. Instead, the next time the bulb is turned on, it will be turned on with those settings.
This works well if your bulbs are manipulated entirely from Hubitat. (Note: unlike some bulbs, a second `setLevel` with the same level
as a previous, prestaged `setLevel() will not turn the bulb on to that level; a manual `on()` must be issued. The same is true for all
prestaged attributes/values.) Unfortunately, Hue itself does not support prestaging on the Bridge (these prestaged settings are "remembered"
entirely on the Hubitat device), so this will *not* work if you prestage in Hubitat and then turn the bulbs on outside of Hubitat. For
this reason, the drivers label these options "pseudo-prestaging." (Recommendation: manipulate bulbs only from Hubitat if prestaging is enabled.)

5. "Select" and "LSelect" Hue alerts: these are basically a one-time flash and a 15-time flash. These are implemented as the
`flashOnce()` command and the pseudo-standard `flash()` command, respectively. An in-progress `flash()` can be stopped
with `flashOff()` (or you can wait until it stops on own, approximately 30 seconds on official bulbs).
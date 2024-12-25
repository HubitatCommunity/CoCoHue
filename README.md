# CoCoHue
CoCoHue: <b>Co</b>mmunity <b>Co</b>llection of <b>Hue</b> Bridge Apps and Drivers for Hubitat

(Hue Bridge Integration App for Hubitat)

This is a Hue Bridge integration designed to replace (or supplement) Hubitat's buit-in Hue Bridge
integration. It provides several additional features compared to older versions of the built-in integration, including:
1. Scene support: create switch/button devices that can be used to activate Hue Bridge scenes
2. Access to Hue bulb "effects" (color loop, select/alert, etc.)
3. Improved group support ("Change Level" capability--`startLevelChange` and stopLevelChange` commands implemented)
4. It's open source! Customize the code to suit your requirements if so desired

As of platform version 2.4.0, most of these features are now also available in the built-in integration. However,
some users may prefer CoCoHue for other reasons (e.g., existing user of this integration, prefer open-source code, 
etc.)

For discussion and more information, visit the <a href="https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978">Hubitat Community forum thread</a>. (GitHub is used primarily for sharing the code. Releases, discussion, and other issues will be noted in the Hubitat Community forum.)

**NOTE:** Users upgrading to 5.x from 4.x will need to open the CoCoHue app and select **Done** once after upgrading. Users upgrading from older versions will need to upgrade to the latest CoCoHue 4.x release before upgrading to 5.x, select **Done**, then upgrade by following these instructions again. It is recommended to download a hub backup before upgrading (restoring this backup is the only way to downgrade, as 5.x contains breaking changes).

Three installation methods are available:
- Hubitat Package Manager (recommended if you have Hubitat Package Manager installed)
- As a bundle (recommended for other users)
- Manually with each app and driver file (the most complicated option, not recommended for most users)

## To Install (Hubitat Package Manager/Automatic Method)

CoCoHue is available via <a href="https://community.hubitat.com/t/beta-hubitat-package-manager/38016">Hubitat Package
Manager</a>, a community app designed to make installing and updating community apps and drivers easier. Search for
"CoCoHue" or browse under the "Integrations" category for "Lights & Switches" or "LAN" tags.

Upgrading: HPM should offer new versions if available when checked. It is recommended to *read the release notes before
any upgrades* (especially from one major version to another, e.g., 4.x to 5.x) and to *not* enable automatic
updates. While such changes are rare, important changes that affect functionality compared to previous versions have
happened and are always noted in the release notes and Community thread.

## To Install (as Bundle)

CoCoHue is also available as a "bundle," a ZIP file that can be downloaded and imported to the hub, installing
all components without the need to manually install each app/driver.

* **Bundle download link:** https://github.com/HubitatCommunity/CoCoHue/blob/master/CoCoHue-Latest-Bundle.zip

To install, navigate to **Bundles** on your hub and import the downloaded file; consult
the <a href="https://docs2.hubitat.com/en/user-interface/developer/bundles">Hubitat documentation</a>
for more details.

## To Install (Manual Method)
1. Back up your hub and download a local copy before proceeding.

2. Install the app  from the "apps" folder in this repository into the **Apps Code** section of Hubitat: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/apps/cocohue-app.groovy
(NOTE: If you are upgrading from 1.x, apply this app code to the old child app, not the parent; the parent app is deprecated, though existing installs should continue to work as-is.)

3. Install all necessary drivers from the "drivers" folder in this repository into the **Drivers Code** section of Hubitat. (There aren't very many, so I'd recommend just installing them all, but technically all you need is the Bridge driver plus the driver for any device types you plan to use.)
    * Install the Bridge driver code: https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy
    * Install the bulb, group, scene, motion sensor, plug, button, etc. drivers:
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgbw-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-rgb-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-ct-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-dimmable-bulb-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-plug-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-motion-sensor-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy
      * https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-button-driver.groovy
    * NOTE: Users upgrading from v4.x or earlier may remove the CoCoHue Generic Status driver if installed, as it is now depcrecated (and any devices creating using it will no longer function and can be removed after being removed from any automations; these are/were Hue Labs activators that should be replaced with supported features).

4. Install an instance of app: go to **Apps > Add User App**, choose **CoCoHue**, and follow the prompts.

**NOTE**: Direct upgrades to version 5.x are possible from version 4.x only. Users of version 3.x or older must first
upgrade to version 4.x (switch to the "cocohue-4.2" branch as an easy way to find this version, or download the release as a
bundle ZIP by browsing old releases). Please carefully follow all instructions above, including open the app after each
upgrade and selecting "Done."

## Feature Documentation
CoCoHue is designed to be a replacement (although it can also be used as as supplement) for Hubitat's built-in Hue integration.

Besides features offered by the built-in integration, this integration adds the following features:

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

3. Buttons and sensors: support for Hue indor/outdoor motion sensor and button devices like Hue Tap and Hue Dimmer (sensors work best with v2 API, and buttons are supported only with v2 API--see below)

4. Hue API V2 support (experimental): allows for instant updates pushed from Bridge instead of polling-based approach from Hubitat.

5. Color loop effect: to fit in with Hubitat's "Light Effects" capability, Hue's only effect, `colorloop`, is implemented using
this capability and the command it uses, "Set Effect." Color loop is effect `1`. It can be activated by calling `setEffect(1)`.
"None" (no effect; normal behavior) is implemented as effect `0`, so the effect can be cancelled by calling `setEffect(0)`.
The `nextEffect` and `previousEffect` commands are pretty boring for this reason, but they are implemented to be consistent with
the standard Hubitat capability as it is currently documented. Setting a color, hue, or color temperature will also cancel the effect
(this is consistent with the behavior of other bulbs I tested). Setting a level or saturation will *not* because Hue allows adjustment
of these while the effect (which does not manipulate these values) is in progress.

6. "Select" and "LSelect" Hue alerts: these are basically a one-time flash and a 15-time flash. These are implemented as the
`flashOnce()` command and the now-standard `flash()` command, respectively. An in-progress flash can be stopped
with `flashOff()` (or you can wait until it stops on own, approximately 30 seconds on official bulbs).
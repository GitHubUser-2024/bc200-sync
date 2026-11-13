# Coospo BC200 sync

Android app that moves ride `.fit` files from a Coospo BC200 / BC200X bike computer to a phone
folder over Bluetooth LE. Android 10 or newer.

## Open, private, no strings attached

- **Free and open source.** All of the app's code is in this repository and is in the public
  domain ([The Unlicense](LICENSE)): use, change and share it however you like. The app uses no
  third-party libraries, only the Android platform itself (JUnit is used for the tests only).
- **No account, no cloud.** You don't sign up anywhere. The app has no internet permission at
  all, no ads and no tracking; your rides only go to a folder on the phone that you pick.
- **Only the permission it really needs:** Bluetooth "Nearby devices", to find and talk to the
  BC200. It needs no storage permission: you choose the sync folder in Android's own folder
  picker, which gives the app that one folder only. (On Android 10 and 11, Android itself
  requires the location permission for any Bluetooth search; the app asks for it only there
  and never uses your location.)

Not affiliated with or endorsed by Coospo; "Coospo" and "BC200" are named only to say which
device the app works with.

## What it does

When connected (and on **Sync now**), for every ride on the BC200:

1. Downloads it and checks the size and the whole-file FIT CRC.
2. Saves it to the chosen phone sync folder, reads it back and compares it byte for byte.
3. Only then, by default, deletes it from the BC200 and re-reads the ride list to confirm it is
   gone. ☰ → **Application settings** → **After sync** can keep rides on the BC200 instead; rides already in the
   phone folder (same name and size) are then not copied again.

The main screen lists the rides on the BC200 (while connected) and the rides on this phone (the
`.fit` files in the sync folder, read at start). Rides stay local in the phone folder; syncing
them to a server is left to other apps; this app has no internet permission.

## BC200 settings

☰ → **BC200 settings** (menus Bike, User, Device) changes the device's own settings, each mapping checked
against the BC200X menu:

| Option | Setting.json | Values |
|---|---|---|
| Backlight off | `backlight_profile.backlight` | 0 Never, 1 Auto, 2 15 s, 3 30 s, 4 1 min, 5 2 min |
| Brightness | `backlight_profile.brightness` | 1–6 as shown |
| Night mode | `user_profile.night_mode` | 0 On, 1 Off, 2 Auto |
| Units | `user_profile.unit` | 0 kg/km/°C, 1 lb/mi/°F |
| Time format | `time_profile.time_format` | 0 12 h, 1 24 h |
| Time zone | `time_profile.time_zone` | decimal hours, −12 … +14 in 0.25 steps |
| Smart pause, overwrite oldest rides | `user_profile.auto_pause`, `file_cover` | true / false |
| Key tone, warning tone | `tone_profile.key_tone`, `warning_tone` | true / false |
| Gender | `user_profile.gender` | 0 Male, 1 Female |
| Age, max HR, LTHR, FTP | `user_profile.age`, `MHR`, `LTHR`, `FTP` | as shown (HR in bpm, FTP in W) |
| Weight | `user_profile.weight` (+ `weight_e`) | kg × 10; `weight_e` = floor(kg × 2.2046226) × 10 |
| Height | `user_profile.stature` (+ `stature_e`) | cm × 10; `stature_e` = floor(cm / 30.48 × 100) |
| Bike 1–3 total distance | `bike_profile[0..2].odo` | km, any unit setting (device writes a typed value as a whole number); the app asks before replacing it |
| Bike wheel circumference | `bike_profile[0..2].wheelsize` | mm, 10–9999 |
| Bike weight | `bike_profile[0..2].weight` (+ `weight_e`) | kg × 10; `weight_e` = floor(kg × 22.046226), i.e. tenths of lb (unlike the rider) |
| Crank length | `bike_profile[0..2].crank_length` | mm × 10, 110.0–220.0 in 0.5 mm steps |

The BC200X ("version":"V2" settings) only accepts the whole `Setting.json` (upload: POST oid 43
FileInfo, then oid 44 packets, each ACKed). The app strips the whitespace from the device's own
file and replaces just the changed value tokens, then reads the file back to confirm (numbers
compared by value; the device prints decimals as `%f`). It refuses other settings versions.
The device itself writes the file when you leave its settings menu.

## Protocol

Service `fda0`: GET `fda1`, POST `fda2`, PUSH `fda3`, fast file `fda4`. See
`app/src/main/java/arpa/home/bc200sync/Protocol.kt` for the frame format, object ids
(delete = POST oid 42, which the device only ACKs) and the fast-transfer packet layout.

## Authors

- **Main author (code):** Claude Opus 5.5 (`claude-opus-5-5`), Anthropic's AI model, working
  in Claude Code.
- **Design and testing:** Jan Novák, who also tested every version on a real BC200X.

## Credits and references

- **BC200 protocol:** worked out independently, for interoperability, by studying how the
  official CoospoRide app (`com.onecoder.coosporide`, proprietary) talks to the BC200, and then
  checked step by step on a BC200X. No CoospoRide code is included in this app.
- **[xoss_sync](https://github.com/ekspla/xoss_sync)** by ekspla (MIT licence): consulted as an
  early lead, since it lists the BC200 among devices with YMODEM file transfer. The BC200X turned
  out to use a different Bluetooth service, so no code or protocol details were taken from it.
- **Icons:** [Material icons](https://github.com/google/material-design-icons) by Google
  (Apache 2.0): arrow_back, brightness_medium, chevron_right, info, menu, settings. The launcher
  icon is drawn by `tools/gen_icon.py` in this repository.

## Build

    gradle test assembleRelease

Needs JDK 17+, Gradle 8.10, Android SDK platform 35 and a `local.properties` with `sdk.dir`.
Release signing reads `~/.android/bc200-release.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); the key is never stored in this repo. Without that file the release
APK is unsigned.

## Updates

Each version is published as a release `vX.Y` with the signed APK attached, so an app updater
such as [Obtainium](https://github.com/ImranR98/Obtainium) can follow this repository.

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

How the app talks to the BC200, as worked out and tested on a BC200X. The code is in
[`Protocol.kt`](app/src/main/java/arpa/home/bc200sync/Protocol.kt) (message format, no
Android code, unit-tested) and
[`Bc200Client.kt`](app/src/main/java/arpa/home/bc200sync/Bc200Client.kt) (the Bluetooth
side). The `arpa/home/bc200sync` folders only follow the app's package name.

- **Bluetooth LE service `fda0`** with four characteristics: GET `fda1`, POST `fda2`,
  PUSH `fda3` (device messages) and `fda4` (fast file transfer). The app asks for an MTU of 247.
- **Frames:** `7E`, then the escaped header + body + CRC, then `7F`. Bytes `7D`/`7E`/`7F`
  inside are sent as `7D 01`/`7D 02`/`7D 03`.
  - Header byte 0 = `cmd<<6 | trans<<4 | sid` (cmd 0 GET, 1 POST; trans 0 request,
    2 ACK; sid = a 4-bit sequence number), bytes 1–2 = body length + 5, big-endian.
  - Body = a 2-byte object id (oid), then a Protocol Buffers message.
  - A 16-bit CRC over header + body ends the frame (see `crc16()`).
- **Object ids:** 1 device info, 4 functions, 41 get file, 42 delete file, 43 file info,
  44 file data, 45 stop transfer, 46 fast transfer over, 47 fast transfer result,
  50 file status (busy or not), 51 storage.
- **Ride list:** download the file `filelist.txt`, one `<name> <size>` per line.
- **Downloading a file:** check that the device isn't busy (oid 50), then GET oid 41 with
  the name. The device replies with oid 43 (the size), streams packets on `fda4`
  (4-byte index, data, 1-byte sum) and sends oid 46 at the end. The app then POSTs oid 47
  with either the missing packet numbers (they are sent again) or "complete". `.fit` files
  are also checked against their own FIT checksum.
- **Deleting a ride:** POST oid 42 with the name. The device only ACKs it, so the app reads
  the ride list again to confirm.
- **Settings:** the device's settings are the JSON file `Setting.json`. Read it like any
  file; to change it, POST oid 43 (name and size), then the whole file in oid 44 packets of
  up to 128 bytes, each starting with `[index, mark]` (mark 0 first, 1 middle, 2 last,
  3 only). Sending only the changed values does not work on firmware V2. The device writes
  the file when its own menu is left; the app reads it back to confirm. Field meanings and
  units are in [`DeviceSettings.kt`](app/src/main/java/arpa/home/bc200sync/DeviceSettings.kt).

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

    ./gradlew test assembleRelease

Needs JDK 17 or newer (releases are built with JDK 21) and the Android SDK (platform 35), found
through `ANDROID_HOME` or a `local.properties` with `sdk.dir`. The Gradle wrapper downloads
Gradle 8.10.2 and checks its SHA-256.
Release signing reads `~/.android/bc200-release.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); the key is never stored in this repo. Without that file the release
APK is unsigned.

**Reproducible build:** an unsigned build from the release tag is identical to the published APK
apart from its signature, so the signature can be copied onto it and verified (e.g. with
[apksigcopier](https://github.com/obfusk/apksigcopier) `compare`). The APK contains no
version-control info and no dependency-info block for this reason.

Store texts, icon and screenshots are in `fastlane/metadata/android/en-US/`.

## Updates

Each version is published as a release `vX.Y` with the signed APK attached, so an app updater
such as [Obtainium](https://github.com/ImranR98/Obtainium) can follow this repository.

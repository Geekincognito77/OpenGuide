# OpenGuide

A cable-style TV guide and channel browser for Fire TV and Android TV / Google TV, including Onn sticks. Channels and
streams come from [iptv-org](https://github.com/iptv-org/iptv).

Download the latest `OpenGuide.apk` from the [releases page](https://github.com/Zapiltar-E7/OpenGuide/releases/latest).
Once it's installed, **Settings > Check for updates** fetches and installs new versions.

## Install on a Fire TV stick

Fire OS sticks only. Newer models running Vega OS, such as the Fire TV Stick 4K Select, can't install Android apps.

1. On the stick, go to **Settings > My Fire TV > About** and press Select on the device name seven
   times to unlock Developer Options.
2. In **Settings > My Fire TV > Developer Options**, turn on **ADB Debugging**.
3. Find the stick's address in **Settings > My Fire TV > About > Network**.
4. On a PC on the same network, run the commands below, replacing the address with yours. Approve
   the prompt that appears on the TV.

```bash
adb connect 192.168.1.50:5555
```

```bash
adb install -r dist/OpenGuide.apk
```

The app then appears under **Your Apps & Channels**. Adb is in `D:\Claude\android-sdk\platform-tools`.

## Install on an Onn or other Google TV stick

1. Go to **Settings > System > About** and press Select on **Android TV OS build** seven times to
   unlock Developer Options.
2. In **Settings > System > Developer Options**, turn on **USB debugging**.
3. Find the stick's address in **Settings > Network & Internet**, then run the same two `adb` commands
   as above and approve the prompt on the TV.

The app appears in the Apps row. Menu names can vary a little between Android versions. If the
connection on port 5555 is refused, turn on **Wireless debugging** in Developer Options and use the
`adb pair` address and code it shows.

## Remote

Everything works with just the D-pad, OK and Back, plus Channel Up and Down to change channel. If your
remote has no channel buttons, turn on **Settings > D-pad Up/Down change channel**.

| Where | Button | Action |
|---|---|---|
| Guide | D-pad | Move through channels and time; hold Up or Down to scroll faster |
| Guide | Channel Up / Down | Page through the guide |
| Guide | OK | Watch full screen |
| Guide | Hold OK | Options: watch, preview in the small window, favorite, CRT list |
| Guide | Back | Return to the channel you're watching and the current time, then to the tabs; press again to exit |
| Channels | OK / Hold OK | Watch / options |
| Watching | Channel Up / Down | Change channel |
| Watching | D-pad Up / Down, or OK | Show programme info |
| Watching | Left | Return to the last channel |
| Watching | Right or hold OK | Options: favorite, CRT list, subtitles, picture, crop, last channel, stream source |
| Watching | Back | Return to the guide |

Remotes that have them can also use these shortcuts:

| Where | Button | Action |
|---|---|---|
| Guide | Play/Pause | Preview in the small window |
| Guide | Menu | Add or remove a favorite |
| Guide | Rewind / Fast Forward | Page through channels |
| Watching | Menu | Open options |
| Watching | Rewind | Return to the last channel |

## CRT TVs

Turn on **Settings > CRT TV mode** for an old-style guide with larger text, flat colours and fewer
half hours on screen. Its settings appear below that row:

- **Default picture** sets how video fits on channels you haven't set yourself: Fit keeps its shape
  with black bars, Fill crops the edges, and Stretch fills the screen.
- **Signal** says what your adapter does. Most HDMI-to-composite adapters squeeze the whole picture
  to 4:3, and the app corrects text and video shape for that. Choose Shape kept if yours doesn't.
- **Adjust overscan** moves everything inside the part of the picture your TV shows. Video still runs
  to the edges, as broadcasts do, unless you turn on **Keep video inside overscan**.

While watching, press Right for two more options, each saved for that channel:

- **Picture** overrides the default fit.
- **Crop black bars** cuts bars baked into a stream, adjusted live over the video. It works best with
  Fill or Stretch, because Fit keeps the bars the picture needs to hold its shape.

Instead of the information banner, CRT mode shows just the channel number in the top left corner when
you change channel, or "NO SIGNAL" beside it if the channel isn't responding.

**CRT list** is your own channel list, like Favorites. Hold OK on a channel to add it. In CRT mode the
guide starts on it once it has channels, and you can switch lists from the guide's menu at the top.

**Guide channel** is channel 0 in CRT mode: what's on now and next across your list, scrolling slowly
up the screen like an old cable guide channel. It lists the channels with something on in whichever
list you're watching from, so from the CRT list it shows just that list. Turn it off with
**Settings > Guide channel**.

**Static** fills the screen for a moment when you change channel in CRT mode, and stays until the new
channel's picture is ready, for up to five seconds. Turn it off with **Settings > Static when changing
channel**.

## Subtitles

Press Right while watching to turn subtitles on or off. The choice is saved for each channel. Not every
stream offers them, and the app tells you when one doesn't.

## Where the listings come from

iptv-org no longer publishes guide data, so listings come from public XMLTV feeds, matched to
iptv-org channels by ID or by name:

- [i.mjh.nz](https://i.mjh.nz/): Pluto TV, Samsung TV Plus, Plex, Roku
- [EPGShare](https://epgshare01.online/) and [EPG.PW](https://epg.pw/): by country

Not every channel gets listings. Those without them show "No listings" and still play. Settings has
an option to show only channels with something listed right now, and shows the current count for your
region. Feed URLs are in
`app/src/main/kotlin/com/openguide/tv/data/Epg.kt`.

## Notes

- Channels refresh every 24 hours and listings every 6, on launch and while the app is open.
- Streams are third-party and can go offline. Each channel's sources are tried in turn. A stream that
  stalls after playing is restarted, and if the connection drops the channel keeps retrying.
- The app checks every channel's sources in the background about twice a day and hides those with none
  working. **Settings > Hide channels with no working source** turns this off. Favorites and the CRT
  list always keep what you added, greyed out if it isn't working.
- **Settings > Language** shows only channels whose main language is the one selected. It defaults to
  the device's language. Language data comes from iptv-org, corrected by channel name because many
  Spanish and Portuguese channels are tagged as English. Some mislabeled channels will still get through.
  Favorites and the CRT list follow the region and language too, so a channel outside them reappears
  when you switch back.
- Adult channels and channels on iptv-org's blocklist are left out, as are iptv-org streams that
  aren't linked to a known channel.

## Build

Requires JDK 21 and the Android SDK (platform 36). Put `sdk.dir` in `local.properties`, then:

```bash
./gradlew testDebugUnitTest assembleRelease
```

With a device or emulator connected, `./gradlew connectedDebugAndroidTest` runs the on-device tests.
They need internet access, and they uninstall the app when they finish.

Release builds are signed with the local debug key (`~/.android/debug.keystore`) so they can be
sideloaded. **Back that file up.** Android only installs an update signed with the same key, so without
it every install would have to be removed and set up again.

## Releasing

Raise `appVersion` in `app/build.gradle.kts`, then build and publish with a matching tag. The update
check compares that tag with the installed version and downloads the release's `OpenGuide.apk`:

```bash
./gradlew testDebugUnitTest assembleRelease
```

```bash
cp app/build/outputs/apk/release/app-release.apk dist/OpenGuide.apk
```

```bash
gh release create v1.2.0 dist/OpenGuide.apk --title "OpenGuide 1.2.0" --notes "What changed"
```

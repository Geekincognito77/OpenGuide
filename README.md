# OpenGuide

A cable-style TV guide and channel browser for Fire TV, Android TV / Google TV (including Onn sticks) and
LG webOS TVs. Channels and streams come from [iptv-org](https://github.com/iptv-org/iptv).

Download the latest build from the [releases page](https://github.com/Zapiltar-E7/OpenGuide/releases/latest):
`OpenGuide.apk` for Fire TV and Android TV, `OpenGuide.ipk` for LG. On Android, **Settings > Check for updates**
fetches and installs new versions.

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

## Install on an LG webOS TV

For webOS 4 and newer, which is most LG TVs from 2018 on. See [webOS differences](#webos-differences) for
what's different from the Android app and how far this build has been tested.

1. On the TV, install the **Developer Mode** app from the LG Content Store and sign in with a free
   [LG developer account](https://webostv.developer.lge.com/).
2. In the app, turn on **Dev Mode Status** and let the TV restart. Open the app again, turn on **Key Server**,
   and note the IP address and passphrase it shows.
3. On a PC on the same network, install LG's command line tools. They need [Node.js](https://nodejs.org/).

```bash
npm install -g @webos-tools/cli
```

4. Add the TV, replacing the address with yours, then fetch its key. Enter the passphrase when asked.

```bash
ares-setup-device -a tv -i "username=prisoner" -i "host=192.168.1.60" -i "port=9922"
```

```bash
ares-novacom --getkey -d tv
```

5. Install and start the app:

```bash
ares-install -d tv OpenGuide.ipk
```

```bash
ares-launch -d tv com.openguide.tv
```

The app then appears in the launcher. Developer Mode runs on a timer, and LG removes sideloaded apps when it
runs out, so open the Developer Mode app now and then and use **Extend**. Installing again keeps your settings
only if the app wasn't removed first.

To update, install the new `OpenGuide.ipk` the same way. **Settings > Check for updates** tells you when there's
a new version, but a webOS app can't install itself.

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

On an LG remote, the red button does what Menu does above. Play and Pause work as Play/Pause does, and the Magic
Remote's pointer works too: point and click to choose, hold the click for options, and turn the wheel to move
through rows or change channel.

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

## webOS differences

The webOS app in [`webos/`](webos) is a packaged web app that shares the Android app's features and rules, with
these differences:

- **Testing so far.** It has been tested in a desktop browser against live data, with automated tests for the
  guide logic and data handling, but **not yet on a real LG TV or the webOS emulator**. Playback through the TV's
  own player, the remote's keys, and old engines are the parts most likely to need fixes.
- **CRT settings.** CRT mode, the CRT list, the guide channel, static, picture and crop are all here. **Signal**
  and **Adjust overscan** aren't, because they exist for adapters feeding a tube, and an LG TV has no video output
  to attach one to.
- **Streams with their own headers.** A web app can't set the User-Agent or Referer on video, so streams that ask
  for them often won't play. They're still tried, after the channel's other sources.
- **Subtitles.** The TV's own player may not expose them, so turning them on switches that channel to
  [hls.js](https://github.com/video-dev/hls.js). That needs the stream's server to allow cross-origin requests,
  so fewer streams offer subtitles than on Android.
- **Pluto TV ad breaks.** The label for Pluto's logo screen goes by the names of the stream's segments, which the
  TV's own player doesn't reveal, so Pluto channels play through hls.js when the TV allows cross-origin requests.
  If it doesn't, they play as usual without the label.
- **Cross-origin requests.** LG's documentation doesn't settle whether packaged apps are subject to CORS, so the
  app tests this when it starts. If they are:
  - EPGShare listings aren't available. Pluto TV, Samsung TV Plus, Plex, Roku and EPG.PW still load, the first
    four straight from where i.mjh.nz hosts them, since its redirect passes through a host that fails the check.
  - The background source check is skipped, so a channel is only hidden once tuning to it has failed.
  - Pluto TV ad breaks aren't labelled.

## Build

### Android

Requires JDK 21 and the Android SDK (platform 36). Put `sdk.dir` in `local.properties`, then:

```bash
./gradlew testDebugUnitTest assembleRelease
```

With a device or emulator connected, `./gradlew connectedDebugAndroidTest` runs the on-device tests.
They need internet access, and they uninstall the app when they finish.

Release builds are signed with the local debug key (`~/.android/debug.keystore`) so they can be
sideloaded. **Back that file up.** Android only installs an update signed with the same key, so without
it every install would have to be removed and set up again.

### webOS

Requires Node.js 20 or newer. From the `webos` folder, this runs the tests and writes `OpenGuide.ipk`:

```bash
npm install
```

```bash
npm test
```

```bash
npm run package
```

To try the app in a desktop browser, run `npm run build` and `npm run serve`, then open
http://localhost:8123. Arrow keys, Enter and Esc stand in for the remote. Browsers enforce CORS, so as described
under [webOS differences](#webos-differences), some listings and streams won't load there.

Every feature goes into both apps, in the same release, apart from the differences listed above.
`webos/src/data/logic.js` is a port of `GuideLogic.kt`, and `webos/test/logic.test.js` of its tests, so a change to
the rules in one app should be made in the other too.

## Releasing

Both apps share a version number. Raise `appVersion` in `app/build.gradle.kts` and `version` in
`webos/package.json` together, then build and publish with a matching tag. The update checks compare that tag with the installed version, and each looks for its own file in the
latest release, so attach both:

```bash
./gradlew testDebugUnitTest assembleRelease
```

```bash
cp app/build/outputs/apk/release/app-release.apk dist/OpenGuide.apk
```

```bash
cd webos && npm test && npm run package && cp OpenGuide.ipk ../dist/ && cd ..
```

```bash
gh release create v1.3.0 dist/OpenGuide.apk dist/OpenGuide.ipk --title "OpenGuide 1.3.0" --notes "What changed"
```

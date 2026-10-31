# Extras phone test

**Result: every extra worked on real phones, but keep-alive depends on the phone brand.** The Samsung worked while locked with no setup, and the vivo worked after a settings change. The OnePlus froze the app while locked, even after those settings were changed.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 11:52–12:53 IST |
| Build | `member1/extras` at `0760f76`, as `builds/emergencymesh-extras.apk` |
| Phones | A: vivo V2404, Android 16. B: Samsung SM-S721B, Android 16. C: OnePlus CPH2569, Android 15 |
| Setup | A-B-C forced in the Debug tab, with A and C unable to reach each other by radio |
| Crashes | 0 crash log lines on all 3 phones across the whole run |

## Limits of this run

- **Not fully offline.** B and C were in airplane mode with Bluetooth on and Wi-Fi off. A stayed on a Wi-Fi hotspot with internet, because its settings weren't changed.
- **USB power.** Phones were plugged in, so deep Doze wasn't reached. The longest lock on a passing phone was about 27 minutes.
- **Laptop tests used USB.** The Mac wasn't on the phones' network, so `adb reverse` carried the traffic. Real Wi-Fi between phones and a laptop is untested.
- **Faked battery.** Last Breath used `dumpsys battery`, and it was reset afterwards.
- **No real AI.** No Featherless key was available, so the dashboard ran with AI off.

## Keep-alive by phone

| Phone | Locked result |
| --- | --- |
| Samsung | **Pass, with no setup.** It relayed with its screen off from 11:57 until it was unlocked at about 12:24 |
| vivo | **Pass, after setup.** With no exemption, it stopped answering once locked, and handled nothing until it was woken. After **Allow background relay** and its high background power setting, it received a message 2 seconds after it was sent, having been locked for 7 minutes |
| OnePlus | **Fail.** Its log shows `OplusHansManager freeze uid: 10390 com.slackers.emergencymesh ... scene: |airPlane|LcdOff`, 26 seconds after the screen went off. It froze again after both exemptions, 22 seconds after the screen went off. Queued packets arrived once the screen came back on |

**For the demo:** use the Samsung as the locked phone. Keep the OnePlus screen on.

## Results

| Extra | Test | Result |
| --- | --- | --- |
| Keep-alive | Installs over existing data, and all 3 phones reconnect | Pass, in 29 seconds |
| Keep-alive | Service runs in the foreground, with its notification | Pass on all 3 |
| Keep-alive | A to C through B with B's screen off | Pass: Delivered at 2.7 seconds, showing 1 hop |
| Keep-alive | B's database holds none of that text | Pass |
| Keep-alive | Stop ends the service and its notification, and Start brings them back | Pass on the OnePlus |
| Keep-alive | Reopening the screen with the mesh on | Partial: the OnePlus kept its service. On the vivo the mesh stayed on, but clearing its task ended the service, and the app warned to keep it open |
| Alerts | Direct message on a locked phone | Pass on the OnePlus and vivo, with the text hidden |
| Alerts | New SOS on a locked phone | Pass on the Samsung |
| Call-Out | Locked Samsung rings and flashes | Pass: confirmed by a person who heard and saw it |
| Call-Out | Timing and clean-up | Pass: alarm volume went from 10 to 15 and back to 10 after 10 seconds, and the torch ended off |
| Call-Out | Rescuer sees confirmation | Pass: "Phone B is ringing. Listen for it." |
| Call-Out | A phone that wasn't called stays silent | Pass |
| Call-Out | A phone whose SOS has ended stays silent | Pass, with no confirmation |
| Talk-Back | Locked Samsung reads a question aloud | Pass: confirmed by a person who heard it |
| Talk-Back | It listens and replies with nobody touching it | Pass, 20 seconds after asking |
| Talk-Back | Understanding speech | Rough: a Mac saying "my leg is stuck" came back as "no my leg is start my legisla somebody's no" |
| Last Breath | One final SOS at 4% battery | Pass: sent within about a second, with the earlier message |
| Last Breath | No repeat at 3% and 2% | Pass |
| No-app SOS | A phone hears `SOS 3 TRAPPED FLOOR2` | Pass, within seconds of listening starting |
| No-app SOS | The alert crosses the mesh, marked not verified | Pass, in about a second |
| No-app SOS | The mesh relays during a scan and a reply | Pass: Delivered at 2.5 seconds |
| No-app SOS | The phone gets its name back | Pass: the reply name went away after 2 minutes, and the real name returned when the mesh stopped |
| Laptop node | A to C and C to A with all radios blocked | Pass: Delivered at 2.5 seconds both ways |
| Laptop node | The laptop can't read messages | Pass: 332 packets held, with none of the test messages in them |
| Laptop node | Laptop goes down, then returns | Pass: the message arrived 11 seconds after it came back |
| Upload | SOS reaches the dashboard | Pass, in under a second |
| Upload | 2 phones upload the same SOS | Pass: 1 alert |
| Upload | Direct messages stay off the dashboard | Pass |
| Upload | Turning it off stops uploads | Pass: 5 later SOS alerts didn't appear |

## How No-app SOS was tested

There was no fourth phone. Phone A used the app's own reply feature to rename its Bluetooth, so the rename, scan and alert were all real. A still had the app installed, so a phone that has never had the app is untested.

## Not tested

- **Keep-alive:** rotating the screen, removing the app from recent apps, and the Stop button inside the notification.
- **Call-Out:** silent mode, **Stop ringing**, and ringing several phones in turn. These calls used a direct link, so relayed calls only have unit tests.
- **Talk-Back:** Hindi, Telugu, and the phone's behavior with the box unticked.
- **Last Breath:** a later phone receiving the final SOS after the sender has switched off.
- **No-app SOS:** a real phone without the app, and iPhones.
- **Upload:** a wrong token, and the dashboard going down.
- **AI:** everything that needs a real model, including accuracy.
- **Battery and range:** neither has been measured.

## Bugs found

| Issue | Status |
| --- | --- |
| The OnePlus freezes the app while locked | Open. The standard exemptions don't stop it |
| The app has no icon of its own | Open. It's cosmetic |
| Phones show as "Phone 98b6" until someone renames them | Open. It's cosmetic |

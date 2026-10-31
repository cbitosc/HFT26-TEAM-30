# Requirement test matrix

Status as of 31 October 2026, 13:38 IST. A requirement is **Pass** only where the cited real-phone test passed. **Partial** means that implementation exists but measurement or device coverage is incomplete.

## Required system

| Requirement | Status | Real evidence | Remaining limit |
| --- | --- | --- | --- |
| 1. Offline communication | **Pass** | Three phones exchanged real app messages in airplane mode with Wi-Fi off; each printed `Network is unreachable` before and after | The later extras run was not fully offline |
| 2. Peer-to-peer messaging | **Pass** | vivo, Samsung and OnePlus connected and exchanged encrypted direct messages | Android only; Google Play services required |
| 3. Mesh relaying | **Pass** | A reached C through B with A-C blocked; both directions delivered in about 2.6 seconds and showed 1 hop | One relay hop tested on real phones |
| 4. SOS broadcasting | **Pass** | SOS reached both phones through the forced topology, worked with location denied and used continuous scanning while active | A real indoor location fix was unavailable |
| 5. Message prioritization | **Pass** | SOS arrived before 20 queued regular messages; regular messages then arrived in order | Test used three phones |
| 6. Delivery status | **Pass** | Pending, Sent, Relayed and Delivered appeared with sender-side recorded times | Messages created before timestamp support can have blank historical times |
| Security | **Pass for tested build** | Tink was integrated; relay storage contained no tested message text; a packet with an invalid signature was rejected | No independent security review or forward secrecy |
| 7. Battery efficiency | **Partial** | Balanced and Continuous scan modes worked; foreground keep-alive worked on Samsung and on vivo after settings changes | No battery comparison; OnePlus froze the app while locked |
| 8. Recovery and synchronization | **Pass** | A Pending message survived restart, delivered after reconnect and appeared exactly once | Reconnect took up to about 40 seconds in one run |
| Fewest-hop direct routing | **Pass in tested topology** | In a three-phone triangle, two direct messages used the zero-hop link without appearing on B; after that link was removed, the next went through B. Five simulated phones checked the short path and fallback | The first message spreads through all paths; 1,000 phones were not tested |
| Offline User, Responder and Admin logins | **Pass in tested cases** | Wrong role code was refused; Responder and Admin opened their own controls; User needed no code; logging out kept the mesh running | The app stores public keys only, but shared responder credentials cannot revoke one person |
| Signed area alerts | **Pass with debug locations** | B relayed without showing the alert; C inside the area displayed it after one hop in 3.0 seconds. A locked Samsung inside another area alerted in 2.1 seconds | Indoor test locations, not real GPS boundaries; a phone without location displays the alert |
| Admin SOS location | **Pass with debug locations** | Admin saw a test SOS at 19.6 km east, with coordinates and accuracy; a no-location SOS showed its hop count | SOS has coordinates only when the sender's fix is under two minutes old |

## Optional extras

| Extra | Status | What passed | Limit of the proof |
| --- | --- | --- | --- |
| Background keep-alive | **Partial** | Locked Samsung relayed with no setup; vivo relayed after a background-power setting | OnePlus froze even after exemptions; deep Doze was not reached |
| Locked-phone alerts | **Pass in tested cases** | Direct-message and SOS notifications appeared with direct text hidden | Not tested on every phone and state |
| Rescue Call-Out | **Pass in tested cases** | Locked Samsung rang and flashed; volume and torch were restored; rescuer saw confirmation | Relayed calls have unit-test proof only; silent mode and Stop ringing untested |
| Talk-Back | **Pass, rough recognition** | Samsung spoke a question, listened and replied without touch | Recognition was poor; Hindi and Telugu untested |
| Last Breath | **Pass with simulated battery** | One final SOS sent at simulated 4%, with no repeat at 3% or 2% | Battery level was faked with `dumpsys battery` |
| No-app SOS | **Pass in simulation** | Bluetooth-name SOS crossed the mesh and was marked unverified | Sender still had the app installed; a true no-app phone and iPhone are untested |
| Laptop node | **Pass over USB path** | With radio links blocked, messages delivered both ways through the laptop; its database contained no tested message text | `adb reverse` carried traffic; real phone-to-laptop Wi-Fi is untested |
| Dashboard upload | **Pass over USB path** | SOS appeared in under a second; duplicates collapsed; direct messages stayed off the dashboard | Wrong token and dashboard outage untested |
| AI dashboard | **Implemented, model untested** | Dashboard UI, safe fallback and 70 tests passed against a fake Featherless server | No real model key was available; accuracy is not measured |

## Latest required-build checks

| Check | Result |
| --- | --- |
| Build | `d0f2b0f`, combining the status UI and M3 engine timestamps |
| Unit tests | 119 of 119 passed on debug and release |
| Phones | vivo V2404 (Android 16), Samsung SM-S721B (Android 16), OnePlus CPH2569 (Android 15) |
| Database upgrade | Existing data survived install on vivo and OnePlus |
| Status timeline | All four stages displayed for `merged71` |
| SOS scanning | Balanced before SOS, Continuous while active, Balanced after ending |
| Key-change warning | Warning appeared and the stored key was kept |
| Stability | Zero app crash lines on all three phones during the final required-build run |

## Mentor changes build

| Check | Result |
| --- | --- |
| Build | `member1/mentor-fixes` at `8a0fe65`, with [phone evidence](evidence/mentor-fixes.md) committed afterwards |
| Unit tests | 249 run on debug and release, 0 failed, 2 skipped because they need a live service |
| Phones | vivo V2404, Samsung SM-S721B, OnePlus CPH2569 |
| Area alert | Crossed an outside relay and appeared on the inside phone in 3.0 seconds |
| Locked alert | Samsung inside the target area alerted in 2.1 seconds |
| Routing | Triangle selected direct link; removing it restored the one-hop route through B |
| Scope | Debug test locations indoors; three real and five simulated phones, not 1,000 |

## Evidence

- [Final required build](evidence/final-build.md)
- [Required feature run](evidence/required-features.md)
- [Extras phone test](evidence/extras-phone-test.md)
- [A-to-C relay](evidence/relay-a-to-c.md)
- [Encrypted messaging](evidence/encrypted-messaging.md)
- [Contact exchange](evidence/contact-exchange.md)
- [Security tests](evidence/tink-security.md)
- [Mentor changes phone test](evidence/mentor-fixes.md)

## Still unmeasured or unproven

- Battery consumption and comparison between scan modes
- Indoor and outdoor range, until M4 records the current range run
- More than one relay hop on real phones
- Real AI model output or accuracy
- Real Wi-Fi transport between phones and the laptop node
- Reliable background operation across all Android brands

## Bug report format

```text
Commit:
Phone and Android version:
Steps:
Expected:
Actual:
Log or screenshot:
Severity:
Owner:
```

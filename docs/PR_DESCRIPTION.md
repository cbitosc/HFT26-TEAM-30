# [SUBMISSION] TEAM-30 - Slackers - Offline Emergency Messaging System

## Overview

EmergencyMesh is an Android prototype for messages and SOS alerts when cellular service and internet are unavailable. Nearby phones can carry encrypted packets to a recipient, and the sender sees a delivery timeline. The app retains an undelivered message through a restart and retries after the mesh reconnects.

## Approach

1. A message is saved on the sender's phone before any send attempt.
2. Nearby phones connect directly with Google Nearby Connections and carry encrypted packets for each other.
3. SOS and emergency messages go ahead of regular messages.
4. Status comes from real acknowledgements: Pending, Sent, Relayed and Delivered.
5. With no path, a message stays Pending and sends when a phone comes in range.
6. The core works entirely offline. The AI dashboard is optional and needs internet.

## Architecture

- Kotlin and Jetpack Compose provide the interface; Room stores messages and retry state.
- Google Nearby Connections discovers peers. An intermediate phone forwards a packet and returns a delivery acknowledgement.
- Direct messages learn a route from verified traffic and try the fewest-hop path first, with an eight-second flood fallback. SOS and signed area alerts continue to flood.
- User, Responder and Admin controls work offline. A responder can send an area alert; an admin sees an SOS's distance and direction if it includes a recent location.
- Google Tink and Android Keystore protect content and identity. See [security evidence](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/tink-security.md) and the [two-phone encrypted messaging test](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/encrypted-messaging.md).
- Optional tools include foreground relay, Call-Out and Talk-Back, a low-battery SOS, Bluetooth-name SOS intake, a laptop node and an SOS dashboard. Dashboard AI was disabled during the phone test.

## Tech Stack

- **Android:** Kotlin, Jetpack Compose, Room and foreground services
- **Radio:** Google Nearby Connections, `P2P_CLUSTER`
- **Security:** Google Tink and the Android Keystore
- **Dashboard and laptop node:** Python 3 standard library
- **Optional AI:** Featherless-hosted model, default `sarvamai/sarvam-m`
- **Tests:** JUnit, AndroidX Test and Python `unittest`

## Setup and demo

Download the [final APK](https://github.com/shrenik111/HFT26-TEAM-30/releases/tag/v0.1.0), or build it yourself by following the [README](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/README.md#setup). The README also has [step-by-step app instructions](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/README.md#using-the-app).

Open `src/android` in Android Studio, let Gradle sync, and install the debug app on Android phones with Bluetooth enabled. Pair and approve the devices. For offline relay, enable airplane mode and Bluetooth, turn off Wi-Fi, and place A, B and C so A cannot reach C directly. Send from A to C through B. The Debug tab's allowlist can enforce the A–B–C topology. See the [demo script](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/DEMO_SCRIPT.md).

The optional dashboard has [setup instructions](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/src/dashboard/README.md). Role codes and the group code aren't in this repository, so ask the team for them.

## Evidence

- [Final offline three-phone build](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/final-build.md): vivo V2404, Samsung SM-S721B and OnePlus CPH2569; one-hop delivery in 2.6 seconds; four-stage timeline; no app crash lines in that run.
- [Required features](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/required-features.md): SOS ahead of 20 regular messages, Pending survives restart, one arrival per message ID.
- [Extras phone test](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/extras-phone-test.md): locked Samsung relay passed and vivo passed after a background setting change; OnePlus froze while locked. Laptop and dashboard used a USB bridge; AI was off.
- [Security review](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/security-review.md): each security claim tied to the build that tested it, including a relay phone holding no readable text on the final build.
- [Mentor changes phone test](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/evidence/mentor-fixes.md): offline roles; area alert crossed an outside relay and reached the inside phone in 3.0 seconds; locked Samsung inside was notified in 2.1 seconds; direct messages preferred a direct triangle link and used the relay after it disappeared.
- [Test matrix](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/TEST_MATRIX.md), [six-slide presentation](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/TEAM30_SUBMISSION.pptx) and its [editable outline](https://github.com/shrenik111/HFT26-TEAM-30/blob/main/docs/SLIDES.md).

### Screenshots

| Phone | Evidence screenshot |
| --- | --- |
| vivo A | ![vivo A](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/three-phone-A-vivo.png) |
| Samsung B | ![Samsung B](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/three-phone-B-samsung.png) |
| OnePlus C | ![OnePlus C](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/three-phone-C-oneplus.png) |

| Feature | Screenshot, using debug test locations |
| --- | --- |
| Offline login | ![Login screen refusing a wrong code](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/mentor-login.png) |
| Responder area alert | ![Responder warning an area](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/mentor-responder.png) |
| Phone inside the area | ![Phone inside the area showing the alert](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/mentor-alert-inside.png) |
| Phone outside the area | ![Phone outside the area showing nothing](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/mentor-alert-outside.png) |
| Admin location screen | ![Admin seeing where someone needs help](https://raw.githubusercontent.com/shrenik111/HFT26-TEAM-30/main/docs/evidence/mentor-admin.png) |

### Demo video

**[ADD FINAL PUBLIC VIDEO LINK AFTER RECORDING THE FINAL APK]**

## Limits

Delivery requires available nearby phones. Locked relay depends on brand and battery policy. Battery drain and physical range have not been measured in the cited evidence. The extras run was not fully offline because A stayed on an internet hotspot; the core final-build test was fully offline. Real laptop Wi-Fi and a real phone without the app were not tested. A live AI model was not evaluated.

Mentor changes used indoor debug test locations, not real GPS boundaries. A phone without location displays an area alert rather than hiding it. An SOS carries a location only if its phone has a fix from the last two minutes. Routing was checked on three real phones and five simulated phones, not 1,000; an initial message to a new peer still spreads through all paths.

SOS is readable by phones holding the group code. There has been no independent security review and no forward secrecy. Demo role codes are short and shared, so real use needs long random codes.

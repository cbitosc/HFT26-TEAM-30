# EmergencyMesh: six-slide deck

Use only results from a build tested on phones. The evidence below names the build used at the time; check later mentor changes before claiming them.

## 1. Problem

### Emergency messages when the network fails

Cellular service or internet can fail during an emergency. Nearby people still need to request help and reach responders. EmergencyMesh keeps messages on Android phones and sends them over nearby connections when a path becomes available.

## 2. How it works

### A message can travel through another phone

With the A–C radio link blocked, A sent to C through B. C returned a delivery acknowledgement. A message sent while isolated remained Pending through an app restart and arrived after reconnecting. An SOS took priority over 20 queued regular messages.

The core three-phone run was offline: airplane mode on, Bluetooth on, Wi-Fi off. See [required features](evidence/required-features.md) and [final build](evidence/final-build.md).

## 3. What we built

### Android mesh and optional response tools

- Kotlin and Jetpack Compose app, Room queue, Nearby Connections radio, Tink and Android Keystore protection.
- Direct messages, SOS alerts, delivery status timeline and queued recovery.
- Prototype extras: locked-screen relay on some brands, audible Call-Out, spoken Talk-Back, low-battery Last Breath and Bluetooth-name SOS intake.
- Laptop relay and SOS dashboard were tested through a USB bridge. Dashboard AI was off without a live model key.

See [extras phone test](evidence/extras-phone-test.md) for precise scope.

## 4. Evidence

### Three brands, measured delivery

| Test | Observed result |
| --- | --- |
| vivo → Samsung → OnePlus | One-hop delivery in 2.6 seconds on the final offline build |
| Queued traffic | SOS arrived before 20 regular messages |
| Restart | Pending survived restart and delivered exactly once |
| Status | Pending, Sent, Relayed and Delivered appeared in the timeline |
| Extra run | Locked Samsung relayed in 2.7 seconds; vivo needed battery settings; OnePlus froze while locked |

Evidence: [final build](evidence/final-build.md), [required features](evidence/required-features.md), [extras phone test](evidence/extras-phone-test.md).
Screenshots: [vivo](evidence/three-phone-A-vivo.png), [Samsung](evidence/three-phone-B-samsung.png), [OnePlus](evidence/three-phone-C-oneplus.png).

## 5. Limits

### Radio coverage and phone policy still matter

- Another phone must be in radio range and able to run the mesh.
- Locked relay varied by brand. Samsung passed, vivo required background settings, OnePlus froze despite exemptions.
- In the extras run, A used an internet hotspot and phones had USB power. Laptop and dashboard links used `adb reverse`.
- Battery drain and physical range were not measured in these evidence files. AI accuracy and a real phone without the app were not tested.

## 6. What the mentors asked and what we changed

### Routing, responder alerts and visibility

- Three-phone tests established relay, delivery acknowledgements and queued recovery. The later routing branch targets fewer hops with an eight-second flood fallback. Mark this implemented only after merge and final phone recheck.
- User, Responder and Admin roles, area alerts and admin location screen remain pending until Shrenik reports that the final APK passed.
- A status timeline now retains the brief Relayed state after delivery.

Before recording, replace pending statements only with mentor changes verified on the final APK.

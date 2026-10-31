# EmergencyMesh: six-slide deck

Use results from the [mentor-changes phone test](evidence/mentor-fixes.md) and the earlier required-feature and extras runs. All code claims refer to the tested debug build.

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
- Three offline logins: User, Responder and Admin. Responders send signed area alerts; Admin sees SOS distance and direction without internet.
- Direct messages try learned fewest-hop routes first, with an eight-second flood fallback.
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
| Area alert | Outside B relayed without showing it; inside C showed it after one hop in 3.0 seconds |
| Locked area alert | Samsung inside the area was alerted in 2.1 seconds |
| Shortest path | In a triangle, B saw none of two direct messages. After the direct link was removed, the next went through B |

Evidence: [mentor changes](evidence/mentor-fixes.md), [final build](evidence/final-build.md), [required features](evidence/required-features.md), [extras phone test](evidence/extras-phone-test.md).
Screenshots: [vivo](evidence/three-phone-A-vivo.png), [Samsung](evidence/three-phone-B-samsung.png), [OnePlus](evidence/three-phone-C-oneplus.png).

## 5. Limits

### Radio coverage and phone policy still matter

- Another phone must be in radio range and able to run the mesh.
- Locked relay varied by brand. Samsung passed, vivo required background settings, OnePlus froze despite exemptions.
- In the extras run, A used an internet hotspot and phones had USB power. Laptop and dashboard links used `adb reverse`.
- Battery drain and physical range were not measured in these evidence files. AI accuracy and a real phone without the app were not tested.
- Routing was tested on 3 real phones and 5 simulated phones, not 1,000. The first message to a new phone still spreads through all paths.
- Area alerts used indoor debug test locations, not real GPS boundaries. An SOS carries a location only when the phone had a fix in the last 2 minutes.
- SOS is readable by phones holding the group code. There has been no independent security review and no forward secrecy.

## 6. What the mentors asked and what we changed

| Mentor question | What we did |
| --- | --- |
| 1,000 phones: how do you pick the shortest path? | Direct messages learn the fewest-hop route from verified traffic and try it first. Other paths open after 8 seconds if needed. Routes expire after 5 minutes and drop when a phone leaves. SOS and area alerts still flood. We tested 3 real and 5 simulated phones, not 1,000. |
| Three logins? | User, Responder and Admin work offline. The app holds only public role keys. |
| Everyone uses hopping. How are you different? | Call-Out, Talk-Back, Last Breath and no-app SOS intake help people who cannot use the app directly. A laptop node can carry packets. The no-app test used a phone with the app installed. |
| How does the admin know where someone is? | Each SOS with a recent fix shows its distance and direction from the admin's phone without maps or internet. |
| Can responders warn one area without a network? | Every phone relays a signed area alert; only phones inside show it when location is known. The phone test used debug locations. |

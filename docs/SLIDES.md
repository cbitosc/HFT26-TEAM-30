# EmergencyMesh: six-slide deck

This is the text of [the deck](TEAM30_SUBMISSION.pptx).

## 1. EmergencyMesh

Emergency messages that work when the network doesn't. Team Slackers, TEAM-30, CBIT Hacktoberfest '26.

## 2. Nearby phones become the network

- **Sender:** saves the message, then encrypts it
- **Relay:** carries it, and stores no readable text
- **Recipient:** decrypts it and confirms delivery
- With no path, a message waits and sends when a phone is in range
- Sent, Relayed and Delivered come from real acknowledgements

## 3. Required features, tested on real phones

| Requirement | Status | Result |
| --- | --- | --- |
| Offline messaging | Pass | Airplane mode on, Wi-Fi off |
| Peer-to-peer | Pass | vivo, Samsung and OnePlus |
| Mesh relay | Pass | A reached C through B in 2.6 s |
| SOS broadcast | Pass | Reached every phone, with or without location |
| Message priority | Pass | SOS arrived before 20 queued messages |
| Delivery status | Pass | Pending, Sent, Relayed, Delivered |
| Security | Pass | Encrypted and signed, with no readable text on the relay |
| Recovery | Pass | Survived a restart and delivered exactly once |
| Battery efficiency | Partial | Scan modes work, but drain isn't measured |

## 4. More than message hopping

**For responders:** three offline logins, area alerts, an admin location view and shortest route first.

**For people who can't reach their phone:** Call-Out, Talk-Back, Last Breath and No-app SOS, which is simulated so far.

Also built: a laptop relay node and an optional AI dashboard for triage and translation.

## 5. Results from our tests

- 2.6 s: relay through one phone, fully offline
- 3.0 s: area alert reached the phone inside the area
- 2.1 s: locked phone inside an area was alerted
- 249: automated Android tests pass on the final code

The screenshots use test locations.

## 6. What we haven't proved yet

- **Scale:** 3 real and 5 simulated phones, not 1,000
- **Location:** test locations indoors, not real GPS boundaries
- **Battery and range:** not measured
- **Locked relay:** failed on OnePlus, passed on two others
- **AI dashboard:** not run with a live model
- **Security:** no independent review yet

EmergencyMesh is a prototype and doesn't replace official emergency services.

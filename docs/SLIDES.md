# Slide outline

Five short slides. Replace anything that isn't proven on the final APK.

## 1. Problem

- Disasters can take out cellular networks, power and internet.
- People still need to reach family and responders.
- Goal: text that works with no network, through nearby phones.

## 2. How the phones communicate

- Draw A, B and C in a line, with A-C blocked.
- A's message hops through B to reach C.
- With no path, it stays Pending until a phone comes in range.

## 3. Implementation

| Layer | Technology |
| --- | --- |
| App | Kotlin, Jetpack Compose |
| Storage and queue | Room |
| Nearby connections | Google Nearby Connections, `P2P_CLUSTER` |
| Security | Google Tink, Android Keystore |

## 4. Evidence

Use results from [the test matrix](TEST_MATRIX.md).

- 3 real phones, 3 brands, offline
- Add A-to-C relay once it passes
- Add the 20-message SOS priority result once it passes
- Add the measured battery comparison once it's finished
- Add the relay-can't-read result once it's proven on phones

## 5. Limitations and next steps

- Delivery needs nearby phones running the app.
- The app must stay open in this prototype.
- SOS is limited to phones with the group code.
- Android only.
- Next: background delivery, range testing and the optional AI assistant.

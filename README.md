# EmergencyMesh

Offline emergency text messaging for Android. Nearby phones connect directly and carry messages for each other when cellular networks and internet are unavailable.

> **Project status:** working prototype in progress. Real-phone results are in the [test matrix](docs/TEST_MATRIX.md). Features aren't claimed until they pass there.

---

## Team Information

| Attribute | Details |
| :--- | :--- |
| **Team Name** | **Slackers** |
| **Team Number** | `TEAM-30` (`HFT26-TEAM-30`) |
| **Registration ID** | `HFEST26-DU3LP6NK` |
| **Problem Track** | **Offline Emergency Messaging System** |
| **Domain** | `General Track` |
| **Team Leader** | Shrenik Singirikonda (shreniksin006@gmail.com) |
| **Institution** | CBIT Hyderabad |

### Team Members

| # | Name | Email | Institution |
| :--- | :--- | :--- | :--- |
| 1 | Saanvi Ponagoti | saanviraop@gmail.com | CBIT Hyderabad |
| 2 | Vindya Yelisetty | vindya975@gmail.com | CBIT Hyderabad |
| 3 | Sai Vishlesh Samala | saivishlesh2305@gmail.com | CBIT Hyderabad |
| 4 | Vangmay Puppireddy | vangmaipuppireddy8514@gmail.com | CBIT Hyderabad |

---

## Problem

During disasters, power cuts or network failures, conventional communication can become unavailable. People still need to reach family, friends and responders. The goal is a network-independent messaging system that works through nearby phones, prioritizes emergencies, shows honest delivery status, limits battery use and protects messages.

## Approach

1. Phones find each other and connect directly with Google Nearby Connections.
2. A message is saved on the sender's phone before any send attempt.
3. Phones carry messages toward the recipient through other phones.
4. SOS and emergency messages go ahead of regular messages.
5. Status comes from real acknowledgements, never from a guess.
6. If there's no path, the message stays Pending until a phone comes in range.

## Architecture

```mermaid
flowchart TD
    UI["Compose screens"] --> Repo["MessageRepository"]
    Repo --> Room["Room: messages, queue, seen packets"]
    Repo --> Engine["Message engine: queue, relay, acknowledgements"]
    Engine --> Security["MessageSecurity: Tink, Android Keystore"]
    Engine --> Transport["MeshTransport: Nearby Connections"]
    Transport <--> Peers["Nearby phones"]
```

| Layer | Package | Responsibility |
| --- | --- | --- |
| Shared contracts | `core` | Packet format, interfaces and contact exchange |
| Radio | `transport` | Discovery, pairing, reconnect and immediate-neighbor packets |
| Storage and engine | `data`, `engine` | Persistent queue, relay, duplicates and acknowledgements |
| Security | `security` | Encryption, signatures and key storage |
| App | `ui` | Screens, permissions and status |

See [the contracts](docs/CONTRACTS.md) for packet rules and status meanings.

## Tech Stack

- Kotlin and Jetpack Compose
- Room
- Google Nearby Connections, `P2P_CLUSTER`
- Google Tink and the Android Keystore
- JUnit and AndroidX Test

## Setup

**Requirements:** Android Studio, and physical Android 8.0+ phones with Google Play services. Emulators can't test phone-to-phone radio.

1. Clone the repository.

```bash
git clone https://github.com/shrenik111/HFT26-TEAM-30.git
```

2. Open the `src/android` folder in Android Studio, and wait for Gradle sync.
3. Turn on USB debugging on each phone, then install the app.

```bash
cd HFT26-TEAM-30/src/android
```

```bash
./gradlew installDebug
```

4. Allow the requested nearby permissions.

## Tests

Run these from `src/android`.

```bash
./gradlew testDebugUnitTest
```

Device tests run on a connected phone. This command uninstalls the app afterwards, which removes its local data.

```bash
./gradlew connectedDebugAndroidTest
```

Android keeps tests with the app code:

| Tests | Location |
| --- | --- |
| Unit tests | `src/android/app/src/test` |
| Device tests | `src/android/app/src/androidTest` |

Manual radio steps are in [the transport test guide](docs/TRANSPORT_TEST.md).

## Demo Topology

The demo forces A to reach C only through B, using a debug allowlist:

```text
Phone A <----> Phone B <----> Phone C
   |                             |
   +--------- blocked -----------+
```

## Limitations

- Delivery needs a chain of nearby phones running the app, immediately or later on.
- Without a future path, a message stays Pending and can't be delivered.
- The app must stay open, because background delivery isn't built.
- Range hasn't been measured, and there are no unlimited-range claims.
- This doesn't replace official emergency services.
- This build is Android-only and needs Google Play services.
- SOS is readable by phones holding the group code, so it isn't open to every nearby person.
- This is a hackathon prototype, not a production disaster network, and it has had no independent security review.

## Demo Video

To be added before submission.

## Screenshots

To be added before submission. Real-phone evidence is in [docs/evidence](docs/evidence).

## Attribution

Open-source and third-party components used:

| Component | Use | License |
| --- | --- | --- |
| [Google Tink](https://github.com/tink-crypto/tink-java) | Encryption and signatures | Apache 2.0 |
| [AndroidX, Jetpack Compose and Room](https://developer.android.com/jetpack) | App framework and storage | Apache 2.0 |
| [Kotlin and kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Language and concurrency | Apache 2.0 |
| [JUnit 4](https://junit.org/junit4/) | Tests | EPL 1.0 |
| [Google Play services Nearby](https://developers.google.com/nearby/connections/overview) | Phone-to-phone connections | Google Play services terms |

## License

This project is available under the [MIT License](LICENSE).

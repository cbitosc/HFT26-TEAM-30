# Contact exchange evidence

**Result: passed.** Two real phones swapped signed contact cards automatically in the real EmergencyMesh app, with no internet and no manual steps.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 03:55–03:58 IST |
| Phones | vivo V2404, Android 16, and Samsung SM-S721B, Android 16 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off |
| Build | Private trial of `member2/app` at `1481b02` plus `member1/transport` at `18515a3`, with `ContactCardService` wired in |
| Security | Tink, with keys in the Android Keystore |

## Results

| Check | Result |
| --- | --- |
| No internet | Both phones printed `Network is unreachable` before and after |
| Connection | Connected 18 seconds after both phones started discovery |
| Pairing | No prompt, because these phones were approved earlier |
| Card received on the vivo | Samsung appeared in Chats as `Fingerprint not verified` |
| Card received on the Samsung | vivo appeared in Chats as `Fingerprint not verified` |
| Crashes | 0 crash log lines for the app on either phone |

## Fingerprints match across phones

| Phone | Fingerprint it shows for itself | Fingerprint the other phone shows for it |
| --- | --- | --- |
| vivo | `F3F8 C9B6 339F D26B 3573 E29A 3392 34AD` | `F3F8 C9B6 339F D26B 3573 E29A 3392 34AD` |
| Samsung | `9DC8 A27E 2AFD 7DFA 0E3A 47C3 FA55 8A9C` | `9DC8 A27E 2AFD 7DFA 0E3A 47C3 FA55 8A9C` |

## Not covered

- Marking a contact verified, which needs the repository attached
- Encrypted messages between phones
- The OnePlus, which was connected to the Samsung but wasn't running this build
- Changed keys or forged cards on real phones, which are covered by unit tests only
- `feature/prototype`, which currently uses development plaintext until relay passes

# Security review

**Result: the code and unit tests support the security design, and a relay phone stored no readable message text on the final build.** This has had no independent security review. M4 reviewed the code and evidence on 31 October 2026, and this page ties each claim to the build that tested it.

| Claim | Evidence | Limit |
| --- | --- | --- |
| Only the recipient can read a direct message | Unit tests, including a relay that can't decrypt. A vivo and a Samsung also exchanged messages both ways in the app | That two-phone run was a private trial build |
| A relay phone stores no readable message text | Final build, 3 messages relayed through the Samsung. See below | Doesn't prove the encryption itself |
| Changed packets and signatures are rejected | Unit tests | Not tested on phones |
| Keys are protected by the Android Keystore | Device test on the Samsung, including keys surviving a restart. The app refuses to run if it can't use the Keystore | Earlier build. Not tested directly on the vivo or OnePlus |
| Phones with the same group code can read an SOS | Real phones, on an earlier build and on `fac5f28` | A wrong group code is covered by a unit test only |
| Forged area alerts are refused | Unit tests | Not tested on phones |

## Relay check on the final build

The final build is `8a0fe65`. During the phone test on 31 October at 13:32–13:38 IST, I searched saved copies of each phone's database for 3 messages relayed through the Samsung, B. A and C are the control: they sent or received these messages, so the search should find them there.

| Message | Path | Copies on A | Copies on B | Copies on C |
| --- | --- | --- | --- | --- |
| `routeline1332` | A to C through B | Found | **Not found** | Found |
| `routeback1333` | C to A through B | Found | **Not found** | Found |
| `routemoved1337` | A to C through B | Found | **Not found** | Found |

All 5 copies of B's database gave the same result. The search ran after SQLite had merged each copy's write-ahead log, so older log entries weren't searched.

Separate searches of B's database and write-ahead log also found no test text on earlier builds: see [relay A to C](relay-a-to-c.md), [required features](required-features.md), [final build](final-build.md) and [extras](extras-phone-test.md).

## Limits

- There has been no independent security review, and there is no forward secrecy.
- Radio traffic wasn't captured, so these checks cover what phones store, not what they transmit.
- Anyone holding the group code can read an SOS.
- Hop counts aren't signed, because relays change them.

# Requirement test matrix

Status as of 31 October 2026, 03:10 IST. Update this from real results only.

**Statuses:** a requirement is **Pass** only when it works in the real app on real phones. **Partial** means part of it is proven, and the Notes column says which part.

| Requirement | Status | Proven so far | Still needed |
| --- | --- | --- | --- |
| 1. Offline communication | Partial | 3 phones exchanged packets with no internet, using the debug Mesh Test screen | Prove it in the real app |
| 2. Peer-to-peer messaging | Partial | Discovery, code approval and two-way packets on vivo, Samsung and OnePlus | Prove it in the real app |
| 3. Mesh relaying | Not built | The forced A-B-C radio layout works, with A-C blocked | Engine forwarding, then a real A-to-C test through B |
| 4. SOS broadcasting | Not built | SOS screen exists, and the location flow is written | Engine sending, encrypted SOS and a denied-location test |
| 5. Message prioritization | Not built | Priority fields and queue storage exist | Priority dispatch, then the 20-message test |
| 6. Delivery status | Not built | Status labels and screen exist | Real acknowledgements driving each status |
| Security | Partial | Tink passed 88 unit tests and 10 device tests on `member4/security` | Connect to the engine, then prove a relay can't read on real phones |
| 7. Battery efficiency | Partial | Burst scanning ran on real phones | Finish the balanced vs continuous comparison |
| 8. Recovery and synchronization | Partial | Radio reconnect works, and Pending storage survives a database reopen on a Samsung | Automatic queued delivery after reconnect |

## Expected output

| Item | Status | Notes |
| --- | --- | --- |
| Offline peer-to-peer application | Partial | App screens exist, but the app can't send yet |
| Multi-hop mesh relaying | Not built | Depends on the engine |
| Encrypted SOS broadcasting | Partial | Tink works in tests, but SOS sending isn't built |
| Real-time delivery status | Not built | Depends on acknowledgements |
| Battery-optimized discovery | Partial | Built, not measured |
| Optional online AI dashboard | Not built | Cut from the current plan. The statement marks this as optional |

## Evidence

| Evidence | Result |
| --- | --- |
| [Two-phone offline exchange](evidence/g1-two-phone-offline.md) | Passed |
| [Forced three-phone topology](evidence/three-phone-topology.md) | Passed, one hop only |
| [Security tests](evidence/tink-security.md), on `member4/security` | Passed, not integrated |
| Room restart test | Passed on a Samsung |

## Phones tested

| Phone | Android | Used for |
| --- | --- | --- |
| vivo V2404 | 16 | Radio tests |
| Samsung SM-S721B | 16 | Radio, Room and Keystore tests |
| OnePlus CPH2569 | 15 | Radio tests |

## Tests to run next

| Test | Depends on | Pass when |
| --- | --- | --- |
| Install and permissions in the real app | Nothing | App opens on 3 phones, and denied permissions show a clear message |
| Discovery and pairing in the real app | Nothing | Phones pair after comparing the code |
| Contact enrollment | M2 and M3 | Both phones show matching fingerprints, then mark each other verified |
| Direct message offline | M2 and M3 | Text arrives once, and status reaches Delivered |
| A-to-C relay through B | M2 and M3 | B's log shows forwarding, and A-C stays disconnected |
| Pending to Delivered | M3 | Isolated message stays Pending, survives restart, then delivers once |
| SOS priority | M3 | SOS arrives before 20 queued regular messages |
| SOS with location denied | M2 and M3 | SOS sends, with no invented coordinates |
| Relay can't read | M4 integration | B holds ciphertext only, and C reads the message |
| Tampered packet | M4 integration | Packet is rejected and logged |
| Battery comparison | Nothing | Same phones, unplugged, 30 minutes per mode, with real numbers recorded |

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

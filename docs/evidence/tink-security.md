# Security evidence

**Result: the Tink provider passed its unit and device tests. It isn't integrated yet.** The message engine doesn't call it so far, so no secure message has crossed real phones.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 03:03–03:05 IST |
| Branch | `member4/security` |
| Unit tests | 88 of 88 passed on the Mac |
| Device tests | 10 of 10 passed on a Samsung SM-S721B, Android 16 |
| Crypto library | Google Tink 1.15.0 |

The 10 device tests include Member 3's Room restart test.

## Design

| Purpose | Method |
| --- | --- |
| Direct messages | Tink hybrid encryption, X25519 with AES-256-GCM, to the recipient's public key |
| Packet signatures | Ed25519 over all immutable fields and the ciphertext |
| SOS | AES-256-GCM under a key derived with PBKDF2 from a group code |
| Private key storage | Tink keysets encrypted by a master key in the Android Keystore |
| Contact trust | People compare a fingerprint covering the device ID and both public keys |

## What the tests proved

| Claim | Evidence |
| --- | --- |
| The recipient can read a direct message | Unit test |
| A relay can't decrypt it | Unit test, device test with two identities |
| Changed ciphertext is rejected | Unit test |
| A changed header, including raised priority, is rejected | Unit test |
| A relay can still verify and forward after changing TTL and hop count | Unit test, using the real packet codec |
| An unverified recipient is refused | Unit test |
| A different group code can't read SOS | Unit test |
| A contact card carries only public keys | Unit test |
| A forged contact card is rejected | Unit test |
| Changed keys for a known device are flagged | Unit test |
| Identity and keys survive a restart | Device test |
| Group key survives a restart | Device test |
| The master key is in the Android Keystore | Device test |
| Stored keysets are in encrypted form, not cleartext | Device test |

Opening the keys took about 100 ms on the Samsung.

## Limits

- **SOS scope:** anyone holding the group code can read SOS. Phones without it can forward SOS but can't read it, so it isn't open to every nearby person.
- **Group code strength:** a weak code can be guessed by someone who captures a packet. Use a long, unrelated code.
- **Contact trust:** it depends on people comparing fingerprints. A signed card only proves the sender holds that key.
- **Relay metadata:** relays can see sender, recipient, priority, time and hop values, because they need them to route.
- **Forward secrecy:** there is none. A stolen private key could decrypt saved direct messages sent to that phone.
- **Replay:** a signature doesn't stop replay by itself. The engine must reject expired and already-seen packets.
- **Hop values:** TTL and hop count aren't signed, because relays change them.

## Not covered

- Encrypted messages between real phones
- Integration with the engine and app
- Keystore behavior on the vivo or OnePlus
- Any independent security review

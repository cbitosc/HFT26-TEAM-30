# EmergencyMesh mentoring guide

Read this before the mentoring evaluation. Tested results are as of 5:07 AM on Saturday 31 October. Update anything that changes, and never claim something that hasn't been tested.

## The 30-second pitch

When towers and internet fail, people still need to reach each other. EmergencyMesh is an Android app that sends encrypted text through nearby phones, with no internet or cellular network. If two people can't reach each other directly, phones in between carry the message. SOS alerts go first, and the sender sees honest delivery status based on real acknowledgements.

**The proof:** three real phones, three brands, offline. A message crossed from A to C through B in about 2.5 seconds, and B couldn't read it.

## How it works

```text
Your phone
  Screens (Compose): chats, nearby devices, SOS, debug
  Repository and engine: queue, relay, duplicates, acknowledgements
  Security (Tink): encrypt, sign, verify
  Storage (Room): messages, queue, seen packets, contacts
  Transport (Nearby Connections): connect to neighbors, move packets
        |
   Bluetooth or Wi-Fi, no internet
        |
Nearby phones running the same layers
```

| Layer | Package | Owner | Job |
| --- | --- | --- | --- |
| Shared rules | `core` | Member 1 | Packet format, interfaces, contact exchange |
| Radio | `transport` | Member 1 | Find phones, pair, reconnect, move packets one hop |
| Engine and storage | `engine`, `data` | Member 3 | Queue, relay, duplicates, status, retries |
| Security | `security` | Member 4 | Encryption, signatures, keys |
| App | `ui` | Member 2 | Screens, permissions, wiring |

**The key design rule:** the radio moves packets one hop. The engine decides what to forward. Security protects the content. No layer does another layer's job.

## One message, step by step

A sends "hello" to C, with B in between, and A and C out of range.

1. **Saved first.** A stores the message as **Pending** before any send attempt, so it survives a crash or restart.
2. **Encrypted.** A encrypts the text with C's public key. Only C's private key can open it.
3. **Signed.** A signs the packet with its private key, so nobody can change or fake it.
4. **Handed to B.** A offers it to any connected phone that doesn't already have it. B isn't the recipient, but it can carry it.
5. **B checks it.** B verifies the signature, records the packet ID, and acknowledges it. A now shows **Sent**.
6. **B forwards it.** B can't decrypt the message. It lowers the TTL by 1, raises the hop count by 1, and passes it on, without sending it back to A.
7. **C receives it.** C verifies, decrypts and shows the message, with 1 hop.
8. **C confirms it.** B tells A that C has the message, which is **Relayed**. C also sends a signed delivery confirmation back through B.
9. **A shows Delivered** only after that confirmation from the real recipient arrives.

If C is unreachable, B keeps the packet and tries again when C appears. That's called store and forward.

## Each requirement

| # | Requirement | How we built it | Proof on real phones |
| --- | --- | --- | --- |
| 1 | Offline | Google Nearby Connections, with no server or internet path | All phones printed `Network is unreachable` before and after |
| 2 | Peer-to-peer | `P2P_CLUSTER` connects many phones to many phones | vivo, Samsung and OnePlus connected in about 29 seconds |
| 3 | Mesh relay | Controlled flooding with TTL, duplicate removal and no echo | A to C and C to A through B, 1 hop, about 2.5 seconds |
| 4 | SOS | Broadcast to all enrolled phones, with optional location | Reached both phones, 0 and 1 hops, and worked with location denied |
| 5 | Priority | SOS, Emergency, Regular. The queue re-checks after every send | SOS arrived before 20 queued messages |
| 6 | Delivery status | Pending, Sent, Relayed, Delivered from real acknowledgements | Pending, Sent and Delivered on screen. Check the Relayed status before saying it's shown |
| Security | Encryption and authentication | Tink encryption, Ed25519 signatures, fingerprint verification | Relay database held no message text, and a forged packet was rejected |
| 7 | Battery | Scans 15 seconds in each minute, and pauses scanning at 3 links | Scanning cycle seen. Use the measured result once it exists |
| 8 | Recovery | Queue stored in Room, and retries when a peer reconnects | Pending survived a restart, then delivered exactly once |

## Security in plain language

| Term | Plain meaning |
| --- | --- |
| Public key | Shared freely. Used to encrypt to you, or check your signature |
| Private key | Stays on your phone. Used to decrypt and sign |
| Signature | Proof that a packet came from you and wasn't changed |
| Fingerprint | Short code summarizing someone's device ID and public keys. People compare it to confirm identity |
| Pairing code | 4-digit code that approves a radio connection |
| Group code | Shared code that protects SOS alerts |

**Pairing and verifying are different.** The pairing code approves a radio link. The fingerprint confirms a contact's identity. Phones swap public contact cards automatically when they connect, but a contact stays unverified until people compare fingerprints.

| Protects | Method |
| --- | --- |
| Direct messages | Tink hybrid encryption, X25519 with AES-256-GCM |
| Packets | Ed25519 signatures over all fixed fields and the ciphertext |
| SOS alerts | AES-256-GCM, with a key derived from the group code using PBKDF2 |
| Private keys | Encrypted by a master key in the Android Keystore |

**What a relay can see:** sender ID, recipient ID, priority, time and hop values, because it needs them to route. It can't read the message.

## Numbers to know

| Item | Value |
| --- | --- |
| Phones tested | vivo V2404 (Android 16), Samsung SM-S721B (Android 16), OnePlus CPH2569 (Android 15) |
| Connection time | About 13–29 seconds on Bluetooth only |
| A to C delivery | About 2.5 seconds through 1 relay |
| Delivery after a phone returns | Up to about 40 seconds |
| Ping between neighbors | Median 86–155 ms |
| Unit tests | 119 passed |
| Device tests | 10 passed on the Samsung |
| TTL | SOS 10 hops, Emergency 8, Regular 5 |
| Kept for | SOS 24 hours, Emergency 12, Regular 6 |
| Message limit | 2,000 characters |
| Retries | Starts at 1 second, doubles, and caps at 60 seconds |
| Discovery | 15 seconds on, 45 seconds off |
| Minimum Android | 8.0, with Google Play services |

## Live demo

### Setup checklist, 15 minutes before

- [ ] All 3 phones have the same APK and commit
- [ ] Phones are charged, unlocked, and set to a long screen timeout
- [ ] Airplane mode on, Bluetooth on, Wi-Fi off
- [ ] Contacts verified on all 3 phones
- [ ] Same group code on all 3 phones
- [ ] Debug topology set: A allows B, C allows B, B allows A and C
- [ ] Debug screen shows A with 1 connection, B with 2, C with 1
- [ ] Backup video ready offline

**Don't start from scratch in front of a mentor.** Pairing, enrollment and topology take several minutes, so do them beforehand.

### Demo script

| Step | Do | Say |
| --- | --- | --- |
| 1 | Show airplane mode on all 3 phones | "No internet, no SIM network." |
| 2 | Show connection counts | "A and C can't reach each other, so B has to carry messages." |
| 3 | Send from A to C | "This is encrypted so only C can read it." |
| 4 | Show C receive it with 1 hop | "It came through B." |
| 5 | Show Delivered on A | "That appears only after C confirms it." |
| 6 | Send SOS from A | "SOS reaches everyone enrolled, and goes first." |
| 7 | Stop C's radio, send, restart C's radio | "With no path it stays Pending, then delivers on its own." |
| 8 | Show the test matrix | "Here's what we proved, and what we didn't." |

### If something goes wrong

| Problem | Fix |
| --- | --- |
| Phones show 0 connected | Open **Nearby**, tap **Start discovery**, and wait 30 seconds |
| A phone locked | Unlock it, reopen the app, start discovery if needed, and wait 30 seconds |
| Message stays Pending | Check connections, then wait. It retries by itself |
| Contact missing | Connect the phones directly with the topology cleared, then verify the contact |
| SOS button disabled | Set the group code |
| Direct send disabled | The contact isn't verified yet |
| Radio won't connect | Toggle Bluetooth, reopen the app, and allow 30 seconds |
| Still broken | Show the backup video and evidence. Stay calm and explain what should happen |

## Mentor questions

### Product

**What problem does this solve?** Text communication when cellular and internet are down, such as after a disaster, or during a power or network failure.

**How is it different from Bridgefy or Bitchat?** Those are real offline messaging apps, so don't claim we're better. Our focus is the problem statement: SOS priority, honest acknowledgement-based status, encrypted relay and tested recovery.

**Does it ever need internet?** Not for messaging. The app must be installed beforehand, and phones need Google Play services.

**Does it work on iPhone?** No. This build is Android only, and the statement doesn't require iPhone.

**What happens if nobody is nearby?** The message stays Pending and can't be delivered until a phone comes in range. We don't promise delivery without a path.

**Does this replace emergency services?** No. It helps people communicate, and doesn't contact official responders.

**How far does it reach?** Use the measured range if the team has it. Otherwise say it isn't measured yet, because our tests had phones side by side and blocked the direct link in software.

**How many phones have you tested?** Three, with one relay hop. The TTL allows more hops, but we haven't tested more.

**Where is the AI?** The AI assistant is optional in the statement. We prioritized the required offline system first.

### Networking

**Why Nearby Connections?** It finds phones and connects them over Bluetooth and Wi-Fi with no internet, so we could focus on the mesh.

**What does `P2P_CLUSTER` mean?** It allows many-to-many connections, which a mesh needs.

**Why flooding, not routing tables?** In a disaster, phones move and no phone knows the whole network. Passing messages to neighbors is simple and resilient. The cost is extra traffic, which TTL and duplicate removal limit.

**How do you stop infinite loops?** Three ways: TTL decreases at each hop, every phone remembers packet IDs it has seen, and a packet is never sent back to the phone it came from.

**How do you stop duplicates?** Each packet has a unique ID stored in `seen_packets`. A repeat isn't shown again. In tests, 24 messages produced 24 unique rows.

**What if two phones connect at the same moment?** We saw this cause error 8012 on real phones. The lower device ID now requests first, and the other waits 30 seconds as a fallback.

**How do you test relay with phones side by side?** A debug allowlist blocks A and C from connecting, so B is the only path.

**What happens if the relay phone disappears?** The sender keeps the message and retries when any useful phone connects.

### Engine and data

**How does SOS jump the queue?** Every packet has a priority, and the queue re-checks after each send, so a new SOS goes next. Relays can't raise priority, because it's signed.

**What do the statuses mean?**

| Status | Meaning |
| --- | --- |
| Pending | Stored here, not yet accepted by another phone |
| Sent | The next phone acknowledged it |
| Relayed | A middle phone confirmed passing it on |
| Delivered | The real recipient confirmed it |

**What if an acknowledgement is lost?** The sender retries, and the receiver recognizes the duplicate and acknowledges again without showing it twice.

**Does the status ever go backward?** No. It only moves forward.

**What if the app crashes?** Messages and the queue are stored in Room before sending, so they survive. We tested an app restart with a Pending message.

**What about phones with wrong clocks?** Expiry uses each phone's own first-seen time. The sender's time is only for display.

### Security

**Is it end-to-end encrypted?** Direct messages are. Only the recipient's private key can decrypt them. We checked the relay's database, and the message text wasn't in it.

**Can a relay change a message?** Any change to the fixed fields or ciphertext breaks the signature, so it's rejected. Only TTL and hop count can change, and the engine checks that they add up.

**Can someone pretend to be another person?** They'd need that person's private key. We tested a packet from a trusted phone without a valid signature, and it was rejected.

**How do you trust a new contact?** Phones swap public cards automatically, then two people compare fingerprints. Until then, direct messages are blocked.

**What if a known contact's keys change?** The app keeps the stored keys and doesn't replace them automatically.

**Where are private keys stored?** On the phone only, encrypted by a master key in the Android Keystore. They're never sent.

**Why does SOS use a group code?** SOS must be readable by many people. Anyone holding the code can read it, and outsiders can't.

**Can a stranger's phone see an SOS?** No, only enrolled phones can read it. That's a deliberate limit of this prototype. A public emergency version would need a different design.

**What if someone sends fake SOS spam?** Only verified contacts are accepted, and that limits outsiders. There's no rate limit for an enrolled member yet.

**Is there forward secrecy?** No. A stolen private key could decrypt saved messages sent to that phone. That's a known limit.

**Can you stop replay?** Seen packet IDs and expiry block repeats while those records are kept.

**Has this had a security review?** No independent review. Don't call it production-secure.

### Battery and background

**How do you save battery?** Discovery runs in 15-second bursts each minute, and pauses when a phone has 3 connections. Retries back off up to 60 seconds, and acknowledgement retries are capped.

**How much battery does it save?** Use the measured comparison. If it isn't finished, say so.

**Does it work in the background?** Check the current build first. If keep-alive isn't merged, say: "The app must stay open in this prototype, and background operation is our next step."

### Process

**How did you test it?** 119 unit tests, 10 device tests, and real offline tests on three phones. Evidence is in `docs/evidence`.

**Did you use AI tools?** Answer honestly, and follow the event rules.

**What was the hardest part?** Three good answers: connection collisions on real phones, a queue query that blocked relay until we redesigned it, and reporting honest status.

**What would you do next?** Background operation, range testing, more phones, SOS rate limits, and the optional AI assistant.

## Who answers what

| Topic | First | Backup |
| --- | --- | --- |
| Pitch and requirements | Member 5 | Member 2 |
| Live demo | Member 2 | Member 5 |
| Engine, queue and relay | Member 3 | Member 2 |
| Security | Member 4 | Member 3 |
| Radio and pairing | Shrenik by call | Member 3 |
| Evidence and limits | Member 5 | Anyone |

If you don't know an answer, say: "I'm not certain. Here's what we tested, and here's what we'd check." That's far better than guessing.

## Never claim these

- "It works everywhere" or "unlimited range"
- "It's fully secure" or "unhackable"
- "It works with no other phones around"
- "It's better than Bridgefy or Bitchat"
- Any battery or range number we haven't measured
- "Every nearby person can read SOS"
- Any feature that hasn't passed on the final APK

## Where to find things

| Need | Location |
| --- | --- |
| Results by requirement | `docs/TEST_MATRIX.md` |
| Latest phone evidence | `docs/evidence/required-features.md` |
| Relay proof | `docs/evidence/relay-a-to-c.md` |
| Security detail | `docs/evidence/tink-security.md` |
| Packet and status rules | `docs/CONTRACTS.md` |
| Radio code | `src/android/app/src/main/java/com/slackers/emergencymesh/transport` |
| Engine code | `src/android/app/src/main/java/com/slackers/emergencymesh/engine` |
| Security code | `src/android/app/src/main/java/com/slackers/emergencymesh/security` |

# EmergencyMesh contracts

The Kotlin files are the source of truth:

- `src/android/app/src/main/java/com/slackers/emergencymesh/core/Packet.kt`
- `src/android/app/src/main/java/com/slackers/emergencymesh/core/Contracts.kt`

Ask Member 1 before changing anything in `core`, Gradle or the manifest.

## Ownership

| Package | Owner |
| --- | --- |
| `core`, `transport`, Gradle, manifest | M1 |
| `ui`, `MainActivity`, app wiring, merges | M2 |
| `data`, `engine` | M3 |
| `security` | M4 |
| `docs`, README, test evidence | M5 |

## Packets

- `id` is the packet UUID. For `MESSAGE` and `SOS` it is also the message UUID.
- ACKs have a new `id`, and `refMessageId` holds the original message ID.
- `initialTtl` and `retentionSeconds` are signed.
- Only `ttl` and `hopCount` change in transit.
- Encode only with `PacketCodec`.

Build a packet in this order:

1. Create it with an empty `encryptedBody` and `signature`.
2. Encrypt the `MessageBody` using `PacketCodec.associatedData(packet)`.
3. Copy the ciphertext into the packet.
4. Sign `PacketCodec.signedBytes(packet)` and copy in the signature.

## Hops and retention

| Priority | TTL | Retention |
| --- | --- | --- |
| `P0_SOS` | 10 | 24 hours |
| `P1_EMERGENCY` | 8 | 12 hours |
| `P2_REGULAR` | 5 | 6 hours |

- A packet can be delivered locally at `ttl` 0, but is not forwarded.
- Forwarding decrements `ttl` and increments `hopCount`.
- Retention runs from the receiving phone's first-seen time.
- `createdAtMillis` is for display only.

## Status

| Status | Meaning | Trigger |
| --- | --- | --- |
| Pending | Stored locally, waiting for a useful encounter or key | Created |
| Sent | The immediate peer's app acknowledged receipt | `ACK_SENT` |
| Relayed | An intermediate phone confirmed onward sending | `ACK_RELAYED` |
| Delivered | The real recipient acknowledged receipt | Verified `ACK_DELIVERED` |

- Status never moves backward.
- `SendResult.TransportComplete` is not Sent.
- Never ACK an ACK. A valid duplicate may trigger the ACK again.
- SOS never shows Delivered or a claimed reach count.

## Transport and engine

- M1 moves packets between immediate neighbors only.
- M3 decides what to forward, retry, deduplicate and ACK.
- Use `deviceId` for identity and `endpointId` only for a live connection.

Use the real transport like this, on the main thread:

```kotlin
val transport = NearbyMeshTransport.create(context)
val device = LocalDeviceStore.load(context)

// Collect first. Missed events are not replayed.
scope.launch { transport.events.collect(engine::onTransportEvent) }

val problems = RadioReadiness.check(context) // show these to the user
transport.start(device.deviceId, device.displayName)
```

- **M2:** request `RadioReadiness.requiredPermissions()` before `start`, and show each `RadioProblem`.
- **M2:** when a peer is `AWAITING_APPROVAL`, show `authToken` on both phones, then call `respondToConnection`.
- **M2:** `transport.log` holds recent radio activity for the Debug section.
- **M2:** this app targets Android 15, so screens draw behind the status bar. Apply `systemBarsPadding()` or a `Scaffold` to every screen.
- **M3:** handle `PacketReceived(fromEndpointId, packet)`, and never echo a packet back to `fromEndpointId`.
- **M3:** re-check retained packets for a peer on every `PeerConnected`.
- **M3:** `send` returning `TransportComplete` only means the bytes left this phone.
- **M3:** set `DiscoveryMode.CONTINUOUS` while an SOS is active, then return to `BALANCED`.

- **M3:** `InMemoryMesh` in `src/test` simulates linked phones, so relay logic can be unit-tested without radios. It isn't relay evidence for the demo.

Approved peers reconnect without another prompt. This approval only controls the radio link, so identity still depends on M4's signature checks.

## Contact exchange

Phones must swap contact cards before direct messaging, so enroll all phones before forcing the A-B-C topology.

**M3 engine:**

1. On `PeerConnected`, send `ContactExchange.buildPacket(security, uuid, now)` straight to that endpoint. Don't queue it.
2. On a received `CONTACT_CARD`, call `ContactExchange.readPacket(security, packet, peer.deviceId)`. A `SecurityException` means drop it and log why.
3. Call `ContactExchange.compare(storedCard, card)`:
   - `NEW`: save it with `verified = false`.
   - `UNCHANGED`: keep the stored card.
   - `KEYS_CHANGED`: keep the stored card and show a warning. Never overwrite it automatically.
4. Never forward, ACK or display a `CONTACT_CARD`. Its TTL is 0, and a forwarded card is rejected.

A received card is never verified. It only becomes verified when a person compares fingerprints in the app.

## Security

**M2:** attach security in `MainActivity`:

```kotlin
security = SecurityProvider.create(applicationContext),
```

- Debug and release builds both use `TinkMessageSecurity`. The plaintext stand-in is gone.
- **M2:** add a group code field for enrollment. Call `security.setGroupCode(code)`, and show when `security.hasGroupKey` is false. Every enrolled phone must enter the same code of at least 8 characters.
- **M2:** never commit a real group code, and don't hardcode one.
- **M3:** `encryptDirect` refuses a recipient unless `ContactCard.verified` is true. Keep the message Pending when the contact isn't verified yet.
- **M3:** `encryptBroadcast` throws `SecurityException` without a group code. Show that failure instead of dropping the SOS.
- Build the packet with an empty body, encrypt with `PacketCodec.associatedData(packet)`, then sign. The receiver verifies before decrypting.

- `MessageSecurity` is the only crypto interface.
- `isDevelopmentPlaintext` must be false in the final demo.
- Relays store and forward ciphertext without decrypting direct messages.
- Enrolled group members can read SOS, and outsiders can't.
- Contacts are enrolled before the A-B-C topology is forced.

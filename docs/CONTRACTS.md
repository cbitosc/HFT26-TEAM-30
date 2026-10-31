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

## Keep-alive

- `MeshRuntime.get(context)` holds one set of services for the whole process. Never create a second transport or repository for the same mesh.
- `MeshSession` starts and stops the engine and radio together. The app calls `session.start` and `session.stop`, not `transport.start` or `transport.stop`.
- `MeshForegroundService` follows `session.running` and ends itself. Never call `stopService`, because Android crashes an app that stops a service before it reaches the foreground.
- **M2:** only stop the mesh when the screen closes if `session.keptAlive` is false, because the radio must never run without its notification.
- **M3:** `recentIncoming` feeds the alerts on a locked phone. A direct message alert must never contain the message text.

See `docs/KEEP_ALIVE_TEST.md` for the phone test.

## Dashboard upload

- `DashboardUploader` copies SOS alerts to the dashboard. It's off until someone sets it up, and the mesh never waits for it.
- Direct messages are never uploaded, because they are end-to-end encrypted.
- Each alert is a separate request, so one the dashboard refuses can't hold back the rest. Repeats are safe because the dashboard ignores known IDs.

See `docs/DASHBOARD_UPLOAD_TEST.md` for the phone test.

## Rescue Call-Out

- `sendCallOut` sends a `CALL_OUT` packet to one phone, and relays forward it like any other addressed packet.
- A called phone only rings if its own SOS is active. It then sends `ACK_DELIVERED`, which means "ringing" for this packet type.
- A call has a 2-minute retention instead of the usual 24 hours for P0, and a phone ignores any call over 5 minutes old.
- Every phone in the mesh needs this build, because an older build can't decode the new packet type.

See `docs/CALL_OUT_TEST.md` for the phone test.

## Last Breath

- At 5% battery and not charging, a phone with an active SOS sends one final SOS through `sendSos`, so relays treat it like any other SOS.
- It sends once per SOS, and that survives a restart.
- A phone that never asked for help sends nothing.

See `docs/LAST_BREATH_TEST.md` for the phone test.

## No-app SOS

- A listening phone scans for Bluetooth names starting with `SOS`, and sends each new one through `sendSos`, so relays treat it like any other SOS.
- Anyone can set a Bluetooth name, so the alert text always starts with `No-app SOS, not verified`.
- Each listening phone sends at most 10 of these an hour, because they travel at SOS priority.
- Never call `cancelDiscovery` while listening, because that also ends the scan Nearby is running.

See `docs/NO_APP_SOS_TEST.md` for the phone test.

## Talk-Back

- `sendQuestion` sends a `TALK_BACK` packet. It's end-to-end encrypted like `MESSAGE`, and it appears as a chat message on both phones.
- The addressed phone reads it aloud only during its own SOS, and only if the question is under 5 minutes old.
- It only listens if its owner ticked the box on the SOS tab. The answer comes back through `sendDirect`.
- Every phone in the mesh needs this build, because an older build can't decode the new packet type.

See `docs/TALK_BACK_TEST.md` for the phone test.

## Contact exchange

`ContactCardService` swaps contact cards automatically when two phones connect, so the engine doesn't have to. Phones must connect directly once, so enroll all phones before forcing the A-B-C topology.

**M2:** start it in `MainActivity`, before the transport starts, and show `keyWarnings` to the user:

```kotlin
val contactCards = ContactCardService(transport, security, RoomContactBook(dao))
contactCards.start(lifecycleScope)
```

**M3:** the engine must ignore `CONTACT_CARD`. Don't forward, ACK, queue or display it.

- A received card is saved as unverified, and only a person comparing fingerprints can verify it.
- A verified contact stays verified when the same card arrives again.
- If a known device sends different keys, the stored keys stay and `keyWarnings` reports it.
- A forged card, a bad signature or a forwarded card is dropped.
- Cards have a TTL of 0, so they never travel beyond a direct connection.

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

# Transport phone test

Use this to prove the radio layer on real phones. The **Mesh Test** app is debug-only and sends unsigned plaintext, so it isn't evidence of security.

## Setup

1. Turn on USB debugging on each phone.
2. Install the same build on every phone:

```bash
cd src/android
./gradlew installDebug
```

3. Open **Mesh Test**, not EmergencyMesh.
4. Name the phone A, B or C.
5. Tap **Permissions** and allow everything.
6. Tap **Recheck** and record any problem shown.

For each test, turn on airplane mode, then turn Bluetooth and Wi-Fi back on. Don't join a Wi-Fi network. Record the phone models, Android versions and result.

## Test 1: two-phone exchange (G1)

1. Tap **Start** on A and B.
2. Wait for a peer to reach `AWAITING_APPROVAL`.
3. Check that both phones show the same code.
4. Tap **Approve** on both phones.
5. Wait for `CONNECTED`.
6. Tap **Ping** on A, and check for a round-trip time.
7. Tap **Ping** on B, and check for a round-trip time.
8. Tap **Send** on each phone, and check that the text arrives.

**Pass:** both directions work with no internet, and no duplicate connection loop appears in the radio log.

## Test 2: reconnect

1. Connect A and B.
2. Tap **Stop** on B, then **Start** again.
3. Check that they reconnect with no approval prompt.
4. Tap **Ping** again.

Repeat by walking out of range, then coming back. Record how long the reconnect takes.

## Test 3: forced A-B-C topology

1. Tap **Clear** on all 3 phones.
2. Connect and approve all 3 phones with each other.
3. On A, tick only B, then tap **Apply**.
4. On C, tick only B, then tap **Apply**.
5. On B, tick A and C, then tap **Apply**.

**Pass:** A shows no connection to C, C shows no connection to A, and B stays connected to both. The radio log should show `Allowlist blocked` or `Rejected blocked device`.

Tap **Clear** on every phone after the test, because the allowlist survives restarts.

## Test 4: discovery modes

1. Leave a phone in **Mode: balanced**, and check that the log shows `Discovery paused` after about 15 seconds.
2. Check that scanning restarts about 45 seconds later.
3. Switch to **Mode: continuous**, and check that scanning stays on.

These modes are ready for the battery comparison, but no battery result exists yet.

## If it fails

Save the radio log, or capture it with:

```bash
adb logcat -s EmergencyMesh
```

Send Member 1 the phone model, Android version, steps, and the log.

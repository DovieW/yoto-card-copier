# Yoto Card Copier

A tiny offline Android app for copying a linked Yoto MYO playlist to a generic **48-byte MIFARE Ultralight EV1** card. One source tap, one destination tap. Formatting, writing, and read-back verification happen in the same NFC connection.

This is an unofficial community tool, not affiliated with Yoto. Generic-card compatibility depends on the player and its firmware. It does not turn generic tags into official MYO cards or bypass Yoto's app linking process.

## Use

1. In the official Yoto app, link the desired playlist to your genuine MYO card.
2. Open this app and tap that genuine card. The app captures the exact NDEF record and protects that card from writes.
3. Remove it and tap an unused generic card. Keep the card steady until **Copied & verified** appears. No separate format or verification tap is needed.
4. Play the genuine card briefly on each target Yoto player to queue the audio. Leave the player online, charging, idle, and without a card to finish downloads. Generic cards may be rejected until content is cached.
5. Choose **Next playlist**, relink the genuine card in Yoto, and repeat. Restore the genuine card's usual playlist after finishing your batch.

For an already-written destination, the app asks before replacement and requires a second tap of that specific card. Every source UID read by this installation stays protected. The app cannot authenticate whether a card is genuine: choose your genuine MYO as the source yourself.

## Scope and privacy

- Android 11+ with NFC-A; no third-party runtime dependencies.
- No internet permission, account, analytics, API keys, or server.
- Playlist data stays in memory for the current session. Only source UIDs are saved privately to prevent accidental overwrites. Android backup is disabled.
- Supports the NXP Ultralight EV1 48-byte version (GET_VERSION storage byte `0B`), both capacitance variants. Other chips and larger tags are refused before writing.
- Copies one HTTPS `yoto.io` URI record, up to 46 NDEF bytes; preserves the original record bytes rather than reconstructing the URL.
- Writes only user pages `04`–`0F` and, for unformatted cards, capability-container page `03` (`E1 10 06 00`). Never changes UID, lock bytes, passwords, or configuration pages.
- Locked or unrecognized layouts are refused. Nonblank cards require explicit replacement approval. An interrupted write can be retried on the same destination while the session remains open; after a process restart, a partially written card may require replacement approval.
- Verification checks all 48 user bytes and the capability container. It does not prove Yoto playback, cloud acceptance, or continued compatibility after firmware changes.

Initial manual workflow was validated with a Galaxy S25 Ultra and Yoto Mini OS 2.23.3. See [VALIDATION.md](VALIDATION.md) for the app's own test status.

## Build

Use JDK 17, Android SDK platform 37 and build tools 36.0.0:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with Android Studio or `adb install -r` and launch `dev.dovie.yotocopy/.MainActivity`.

Run pure-Java memory-policy checks:

```sh
mkdir -p /tmp/yoto-copy-tests
javac -d /tmp/yoto-copy-tests app/src/main/java/dev/dovie/yotocopy/TagMemory.java tests/TagMemoryTest.java
java -cp /tmp/yoto-copy-tests dev.dovie.yotocopy.TagMemoryTest
```

## References

- [NXP Ultralight EV1 datasheet](https://www.nxp.com/docs/en/data-sheet/MF0ULX1.pdf): GET_VERSION, READ, WRITE, memory layout.
- [Android NFC reader mode](https://developer.android.com/reference/android/nfc/NfcAdapter#enableReaderMode(android.app.Activity,%20android.nfc.NfcAdapter.ReaderCallback,%20int,%20android.os.Bundle)).
- [Community card workflow](https://github.com/davidcjw/custom-yoto-cards). Historical guidance, not a guarantee for every player.

MIT licensed. Use audio you have permission to use.

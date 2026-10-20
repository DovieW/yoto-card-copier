# Validation

The underlying manual source-link / destination-copy workflow worked on a Samsung Galaxy S25 Ultra and a Yoto Mini running OS 2.23.3. That predates this app and is not an app hardware test.

## Version 0.1.0

- 20 pure-Java memory-policy checks passed (capacity, TLV bounds, blank detection, supported chip variants, capability-container checks, and locked-tag refusal).
- `:app:assembleDebug :app:lintDebug` passed using JDK 17 and the installed Android SDK. Lint has no errors; warnings cover target-version currency, Gradle-version currency, backup rules, the intentionally required NFC feature, and an English-only session-counter string.
- Debug APK installed over wireless ADB on Samsung SM-S938U1 and launched successfully. The source-capture screen was verified through the live UI.
- Physical source capture, blank writing, retry behavior, and same-connection read-back are not yet verified in this app. The manual NFC Tools workflow is separate evidence.

Debug builds use the standard local Android debug signing key. The downloadable APK is for sideloading and is not a Play Store release.

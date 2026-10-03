# Severed Chains Android

This is the native Android packaging layer for Severed Chains.

The APK is intentionally split into two runtimes:

- The Android launcher and lifecycle code run on ART.
- The game runs unchanged as Java 25 bytecode inside an embedded Android OpenJDK 25 HotSpot runtime.
- SDL3 owns the Android surface/input lifecycle.
- LWJGL uses Android ARM64 JNI libraries and the existing Severed Chains OpenGL ES 3.2 renderer.

## Current target

The first milestone targets **ARM64 Android**, **OpenGL ES 3.2**, and a Bluetooth/USB controller. Touch controls are a later milestone.

The APK contains no Legend of Dragoon disc data. Use the launcher screen's **Import LoD disc images** button to copy your own disc files into the app-private game folder.

## Local build outline

1. From the repository root, prepare the game payload with Java 25:
   `./gradlew clean prepareAndroidRuntime -PandroidRuntime=true -PlwjglVersion=3.4.1`
2. Copy `build/android-game/` to `android/app/src/main/assets/game/`.
3. Put FCL Android OpenJDK 25 `universal.tar.xz` and `bin-arm64.tar.xz` in `android/app/src/main/assets/runtime/`.
4. Run `android/scripts/fetch-mobile-deps.sh`.
5. Build the APK with an Android SDK/NDK installed:
   `gradle -p android :app:assembleDebug`

The development CI performs these steps automatically.

## Third-party Android runtime components

See `THIRD_PARTY.md`. The Android launcher does not include or redistribute Legend of Dragoon game data.

# Android third-party components

The Android port uses the following external components in addition to Severed Chains' normal dependencies.

- **SDL 3.2.30** — built from source at APK build time. SDL is distributed under the zlib license.
- **LWJGL 3.4.1 Android native binaries** — fetched from the pinned Amethyst Android repository build. LWJGL is distributed under the BSD-3-Clause license.
- **OpenAL Soft Android binary** — fetched from the pinned Amethyst Android repository build. OpenAL Soft is distributed under the LGPL.
- **Android OpenJDK 25** — runtime artifacts produced by `FCL-Team/Android-OpenJDK-Build`. OpenJDK is distributed under GPLv2 with the Classpath Exception.

Pinned Amethyst source commit:
`330c6eae3164df64bdc4828e946a9e62cc5169e4`

Android OpenJDK source/build project:
`FCL-Team/Android-OpenJDK-Build`, branch `Build_JRE_25`.

The APK does not contain Legend of Dragoon disc images or extracted retail assets.

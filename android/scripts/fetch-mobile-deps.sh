#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app"
VENDOR="$ROOT/vendor"

AMETHYST_COMMIT="330c6eae3164df64bdc4828e946a9e62cc5169e4"
SDL_TAG="release-3.2.30"

mkdir -p "$APP/libs" "$VENDOR"
rm -rf "$VENDOR/SDL" "$APP/src/sdl"
mkdir -p "$APP/src/sdl/java/org/libsdl"

echo "Fetching Android LWJGL/OpenAL native AARs..."
curl --fail --location --retry 3   "https://raw.githubusercontent.com/AngelAuraMC/Amethyst-Android/$AMETHYST_COMMIT/app_pojavlauncher/libs/lwjgl-3.4.1-natives-release.aar"   -o "$APP/libs/lwjgl-3.4.1-natives-release.aar"

curl --fail --location --retry 3   "https://raw.githubusercontent.com/AngelAuraMC/Amethyst-Android/$AMETHYST_COMMIT/app_pojavlauncher/libs/openal-soft-release.aar"   -o "$APP/libs/openal-soft-release.aar"

echo "Fetching SDL $SDL_TAG..."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl --fail --location --retry 3   "https://github.com/libsdl-org/SDL/archive/refs/tags/$SDL_TAG.tar.gz"   -o "$TMP/sdl.tar.gz"

mkdir -p "$VENDOR/SDL"
tar -xzf "$TMP/sdl.tar.gz" --strip-components=1 -C "$VENDOR/SDL"

SDL_JAVA="$VENDOR/SDL/android-project/app/src/main/java/org/libsdl/app"
if [[ ! -d "$SDL_JAVA" ]]; then
  echo "SDL Android Java glue not found at $SDL_JAVA" >&2
  exit 1
fi

cp -R "$SDL_JAVA" "$APP/src/sdl/java/org/libsdl/"

echo "Android native dependencies prepared."

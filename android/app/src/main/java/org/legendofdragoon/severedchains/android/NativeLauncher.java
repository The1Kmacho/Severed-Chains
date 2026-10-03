package org.legendofdragoon.severedchains.android;

final class NativeLauncher {
  private NativeLauncher() { }

  static native int launch(
    String runtimeHome,
    String gameDirectory,
    String cacheDirectory,
    String nativeLibraryDirectory,
    String classPath
  );
}

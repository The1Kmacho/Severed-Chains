package org.legendofdragoon.severedchains.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class AndroidFiles {
  private AndroidFiles() { }

  static File root(final Context context) {
    return new File(context.getFilesDir(), "severed-chains");
  }

  static File game(final Context context) {
    return new File(root(context), "game");
  }

  static File runtime(final Context context) {
    return new File(root(context), "runtime");
  }

  static void installBundledContent(final Context context, final String buildId) throws IOException {
    final File root = root(context);
    final File game = game(context);
    final File runtime = runtime(context);
    final File marker = new File(root, "installed-" + sanitize(buildId));

    if(marker.isFile() && new File(game, "lod-game-android.jar").isFile()
      && new File(runtime, "bin/java").isFile()) {
      ensureDirectories(game);
      return;
    }

    if(!root.isDirectory() && !root.mkdirs()) {
      throw new IOException("Unable to create " + root);
    }

    copyAssetTree(context.getAssets(), "game", game);

    if(!new File(runtime, "bin/java").isFile()) {
      if(runtime.exists()) {
        deleteTree(runtime);
      }
      if(!runtime.mkdirs()) {
        throw new IOException("Unable to create " + runtime);
      }

      extractTarXzAsset(context.getAssets(), "runtime/universal.tar.xz", runtime);
      extractTarXzAsset(context.getAssets(), "runtime/bin-arm64.tar.xz", runtime);
    }

    makeRuntimeExecutable(runtime);
    ensureDirectories(game);

    final File[] oldMarkers = root.listFiles((dir, name) -> name.startsWith("installed-"));
    if(oldMarkers != null) {
      for(final File old : oldMarkers) {
        //noinspection ResultOfMethodCallIgnored
        old.delete();
      }
    }

    if(!marker.createNewFile() && !marker.isFile()) {
      throw new IOException("Unable to create install marker");
    }
  }

  static String classPath(final File game) {
    final StringBuilder cp = new StringBuilder(new File(game, "lod-game-android.jar").getAbsolutePath());
    final File libs = new File(game, "libs");
    final File[] jars = libs.listFiles((dir, name) -> name.endsWith(".jar"));

    if(jars != null) {
      java.util.Arrays.sort(jars, java.util.Comparator.comparing(File::getName));
      for(final File jar : jars) {
        cp.append(File.pathSeparator).append(jar.getAbsolutePath());
      }
    }

    return cp.toString();
  }

  static int importedDiscCount(final Context context) {
    final File isos = new File(game(context), "isos");
    final File[] files = isos.listFiles(File::isFile);
    return files == null ? 0 : files.length;
  }

  static void ensureDirectories(final File game) throws IOException {
    for(final String dir : new String[] {"isos", "saves", "mods", "config"}) {
      final File child = new File(game, dir);
      if(!child.isDirectory() && !child.mkdirs()) {
        throw new IOException("Unable to create " + child);
      }
    }
  }

  private static void copyAssetTree(
    final AssetManager assets,
    final String assetPath,
    final File destination
  ) throws IOException {
    final String[] children = assets.list(assetPath);
    if(children == null) {
      throw new IOException("Missing APK asset " + assetPath);
    }

    if(children.length == 0) {
      final File parent = destination.getParentFile();
      if(parent != null && !parent.isDirectory() && !parent.mkdirs()) {
        throw new IOException("Unable to create " + parent);
      }

      try(InputStream in = new BufferedInputStream(assets.open(assetPath))) {
        Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      return;
    }

    if(!destination.isDirectory() && !destination.mkdirs()) {
      throw new IOException("Unable to create " + destination);
    }

    for(final String child : children) {
      copyAssetTree(assets, assetPath + "/" + child, new File(destination, child));
    }
  }

  private static void extractTarXzAsset(
    final AssetManager assets,
    final String assetPath,
    final File destination
  ) throws IOException {
    try(
      InputStream raw = new BufferedInputStream(assets.open(assetPath));
      XZCompressorInputStream xz = new XZCompressorInputStream(raw, true);
      TarArchiveInputStream tar = new TarArchiveInputStream(xz)
    ) {
      TarArchiveEntry entry;
      final String rootPath = destination.getCanonicalPath() + File.separator;

      while((entry = tar.getNextTarEntry()) != null) {
        final File output = new File(destination, entry.getName());
        final String canonical = output.getCanonicalPath();

        if(!canonical.startsWith(rootPath) && !canonical.equals(destination.getCanonicalPath())) {
          throw new IOException("Unsafe runtime archive entry " + entry.getName());
        }

        if(entry.isDirectory()) {
          if(!output.isDirectory() && !output.mkdirs()) {
            throw new IOException("Unable to create " + output);
          }
          continue;
        }

        final File parent = output.getParentFile();
        if(parent != null && !parent.isDirectory() && !parent.mkdirs()) {
          throw new IOException("Unable to create " + parent);
        }

        try(BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
          tar.transferTo(out);
        }

        if((entry.getMode() & 0111) != 0) {
          //noinspection ResultOfMethodCallIgnored
          output.setExecutable(true, false);
        }
      }
    }
  }

  private static void makeRuntimeExecutable(final File runtime) {
    final File bin = new File(runtime, "bin");
    final File[] executables = bin.listFiles(File::isFile);
    if(executables != null) {
      for(final File executable : executables) {
        //noinspection ResultOfMethodCallIgnored
        executable.setExecutable(true, false);
      }
    }

    final File jexec = new File(runtime, "lib/jexec");
    if(jexec.isFile()) {
      //noinspection ResultOfMethodCallIgnored
      jexec.setExecutable(true, false);
    }
  }

  private static void deleteTree(final File path) throws IOException {
    if(!path.exists()) {
      return;
    }

    try(final var stream = Files.walk(path.toPath())) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch(final IOException e) {
          throw new RuntimeException(e);
        }
      });
    } catch(final RuntimeException e) {
      if(e.getCause() instanceof IOException io) {
        throw io;
      }
      throw e;
    }
  }

  private static String sanitize(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}

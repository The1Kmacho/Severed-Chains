package legend.visualremaster;

import legend.core.renderer.Texture;
import legend.game.modding.events.submap.SubmapEnvironmentTextureEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Mod(id = VisualRemasterMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class VisualRemasterMod {
  public static final String MOD_ID = "visual_remaster";

  private static final Logger LOGGER = LogManager.getFormatterLogger(VisualRemasterMod.class);
  private static final Path ROOT = Path.of("mods", MOD_ID);
  private static final Path BACKGROUNDS = ROOT.resolve("backgrounds");

  private VisualRemasterMod() { }

  @EventListener
  public static void replaceSubmapEnvironment(final SubmapEnvironmentTextureEvent event) {
    final String location = event.getEngineState().getLocation(event.getGameState());
    final String drgn = "DRGN2" + event.disk;
    final Path sceneDir = BACKGROUNDS
      .resolve(drgn.toLowerCase())
      .resolve("cut_%04d".formatted(event.submapCut));

    LOGGER.info(
      "[Visual Remaster] Environment location=%s archive=%s cut=%d foregrounds=%d folder=%s",
      location,
      drgn,
      event.submapCut,
      event.foregrounds.length,
      sceneDir
    );

    writeSceneInfo(sceneDir, location, drgn, event);

    final Path background = sceneDir.resolve("background.png");
    if(Files.isRegularFile(background)) {
      try {
        event.background = loadTexture("Visual Remaster " + drgn + '/' + event.submapCut + " background", background);
        LOGGER.info("[Visual Remaster] Loaded background override %s", background);
      } catch(final RuntimeException e) {
        LOGGER.error("[Visual Remaster] Failed to load background override %s", background, e);
      }
    }

    for(int i = 0; i < event.foregrounds.length; i++) {
      final Path foreground = sceneDir.resolve("foreground_%d.png".formatted(i));
      if(!Files.isRegularFile(foreground)) {
        continue;
      }

      try {
        event.foregrounds[i] = loadTexture(
          "Visual Remaster " + drgn + '/' + event.submapCut + " foreground " + i,
          foreground
        );
        LOGGER.info("[Visual Remaster] Loaded foreground override %s", foreground);
      } catch(final RuntimeException e) {
        LOGGER.error("[Visual Remaster] Failed to load foreground override %s", foreground, e);
      }
    }
  }

  private static Texture loadTexture(final String name, final Path path) {
    return Texture.create(name, builder -> {
      builder.png(path);
      builder.minFilter(true);
      builder.magFilter(true);
      builder.wrapS(false);
      builder.wrapT(false);
    });
  }

  private static void writeSceneInfo(
    final Path sceneDir,
    final String location,
    final String drgn,
    final SubmapEnvironmentTextureEvent event
  ) {
    try {
      Files.createDirectories(sceneDir);

      final String info = """
        Visual Remaster submap environment
        location=%s
        archive=%s
        cut=%d
        foregrounds=%d

        Replacement files:
        background.png
        foreground_0.png ... foreground_%d.png

        Keep the replacement images at the same aspect ratio as the retail stitched environment.
        Foreground images must be full-canvas RGBA PNGs with transparency outside the cutout.
        """.formatted(
          location,
          drgn,
          event.submapCut,
          event.foregrounds.length,
          Math.max(0, event.foregrounds.length - 1)
        );

      Files.writeString(
        sceneDir.resolve("scene.txt"),
        info,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      );
    } catch(final IOException e) {
      LOGGER.warn("[Visual Remaster] Failed to write scene metadata for %s", sceneDir, e);
    }
  }
}

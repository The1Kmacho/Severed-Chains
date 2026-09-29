package legend.visualremaster;

import legend.core.renderer.Texture;
import legend.game.modding.events.submap.SubmapEnvironmentTextureEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import java.nio.file.Files;
import java.nio.file.Path;

@Mod(id = VisualRemasterMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class VisualRemasterMod {
  public static final String MOD_ID = "visual_remaster";

  private static final Logger LOGGER = LogManager.getFormatterLogger(VisualRemasterMod.class);
  private static final Path BACKGROUNDS = Path.of("mods", MOD_ID, "backgrounds");

  public VisualRemasterMod() { }

  @EventListener
  public static void replaceSubmapEnvironment(final SubmapEnvironmentTextureEvent event) {
    final String archive = "drgn2" + event.disk;
    final Path sceneDir = BACKGROUNDS
      .resolve(archive)
      .resolve("cut_%04d".formatted(event.submapCut));

    LOGGER.info(
      "[Visual Remaster] environment archive=%s cut=%d foregrounds=%d folder=%s",
      archive,
      event.submapCut,
      event.foregrounds.length,
      sceneDir
    );

    final Path background = sceneDir.resolve("background.png");
    if(Files.isRegularFile(background)) {
      try {
        event.background = loadTexture(
          "Visual Remaster " + archive + '/' + event.submapCut + " background",
          background
        );
        LOGGER.info("[Visual Remaster] loaded background override %s", background);
      } catch(final RuntimeException e) {
        LOGGER.error("[Visual Remaster] failed to load background override %s", background, e);
      }
    }

    for(int i = 0; i < event.foregrounds.length; i++) {
      final Path foreground = sceneDir.resolve("foreground_%d.png".formatted(i));
      if(!Files.isRegularFile(foreground)) {
        continue;
      }

      try {
        event.foregrounds[i] = loadTexture(
          "Visual Remaster " + archive + '/' + event.submapCut + " foreground " + i,
          foreground
        );
        LOGGER.info("[Visual Remaster] loaded foreground override %s", foreground);
      } catch(final RuntimeException e) {
        LOGGER.error("[Visual Remaster] failed to load foreground override %s", foreground, e);
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
}

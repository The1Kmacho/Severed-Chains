package legend.visualremaster;

import legend.core.gpu.Rect4i;
import legend.core.gpu.VramTextureLoader;
import legend.core.gpu.VramTextureSingle;
import legend.core.renderer.Texture;
import legend.game.modding.events.submap.SubmapEnvironmentTextureEvent;
import legend.game.submap.EnvironmentRenderingMetrics24;
import legend.game.submap.RetailSubmap;
import legend.game.textures.PngWriter;
import legend.game.tim.Tim;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
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

  // The current mod API deliberately exposes replacement textures, not retail CPU
  // pixels. For authoring only, reconstruct the stitched retail layers from the
  // RetailSubmap's already-decoded TIMs. This stays entirely inside the mod and
  // does not alter runtime rendering or distribute retail assets.
  private static final Field ENV_TEXTURES = privateField("envTextures");
  private static final Field ENV_TEXTURE_COUNT = privateField("envTextureCount_800cb584");
  private static final Field ENV_BACKGROUND_COUNT = privateField("envBackgroundTextureCount_800cb57c");
  private static final Field ENV_FOREGROUND_COUNT = privateField("envForegroundTextureCount_800cb580");
  private static final Field ENV_RENDER_METRICS = privateField("envRenderMetrics_800cb710");

  public VisualRemasterMod() { }

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

    if(event.getSubmap() instanceof final RetailSubmap retailSubmap) {
      exportRetailEnvironment(sceneDir, retailSubmap);
    }

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

  private static void exportRetailEnvironment(final Path sceneDir, final RetailSubmap submap) {
    final Path backgroundPath = sceneDir.resolve("source_background.png");
    if(Files.isRegularFile(backgroundPath)) {
      return;
    }

    try {
      Files.createDirectories(sceneDir);

      final Tim[] envTextures = (Tim[])ENV_TEXTURES.get(submap);
      final int envTextureCount = ENV_TEXTURE_COUNT.getInt(submap);
      final int backgroundCount = ENV_BACKGROUND_COUNT.getInt(submap);
      final int foregroundCount = ENV_FOREGROUND_COUNT.getInt(submap);
      final EnvironmentRenderingMetrics24[] metrics =
        (EnvironmentRenderingMetrics24[])ENV_RENDER_METRICS.get(submap);

      final Tim[] tims = new Tim[envTextureCount];
      final Rect4i[] rects = new Rect4i[envTextureCount];

      for(int i = 0; i < envTextureCount; i++) {
        final EnvironmentRenderingMetrics24 metric = metrics[i];

        for(final Tim texture : envTextures) {
          final Rect4i bounds = texture.getImageRect();
          final int tpX = (metric.tpage_04 & 0b1111) * 64;
          final int tpY = (metric.tpage_04 & 0b10000) != 0 ? 256 : 0;

          if(bounds.contains(tpX, tpY)) {
            tims[i] = texture;
            rects[i] = new Rect4i(metric.offsetX_1c, metric.offsetY_1e, metric.w_18, metric.h_1a);
            break;
          }
        }
      }

      final Rect4i canvas = Rect4i.bound(rects);
      final int[] background = new int[canvas.w * canvas.h];

      for(int i = 0; i < backgroundCount; i++) {
        if(tims[i] == null || rects[i] == null) {
          continue;
        }

        final EnvironmentRenderingMetrics24 metric = metrics[i];
        final VramTextureSingle texture = VramTextureLoader.textureFromTim(tims[i]);
        final VramTextureSingle palette = VramTextureLoader.palettesFromTim(tims[i])[0];
        final Rect4i rect = rects[i];
        final int[] pixels = texture.applyPalette(
          palette,
          new Rect4i(metric.u_14, metric.v_15, rect.w, rect.h)
        );

        makeOpaqueNonZero(pixels);
        blit(
          pixels,
          rect.w,
          rect.h,
          background,
          canvas.w,
          metric.offsetX_1c - canvas.x,
          metric.offsetY_1e - canvas.y
        );
      }

      writePng(backgroundPath, background, canvas.w, canvas.h);

      for(int i = 0; i < foregroundCount; i++) {
        final int envIndex = backgroundCount + i;
        if(envIndex >= tims.length || tims[envIndex] == null || rects[envIndex] == null) {
          continue;
        }

        final EnvironmentRenderingMetrics24 metric = metrics[envIndex];
        final VramTextureSingle texture = VramTextureLoader.textureFromTim(tims[envIndex]);
        final VramTextureSingle palette = VramTextureLoader.palettesFromTim(tims[envIndex])[0];

        final Rect4i appliedRect = new Rect4i(metric.u_14, metric.v_15, rects[envIndex].w, rects[envIndex].h);
        if(appliedRect.right() > texture.rect.w) {
          appliedRect.w = texture.rect.w - appliedRect.x;
        }
        if(appliedRect.bottom() > texture.rect.h) {
          appliedRect.h = texture.rect.h - appliedRect.y;
        }

        final int[] pixels = texture.applyPalette(palette, appliedRect);
        makeOpaqueNonZero(pixels);

        final int[] foreground = new int[canvas.w * canvas.h];
        blit(
          pixels,
          appliedRect.w,
          appliedRect.h,
          foreground,
          canvas.w,
          metric.offsetX_1c - canvas.x,
          metric.offsetY_1e - canvas.y
        );

        writePng(sceneDir.resolve("source_foreground_%d.png".formatted(i)), foreground, canvas.w, canvas.h);
      }

      LOGGER.info(
        "[Visual Remaster] Exported retail environment %dx%d to %s",
        canvas.w,
        canvas.h,
        sceneDir
      );
    } catch(final ReflectiveOperationException | IOException | RuntimeException e) {
      LOGGER.warn("[Visual Remaster] Failed to export retail environment to %s", sceneDir, e);
    }
  }

  private static void makeOpaqueNonZero(final int[] pixels) {
    for(int i = 0; i < pixels.length; i++) {
      if(pixels[i] != 0) {
        pixels[i] |= 0xff000000;
      }
    }
  }

  private static void blit(
    final int[] source,
    final int sourceWidth,
    final int sourceHeight,
    final int[] destination,
    final int destinationWidth,
    final int x,
    final int y
  ) {
    for(int row = 0; row < sourceHeight; row++) {
      System.arraycopy(
        source,
        row * sourceWidth,
        destination,
        (y + row) * destinationWidth + x,
        sourceWidth
      );
    }
  }

  private static void writePng(final Path path, final int[] pixels, final int width, final int height) throws IOException {
    final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * Integer.BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
    final IntBuffer ints = buffer.asIntBuffer();
    ints.put(pixels);
    PngWriter.write(path, buffer, width, height);
  }

  private static Field privateField(final String name) {
    try {
      final Field field = RetailSubmap.class.getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch(final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void writeSceneInfo(
    final Path sceneDir,
    final String location,
    final String drgn,
    final SubmapEnvironmentTextureEvent event
  ) {
    try {
      Files.createDirectories(sceneDir);

      String sourceDimensions = "unknown";
      final Path sourceBackground = sceneDir.resolve("source_background.png");
      if(Files.isRegularFile(sourceBackground)) {
        sourceDimensions = "see source_background.png";
      }

      final String info = """
        Visual Remaster submap environment
        location=%s
        archive=%s
        cut=%d
        foregrounds=%d
        source_dimensions=%s

        Source files exported from your local game data:
        source_background.png
        source_foreground_0.png ... source_foreground_%d.png

        Replacement files:
        background.png
        foreground_0.png ... foreground_%d.png

        Replacement background and foreground images must share the same canvas/aspect ratio.
        Foreground images must be full-canvas RGBA PNGs with transparency outside the cutout.
        Missing replacement files fall back to the retail layer.
        """.formatted(
          location,
          drgn,
          event.submapCut,
          event.foregrounds.length,
          sourceDimensions,
          Math.max(0, event.foregrounds.length - 1),
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

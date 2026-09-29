package legend.visualremaster;

import legend.core.gte.ModelPart10;
import legend.core.renderer.Obj;
import legend.core.renderer.Texture;
import legend.game.modding.events.battle.CombatantModelLoadedEvent;
import legend.game.modding.events.submap.SubmapEnvironmentTextureEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(id = VisualRemasterMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class VisualRemasterMod {
  public static final String MOD_ID = "visual_remaster";

  private static final Logger LOGGER = LogManager.getFormatterLogger(VisualRemasterMod.class);
  private static final Path BACKGROUNDS = Path.of("mods", MOD_ID, "backgrounds");
  private static final Path BATTLE_MODELS = Path.of("mods", MOD_ID, "models", "battle");
  private static Texture dartBattleAlbedo;
  private static Texture dartBattleMaterial;

  public VisualRemasterMod() { }

  @EventListener
  public static void replaceCombatantModel(final CombatantModelLoadedEvent event) {
    if(!event.combatant.isPlayer() || event.combatant.playerBent == null) {
      return;
    }

    // Dart is retail character ID 0. Keep Dragoon Dart on the retail path for
    // the first vertical slice so normal Dart can be developed independently.
    if(event.combatant.playerBent.charId_272 != 0 || event.combatant.playerBent.isDragoon()) {
      return;
    }

    final Path modelDir = BATTLE_MODELS.resolve("dart").resolve("combat");
    final Path albedoPath = modelDir.resolve("albedo.png");
    final Path materialPath = modelDir.resolve("material.png");

    if(!Files.isRegularFile(albedoPath)) {
      return;
    }

    Texture albedo = dartBattleAlbedo;
    Texture material = dartBattleMaterial;
    int replacementCount = 0;

    for(int partIndex = 0; partIndex < event.model.modelParts_00.length; partIndex++) {
      final Path objPath = modelDir.resolve("part_%02d.obj".formatted(partIndex));
      if(!Files.isRegularFile(objPath)) {
        continue;
      }

      try {
        if(albedo == null) {
          albedo = Texture.create("Visual Remaster Dart battle albedo", builder -> {
            builder.png(albedoPath);
            builder.minFilter(true);
            builder.magFilter(true);
            builder.wrapS(false);
            builder.wrapT(false);
          });
          albedo.persistent = true;
          dartBattleAlbedo = albedo;
        }

        final Obj replacement = ModernObjLoader.load(
          "Visual Remaster Dart battle part " + partIndex,
          objPath
        );

        final ModelPart10 part = event.model.modelParts_00[partIndex];
        if(part.renderObjOverride != null) {
          part.renderObjOverride.delete();
        }

        part.renderObjOverride = replacement;
        part.renderTextureOverride = albedo;

        if(Files.isRegularFile(materialPath)) {
          if(material == null) {
            material = Texture.create("Visual Remaster Dart battle material", builder -> {
              builder.png(materialPath);
              builder.minFilter(true);
              builder.magFilter(true);
              builder.wrapS(false);
              builder.wrapT(false);
            });
            material.persistent = true;
            dartBattleMaterial = material;
          }

          part.renderMaterialOverride = material;
        }

        replacementCount++;
      } catch(final IOException | RuntimeException e) {
        LOGGER.error(
          "[Visual Remaster] Failed to load Dart battle replacement part %d from %s; keeping retail part",
          partIndex,
          objPath,
          e
        );
      }
    }

    if(replacementCount != 0) {
      LOGGER.info(
        "[Visual Remaster] Loaded %d/%d Dart battle replacement parts from %s",
        replacementCount,
        event.model.modelParts_00.length,
        modelDir
      );
    }
  }

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

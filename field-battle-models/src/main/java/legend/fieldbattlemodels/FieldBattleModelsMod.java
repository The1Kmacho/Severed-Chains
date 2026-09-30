package legend.fieldbattlemodels;

import legend.game.modding.events.submap.SubmapObjectAssetsEvent;
import legend.game.modding.events.worldmap.WorldMapCharacterModelEvent;
import legend.game.tim.Tim;
import legend.game.tmd.TmdObjTable1c;
import legend.game.types.CContainer;
import legend.game.types.TmdAnimationFile;
import legend.game.unpacker.Loader;
import legend.lodmod.LodMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mod(id = FieldBattleModelsMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class FieldBattleModelsMod {
  public static final String MOD_ID = "field_battle_models";
  private static final Logger LOGGER = LogManager.getFormatterLogger(FieldBattleModelsMod.class);

  /**
   * Dart's field/world-map animations have 15 tracks while the battle model has
   * 17 rigid parts. Battle parts 9 and 14 are extra pieces; later shared body
   * parts are shifted by those insertions.
   *
   * Part 9 follows the torso. Part 14 (sword) follows the adjacent weapon-hand
   * track for the first retarget pass.
   */
  private static final int[] DART_BATTLE_TO_FIELD_ANIMATION = {
    0, 1, 2, 3, 4, 5, 6, 7, 8,
    2,
    9, 10, 11, 12,
    12,
    13, 14
  };

  public FieldBattleModelsMod() { }

  @EventListener
  public static void replaceWorldMapCharacter(final WorldMapCharacterModelEvent event) {
    if(!event.character.template.getRegistryId().equals(LodMod.id("dart"))) {
      return;
    }

    try {
      final Path modelPath = Loader.resolve(Path.of("characters", "dart", "models", "combat", "32"));
      final Path idlePath = Loader.resolve(Path.of("characters", "dart", "models", "combat", "0"));
      final Path texturePath = Loader.resolve(Path.of("characters", "dart", "textures", "combat"));

      final CContainer fieldModel = event.model;
      final CContainer battleModel = new CContainer(
        "Field Battle Models - Dart combat model",
        Loader.loadFileSync(modelPath)
      );
      final TmdAnimationFile battleIdle = new TmdAnimationFile(Loader.loadFileSync(idlePath));
      final Tim battleTexture = new Tim(Loader.loadFileSync(texturePath));
      final float geometryScale = calculateGeometryScale(fieldModel, battleModel);
      final Quaternionf[] rotationCorrections =
        calculateRotationCorrections(event.animations[0], battleIdle);

      event.model = battleModel;
      event.texture = battleTexture;
      event.animationPartMap = DART_BATTLE_TO_FIELD_ANIMATION.clone();
      event.animationRotationCorrections = rotationCorrections;
      event.geometryScale = geometryScale;

      LOGGER.info(
        "[Field Battle Models] Requested Dart battle model on world map: modelParts=%d, idle=%d, walk=%d, run=%d, geometryScale=%.4f",
        battleModel.tmdPtr_00.tmd.header.nobj,
        event.animations[0].modelPartCount_0c,
        event.animations[1].modelPartCount_0c,
        event.animations[2].modelPartCount_0c,
        geometryScale
      );
    } catch(final RuntimeException e) {
      LOGGER.error("[Field Battle Models] Failed to load Dart battle model for world map; retail field model will be used", e);
    }
  }


  @EventListener
  public static void replaceSubmapPlayer(final SubmapObjectAssetsEvent event) {
    if(event.objectIndex != 0) {
      return;
    }

    final var character = event.getGameState().getCharacterBySlot(0);
    if(character == null || !character.template.getRegistryId().equals(LodMod.id("dart"))) {
      return;
    }

    try {
      final Path modelPath = Loader.resolve(Path.of("characters", "dart", "models", "combat", "32"));
      final Path idlePath = Loader.resolve(Path.of("characters", "dart", "models", "combat", "0"));
      final Path texturePath = Loader.resolve(Path.of("characters", "dart", "textures", "combat"));

      final CContainer fieldModel = event.object.model;
      final CContainer battleModel = new CContainer(
        "Field Battle Models - Dart submap combat model",
        Loader.loadFileSync(modelPath)
      );
      final TmdAnimationFile battleIdle = new TmdAnimationFile(Loader.loadFileSync(idlePath));
      final Tim battleTexture = new Tim(Loader.loadFileSync(texturePath));
      final float geometryScale = calculateGeometryScale(fieldModel, battleModel);
      final Quaternionf[] rotationCorrections =
        event.animations.isEmpty()
          ? null
          : calculateRotationCorrections(event.animations.get(0), battleIdle);

      event.object.model = battleModel;
      event.texture = battleTexture;
      event.animationPartMap = DART_BATTLE_TO_FIELD_ANIMATION.clone();
      event.animationRotationCorrections = rotationCorrections;
      event.geometryScale = geometryScale;

      final int firstAnimParts =
        event.animations.isEmpty() || event.animations.get(0) == null
          ? -1
          : event.animations.get(0).modelPartCount_0c;

      LOGGER.info(
        "[Field Battle Models] Replaced submap player with Dart battle model: modelParts=%d, firstAnimationParts=%d, retargetEntries=%d, geometryScale=%.4f",
        battleModel.tmdPtr_00.tmd.header.nobj,
        firstAnimParts,
        DART_BATTLE_TO_FIELD_ANIMATION.length,
        geometryScale
      );
    } catch(final RuntimeException e) {
      LOGGER.error(
        "[Field Battle Models] Failed to replace Dart submap player with battle model; retail field model will be used",
        e
      );
    }
  }

  private static Quaternionf[] calculateRotationCorrections(
    final TmdAnimationFile fieldIdle,
    final TmdAnimationFile battleIdle
  ) {
    final Quaternionf[] corrections = new Quaternionf[DART_BATTLE_TO_FIELD_ANIMATION.length];

    if(fieldIdle.partTransforms_10.length == 0 || battleIdle.partTransforms_10.length == 0) {
      LOGGER.warn("[Field Battle Models] Missing idle bind pose; rotation corrections disabled");
      return corrections;
    }

    for(int battlePart = 0; battlePart < corrections.length; battlePart++) {
      final int fieldPart = DART_BATTLE_TO_FIELD_ANIMATION[battlePart];
      if(fieldPart < 0
        || fieldPart >= fieldIdle.partTransforms_10[0].length
        || battlePart >= battleIdle.partTransforms_10[0].length) {
        continue;
      }

      final Quaternionf fieldBind = fieldIdle.partTransforms_10[0][fieldPart].quat;
      final Quaternionf battleBind = battleIdle.partTransforms_10[0][battlePart].quat;

      corrections[battlePart] = new Quaternionf(fieldBind)
        .invert()
        .mul(battleBind)
        .normalize();
    }

    LOGGER.info(
      "[Field Battle Models] Derived Dart bind rotation corrections for %d battle parts",
      corrections.length
    );
    return corrections;
  }

  private static float calculateGeometryScale(final CContainer fieldModel, final CContainer battleModel) {
    final List<Float> ratios = new ArrayList<>();

    for(int battlePart = 0; battlePart < DART_BATTLE_TO_FIELD_ANIMATION.length; battlePart++) {
      if(battlePart == 9 || battlePart == 14) {
        continue;
      }

      final int fieldPart = DART_BATTLE_TO_FIELD_ANIMATION[battlePart];
      if(fieldPart < 0
        || fieldPart >= fieldModel.tmdPtr_00.tmd.objTable.length
        || battlePart >= battleModel.tmdPtr_00.tmd.objTable.length) {
        continue;
      }

      final float fieldExtent = getExtent(fieldModel.tmdPtr_00.tmd.objTable[fieldPart]);
      final float battleExtent = getExtent(battleModel.tmdPtr_00.tmd.objTable[battlePart]);

      if(fieldExtent > 0.0f && battleExtent > 0.0f) {
        final float ratio = fieldExtent / battleExtent;
        if(ratio > 0.05f && ratio < 2.0f) {
          ratios.add(ratio);
        }
      }
    }

    if(ratios.isEmpty()) {
      LOGGER.warn("[Field Battle Models] Could not derive Dart geometry scale; using 1.0");
      return 1.0f;
    }

    Collections.sort(ratios);
    final int mid = ratios.size() / 2;
    final float median = ratios.size() % 2 == 0
      ? (ratios.get(mid - 1) + ratios.get(mid)) * 0.5f
      : ratios.get(mid);

    LOGGER.info(
      "[Field Battle Models] Derived Dart local geometry scale %.4f from %d shared parts",
      median,
      ratios.size()
    );
    return median;
  }

  private static float getExtent(final TmdObjTable1c part) {
    if(part.vert_top_00.length == 0) {
      return 0.0f;
    }

    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;

    for(final var vertex : part.vert_top_00) {
      minX = Math.min(minX, vertex.x);
      minY = Math.min(minY, vertex.y);
      minZ = Math.min(minZ, vertex.z);
      maxX = Math.max(maxX, vertex.x);
      maxY = Math.max(maxY, vertex.y);
      maxZ = Math.max(maxZ, vertex.z);
    }

    final float x = maxX - minX;
    final float y = maxY - minY;
    final float z = maxZ - minZ;
    return (float)Math.sqrt(x * x + y * y + z * z);
  }
}

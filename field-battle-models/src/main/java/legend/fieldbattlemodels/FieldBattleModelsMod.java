package legend.fieldbattlemodels;

import legend.game.modding.events.submap.SubmapObjectAssetsEvent;
import legend.game.modding.events.worldmap.WorldMapCharacterModelEvent;
import legend.game.tim.Tim;
import legend.game.types.CContainer;
import legend.game.unpacker.Loader;
import legend.lodmod.LodMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import java.nio.file.Path;

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
      final Path texturePath = Loader.resolve(Path.of("characters", "dart", "textures", "combat"));

      final CContainer battleModel = new CContainer(
        "Field Battle Models - Dart combat model",
        Loader.loadFileSync(modelPath)
      );
      final Tim battleTexture = new Tim(Loader.loadFileSync(texturePath));

      event.model = battleModel;
      event.texture = battleTexture;
      event.animationPartMap = DART_BATTLE_TO_FIELD_ANIMATION.clone();

      LOGGER.info(
        "[Field Battle Models] Requested Dart battle model on world map: modelParts=%d, idle=%d, walk=%d, run=%d",
        battleModel.tmdPtr_00.tmd.header.nobj,
        event.animations[0].modelPartCount_0c,
        event.animations[1].modelPartCount_0c,
        event.animations[2].modelPartCount_0c
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
      final Path texturePath = Loader.resolve(Path.of("characters", "dart", "textures", "combat"));

      final CContainer battleModel = new CContainer(
        "Field Battle Models - Dart submap combat model",
        Loader.loadFileSync(modelPath)
      );
      final Tim battleTexture = new Tim(Loader.loadFileSync(texturePath));

      event.object.model = battleModel;
      event.texture = battleTexture;
      event.animationPartMap = DART_BATTLE_TO_FIELD_ANIMATION.clone();

      final int firstAnimParts =
        event.animations.isEmpty() || event.animations.get(0) == null
          ? -1
          : event.animations.get(0).modelPartCount_0c;

      LOGGER.info(
        "[Field Battle Models] Replaced submap player with Dart battle model: modelParts=%d, firstAnimationParts=%d, retargetEntries=%d",
        battleModel.tmdPtr_00.tmd.header.nobj,
        firstAnimParts,
        DART_BATTLE_TO_FIELD_ANIMATION.length
      );
    } catch(final RuntimeException e) {
      LOGGER.error(
        "[Field Battle Models] Failed to replace Dart submap player with battle model; retail field model will be used",
        e
      );
    }
  }
}

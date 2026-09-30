package legend.fieldbattlemodels;

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
}

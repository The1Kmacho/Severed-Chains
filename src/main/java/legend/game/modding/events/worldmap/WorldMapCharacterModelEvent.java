package legend.game.modding.events.worldmap;

import legend.game.characters.CharacterData2c;
import legend.game.tim.Tim;
import legend.game.types.CContainer;
import legend.game.types.TmdAnimationFile;
import org.legendofdragoon.modloader.events.Event;

/**
 * Fired when the active party leader's world-map character model has been loaded,
 * before it is initialized for rendering.
 *
 * Mods may replace {@link #model} and {@link #texture}. The existing world-map
 * idle/walk/run animations are retained unless a future API explicitly replaces them.
 */
public class WorldMapCharacterModelEvent extends Event {
  public final CharacterData2c character;
  public CContainer model;
  public Tim texture;
  public final TmdAnimationFile[] animations;

  public WorldMapCharacterModelEvent(
    final CharacterData2c character,
    final CContainer model,
    final Tim texture,
    final TmdAnimationFile[] animations
  ) {
    this.character = character;
    this.model = model;
    this.texture = texture;
    this.animations = animations;
  }
}

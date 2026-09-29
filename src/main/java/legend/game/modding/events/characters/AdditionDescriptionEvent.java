package legend.game.modding.events.characters;

import legend.game.additions.Addition;
import legend.game.characters.CharacterData2c;
import org.legendofdragoon.modloader.events.Event;

/**
 * Allows mods to append a concise gameplay-effect description to the in-battle
 * addition selection UI.
 */
public class AdditionDescriptionEvent extends Event {
  public final CharacterData2c character;
  public final Addition addition;
  public String description = "";

  public AdditionDescriptionEvent(final CharacterData2c character, final Addition addition) {
    this.character = character;
    this.addition = addition;
  }
}

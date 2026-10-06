package legend.game.modding.events.battle;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import legend.game.characters.CharacterData2c;
import org.legendofdragoon.modloader.events.Event;

/** Allows mods to adjust the post-battle XP allocation without changing encounter XP. */
public class PostBattleXpDistributionEvent extends Event {
  public final int totalXp;
  private final Object2IntMap<CharacterData2c> allocations;

  public PostBattleXpDistributionEvent(final int totalXp, final Object2IntMap<CharacterData2c> allocations) {
    this.totalXp = totalXp;
    this.allocations = allocations;
  }

  public int get(final CharacterData2c character) {
    return this.allocations.getInt(character);
  }

  public void set(final CharacterData2c character, final int xp) {
    if(xp > 0) {
      this.allocations.put(character, xp);
    } else {
      this.allocations.removeInt(character);
    }
  }

  public void add(final CharacterData2c character, final int xp) {
    if(xp > 0) {
      this.allocations.put(character, this.allocations.getInt(character) + xp);
    }
  }

  public void remove(final CharacterData2c character) {
    this.allocations.removeInt(character);
  }
}

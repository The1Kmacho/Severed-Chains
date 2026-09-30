package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.encounters.Encounter;

/**
 * Fired before the standard opening battle-camera sequence is selected.
 * Setting skipStandardIntro skips only that generic camera presentation; mods
 * can leave scripted/special encounters untouched.
 */
public class BattleIntroCameraEvent extends BattleStateEvent {
  public final Encounter encounter;
  public boolean skipStandardIntro;

  public BattleIntroCameraEvent(final Battle battle, final Encounter encounter) {
    super(battle);
    this.encounter = encounter;
  }
}

package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.encounters.Encounter;

/**
 * Fired before the standard opening battle-camera sequence is selected.
 * Setting skipStandardIntro selects the retail no-showcase camera path.
 */
public class BattleIntroCameraEvent extends BattleStateEvent {
  public boolean skipStandardIntro;

  public BattleIntroCameraEvent(final Battle battle, final Encounter encounter) {
    super(battle, encounter);
  }
}

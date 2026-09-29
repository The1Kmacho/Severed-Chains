package legend.game.modding.events.characters;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEvent;

/**
 * Posted once when a Dragoon attack meter resolves.
 */
public class DragoonAdditionCompletedEvent extends BattleEvent {
  public final int charId;
  public final int successfulCircles;
  public final int totalCircles;

  public DragoonAdditionCompletedEvent(
    final Battle battle,
    final int charId,
    final int successfulCircles,
    final int totalCircles
  ) {
    super(battle);
    this.charId = charId;
    this.successfulCircles = successfulCircles;
    this.totalCircles = totalCircles;
  }

  public boolean perfect() {
    return this.totalCircles > 0 && this.successfulCircles >= this.totalCircles;
  }
}

package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.types.AttackType;

/**
 * Fired immediately before an attack is resolved. Mods may request a short
 * script delay to present a timing prompt before damage and special effects are
 * calculated.
 */
public class IncomingAttackTimingEvent extends BattleEvent {
  public final BattleEntity27c attacker;
  public final BattleEntity27c defender;
  public final AttackType attackType;

  /** Number of battle ticks to wait before resolving this attack. */
  public int delayTicks;

  public IncomingAttackTimingEvent(
    final Battle battle,
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType
  ) {
    super(battle);
    this.attacker = attacker;
    this.defender = defender;
    this.attackType = attackType;
  }
}

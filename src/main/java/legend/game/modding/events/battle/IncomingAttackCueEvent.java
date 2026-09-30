package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.types.AttackType;

/**
 * Fired while an incoming attack is already proceeding on its normal timeline.
 * Mods may use the cue for visual timing feedback, but it never pauses or
 * advances the attack.
 */
public class IncomingAttackCueEvent extends BattleEvent {
  public final BattleEntity27c attacker;
  public final BattleEntity27c defender;
  public final AttackType attackType;

  /**
   * Optional first-use estimate, in battle ticks, from this cue to impact.
   * Zero means the source has no useful estimate.
   */
  public final int suggestedImpactTicks;

  public IncomingAttackCueEvent(
    final Battle battle,
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType
  ) {
    this(battle, attacker, defender, attackType, 0);
  }

  public IncomingAttackCueEvent(
    final Battle battle,
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType,
    final int suggestedImpactTicks
  ) {
    super(battle);
    this.attacker = attacker;
    this.defender = defender;
    this.attackType = attackType;
    this.suggestedImpactTicks = java.lang.Math.max(0, suggestedImpactTicks);
  }
}

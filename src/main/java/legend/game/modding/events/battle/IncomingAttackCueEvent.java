package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.types.AttackType;

/**
 * Fired when an attack has passed its normal hit check but before the attack
 * script reaches damage resolution. This event never pauses or alters the
 * attack timeline; it exists so mods can present timing feedback alongside the
 * retail animation.
 */
public class IncomingAttackCueEvent extends BattleEvent {
  public final BattleEntity27c attacker;
  public final BattleEntity27c defender;
  public final AttackType attackType;

  public IncomingAttackCueEvent(
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

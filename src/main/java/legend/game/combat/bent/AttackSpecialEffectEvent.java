package legend.game.combat.bent;

import legend.game.combat.Battle;
import legend.game.combat.types.AttackType;

/**
 * Posted after the retail attack special-effect roll has been resolved, but before
 * the result is returned to battle scripts. Mods may replace {@link #effect}.
 *
 * effect values follow the retail convention: -1 = none, 0 = non-status special
 * effect, otherwise a status bit.
 */
public class AttackSpecialEffectEvent extends BattleEvent {
  public final BattleEntity27c attacker;
  public final BattleEntity27c defender;
  public final AttackType attackType;
  public int effect;

  public AttackSpecialEffectEvent(
    final Battle battle,
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType,
    final int effect
  ) {
    super(battle);
    this.attacker = attacker;
    this.defender = defender;
    this.attackType = attackType;
    this.effect = effect;
  }
}

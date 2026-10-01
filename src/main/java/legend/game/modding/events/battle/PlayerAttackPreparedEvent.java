package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.bent.PlayerBattleEntity;

public final class PlayerAttackPreparedEvent extends BattleEvent {
  public final PlayerBattleEntity player;
  public final int waitTicks;

  public PlayerAttackPreparedEvent(final Battle battle, final PlayerBattleEntity player, final int waitTicks) {
    super(battle);
    this.player = player;
    this.waitTicks = waitTicks;
  }
}

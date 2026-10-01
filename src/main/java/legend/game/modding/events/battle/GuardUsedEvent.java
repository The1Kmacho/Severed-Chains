package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.bent.PlayerBattleEntity;

public final class GuardUsedEvent extends BattleEvent {
  public final PlayerBattleEntity player;

  public GuardUsedEvent(final Battle battle, final PlayerBattleEntity player) {
    super(battle);
    this.player = player;
  }
}

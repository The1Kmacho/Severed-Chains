package legend.lodmod.battleactions;

import legend.game.combat.Battle;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleActionUseFlowControl;
import legend.game.modding.events.battle.GuardUsedEvent;

import static legend.core.GameEngine.EVENTS;

public final class GuardBattleAction extends RetailBattleAction {
  public GuardBattleAction() {
    super(1);
  }

  @Override
  public BattleActionUseFlowControl use(final Battle battle, final PlayerBattleEntity player) {
    EVENTS.postEvent(new GuardUsedEvent(battle, player));
    return BattleActionUseFlowControl.CONTINUE_SCRIPT;
  }
}

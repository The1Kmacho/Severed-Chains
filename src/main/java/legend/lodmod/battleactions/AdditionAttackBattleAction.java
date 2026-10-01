package legend.lodmod.battleactions;

import legend.game.combat.Battle;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleActionUseFlowControl;

/**
 * Normal-form Attack opens the in-battle Addition list first. Once an Addition
 * is chosen the HUD hands the normal retail Attack action back to the player
 * combat script, which immediately continues into its existing target selector.
 */
public final class AdditionAttackBattleAction extends RetailBattleAction {
  public AdditionAttackBattleAction() {
    super(4);
  }

  @Override
  public BattleActionUseFlowControl use(final Battle battle, final PlayerBattleEntity player) {
    // Shana/Miranda and any modded character without Additions retain the
    // normal direct Attack -> target flow.
    if(player.character.getUnlockedAdditions().isEmpty()) {
      return BattleActionUseFlowControl.CONTINUE_SCRIPT;
    }

    battle.hud.beginAdditionAttackSelection(this);
    battle.hud.initListMenu(player, 2);
    return BattleActionUseFlowControl.PAUSE_SCRIPT;
  }
}

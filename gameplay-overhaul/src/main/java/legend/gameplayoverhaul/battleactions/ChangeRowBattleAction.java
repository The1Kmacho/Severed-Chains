package legend.gameplayoverhaul.battleactions;

import legend.core.MathHelper;
import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleActionTickFlowControl;
import legend.game.combat.ui.BattleActionUseFlowControl;
import legend.gameplayoverhaul.camera.BattleCameraDirector;
import legend.gameplayoverhaul.rows.BattleRows;
import legend.lodmod.battleactions.SeveredBattleAction;
import org.joml.Vector3f;

import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.sound.Audio.playMenuSound;
import static legend.game.sound.Audio.stopMenuSound;

public final class ChangeRowBattleAction extends SeveredBattleAction {
  private static final int RUN_ANIMATION = 4;
  private static final int RUN_DELAY_TICKS = 4;
  private static final int MOVE_TICKS = 12;

  private PlayerBattleEntity actor;
  private final Vector3f target = new Vector3f();
  private boolean targetBackRow;
  private float originalRotation;
  private int phase;
  private int phaseTicks;

  public ChangeRowBattleAction() {
    // Animated double-arrow icon in battle_icons.png row 1.
    super(1, 3);
  }

  public static boolean canChangeRow(final PlayerBattleEntity player) {
    return player.typeBentSlot_276 >= 0
      && !player.isDragoon()
      && battleState_8006e398._54c == 0
      && (player.status_0e & 0x17) == 0;
  }

  @Override
  public BattleActionUseFlowControl use(final Battle battle, final PlayerBattleEntity player) {
    if(!canChangeRow(player)) {
      return BattleActionUseFlowControl.FAIL;
    }

    this.actor = player;
    this.targetBackRow = !BattleRows.isBackRow(player.charId_272);
    this.target.set(BattleRows.prepareToggleTarget(player));
    BattleCameraDirector.startRowShot(player, this.target);
    this.originalRotation = player.model_148.coord2_14.transforms.rotate.y;
    this.phase = 0;
    this.phaseTicks = 0;
    return BattleActionUseFlowControl.PAUSE_ACTION;
  }

  @Override
  public BattleActionTickFlowControl tick(final Battle battle, final PlayerBattleEntity player) {
    if(this.actor == null || player != this.actor) {
      return BattleActionTickFlowControl.CONTINUE_SCRIPT;
    }

    switch(this.phase) {
      case 0 -> {
        if(!battle.setBattleEntityAnimation(player.getState(), RUN_ANIMATION)) {
          return BattleActionTickFlowControl.PAUSE_SCRIPT;
        }

        player.getState().clearFlag(BattleEntity27c.FLAG_ANIMATE_ONCE);
        final Vector3f position = player.model_148.coord2_14.coord.transfer;
        player.model_148.coord2_14.transforms.rotate.y =
          MathHelper.atan2(this.target.x - position.x, this.target.z - position.z) + MathHelper.PI;
        this.phaseTicks = RUN_DELAY_TICKS;
        this.phase = 1;
        return BattleActionTickFlowControl.PAUSE_SCRIPT;
      }

      case 1 -> {
        if(--this.phaseTicks > 0) {
          return BattleActionTickFlowControl.PAUSE_SCRIPT;
        }

        playMenuSound(0x20, 0, 3);
        battle.moveBattleEntityTo(player.getState(), this.target, MOVE_TICKS);
        this.phase = 2;
        return BattleActionTickFlowControl.PAUSE_SCRIPT;
      }

      case 2 -> {
        if(battle.isBattleEntityMoving(player)) {
          return BattleActionTickFlowControl.PAUSE_SCRIPT;
        }

        stopMenuSound(0x20, 3);
        BattleRows.finishToggle(player, this.targetBackRow);
        battle.hud.showEffectNotification(this.targetBackRow ? "BACK ROW" : "FRONT ROW");
        player.model_148.coord2_14.transforms.rotate.y = this.originalRotation;
        battle.restorePlayerBattleAnimation(player);
        BattleCameraDirector.finishActionCamera(player);

        this.actor = null;
        this.phase = 0;
        return BattleActionTickFlowControl.CONTINUE_SCRIPT;
      }

      default -> throw new IllegalStateException("Invalid row-change phase " + this.phase);
    }
  }
}

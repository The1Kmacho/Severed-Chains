package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEvent;
import legend.game.combat.bent.PlayerBattleEntity;

/** Fired once the active player battle slot has atomically changed occupants. */
public class PlayerBattleSlotChangedEvent extends BattleEvent {
  public final int slot;
  public final PlayerBattleEntity outgoing;
  public final PlayerBattleEntity incoming;

  public PlayerBattleSlotChangedEvent(final Battle battle, final int slot, final PlayerBattleEntity outgoing, final PlayerBattleEntity incoming) {
    super(battle);
    this.slot = slot;
    this.outgoing = outgoing;
    this.incoming = incoming;
  }
}

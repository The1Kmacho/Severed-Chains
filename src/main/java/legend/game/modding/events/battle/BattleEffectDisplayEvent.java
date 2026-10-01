package legend.game.modding.events.battle;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.BattleEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets battle systems and mods expose short, persistent effect labels for a
 * battle entity. BattleHud renders these on party rows and beside the currently
 * targeted combatant.
 */
public class BattleEffectDisplayEvent extends BattleEvent {
  public final BattleEntity27c entity;
  public final List<String> effects = new ArrayList<>();

  public BattleEffectDisplayEvent(final Battle battle, final BattleEntity27c entity) {
    super(battle);
    this.entity = entity;
  }
}

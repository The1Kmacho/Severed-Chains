package legend.game.combat;

import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.types.BattleStateEf4;
import legend.game.combat.types.battlestate.StatusConditions20;

/**
 * Captures the small set of retail battle values that live in global slot
 * arrays instead of on PlayerBattleEntity. This lets those values follow a
 * character across an in-battle slot replacement.
 */
final class PartySwitchLegacyState {
  private int sc00;
  private int sc04;
  private int sc08;
  private int sc0c;
  private int sc10;
  private int sc14;
  private int menuBlock;
  private int shieldTurns;
  private int pandemonium;
  private int chargingSpirit;
  private int stolenItem;

  private int statusEffect;
  private int statusTurns;
  private int statusUnknown;

  private int additionFlags;
  private int additionUnknown;

  private int dragoonTurns;
  private int e2e8;
  private int e334;
  private int e34c;
  private int e460;

  static PartySwitchLegacyState capture(final BattleStateEf4 state, final int charSlot, final int allBentSlot) {
    final PartySwitchLegacyState out = new PartySwitchLegacyState();
    final StatusConditions20 conditions = state.statusConditions_00[allBentSlot];

    out.sc00 = conditions._00;
    out.sc04 = conditions._04;
    out.sc08 = conditions._08;
    out.sc0c = conditions._0c;
    out.sc10 = conditions._10;
    out.sc14 = conditions._14;
    out.menuBlock = conditions.menuBlockFlag_18;
    out.shieldTurns = conditions.shieldsSigStoneCharmTurns_1c;
    out.pandemonium = conditions.pandemoniumTurnsDiedAsDragoon_1d;
    out.chargingSpirit = conditions.chargingSpirit_1e;
    out.stolenItem = conditions.stolenItem_1f;

    out.statusEffect = state.status_384[allBentSlot].statusEffect_00;
    out.statusTurns = state.status_384[allBentSlot].statusTurns_01;
    out.statusUnknown = state.status_384[allBentSlot].unknown_02;

    out.additionFlags = state.additionExtra_474[allBentSlot].flag_00;
    out.additionUnknown = state.additionExtra_474[allBentSlot].unknown_01;

    out.dragoonTurns = state.dragoonTurnsRemaining_294[charSlot];
    out.e2e8 = state._2e8[charSlot];
    out.e334 = state._334[charSlot];
    out.e34c = state._34c[charSlot];
    out.e460 = state._460[charSlot];
    return out;
  }

  static PartySwitchLegacyState initialize(final PlayerBattleEntity player) {
    final PartySwitchLegacyState out = new PartySwitchLegacyState();
    out.sc10 = -1;
    out.sc14 = 0x20;
    out.e460 = -1;

    final int status = player.status_0e & 0xff;
    if(status != 0) {
      out.statusEffect = Integer.numberOfTrailingZeros(status);
      out.statusTurns = 3;
    }
    return out;
  }

  void restore(final BattleStateEf4 state, final int charSlot, final int allBentSlot) {
    final StatusConditions20 conditions = state.statusConditions_00[allBentSlot];
    conditions._00 = this.sc00;
    conditions._04 = this.sc04;
    conditions._08 = this.sc08;
    conditions._0c = this.sc0c;
    conditions._10 = this.sc10;
    conditions._14 = this.sc14;
    conditions.menuBlockFlag_18 = this.menuBlock;
    conditions.shieldsSigStoneCharmTurns_1c = this.shieldTurns;
    conditions.pandemoniumTurnsDiedAsDragoon_1d = this.pandemonium;
    conditions.chargingSpirit_1e = this.chargingSpirit;
    conditions.stolenItem_1f = this.stolenItem;

    state.status_384[allBentSlot].statusEffect_00 = this.statusEffect;
    state.status_384[allBentSlot].statusTurns_01 = this.statusTurns;
    state.status_384[allBentSlot].unknown_02 = this.statusUnknown;

    state.additionExtra_474[allBentSlot].flag_00 = this.additionFlags;
    state.additionExtra_474[allBentSlot].unknown_01 = this.additionUnknown;

    state.dragoonTurnsRemaining_294[charSlot] = this.dragoonTurns;
    state._2e8[charSlot] = this.e2e8;
    state._334[charSlot] = this.e334;
    state._34c[charSlot] = this.e34c;
    state._460[charSlot] = this.e460;
  }
}

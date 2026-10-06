package legend.gameplayoverhaul.battleactions;

import legend.game.characters.CharacterData2c;
import legend.game.characters.VitalsStat;
import legend.game.combat.Battle;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleActionUseFlowControl;
import legend.game.modding.coremod.CoreMod;
import legend.gameplayoverhaul.ui.PartySwitchListMenu;
import legend.lodmod.LodMod;
import legend.lodmod.battleactions.RetailBattleAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static legend.core.GameEngine.CONFIG;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;

public final class SwitchPartyBattleAction extends RetailBattleAction {
  public SwitchPartyBattleAction() {
    // Retail Escape runner icon matches the reused run-out/run-in choreography.
    super(6);
  }

  public static boolean hasEligibleReplacement(final Battle battle, final PlayerBattleEntity outgoing) {
    for(final int charId : reserveCharacterIds()) {
      if(isEligibleReplacement(battle, outgoing, charId)) {
        return true;
      }
    }
    return false;
  }

  public static List<Integer> reserveCharacterIds() {
    final Set<Integer> active = new HashSet<>();
    for(final var state : battleState_8006e398.playerBents_e40) {
      active.add(state.innerStruct_00.charId_272);
    }

    final List<Integer> reserves = new ArrayList<>();
    for(int charId = 0; charId < gameState_800babc8.charData_32c.size(); charId++) {
      if(active.contains(charId)) {
        continue;
      }

      final CharacterData2c character = gameState_800babc8.charData_32c.get(charId);
      if((character.partyFlags_04 & CharacterData2c.IN_PARTY) != 0) {
        reserves.add(charId);
      }
    }
    return reserves;
  }

  public static boolean isEligibleReplacement(final Battle battle, final PlayerBattleEntity outgoing, final int charId) {
    // Petrified, bewitched, confused, and stunned actors cannot voluntarily leave.
    if(outgoing.isDragoon() || (outgoing.status_0e & 0x17) != 0 || battleState_8006e398._54c != 0 || outgoing.typeBentSlot_276 < 0) {
      return false;
    }

    final boolean unlockParty = CONFIG.getConfig(CoreMod.UNLOCK_PARTY_CONFIG.get());
    if(!unlockParty && (outgoing.character.partyFlags_04 & CharacterData2c.CANT_REMOVE) != 0) {
      return false;
    }

    for(final var state : battleState_8006e398.playerBents_e40) {
      if(state.innerStruct_00.charId_272 == charId) {
        return false;
      }
    }

    final CharacterData2c character = gameState_800babc8.charData_32c.get(charId);
    if((character.partyFlags_04 & CharacterData2c.IN_PARTY) == 0) {
      return false;
    }
    if(!unlockParty && (character.partyFlags_04 & CharacterData2c.CAN_BE_IN_PARTY) == 0) {
      return false;
    }

    final PlayerBattleEntity cached = battle.getBattlePlayerForCharacter(charId);
    final VitalsStat hp = cached != null
      ? cached.stats.getStat(LodMod.HP_STAT.get())
      : character.stats.getStat(LodMod.HP_STAT.get());
    return hp.getCurrent() > 0;
  }

  public static String disabledReason(final Battle battle, final int charId) {
    final PlayerBattleEntity cached = battle.getBattlePlayerForCharacter(charId);
    final CharacterData2c character = gameState_800babc8.charData_32c.get(charId);
    final VitalsStat hp = cached != null
      ? cached.stats.getStat(LodMod.HP_STAT.get())
      : character.stats.getStat(LodMod.HP_STAT.get());
    return hp.getCurrent() <= 0 ? "KO" : "LOCKED";
  }

  @Override
  public BattleActionUseFlowControl use(final Battle battle, final PlayerBattleEntity player) {
    if(!hasEligibleReplacement(battle, player)) {
      return BattleActionUseFlowControl.FAIL;
    }

    battle.hud.listMenu_800c6b60 = new PartySwitchListMenu(battle.hud, player, reserveCharacterIds());
    return BattleActionUseFlowControl.PAUSE_SCRIPT;
  }
}

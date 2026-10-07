package legend.gameplayoverhaul.rows;

import legend.core.tags.IntTag;
import legend.core.tags.ListTag;
import legend.core.tags.Tag;
import legend.game.combat.Battle;
import legend.game.combat.bent.AttackEvent;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.types.AttackType;
import legend.game.saves.ReadSaveDataEvent;
import legend.game.saves.WriteSaveDataEvent;
import org.joml.Vector3f;
import org.legendofdragoon.modloader.registries.RegistryId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.gameplayoverhaul.GameplayOverhaulMod.MOD_ID;

/** Character-owned persistent front/back row state plus battle-local formation anchors. */
public final class BattleRows {
  private static final RegistryId SAVE_ID = new RegistryId(MOD_ID, "battle_rows");
  private static final float BACK_ROW_DISTANCE = 600.0f;

  private static final int FRONT_PHYSICAL_DAMAGE_PERCENT = 110;
  private static final int BACK_MELEE_DAMAGE_PERCENT = 80;
  private static final int BACK_PHYSICAL_DAMAGE_TAKEN_PERCENT = 80;

  private static final Set<Integer> BACK_ROW_CHARACTERS = new HashSet<>();
  private static final Map<Integer, Vector3f> SLOT_FRONT_POSITIONS = new HashMap<>();

  private BattleRows() { }

  public static void resetForLoadedGame() {
    BACK_ROW_CHARACTERS.clear();
    SLOT_FRONT_POSITIONS.clear();
  }

  public static void readSaveData(final ReadSaveDataEvent event) {
    BACK_ROW_CHARACTERS.clear();
    final Tag tag = event.get(SAVE_ID);
    if(tag == null) {
      return;
    }

    final ListTag rows = tag.asList();
    for(int charId = 0; charId < rows.size(); charId++) {
      if(rows.get(charId).asInt().get() != 0) {
        BACK_ROW_CHARACTERS.add(charId);
      }
    }
  }

  public static void writeSaveData(final WriteSaveDataEvent event) {
    final ListTag rows = new ListTag();
    for(int charId = 0; charId < gameState_800babc8.charData_32c.size(); charId++) {
      rows.add(new IntTag(isBackRow(charId) ? 1 : 0));
    }
    event.add(SAVE_ID, rows);
  }

  public static void beginBattle(final Battle battle) {
    SLOT_FRONT_POSITIONS.clear();

    for(int slot = 0; slot < battleState_8006e398.getPlayerCount(); slot++) {
      final PlayerBattleEntity player = battleState_8006e398.playerBents_e40.get(slot).innerStruct_00;
      final Vector3f front = new Vector3f(player.model_148.coord2_14.coord.transfer);
      SLOT_FRONT_POSITIONS.put(slot, front);

      if(isBackRow(player.charId_272)) {
        player.model_148.coord2_14.coord.transfer.set(calculateBackPosition(front));
      }
    }
  }

  public static void endBattle() {
    SLOT_FRONT_POSITIONS.clear();
  }

  public static boolean isBackRow(final int charId) {
    return BACK_ROW_CHARACTERS.contains(charId);
  }

  public static Vector3f prepareToggleTarget(final PlayerBattleEntity player) {
    final int slot = player.typeBentSlot_276;
    final boolean currentlyBack = isBackRow(player.charId_272);

    if(!currentlyBack) {
      SLOT_FRONT_POSITIONS.put(slot, new Vector3f(player.model_148.coord2_14.coord.transfer));
    }

    return getBattlePosition(slot, player.charId_272, player.model_148.coord2_14.coord.transfer, !currentlyBack);
  }

  public static void finishToggle(final PlayerBattleEntity player, final boolean backRow) {
    if(backRow) {
      BACK_ROW_CHARACTERS.add(player.charId_272);
    } else {
      BACK_ROW_CHARACTERS.remove(player.charId_272);
    }
  }

  public static Vector3f getBattlePosition(final int slot, final int charId, final Vector3f fallback) {
    return getBattlePosition(slot, charId, fallback, isBackRow(charId));
  }

  private static Vector3f getBattlePosition(final int slot, final int charId, final Vector3f fallback, final boolean backRow) {
    final Vector3f front = SLOT_FRONT_POSITIONS.get(slot);
    final Vector3f anchor = front != null ? new Vector3f(front) : new Vector3f(fallback);
    return backRow ? calculateBackPosition(anchor) : anchor;
  }

  private static Vector3f calculateBackPosition(final Vector3f front) {
    final Vector3f enemyCenter = getEnemyCenter();
    final Vector3f away = new Vector3f(front.x - enemyCenter.x, 0.0f, front.z - enemyCenter.z);

    if(away.lengthSquared() < 1.0f) {
      away.set(1.0f, 0.0f, 0.0f);
    } else {
      away.normalize();
    }

    return new Vector3f(front).add(away.mul(BACK_ROW_DISTANCE));
  }

  private static Vector3f getEnemyCenter() {
    final Vector3f center = new Vector3f();
    int count = 0;

    if(!battleState_8006e398.aliveMonsterBents_ebc.isEmpty()) {
      for(final var state : battleState_8006e398.aliveMonsterBents_ebc) {
        center.add(state.innerStruct_00.model_148.coord2_14.coord.transfer);
        count++;
      }
    } else {
      for(int i = 0; i < battleState_8006e398.getMonsterCount(); i++) {
        final var state = battleState_8006e398.monsterBents_e50[i];
        if(state != null) {
          center.add(state.innerStruct_00.model_148.coord2_14.coord.transfer);
          count++;
        }
      }
    }

    if(count > 0) {
      center.div(count);
    }

    return center;
  }

  public static void applyPhysicalDamageModifiers(final AttackEvent event) {
    if(event.attackType != AttackType.PHYSICAL || event.damage <= 0) {
      return;
    }

    if(event.attacker instanceof final PlayerBattleEntity attacker) {
      if(isBackRow(attacker.charId_272)) {
        if(!attacker.character.isArcher()) {
          event.damage = scaleDamage(event.damage, BACK_MELEE_DAMAGE_PERCENT);
        }
      } else {
        event.damage = scaleDamage(event.damage, FRONT_PHYSICAL_DAMAGE_PERCENT);
      }
    }

    if(event.defender instanceof final PlayerBattleEntity defender && isBackRow(defender.charId_272)) {
      event.damage = scaleDamage(event.damage, BACK_PHYSICAL_DAMAGE_TAKEN_PERCENT);
    }
  }

  private static int scaleDamage(final int damage, final int percent) {
    if(damage <= 0) {
      return damage;
    }
    return java.lang.Math.max(1, damage * percent / 100);
  }
}

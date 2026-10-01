package legend.gameplayoverhaul;

import legend.game.additions.Addition;
import legend.game.characters.CharacterAdditionInfo;
import legend.game.characters.Element;
import legend.game.characters.UnaryStat;
import legend.game.characters.UnaryStatMod;
import legend.game.characters.UnaryStatModConfig;
import legend.game.characters.VitalsStat;
import legend.game.combat.Battle;
import legend.game.combat.SEffe;
import legend.game.combat.bent.AttackEvent;
import legend.game.combat.bent.AttackSpecialEffectEvent;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.MonsterBattleEntity;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.effects.AdditionOverlaysEffect44;
import legend.game.combat.effects.GenericAttachment1c;
import legend.game.combat.effects.GuardEffect06;
import legend.game.combat.types.AttackType;
import legend.game.modding.events.battle.BattleEffectDisplayEvent;
import legend.game.modding.events.battle.ArcherSpEvent;
import legend.game.modding.events.battle.BattleEntityTurnEvent;
import legend.game.modding.events.battle.BattleIntroCameraEvent;
import legend.game.modding.events.battle.PlayerAttackPreparedEvent;
import legend.game.modding.events.battle.IncomingAttackCueEvent;
import legend.game.modding.events.characters.AdditionDescriptionEvent;
import legend.game.modding.events.characters.DragoonAdditionCompletedEvent;
import legend.game.modding.events.input.InputPressedEvent;
import legend.lodmod.LodAdditions;
import legend.lodmod.LodMod;
import legend.lodmod.additions.ArcherAddition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.legendofdragoon.modloader.registries.RegistryId;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static legend.core.GameEngine.REGISTRIES;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.spGained_800bc950;
import static legend.game.Scus94491BpeSegment_800b.tickCount_800bb0fc;

@Mod(id = GameplayOverhaulMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class GameplayOverhaulMod {
  public static final String MOD_ID = "gameplay_overhaul";

  private static final Logger LOGGER = LogManager.getFormatterLogger(GameplayOverhaulMod.class);

  private static final int PARRY_WINDOW_TICKS = 2;
  private static final int DEFAULT_PARRY_CUE_TICKS = 18;
  private static final int GUARD_ANIMATION_INDEX = 5;
  private static final int PARRY_GUARD_EFFECT_TICKS = 12;

  private static int lastParryTick = Integer.MIN_VALUE;
  private static int negatedAttackTick = Integer.MIN_VALUE;
  private static BattleEntity27c negatedDefender;

  private static final Map<String, Integer> PARRY_CUE_TIMINGS = new HashMap<>();
  private static BattleEntity27c activeCueAttacker;
  private static BattleEntity27c activeCueDefender;
  private static AttackType activeCueAttackType;
  private static int activeCueStartTick = Integer.MIN_VALUE;
  private static String activeCueKey;
  private static legend.game.scripting.ScriptState<?> activeParryOverlay;
  private static AdditionOverlaysEffect44 activeParryReticle;
  private static final Vector3f savedCameraViewpoint = new Vector3f();
  private static final Vector3f savedCameraRefpoint = new Vector3f();
  private static boolean parryCameraActive;

  private static PlayerBattleEntity activeArcherPlayer;
  private static RegistryId activeArcherAddition;
  private static legend.game.scripting.ScriptState<?> activeArcherOverlay;
  private static AdditionOverlaysEffect44 activeArcherReticle;
  private static int activeArcherCueStartTick = Integer.MIN_VALUE;
  private static int activeArcherWindowStartTick = Integer.MIN_VALUE;
  private static int activeArcherTargetTick = Integer.MIN_VALUE;
  private static boolean activeArcherResolved;
  private static boolean activeArcherSuccess;
  private static boolean activeArcherAttackResolved;
  private static boolean activeArcherXpAwarded;

  private static int lastMomentumTick = Integer.MIN_VALUE;
  private static PlayerBattleEntity lastMomentumPlayer;
  private static RegistryId lastMomentumAddition;

  private static final Set<RegistryId> FINAL_ADDITIONS = Set.of(
    LodAdditions.BLAZING_DYNAMO.getId(),
    LodAdditions.FLOWER_STORM.getId(),
    LodAdditions.ALBERT_FLOWER_STORM.getId(),
    LodAdditions.DEMONS_DANCE.getId(),
    LodAdditions.OMNI_SWEEP.getId(),
    LodAdditions.PERKY_STEP.getId(),
    LodAdditions.BONE_CRUSH.getId(),
    LodAdditions.MOONSHOT.getId()
  );

  public GameplayOverhaulMod() { }

  @EventListener
  public static void inputPressed(final InputPressedEvent event) {
    if(event.repeat) {
      return;
    }

    if(activeArcherPlayer != null && !activeArcherResolved) {
      if(event.action == LodMod.INPUT_ACTION_BTTL_ATTACK.get()) {
        final int now = tickCount_800bb0fc;
        resolveArcherAddition(now >= activeArcherWindowStartTick && now <= activeArcherTargetTick);
        return;
      }

      if(event.action == LodMod.INPUT_ACTION_BTTL_COUNTER.get()) {
        resolveArcherAddition(false);
        return;
      }
    }

    if(event.action == LodMod.INPUT_ACTION_BTTL_COUNTER.get()) {
      lastParryTick = tickCount_800bb0fc;
    }
  }

  @EventListener
  public static void playerAttackPrepared(final PlayerAttackPreparedEvent event) {
    final PlayerBattleEntity player = event.player;
    if(player.isDragoon() || !player.character.isArcher() || player.character.selectedAddition_19 == null) {
      return;
    }

    final var entry = REGISTRIES.additions.getEntry(player.character.selectedAddition_19);
    if(!entry.isValid() || !(entry.get() instanceof final ArcherAddition addition)) {
      return;
    }

    clearArcherAdditionCue();

    activeArcherPlayer = player;
    activeArcherAddition = player.character.selectedAddition_19;
    activeArcherCueStartTick = tickCount_800bb0fc;

    final int targetFrame = java.lang.Math.max(18, event.waitTicks - addition.getReleaseLeadTicks());
    activeArcherTargetTick = activeArcherCueStartTick + targetFrame;
    activeArcherWindowStartTick = activeArcherTargetTick - addition.getSuccessFrames() + 1;
    activeArcherResolved = false;
    activeArcherSuccess = false;
    activeArcherAttackResolved = false;
    activeArcherXpAwarded = false;

    activeArcherReticle = new AdditionOverlaysEffect44(targetFrame, addition.getSuccessFrames(), false, true);
    activeArcherOverlay = SEffe.allocateEffectManager("GameplayOverhaulArcherAddition", null, activeArcherReticle);
  }

  @EventListener
  public static void incomingAttackCue(final IncomingAttackCueEvent event) {
    if(!(event.attacker instanceof MonsterBattleEntity)
      || !(event.defender instanceof PlayerBattleEntity)) {
      return;
    }

    // Enemy melee scripts often hit the normal hit-check only a few ticks
    // before damage. If an earlier movement cue already started this exact
    // attack, the hit-check is only confirmation and must not restart the
    // spiral or camera.
    if(activeCueAttacker == event.attacker
      && activeCueDefender == event.defender
      && activeCueAttackType == event.attackType) {
      return;
    }

    finishActiveParryOverlay();

    activeCueAttacker = event.attacker;
    activeCueDefender = event.defender;
    activeCueAttackType = event.attackType;
    activeCueStartTick = tickCount_800bb0fc;
    activeCueKey = attackCueKey(event.attacker, event.attackType);
    lastParryTick = Integer.MIN_VALUE;

    final int expectedImpactTicks = PARRY_CUE_TIMINGS.getOrDefault(
      activeCueKey,
      event.suggestedImpactTicks > 0 ? event.suggestedImpactTicks : DEFAULT_PARRY_CUE_TICKS
    );
    activeParryReticle = new AdditionOverlaysEffect44(
      expectedImpactTicks,
      PARRY_WINDOW_TICKS,
      true
    );
    activeParryOverlay = SEffe.allocateEffectManager(
      "GameplayOverhaulParryTiming",
      null,
      activeParryReticle
    );

    focusParryCamera(event.attacker, expectedImpactTicks);
  }

  @EventListener
  public static void battleEntityTurn(final BattleEntityTurnEvent<?> event) {
    if(parryCameraActive && activeCueAttacker == null) {
      restoreParryCamera();
    }

    if(activeArcherPlayer != null && activeArcherAttackResolved) {
      clearArcherAdditionState();
    }
  }

  @EventListener
  public static void battleIntroCamera(final BattleIntroCameraEvent event) {
    // Camera scripts 0-31 are the standard battle-intro showcase set. Higher
    // indices are encounter-specific scripted openings. Explicit intro waits
    // likewise indicate bespoke staging (for example the Divine Dragon).
    event.skipStandardIntro =
      event.encounter.introWaitTicks == 0
        && event.encounter.playerOpeningCamera < 32
        && event.encounter.monsterOpeningCamera < 32;
  }

  @EventListener
  public static void attack(final AttackEvent event) {
    if(event.attacker instanceof MonsterBattleEntity && event.defender instanceof PlayerBattleEntity) {
      final int now = tickCount_800bb0fc;
      final boolean cueMatches = finishParryCue(event.attacker, event.defender, event.attackType, now);
      final int parryAge = now - lastParryTick;

      if(cueMatches && parryAge >= 0 && parryAge <= PARRY_WINDOW_TICKS) {
        event.damage = 0;
        lastParryTick = Integer.MIN_VALUE;
        markNegatedAttack(event.defender);
        playParryReaction((PlayerBattleEntity)event.defender);
        LOGGER.info("[Gameplay Overhaul] PARRY");
        return;
      }

      lastParryTick = Integer.MIN_VALUE;
    }

    if(!(event.attacker instanceof final PlayerBattleEntity player)
      || event.attackType != AttackType.PHYSICAL
      || player.isDragoon()) {
      return;
    }

    if(player.character.isArcher()) {
      if(isActiveArcherAddition(player)) {
        if(!activeArcherResolved) {
          resolveArcherAddition(false);
        }

        activeArcherAttackResolved = true;

        if(activeArcherSuccess) {
          final ArcherAddition addition = getActiveArcherAddition();
          final CharacterAdditionInfo info = player.character.getAdditionInfo(activeArcherAddition);
          if(addition != null && info != null) {
            event.damage = java.lang.Math.max(1, event.damage * addition.getDamage(player.character, info) / 100);
            addElementalBonus(event, player.getElement(), isFinal(addition) ? 25 : 10);
            awardArcherAdditionXp(player, addition, info);
          }
        }
      }
      return;
    }

    if(player.addition == null || (battleState_8006e398.additionState_324 & 0x20) == 0) {
      return;
    }

    final int percent = isFinal(player.addition) ? 25 : 10;
    addElementalBonus(event, player.getElement(), percent);
  }

  @EventListener
  public static void specialEffect(final AttackSpecialEffectEvent event) {
    if(event.defender == negatedDefender && tickCount_800bb0fc == negatedAttackTick) {
      event.effect = -1;
      negatedDefender = null;
      negatedAttackTick = Integer.MIN_VALUE;
      return;
    }

    if(!(event.attacker instanceof final PlayerBattleEntity player)
      || event.attackType != AttackType.PHYSICAL
      || player.isDragoon()) {
      return;
    }

    if(player.character.isArcher()) {
      if(isActiveArcherAddition(player) && activeArcherSuccess) {
        final RegistryId id = activeArcherAddition;
        if(lastMomentumTick != tickCount_800bb0fc
          || lastMomentumPlayer != player
          || !id.equals(lastMomentumAddition)) {
          lastMomentumTick = tickCount_800bb0fc;
          lastMomentumPlayer = player;
          lastMomentumAddition = id;
          applyArcherMomentum(player, event.defender, id, event);
        }
      }
      return;
    }

    if(player.addition == null
      || (battleState_8006e398.additionState_324 & 0x20) == 0) {
      return;
    }

    final RegistryId id = player.addition.getRegistryId();
    if(lastMomentumTick == tickCount_800bb0fc
      && lastMomentumPlayer == player
      && id.equals(lastMomentumAddition)) {
      return;
    }

    lastMomentumTick = tickCount_800bb0fc;
    lastMomentumPlayer = player;
    lastMomentumAddition = id;
    applyAdditionMomentum(player, event.defender, id, event);
  }

  @EventListener
  public static void archerSp(final ArcherSpEvent event) {
    if(!isActiveArcherAddition(event.bent) || !activeArcherSuccess) {
      return;
    }

    final ArcherAddition addition = getActiveArcherAddition();
    final CharacterAdditionInfo info = event.bent.character.getAdditionInfo(activeArcherAddition);
    if(addition != null && info != null) {
      event.sp += addition.getSp(event.bent.character, info);
    }
  }

  @EventListener
  public static void describeAddition(final AdditionDescriptionEvent event) {
    final RegistryId id = event.addition.getRegistryId();
    final String elementBonus = isFinal(event.addition) ? "Element +25%" : "Element +10%";

    if(isAddition(id, LodAdditions.QUICK_DRAW.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +15% (2T)";
    } else if(isAddition(id, LodAdditions.PINPOINT.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +10% (2T)";
    } else if(isAddition(id, LodAdditions.PIERCING_ARROW.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -20% (3T)";
    } else if(isAddition(id, LodAdditions.SPIRIT_SHOT.getId())) {
      event.description = "Complete: " + elementBonus + ", Heal 10% HP";
    } else if(isAddition(id, LodAdditions.MOONSHOT.getId())) {
      event.description = "Complete: " + elementBonus + ", Stun";
    } else if(isAddition(id, LodAdditions.DOUBLE_SLASH.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +10% (2T)";
    } else if(isAddition(id, LodAdditions.VOLCANO.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -15% (3T)";
    } else if(isAddition(id, LodAdditions.BURNING_RUSH.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +20% (2T)";
    } else if(isAddition(id, LodAdditions.CRUSH_DANCE.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy ATK -15% (3T)";
    } else if(isAddition(id, LodAdditions.MADNESS_HERO.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +30% (3T)";
    } else if(isAddition(id, LodAdditions.MOON_STRIKE.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -25% (2T)";
    } else if(isAddition(id, LodAdditions.BLAZING_DYNAMO.getId())) {
      event.description = "Complete: " + elementBonus + ", Stun";

    } else if(isAddition(id, LodAdditions.HARPOON.getId(), LodAdditions.ALBERT_HARPOON.getId())) {
      event.description = "Complete: " + elementBonus + ", Defense +15% (2T)";
    } else if(isAddition(id, LodAdditions.SPINNING_CANE.getId(), LodAdditions.ALBERT_SPINNING_CANE.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy ATK -15% (3T)";
    } else if(isAddition(id, LodAdditions.ROD_TYPHOON.getId(), LodAdditions.ALBERT_ROD_TYPHOON.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +15% (2T)";
    } else if(isAddition(id, LodAdditions.GUST_OF_WIND_DANCE.getId(), LodAdditions.ALBERT_GUST_OF_WIND_DANCE.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +25% (3T)";
    } else if(isAddition(id, LodAdditions.FLOWER_STORM.getId(), LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      event.description = "Complete: " + elementBonus + ", Defense +50% (3T)";

    } else if(isAddition(id, LodAdditions.WHIP_SMACK.getId())) {
      event.description = "Complete: " + elementBonus + ", Heal 8% HP";
    } else if(isAddition(id, LodAdditions.MORE_MORE.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +20% (3T)";
    } else if(isAddition(id, LodAdditions.HARD_BLADE.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -25% (3T)";
    } else if(isAddition(id, LodAdditions.DEMONS_DANCE.getId())) {
      event.description = "Complete: " + elementBonus + ", Fear";

    } else if(isAddition(id, LodAdditions.DOUBLE_PUNCH.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +15% (2T)";
    } else if(isAddition(id, LodAdditions.FERRY_OF_STYX.getId())) {
      event.description = "Complete: " + elementBonus + ", Heal 10% HP";
    } else if(isAddition(id, LodAdditions.SUMMON_4_GODS.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +15% (3T)";
    } else if(isAddition(id, LodAdditions.FIVE_RING_SHATTERING.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -20% (3T)";
    } else if(isAddition(id, LodAdditions.HEX_HAMMER.getId())) {
      event.description = "Complete: " + elementBonus + ", Stun";
    } else if(isAddition(id, LodAdditions.OMNI_SWEEP.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +50% (3T)";

    } else if(isAddition(id, LodAdditions.DOUBLE_SMACK.getId())) {
      event.description = "Complete: " + elementBonus + ", Heal 8% HP";
    } else if(isAddition(id, LodAdditions.HAMMER_SPIN.getId())) {
      event.description = "Complete: " + elementBonus + ", Speed +20% (3T)";
    } else if(isAddition(id, LodAdditions.COOL_BOOGIE.getId())) {
      event.description = "Complete: " + elementBonus + ", +25 SP";
    } else if(isAddition(id, LodAdditions.CATS_CRADLE.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy Speed -25% (3T)";
    } else if(isAddition(id, LodAdditions.PERKY_STEP.getId())) {
      event.description = "Complete: " + elementBonus + ", Stun";

    } else if(isAddition(id, LodAdditions.PURSUIT.getId())) {
      event.description = "Complete: " + elementBonus + ", Enemy DEF -15% (3T)";
    } else if(isAddition(id, LodAdditions.INFERNO.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +25% (3T)";
    } else if(isAddition(id, LodAdditions.BONE_CRUSH.getId())) {
      event.description = "Complete: " + elementBonus + ", Power +50% (3T)";
    } else {
      event.description = "Complete: " + elementBonus;
    }
  }

  @EventListener
  public static void battleEffectDisplay(final BattleEffectDisplayEvent event) {
    appendMomentumStatEffect(
      event,
      event.entity.stats.getStat(LodMod.ATTACK_STAT.get()),
      "ATK",
      "double_slash_attack",
      "crush_dance_attack_down",
      "spinning_cane_attack_down",
      "rod_typhoon_attack",
      "summon_4_gods_attack",
      "inferno_attack",
      "bone_crush_attack",
      "pinpoint_attack"
    );
    appendMomentumStatEffect(
      event,
      event.entity.stats.getStat(LodMod.DEFENSE_STAT.get()),
      "DEF",
      "volcano_defense_down",
      "moon_strike_defense_down",
      "harpoon_defense",
      "flower_storm_defense",
      "hard_blade_defense_down",
      "five_ring_defense_down",
      "pursuit_defense_down",
      "piercing_arrow_defense_down"
    );
    appendMomentumStatEffect(
      event,
      event.entity.stats.getStat(LodMod.SPEED_STAT.get()),
      "SPD",
      "burning_rush_speed",
      "madness_hero_speed",
      "gust_speed",
      "more_more_speed",
      "double_punch_speed",
      "omni_sweep_speed",
      "hammer_spin_speed",
      "cats_cradle_slow",
      "quick_draw_speed"
    );
  }

  @EventListener
  public static void dragoonAdditionCompleted(final DragoonAdditionCompletedEvent event) {
    if(event.successfulCircles <= 0 || event.charId < 0 || event.charId >= gameState_800babc8.charData_32c.size()) {
      return;
    }

    final var character = gameState_800babc8.charData_32c.get(event.charId);
    if(character.selectedAddition_19 == null) {
      return;
    }

    final CharacterAdditionInfo info = character.getAdditionInfo(character.selectedAddition_19);
    if(info != null) {
      info.xp += event.successfulCircles;
      LOGGER.info(
        "[Gameplay Overhaul] Dragoon circles granted %d addition XP to %s",
        event.successfulCircles,
        character.selectedAddition_19
      );
    }
  }

  private static boolean isActiveArcherAddition(final PlayerBattleEntity player) {
    return activeArcherPlayer == player
      && activeArcherAddition != null
      && activeArcherAddition.equals(player.character.selectedAddition_19);
  }

  private static ArcherAddition getActiveArcherAddition() {
    if(activeArcherAddition == null) {
      return null;
    }

    final var entry = REGISTRIES.additions.getEntry(activeArcherAddition);
    return entry.isValid() && entry.get() instanceof final ArcherAddition addition ? addition : null;
  }

  private static void resolveArcherAddition(final boolean success) {
    if(activeArcherResolved) {
      return;
    }

    activeArcherResolved = true;
    activeArcherSuccess = success;

    if(activeArcherReticle != null) {
      activeArcherReticle.completeVisualOnly(success);
    }

    activeArcherOverlay = null;
    activeArcherReticle = null;
  }

  private static void clearArcherAdditionCue() {
    if(activeArcherOverlay != null) {
      activeArcherOverlay.deallocateWithChildren();
    }
    activeArcherOverlay = null;
    activeArcherReticle = null;
  }

  private static void clearArcherAdditionState() {
    clearArcherAdditionCue();
    activeArcherPlayer = null;
    activeArcherAddition = null;
    activeArcherCueStartTick = Integer.MIN_VALUE;
    activeArcherWindowStartTick = Integer.MIN_VALUE;
    activeArcherTargetTick = Integer.MIN_VALUE;
    activeArcherResolved = false;
    activeArcherSuccess = false;
    activeArcherAttackResolved = false;
    activeArcherXpAwarded = false;
  }

  private static void awardArcherAdditionXp(final PlayerBattleEntity player, final ArcherAddition addition, final CharacterAdditionInfo info) {
    if(activeArcherXpAwarded) {
      return;
    }

    activeArcherXpAwarded = true;
    info.xp++;
    while(info.level < addition.getMaxLevel(player.character, info)
      && info.xp >= addition.getXpToNextLevel(player.character, info)) {
      info.level++;
    }
    player.character.template.checkUnlocks(player.character, null);
  }

  private static String attackCueKey(final BattleEntity27c attacker, final AttackType attackType) {
    final int animationIndex =
      attacker.currentAnimIndex_270 >= 0
        ? attacker.currentAnimIndex_270
        : attacker.loadingAnimIndex_26e;
    return attacker.charId_272 + ":" + animationIndex + ":" + attackType.name();
  }

  private static boolean finishParryCue(
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType,
    final int impactTick
  ) {
    if(activeCueAttacker != attacker
      || activeCueDefender != defender
      || activeCueAttackType != attackType) {
      return false;
    }

    final int measuredTicks = java.lang.Math.max(1, impactTick - activeCueStartTick);
    if(activeCueKey != null) {
      PARRY_CUE_TIMINGS.merge(
        activeCueKey,
        measuredTicks,
        (oldValue, newValue) -> java.lang.Math.max(
          PARRY_WINDOW_TICKS + 1,
          java.lang.Math.round(oldValue * 0.75f + newValue * 0.25f)
        )
      );
    }

    if(activeParryReticle != null) {
      final int parryAge = impactTick - lastParryTick;
      activeParryReticle.completeVisualOnly(parryAge >= 0 && parryAge <= PARRY_WINDOW_TICKS);
    }

    // The effect owns its two-frame success/failure flash from here; clear our
    // bookkeeping so the next attack never tries to deallocate an old state.
    activeParryOverlay = null;
    activeParryReticle = null;
    activeCueAttacker = null;
    activeCueDefender = null;
    activeCueAttackType = null;
    activeCueStartTick = Integer.MIN_VALUE;
    activeCueKey = null;
    return true;
  }

  private static void finishActiveParryOverlay() {
    if(activeParryOverlay != null) {
      activeParryOverlay.deallocateWithChildren();
      activeParryOverlay = null;
    }
    activeParryReticle = null;
    restoreParryCamera();
  }

  private static void focusParryCamera(final BattleEntity27c attacker, final int expectedImpactTicks) {
    if(!(currentEngineState_8004dd04 instanceof final Battle battle)
      || !(activeCueDefender instanceof final PlayerBattleEntity defender)) {
      return;
    }

    final var camera = battle.camera_800c67f0;
    savedCameraViewpoint.set(camera.rview2_00.viewpoint_00);
    savedCameraRefpoint.set(camera.rview2_00.refpoint_0c);
    parryCameraActive = true;

    // Keep the current camera side and orientation. Counter readability is more
    // important than creating a dramatic orbit: gently pan the shot toward the
    // incoming attack and dolly straight inward along the existing camera ray.
    final Vector3f attackerPos = new Vector3f(attacker.getPosition())
      .add(0.0f, -attacker.middleOffsetY_86 * 50.0f, 0.0f);
    final Vector3f defenderPos = new Vector3f(defender.getPosition())
      .add(0.0f, -defender.middleOffsetY_86 * 50.0f, 0.0f);
    final Vector3f attackDirection = new Vector3f(defenderPos).sub(attackerPos);
    final float attackDistance = attackDirection.length();

    if(attackDistance > 0.001f) {
      attackDirection.div(attackDistance);
    } else {
      attackDirection.set(0.0f, 0.0f, 1.0f);
    }

    // A small framing shift prevents the shot feeling pinned to the centre,
    // without dragging the camera all the way around the attacker.
    final float refpointTravel = java.lang.Math.min(320.0f, attackDistance * 0.14f);
    final Vector3f desiredRefpoint = new Vector3f(savedCameraRefpoint)
      .add(new Vector3f(attackDirection).mul(refpointTravel));

    final Vector3f cameraRay = new Vector3f(savedCameraViewpoint).sub(savedCameraRefpoint);
    final float cameraDistance = cameraRay.length();

    if(cameraDistance <= 0.001f) {
      return;
    }

    cameraRay.div(cameraDistance);
    final float desiredDistance = java.lang.Math.max(1400.0f, cameraDistance - 700.0f);
    final Vector3f desiredViewpoint = new Vector3f(desiredRefpoint)
      .add(cameraRay.mul(desiredDistance));

    final int cameraTicks = java.lang.Math.max(9, java.lang.Math.min(18, expectedImpactTicks));

    // Mode 0 is world-space translation: no angle interpolation, no orbit.
    // Smoothing mode 1 eases into the final framing instead of accelerating
    // through it.
    camera.cameraAccelerateRefpoint(
      0,
      desiredRefpoint.x,
      desiredRefpoint.y,
      desiredRefpoint.z,
      cameraTicks,
      1,
      0.0f,
      0,
      null
    );
    camera.cameraAccelerateViewpoint(
      0,
      desiredViewpoint.x,
      desiredViewpoint.y,
      desiredViewpoint.z,
      cameraTicks,
      1,
      0.0f,
      0,
      null
    );
  }

  private static void restoreParryCamera() {
    if(!parryCameraActive || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      parryCameraActive = false;
      return;
    }

    final var camera = battle.camera_800c67f0;

    // Ease back to the pre-attack framing as well, avoiding a linear snap-out.
    camera.cameraAccelerateViewpoint(
      0,
      savedCameraViewpoint.x,
      savedCameraViewpoint.y,
      savedCameraViewpoint.z,
      10,
      1,
      0.0f,
      0,
      null
    );
    camera.cameraAccelerateRefpoint(
      0,
      savedCameraRefpoint.x,
      savedCameraRefpoint.y,
      savedCameraRefpoint.z,
      10,
      1,
      0.0f,
      0,
      null
    );
    parryCameraActive = false;
  }

  private static void applyArcherMomentum(
    final PlayerBattleEntity player,
    final BattleEntity27c defender,
    final RegistryId id,
    final AttackSpecialEffectEvent event
  ) {
    String notification = null;

    if(isAddition(id, LodAdditions.QUICK_DRAW.getId())) {
      setSpeed(player, "quick_draw_speed", 15, selfEffectTurns(2));
      notification = "+15% SPD (2T)";
    } else if(isAddition(id, LodAdditions.PINPOINT.getId())) {
      setAttack(player, "pinpoint_attack", 10, selfEffectTurns(2));
      notification = "+10% ATK (2T)";
    } else if(isAddition(id, LodAdditions.PIERCING_ARROW.getId())) {
      setDefense(defender, "piercing_arrow_defense_down", -20, 3);
      notification = "ENEMY DEF -20% (3T)";
    } else if(isAddition(id, LodAdditions.SPIRIT_SHOT.getId())) {
      healPercent(player, 10);
      notification = "+10% HP";
    } else if(isAddition(id, LodAdditions.MOONSHOT.getId()) && forceStatusIfAllowed(event, 0x10)) {
      notification = "+STUN";
    }

    if(notification != null && currentEngineState_8004dd04 instanceof final Battle battle) {
      battle.hud.showEffectNotification(notification);
    }
  }

  private static void applyAdditionMomentum(
    final PlayerBattleEntity player,
    final BattleEntity27c defender,
    final RegistryId id,
    final AttackSpecialEffectEvent event
  ) {
    String notification = null;

    if(isAddition(id, LodAdditions.DOUBLE_SLASH.getId())) {
      setAttack(player, "double_slash_attack", 10, selfEffectTurns(2));
      notification = "+10% ATK (2T)";
    } else if(isAddition(id, LodAdditions.VOLCANO.getId())) {
      setDefense(defender, "volcano_defense_down", -15, 3);
      notification = "ENEMY DEF -15% (3T)";
    } else if(isAddition(id, LodAdditions.BURNING_RUSH.getId())) {
      setSpeed(player, "burning_rush_speed", 20, selfEffectTurns(2));
      notification = "+20% SPD (2T)";
    } else if(isAddition(id, LodAdditions.CRUSH_DANCE.getId())) {
      setAttack(defender, "crush_dance_attack_down", -15, 3);
      notification = "ENEMY ATK -15% (3T)";
    } else if(isAddition(id, LodAdditions.MADNESS_HERO.getId())) {
      setSpeed(player, "madness_hero_speed", 30, selfEffectTurns(3));
      notification = "+30% SPD (3T)";
    } else if(isAddition(id, LodAdditions.MOON_STRIKE.getId())) {
      setDefense(defender, "moon_strike_defense_down", -25, 2);
      notification = "ENEMY DEF -25% (2T)";
    } else if(isAddition(id, LodAdditions.BLAZING_DYNAMO.getId())) {
      if(forceStatusIfAllowed(event, 0x10)) {
        notification = "+STUN";
      }

    } else if(isAddition(id, LodAdditions.HARPOON.getId(), LodAdditions.ALBERT_HARPOON.getId())) {
      setDefense(player, "harpoon_defense", 15, selfEffectTurns(2));
      notification = "+15% DEF (2T)";
    } else if(isAddition(id, LodAdditions.SPINNING_CANE.getId(), LodAdditions.ALBERT_SPINNING_CANE.getId())) {
      setAttack(defender, "spinning_cane_attack_down", -15, 3);
      notification = "ENEMY ATK -15% (3T)";
    } else if(isAddition(id, LodAdditions.ROD_TYPHOON.getId(), LodAdditions.ALBERT_ROD_TYPHOON.getId())) {
      setAttack(player, "rod_typhoon_attack", 15, selfEffectTurns(2));
      notification = "+15% ATK (2T)";
    } else if(isAddition(id, LodAdditions.GUST_OF_WIND_DANCE.getId(), LodAdditions.ALBERT_GUST_OF_WIND_DANCE.getId())) {
      setSpeed(player, "gust_speed", 25, selfEffectTurns(3));
      notification = "+25% SPD (3T)";
    } else if(isAddition(id, LodAdditions.FLOWER_STORM.getId(), LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      setDefense(player, "flower_storm_defense", 50, selfEffectTurns(3));
      notification = "+50% DEF (3T)";

    } else if(isAddition(id, LodAdditions.WHIP_SMACK.getId())) {
      healPercent(player, 8);
      notification = "+8% HP";
    } else if(isAddition(id, LodAdditions.MORE_MORE.getId())) {
      setSpeed(player, "more_more_speed", 20, selfEffectTurns(3));
      notification = "+20% SPD (3T)";
    } else if(isAddition(id, LodAdditions.HARD_BLADE.getId())) {
      setDefense(defender, "hard_blade_defense_down", -25, 3);
      notification = "ENEMY DEF -25% (3T)";
    } else if(isAddition(id, LodAdditions.DEMONS_DANCE.getId())) {
      if(forceStatusIfAllowed(event, 0x08)) {
        notification = "+FEAR";
      }

    } else if(isAddition(id, LodAdditions.DOUBLE_PUNCH.getId())) {
      setSpeed(player, "double_punch_speed", 15, selfEffectTurns(2));
      notification = "+15% SPD (2T)";
    } else if(isAddition(id, LodAdditions.FERRY_OF_STYX.getId())) {
      healPercent(player, 10);
      notification = "+10% HP";
    } else if(isAddition(id, LodAdditions.SUMMON_4_GODS.getId())) {
      setAttack(player, "summon_4_gods_attack", 15, selfEffectTurns(3));
      notification = "+15% ATK (3T)";
    } else if(isAddition(id, LodAdditions.FIVE_RING_SHATTERING.getId())) {
      setDefense(defender, "five_ring_defense_down", -20, 3);
      notification = "ENEMY DEF -20% (3T)";
    } else if(isAddition(id, LodAdditions.HEX_HAMMER.getId())) {
      if(forceStatusIfAllowed(event, 0x10)) {
        notification = "+STUN";
      }
    } else if(isAddition(id, LodAdditions.OMNI_SWEEP.getId())) {
      setSpeed(player, "omni_sweep_speed", 50, selfEffectTurns(3));
      notification = "+50% SPD (3T)";

    } else if(isAddition(id, LodAdditions.DOUBLE_SMACK.getId())) {
      healPercent(player, 8);
      notification = "+8% HP";
    } else if(isAddition(id, LodAdditions.HAMMER_SPIN.getId())) {
      setSpeed(player, "hammer_spin_speed", 20, selfEffectTurns(3));
      notification = "+20% SPD (3T)";
    } else if(isAddition(id, LodAdditions.COOL_BOOGIE.getId())) {
      grantSp(player, 25);
      notification = "+25 SP";
    } else if(isAddition(id, LodAdditions.CATS_CRADLE.getId())) {
      setSpeed(defender, "cats_cradle_slow", -25, 3);
      notification = "ENEMY SPD -25% (3T)";
    } else if(isAddition(id, LodAdditions.PERKY_STEP.getId())) {
      if(forceStatusIfAllowed(event, 0x10)) {
        notification = "+STUN";
      }

    } else if(isAddition(id, LodAdditions.PURSUIT.getId())) {
      setDefense(defender, "pursuit_defense_down", -15, 3);
      notification = "ENEMY DEF -15% (3T)";
    } else if(isAddition(id, LodAdditions.INFERNO.getId())) {
      setAttack(player, "inferno_attack", 25, selfEffectTurns(3));
      notification = "+25% ATK (3T)";
    } else if(isAddition(id, LodAdditions.BONE_CRUSH.getId())) {
      setAttack(player, "bone_crush_attack", 50, selfEffectTurns(3));
      notification = "+50% ATK (3T)";
    }

    if(notification != null && currentEngineState_8004dd04 instanceof final Battle battle) {
      battle.hud.showEffectNotification(notification);
    }
  }

  private static boolean isAddition(final RegistryId id, final RegistryId... candidates) {
    for(final RegistryId candidate : candidates) {
      if(id.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  private static int selfEffectTurns(final int displayedTurns) {
    // Addition Momentum is granted late in the attacker's current turn. Stat
    // mods tick when that turn finishes, so keep one extra internal tick to
    // deliver the displayed number of future turns.
    return displayedTurns + 1;
  }

  private static void setAttack(
    final BattleEntity27c entity,
    final String key,
    final int percent,
    final int turns
  ) {
    entity.stats.getStat(LodMod.ATTACK_STAT.get()).addMod(
      new RegistryId(MOD_ID, key),
      LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(percent).turns(turns))
    );
  }

  private static void setDefense(
    final BattleEntity27c entity,
    final String key,
    final int percent,
    final int turns
  ) {
    entity.stats.getStat(LodMod.DEFENSE_STAT.get()).addMod(
      new RegistryId(MOD_ID, key),
      LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(percent).turns(turns))
    );
  }

  private static void setSpeed(
    final BattleEntity27c entity,
    final String key,
    final int percent,
    final int turns
  ) {
    entity.stats.getStat(LodMod.SPEED_STAT.get()).addMod(
      new RegistryId(MOD_ID, key),
      LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(percent).turns(turns))
    );
  }

  private static void grantSp(final PlayerBattleEntity player, final int amount) {
    final VitalsStat sp = player.stats.getStat(LodMod.SP_STAT.get());
    sp.setCurrent(sp.getCurrent() + amount);
    spGained_800bc950.mergeInt(player.character, amount, Integer::sum);
  }


  private static void appendMomentumStatEffect(
    final BattleEffectDisplayEvent event,
    final UnaryStat stat,
    final String label,
    final String... keys
  ) {
    int amount = 0;
    int turns = 0;

    for(final String key : keys) {
      final RegistryId id = new RegistryId(MOD_ID, key);
      if(stat.hasMod(id)) {
        final UnaryStatMod mod = stat.getMod(id);
        amount += mod.getAmount();
        turns = java.lang.Math.max(turns, mod.getTurns());
      }
    }

    if(amount != 0) {
      event.effects.add(label + (amount > 0 ? "+" : "") + amount + "%(" + turns + "T)");
    }
  }

  private static boolean isFinal(final Addition addition) {
    return FINAL_ADDITIONS.contains(addition.getRegistryId());
  }

  private static void markNegatedAttack(final BattleEntity27c defender) {
    negatedDefender = defender;
    negatedAttackTick = tickCount_800bb0fc;
  }

  private static void playParryReaction(final PlayerBattleEntity defender) {
    if(!(currentEngineState_8004dd04 instanceof final Battle battle)) {
      return;
    }

    // Retail guard uses animation 5 for normal-form characters. Dragoons skip
    // that animation in the retail damage script, so mirror that behavior here.
    if(!defender.isDragoon()) {
      playGuardAnimation(battle, defender);
    }

    spawnParryGuardEffect(defender);
  }

  private static void playGuardAnimation(final Battle battle, final PlayerBattleEntity defender) {
    if(defender.combatant_144 == null || defender.combatant_144.assets_14[GUARD_ANIMATION_INDEX] == null) {
      LOGGER.warn("[Gameplay Overhaul] Guard animation is unavailable for %s", defender);
      return;
    }

    if(!defender.combatant_144.isAssetLoaded(GUARD_ANIMATION_INDEX)) {
      battle.FUN_800c9e10(defender.combatant_144, GUARD_ANIMATION_INDEX);
    }

    if(!defender.combatant_144.isAssetLoaded(GUARD_ANIMATION_INDEX)) {
      LOGGER.warn("[Gameplay Overhaul] Guard animation did not load for %s", defender);
      return;
    }

    final int previousAnimation = defender.loadingAnimIndex_26e;
    if(previousAnimation >= 0
      && previousAnimation < defender.combatant_144.assets_14.length
      && defender.combatant_144.assets_14[previousAnimation] != null) {
      Battle.FUN_800ca194(defender.combatant_144.assets_14[previousAnimation]);
    }

    defender.getState().clearFlag(BattleEntity27c.FLAG_ANIMATE_ONCE);
    battle.loadAnimationAssetIntoModel(defender.model_148, defender.combatant_144, GUARD_ANIMATION_INDEX);
    defender.model_148.animationState_9c = 1;
    defender.loadingAnimIndex_26e = GUARD_ANIMATION_INDEX;
    defender.currentAnimIndex_270 = -1;
    defender.getState().setFlag(BattleEntity27c.FLAG_ANIMATE_ONCE);
  }

  private static void spawnParryGuardEffect(final PlayerBattleEntity defender) {
    final var effectState = SEffe.allocateEffectManager(
      "GameplayOverhaulParryGuard",
      null,
      new GuardEffect06()
    );
    final var manager = effectState.innerStruct_00;

    // Same family of shield effect used by retail Guard, but with the magical
    // shield's blue palette so a successful parry reads differently at a glance.
    manager.params_10.colour_1c.set(0x19, 0x82, 0xfd);
    manager.params_10.scale_16.set(-0.375f, 0.75f, 0.75f);
    manager.params_10.trans_04.set(0.0f, -768.0f, -512.0f);
    defender.getRelativePosition(manager.params_10.trans_04);

    final GenericAttachment1c lifespan = manager.addAttachment(
      0,
      0,
      SEffe::tickLifespanAttachment,
      new GenericAttachment1c()
    );
    lifespan.ticksRemaining_1a = PARRY_GUARD_EFFECT_TICKS;
  }

  private static void addElementalBonus(final AttackEvent event, final Element element, final int percent) {
    int bonus = Math.max(1, event.damage * percent / 100);
    bonus = element.adjustAttackingElementalDamage(AttackType.PHYSICAL, bonus, event.defender.getElement());
    bonus = event.defender.getElement().adjustDefendingElementalDamage(AttackType.PHYSICAL, bonus, element);
    bonus = event.defender.applyElementalResistanceAndImmunity(bonus, element);
    event.damage += Math.max(0, bonus);
  }

  private static void healPercent(final PlayerBattleEntity player, final int percent) {
    final VitalsStat hp = player.stats.getStat(LodMod.HP_STAT.get());
    hp.setCurrent(Math.min(hp.getMax(), hp.getCurrent() + Math.max(1, hp.getMax() * percent / 100)));
  }

  private static boolean forceStatusIfAllowed(final AttackSpecialEffectEvent event, final int status) {
    if((event.defender.specialEffectFlag_14 & 0x80) != 0) {
      return false;
    }

    if((event.defender.equipmentStatusResist_24 & status) != 0) {
      return false;
    }

    if(event.defender instanceof final MonsterBattleEntity monster && (monster.monsterStatusResistFlag_76 & status) != 0) {
      return false;
    }

    event.effect = status;
    return true;
  }
}

package legend.gameplayoverhaul;

import legend.game.additions.Addition;
import legend.game.characters.CharacterAdditionInfo;
import legend.game.characters.Element;
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
import legend.game.modding.events.battle.BattleIntroCameraEvent;
import legend.game.modding.events.battle.IncomingAttackCueEvent;
import legend.game.modding.events.characters.AdditionDescriptionEvent;
import legend.game.modding.events.characters.DragoonAdditionCompletedEvent;
import legend.game.modding.events.input.InputPressedEvent;
import legend.lodmod.LodAdditions;
import legend.lodmod.LodMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.legendofdragoon.modloader.registries.RegistryId;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.tickCount_800bb0fc;

@Mod(id = GameplayOverhaulMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class GameplayOverhaulMod {
  public static final String MOD_ID = "gameplay_overhaul";

  private static final Logger LOGGER = LogManager.getFormatterLogger(GameplayOverhaulMod.class);

  private static final int PARRY_WINDOW_TICKS = 4;
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

  private static final Set<RegistryId> FINAL_ADDITIONS = Set.of(
    LodAdditions.BLAZING_DYNAMO.getId(),
    LodAdditions.FLOWER_STORM.getId(),
    LodAdditions.ALBERT_FLOWER_STORM.getId(),
    LodAdditions.DEMONS_DANCE.getId(),
    LodAdditions.OMNI_SWEEP.getId(),
    LodAdditions.PERKY_STEP.getId(),
    LodAdditions.BONE_CRUSH.getId()
  );

  public GameplayOverhaulMod() { }

  @EventListener
  public static void inputPressed(final InputPressedEvent event) {
    if(!event.repeat && event.action == LodMod.INPUT_ACTION_BTTL_COUNTER.get()) {
      lastParryTick = tickCount_800bb0fc;
    }
  }

  @EventListener
  public static void incomingAttackCue(final IncomingAttackCueEvent event) {
    if(!(event.attacker instanceof MonsterBattleEntity)
      || !(event.defender instanceof PlayerBattleEntity)) {
      return;
    }

    finishActiveParryOverlay();

    activeCueAttacker = event.attacker;
    activeCueDefender = event.defender;
    activeCueAttackType = event.attackType;
    activeCueStartTick = tickCount_800bb0fc;
    activeCueKey = attackCueKey(event.attacker, event.attackType);
    lastParryTick = Integer.MIN_VALUE;

    final int expectedImpactTicks = PARRY_CUE_TIMINGS.getOrDefault(activeCueKey, DEFAULT_PARRY_CUE_TICKS);
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

    focusParryCamera((PlayerBattleEntity)event.defender);
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

    // Shana and Miranda have no retail additions. Their bow attacks receive the
    // archer-specific gameplay identity directly.
    if(player.charId_272 == 2 || player.charId_272 == 8) {
      addElementalBonus(event, player.getElement(), 20);
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

    // Archer exception: no retail addition exists, so the regular attack heals.
    if(player.charId_272 == 2 || player.charId_272 == 8) {
      healPercent(player, 10);
      return;
    }

    if(player.addition == null
      || !isFinal(player.addition)
      || (battleState_8006e398.additionState_324 & 0x20) == 0) {
      return;
    }

    final RegistryId id = player.addition.getRegistryId();

    if(id.equals(LodAdditions.BLAZING_DYNAMO.getId())) {
      forceStatusIfAllowed(event, 0x10); // Stun
    } else if(id.equals(LodAdditions.FLOWER_STORM.getId()) || id.equals(LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      player.powerDefence_b8 = 50;
      player.powerDefenceTurns_b9 = 3;
    } else if(id.equals(LodAdditions.DEMONS_DANCE.getId())) {
      forceStatusIfAllowed(event, 0x08); // Fear
    } else if(id.equals(LodAdditions.OMNI_SWEEP.getId())) {
      player.stats.getStat(LodMod.SPEED_STAT.get()).addMod(
        new RegistryId(MOD_ID, "omni_sweep_speed"),
        LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(50).turns(3))
      );
    } else if(id.equals(LodAdditions.PERKY_STEP.getId())) {
      forceStatusIfAllowed(event, 0x10); // Stun/freeze analogue in the retail status set
    } else if(id.equals(LodAdditions.BONE_CRUSH.getId())) {
      player.powerAttack_b4 = 50;
      player.powerAttackTurns_b5 = 3;
    }
  }

  @EventListener
  public static void describeAddition(final AdditionDescriptionEvent event) {
    final RegistryId id = event.addition.getRegistryId();

    if(id.equals(LodAdditions.BLAZING_DYNAMO.getId())) {
      event.description = "Complete: Fire +25%, Stun";
    } else if(id.equals(LodAdditions.FLOWER_STORM.getId()) || id.equals(LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      event.description = "Complete: Wind +25%, Defense Up";
    } else if(id.equals(LodAdditions.DEMONS_DANCE.getId())) {
      event.description = "Complete: Dark +25%, Fear";
    } else if(id.equals(LodAdditions.OMNI_SWEEP.getId())) {
      event.description = "Complete: Thunder +25%, Speed Up";
    } else if(id.equals(LodAdditions.PERKY_STEP.getId())) {
      event.description = "Complete: Water +25%, Stun";
    } else if(id.equals(LodAdditions.BONE_CRUSH.getId())) {
      event.description = "Complete: Earth +25%, Power Up";
    } else {
      event.description = "Complete: innate element +10%";
    }
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
    restoreParryCamera();
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

  private static void focusParryCamera(final PlayerBattleEntity defender) {
    if(!(currentEngineState_8004dd04 instanceof final Battle battle)) {
      return;
    }

    final var camera = battle.camera_800c67f0;
    savedCameraViewpoint.set(camera.rview2_00.viewpoint_00);
    savedCameraRefpoint.set(camera.rview2_00.refpoint_0c);
    parryCameraActive = true;

    // Match the Addition presentation idea: keep combat moving, but quickly
    // bring the camera's focus onto the character about to receive the hit.
    camera.cameraMoveRefpoint(
      6,
      0.0f,
      -defender.middleOffsetY_86 * 0.5f,
      0.0f,
      0,
      6,
      0,
      defender
    );

    // Pull the viewpoint partway toward the same target while preserving the
    // current battle-camera side. This produces the same "commit to the hit"
    // feeling as an Addition without pausing the enemy animation.
    final Vector3f target = defender.getPosition();
    final Vector3f view = new Vector3f(savedCameraViewpoint);
    view.lerp(target, 0.28f);
    camera.cameraMoveViewpoint(
      0,
      view.x,
      view.y - 180.0f,
      view.z,
      0,
      6,
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
    camera.cameraMoveViewpoint(
      0,
      savedCameraViewpoint.x,
      savedCameraViewpoint.y,
      savedCameraViewpoint.z,
      0,
      6,
      0,
      null
    );
    camera.cameraMoveRefpoint(
      0,
      savedCameraRefpoint.x,
      savedCameraRefpoint.y,
      savedCameraRefpoint.z,
      0,
      6,
      0,
      null
    );
    parryCameraActive = false;
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

  private static void forceStatusIfAllowed(final AttackSpecialEffectEvent event, final int status) {
    if((event.defender.specialEffectFlag_14 & 0x80) != 0) {
      return;
    }

    if((event.defender.equipmentStatusResist_24 & status) != 0) {
      return;
    }

    if(event.defender instanceof final MonsterBattleEntity monster && (monster.monsterStatusResistFlag_76 & status) != 0) {
      return;
    }

    event.effect = status;
  }
}

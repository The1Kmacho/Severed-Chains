package legend.gameplayoverhaul;

import legend.game.additions.Addition;
import legend.game.characters.CharacterAdditionInfo;
import legend.game.characters.Element;
import legend.game.characters.UnaryStat;
import legend.game.characters.UnaryStatMod;
import legend.game.characters.UnaryStatModConfig;
import legend.game.characters.VitalsStat;
import legend.game.combat.Battle;
import legend.game.combat.ui.BattleAction;
import legend.game.combat.ui.GatherBattleActionsEvent;
import legend.game.combat.ui.RegisterBattleActionsEvent;
import legend.game.combat.SEffe;
import legend.game.combat.bent.AttackEvent;
import legend.game.combat.bent.AttackSpecialEffectEvent;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.MonsterBattleEntity;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.effects.AdditionOverlaysEffect44;
import legend.game.combat.effects.GenericAttachment1c;
import legend.game.combat.effects.GuardEffect06;
import legend.gameplayoverhaul.effects.CombatImpactEffect;
import legend.gameplayoverhaul.effects.DragoonAuraEffect;
import legend.gameplayoverhaul.battleactions.ChangeRowBattleAction;
import legend.gameplayoverhaul.battleactions.SwitchPartyBattleAction;
import legend.gameplayoverhaul.camera.BattleCameraDirector;
import legend.gameplayoverhaul.rows.BattleRows;
import legend.game.combat.types.AttackType;
import legend.game.modding.events.battle.BattleEffectDisplayEvent;
import legend.game.modding.events.battle.ArcherSpEvent;
import legend.game.modding.events.battle.BattleEntityTurnEvent;
import legend.game.modding.events.battle.BattleEndedEvent;
import legend.game.modding.events.battle.BattleIntroCameraEvent;
import legend.game.modding.events.battle.BattleStartedEvent;
import legend.game.modding.events.battle.CombatantModelLoadedEvent;
import legend.game.modding.events.battle.PlayerAttackPreparedEvent;
import legend.game.modding.events.battle.PlayerBattleSlotChangedEvent;
import legend.game.modding.events.battle.PostBattleXpDistributionEvent;
import legend.game.modding.events.gamestate.GameLoadedEvent;
import legend.game.saves.ReadSaveDataEvent;
import legend.game.saves.WriteSaveDataEvent;
import legend.game.modding.events.battle.GuardUsedEvent;
import legend.game.modding.events.battle.IncomingAttackCueEvent;
import legend.game.modding.events.characters.AdditionDescriptionEvent;
import legend.game.modding.events.characters.DragoonAdditionCompletedEvent;
import legend.game.modding.events.input.InputPressedEvent;
import legend.game.inventory.screens.TextColour;
import legend.game.scripting.ScriptState;
import legend.lodmod.LodAdditions;
import legend.lodmod.LodMod;
import legend.lodmod.additions.ArcherAddition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.legendofdragoon.modloader.registries.Registrar;
import org.legendofdragoon.modloader.registries.RegistryDelegate;
import org.legendofdragoon.modloader.registries.RegistryId;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static legend.core.GameEngine.GTE;
import static legend.core.GameEngine.REGISTRIES;
import static legend.core.GameEngine.SCRIPTS;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Graphics.lightColourMatrix_800c3508;
import static legend.game.combat.environment.BattleCamera.UPDATE_REFPOINT;
import static legend.game.combat.environment.BattleCamera.UPDATE_VIEWPOINT;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.spGained_800bc950;
import static legend.game.Scus94491BpeSegment_800b.tickCount_800bb0fc;

@Mod(id = GameplayOverhaulMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class GameplayOverhaulMod {
  public static final String MOD_ID = "gameplay_overhaul";

  private static final Logger LOGGER = LogManager.getFormatterLogger(GameplayOverhaulMod.class);

  private static final Registrar<BattleAction, RegisterBattleActionsEvent> BATTLE_ACTION_REGISTRAR =
    new Registrar<>(REGISTRIES.battleActions, MOD_ID);
  public static final RegistryDelegate<BattleAction> SWITCH_PARTY =
    BATTLE_ACTION_REGISTRAR.register("switch_party", SwitchPartyBattleAction::new);
  public static final RegistryDelegate<BattleAction> CHANGE_ROW =
    BATTLE_ACTION_REGISTRAR.register("change_row", ChangeRowBattleAction::new);

  private static final int PARRY_WINDOW_TICKS = 4;
  private static final int GUARD_ANIMATION_INDEX = 5;
  private static final int PARRY_GUARD_EFFECT_TICKS = 12;
  private static final int BREAK_THRESHOLD = 100;
  private static final int BREAK_ADDITION_GAIN = 25;
  private static final int BREAK_FINAL_ADDITION_GAIN = 35;
  private static final int BREAK_PARRY_GAIN = 18;
  private static final int CHAIN_SP_BONUS = 5;

  private static final Map<MonsterBattleEntity, Integer> BREAK_GAUGE = new HashMap<>();
  private static final Map<Integer, RegistryId> LAST_COMPLETED_ADDITIONS = new HashMap<>();
  private static final Map<PlayerBattleEntity, ScriptState<?>> DRAGOON_AURAS = new HashMap<>();

  private static final Map<Integer, LinkedHashSet<Integer>> PARTY_SLOT_MEMBERS = new LinkedHashMap<>();
  private static final Map<Integer, Map<Integer, Integer>> PARTY_SLOT_TURNS = new LinkedHashMap<>();
  private static final Set<Integer> CURRENT_BENCH = new HashSet<>();
  private static final Set<Integer> XP_SURVIVORS = new HashSet<>();
  private static boolean partySwitched;
  private static int startingPartySlots;

  private static final Matrix3f SAVED_BATTLE_LIGHT_COLOUR = new Matrix3f();
  private static final Vector3f SAVED_BATTLE_AMBIENT = new Vector3f();
  private static boolean battleLightingPolished;

  private static final int MIN_PARRY_REACTION_TICKS = 14;
  private static final int MIN_PARRY_VISIBLE_TICKS = PARRY_WINDOW_TICKS + 2;

  private static MonsterBattleEntity activeEnemyTurnAttacker;
  private static int activeEnemyTurnStartTick = Integer.MIN_VALUE;

  private static final class ParryCue {
    private final MonsterBattleEntity attacker;
    private final PlayerBattleEntity defender;
    private final AttackType attackType;
    private final int actionStartTick;
    private int expectedImpactTick;
    private int windowStartTick;
    private boolean parryable;
    private IncomingAttackCueEvent.TimingSource timingSource;
    private ScriptState<?> overlay;
    private AdditionOverlaysEffect44 reticle;
    private int pressTick = Integer.MIN_VALUE;
    private boolean pressConsumed;

    private ParryCue(
      final MonsterBattleEntity attacker,
      final PlayerBattleEntity defender,
      final AttackType attackType,
      final int actionStartTick,
      final int expectedImpactTick,
      final boolean parryable,
      final IncomingAttackCueEvent.TimingSource timingSource
    ) {
      this.attacker = attacker;
      this.defender = defender;
      this.attackType = attackType;
      this.actionStartTick = actionStartTick;
      this.expectedImpactTick = expectedImpactTick;
      this.windowStartTick = this.expectedImpactTick - PARRY_WINDOW_TICKS + 1;
      this.parryable = parryable;
      this.timingSource = timingSource;
    }
  }
  private static final List<ParryCue> ACTIVE_PARRY_CUES = new ArrayList<>();
  private static final Map<BattleEntity27c, Integer> NEGATED_ATTACKS = new HashMap<>();
  private static ScriptState<BattleEntity27c> activeParryCameraTracker;
  private static BattleEntity27c parryCameraAttacker;
  private static PlayerBattleEntity parryCameraDefender;
  private static final Vector3f parryCameraAttackerStart = new Vector3f();
  private static final Vector3f savedCameraViewpoint = new Vector3f();
  private static final Vector3f savedCameraRefpoint = new Vector3f();
  private static final Vector3f savedCameraRay = new Vector3f();
  private static float parryCameraStartDistance;
  private static float parryCameraBaseDistance;
  private static boolean parryCameraCueResolved;
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
  public static void registerBattleActions(final RegisterBattleActionsEvent event) {
    BATTLE_ACTION_REGISTRAR.registryEvent(event);
  }

  @EventListener
  public static void gatherBattleActions(final GatherBattleActionsEvent event) {
    if(ChangeRowBattleAction.canChangeRow(event.player)) {
      event.actions.put(CHANGE_ROW.get(), 900);
    }

    if(SwitchPartyBattleAction.hasEligibleReplacement(event.battle, event.player)) {
      event.actions.put(SWITCH_PARTY.get(), 950);
    }
  }

  @EventListener
  public static void gameLoaded(final GameLoadedEvent event) {
    BattleRows.resetForLoadedGame();
  }

  @EventListener
  public static void readSaveData(final ReadSaveDataEvent event) {
    BattleRows.readSaveData(event);
  }

  @EventListener
  public static void writeSaveData(final WriteSaveDataEvent event) {
    BattleRows.writeSaveData(event);
  }

  @EventListener
  public static void inputPressed(final InputPressedEvent event) {
    if(event.repeat) {
      return;
    }

    BattleCameraDirector.onInputPressed(event.action);

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
      consumeParryInput(tickCount_800bb0fc);
    }
  }

  @EventListener
  public static void playerAttackPrepared(final PlayerAttackPreparedEvent event) {
    final PlayerBattleEntity player = event.player;
    BattleCameraDirector.onPlayerAttackPrepared(player, event.waitTicks);
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
    if(!(event.attacker instanceof final MonsterBattleEntity attacker)
      || !(event.defender instanceof final PlayerBattleEntity defender)) {
      return;
    }

    if(event.attackType != AttackType.PHYSICAL) {
      cancelParryCuesForAttacker(attacker);
      return;
    }

    // HIT_CHECK is authoritative impact validation, not a place to invent a
    // new reaction window. First-use cues must come from live animation or
    // movement timing before contact.
    if(event.suggestedImpactTicks <= 0
      || event.timingSource == IncomingAttackCueEvent.TimingSource.HIT_CHECK) {
      return;
    }

    final int now = tickCount_800bb0fc;
    final int actionStartTick =
      activeEnemyTurnAttacker == attacker && activeEnemyTurnStartTick != Integer.MIN_VALUE
        ? activeEnemyTurnStartTick
        : now;
    final int expectedImpactTick = now + event.suggestedImpactTicks;
    final int totalActionTicks = java.lang.Math.max(1, expectedImpactTick - actionStartTick);
    final boolean parryable =
      totalActionTicks >= MIN_PARRY_REACTION_TICKS
        && event.suggestedImpactTicks >= MIN_PARRY_VISIBLE_TICKS;

    final ParryCue existing = findParryCue(attacker, defender, event.attackType);
    if(existing != null) {
      refineParryCue(
        existing,
        now,
        expectedImpactTick,
        parryable,
        event.timingSource
      );
      return;
    }

    final ParryCue cue = new ParryCue(
      attacker,
      defender,
      event.attackType,
      actionStartTick,
      expectedImpactTick,
      parryable,
      event.timingSource
    );
    ACTIVE_PARRY_CUES.add(cue);
    configureParryOverlay(cue, now);
  }

  private static void refineParryCue(
    final ParryCue cue,
    final int now,
    final int expectedImpactTick,
    final boolean parryable,
    final IncomingAttackCueEvent.TimingSource timingSource
  ) {
    if(cue.pressConsumed) {
      return;
    }

    final int currentPriority = parryTimingPriority(cue.timingSource);
    final int newPriority = parryTimingPriority(timingSource);
    if(newPriority < currentPriority) {
      return;
    }

    cue.expectedImpactTick = expectedImpactTick;
    cue.windowStartTick = expectedImpactTick - PARRY_WINDOW_TICKS + 1;
    cue.parryable = parryable;
    cue.timingSource = timingSource;
    configureParryOverlay(cue, now);
  }

  private static int parryTimingPriority(final IncomingAttackCueEvent.TimingSource source) {
    return switch(source) {
      case MOVEMENT -> 2;
      case ANIMATION -> 1;
      case HIT_CHECK -> 0;
    };
  }

  private static void configureParryOverlay(final ParryCue cue, final int now) {
    if(cue.overlay != null) {
      cue.overlay.deallocateWithChildren();
      cue.overlay = null;
      cue.reticle = null;
    }

    final int remainingTicks = cue.expectedImpactTick - now;
    if(!cue.parryable || remainingTicks < MIN_PARRY_VISIBLE_TICKS) {
      return;
    }

    cue.reticle = new AdditionOverlaysEffect44(
      remainingTicks,
      PARRY_WINDOW_TICKS,
      true
    );
    cue.reticle.setVisualOnlyAnchor(cue.defender);
    cue.overlay = SEffe.allocateEffectManager(
      "GameplayOverhaulParryTiming",
      null,
      cue.reticle
    );
  }

  @EventListener
  public static void battleEntityTurn(final BattleEntityTurnEvent<?> event) {
    BattleCameraDirector.onTurn(event.bent);

    if(event.bent instanceof final PlayerBattleEntity player && player.typeBentSlot_276 >= 0) {
      PARTY_SLOT_MEMBERS.computeIfAbsent(player.typeBentSlot_276, ignored -> new LinkedHashSet<>()).add(player.charId_272);
      PARTY_SLOT_TURNS.computeIfAbsent(player.typeBentSlot_276, ignored -> new LinkedHashMap<>()).merge(player.charId_272, 1, Integer::sum);
    }

    if(!ACTIVE_PARRY_CUES.isEmpty()) {
      cancelParryCues();
    } else if(parryCameraActive) {
      restoreParryCamera();
    }

    if(event.bent instanceof final MonsterBattleEntity monster) {
      activeEnemyTurnAttacker = monster;
      activeEnemyTurnStartTick = tickCount_800bb0fc;
    } else {
      activeEnemyTurnAttacker = null;
      activeEnemyTurnStartTick = Integer.MIN_VALUE;
    }

    if(activeArcherPlayer != null && activeArcherAttackResolved) {
      clearArcherAdditionState();
    }

    updateDragoonAuras();
  }

  @EventListener
  public static void battleEnded(final BattleEndedEvent event) {
    BattleCameraDirector.endBattle();
    activeEnemyTurnAttacker = null;
    activeEnemyTurnStartTick = Integer.MIN_VALUE;

    XP_SURVIVORS.clear();
    XP_SURVIVORS.addAll(CURRENT_BENCH);
    for(final var playerState : battleState_8006e398.alivePlayerBents_eac) {
      XP_SURVIVORS.add(playerState.innerStruct_00.charId_272);
    }

    if(partySwitched && battleState_8006e398.getAlivePlayerCount() > 0) {
      gameState_800babc8.charIds_88.clear();
      for(final var playerState : battleState_8006e398.playerBents_e40) {
        gameState_800babc8.charIds_88.add(playerState.innerStruct_00.charId_272);
      }
      legend.game.SItem.cacheCharacterSlots();
    }

    cancelParryCues();
    if(activeArcherPlayer != null) {
      clearArcherAdditionState();
    }

    BattleRows.endBattle();
    clearDragoonAuras();
    restoreBattleLighting();
    BREAK_GAUGE.clear();
    LAST_COMPLETED_ADDITIONS.clear();
    NEGATED_ATTACKS.clear();
  }

  @EventListener
  public static void battleStarted(final BattleStartedEvent event) {
    activeEnemyTurnAttacker = null;
    activeEnemyTurnStartTick = Integer.MIN_VALUE;
    PARTY_SLOT_MEMBERS.clear();
    PARTY_SLOT_TURNS.clear();
    CURRENT_BENCH.clear();
    XP_SURVIVORS.clear();
    partySwitched = false;
    startingPartySlots = battleState_8006e398.getPlayerCount();

    for(int slot = 0; slot < startingPartySlots; slot++) {
      final int charId = battleState_8006e398.playerBents_e40.get(slot).innerStruct_00.charId_272;
      PARTY_SLOT_MEMBERS.computeIfAbsent(slot, ignored -> new LinkedHashSet<>()).add(charId);
    }

    BattleRows.beginBattle(event.battle);
    polishBattleLighting();
    updateDragoonAuras();
  }

  @EventListener
  public static void playerBattleSlotChanged(final PlayerBattleSlotChangedEvent event) {
    partySwitched = true;
    PARTY_SLOT_MEMBERS.computeIfAbsent(event.slot, ignored -> new LinkedHashSet<>()).add(event.outgoing.charId_272);
    PARTY_SLOT_MEMBERS.get(event.slot).add(event.incoming.charId_272);
    CURRENT_BENCH.add(event.outgoing.charId_272);
    CURRENT_BENCH.remove(event.incoming.charId_272);
  }

  @EventListener
  public static void postBattleXpDistribution(final PostBattleXpDistributionEvent event) {
    if(!partySwitched || startingPartySlots <= 0) {
      clearPartySwitchXpState();
      return;
    }

    final Set<Integer> participants = new HashSet<>();
    for(final Set<Integer> members : PARTY_SLOT_MEMBERS.values()) {
      participants.addAll(members);
    }

    // Participants replace retail primary/secondary allocation; uninvolved
    // reserves keep the configured secondary-character XP.
    for(final int charId : participants) {
      event.remove(gameState_800babc8.charData_32c.get(charId));
    }

    final List<Integer> eligibleSlots = new ArrayList<>();
    for(int slot = 0; slot < startingPartySlots; slot++) {
      final Set<Integer> members = PARTY_SLOT_MEMBERS.getOrDefault(slot, new LinkedHashSet<>());
      boolean hasSurvivor = false;
      for(final int charId : members) {
        if(XP_SURVIVORS.contains(charId)) {
          hasSurvivor = true;
          break;
        }
      }
      if(hasSurvivor) {
        eligibleSlots.add(slot);
      }
    }

    if(eligibleSlots.isEmpty()) {
      clearPartySwitchXpState();
      return;
    }

    // Preserve exactly one retail primary XP pool for the encounter.
    final int baseSlotXp = event.totalXp / eligibleSlots.size();
    int slotRemainder = event.totalXp % eligibleSlots.size();

    for(final int slot : eligibleSlots) {
      final int slotXp = baseSlotXp + (slotRemainder-- > 0 ? 1 : 0);
      final LinkedHashSet<Integer> members = PARTY_SLOT_MEMBERS.getOrDefault(slot, new LinkedHashSet<>());
      final Map<Integer, Integer> turns = PARTY_SLOT_TURNS.getOrDefault(slot, Map.of());

      final List<Integer> survivors = new ArrayList<>();
      int totalTurns = 0;
      for(final int charId : members) {
        if(XP_SURVIVORS.contains(charId)) {
          survivors.add(charId);
          totalTurns += turns.getOrDefault(charId, 0);
        }
      }

      if(survivors.isEmpty()) {
        continue;
      }

      if(totalTurns <= 0) {
        event.add(gameState_800babc8.charData_32c.get(survivors.getFirst()), slotXp);
        continue;
      }

      int distributed = 0;
      int remainderTarget = survivors.getFirst();
      for(final int charId : survivors) {
        final int weight = turns.getOrDefault(charId, 0);
        if(weight <= 0) {
          continue;
        }

        remainderTarget = charId;
        final int xp = slotXp * weight / totalTurns;
        distributed += xp;
        event.add(gameState_800babc8.charData_32c.get(charId), xp);
      }

      if(distributed < slotXp) {
        event.add(gameState_800babc8.charData_32c.get(remainderTarget), slotXp - distributed);
      }
    }

    clearPartySwitchXpState();
  }

  private static void clearPartySwitchXpState() {
    PARTY_SLOT_MEMBERS.clear();
    PARTY_SLOT_TURNS.clear();
    CURRENT_BENCH.clear();
    XP_SURVIVORS.clear();
    partySwitched = false;
    startingPartySlots = 0;
  }

  @EventListener
  public static void combatantModelLoaded(final CombatantModelLoadedEvent event) {
    // Player templates already provide authored shadows. Generic monster models
    // usually load with shadows disabled, so give them a restrained contact
    // shadow without overriding encounter-specific shadow scripts.
    if((event.combatant.flags_19e & 0x4) == 0 && event.model.shadowType_cc == 0) {
      event.model.shadowType_cc = 1;
      event.model.shadowSize_10c.set(1.15f, 1.0f, 0.78f);
      event.model.shadowOffset_118.zero();
    }
  }

  @EventListener
  public static void guardUsed(final GuardUsedEvent event) {
    BattleCameraDirector.startGuardShot(event.player);
    setDefense(event.player, "guard_defense", 25, selfEffectTurns(1));
    event.player.stats.getStat(LodMod.MAGIC_DEFENSE_STAT.get()).addMod(
      new RegistryId(MOD_ID, "guard_magic_defense"),
      LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(25).turns(selfEffectTurns(1)))
    );
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
    BattleRows.applyPhysicalDamageModifiers(event);

    if(event.attacker instanceof final MonsterBattleEntity monster
      && event.defender instanceof final PlayerBattleEntity defender) {
      if(event.attackType != AttackType.PHYSICAL) {
        cancelParryCuesForAttacker(monster);
      } else {
        final ParryCue cue = finishParryCue(monster, defender, event.attackType, tickCount_800bb0fc);

        if(cue != null
          && cue.parryable
          && cue.pressConsumed
          && cue.pressTick >= cue.windowStartTick
          && cue.pressTick <= cue.expectedImpactTick) {
          event.damage = 0;
          markNegatedAttack(defender);
          playParryReaction(defender);
          addBreak(monster, BREAK_PARRY_GAIN, false, false);
          LOGGER.info("[Gameplay Overhaul] PARRY");
          return;
        }
      }
    }

    if(!(event.attacker instanceof final PlayerBattleEntity player)
      || event.attackType != AttackType.PHYSICAL) {
      return;
    }

    if(player.isDragoon()) {
      if(event.damage > 0) {
        spawnImpact(event.defender, CombatImpactEffect.Style.DRAGOON);
      }
      return;
    }

    if(player.character.isArcher()) {
      boolean completed = false;
      boolean finalAddition = false;

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
            finalAddition = isFinal(addition);
            addElementalBonus(event, player.getElement(), finalAddition ? 25 : 10);
            awardArcherAdditionXp(player, addition, info);
            completed = true;
          }
        }
      }

      if(event.damage > 0) {
        spawnImpact(
          event.defender,
          completed && finalAddition
            ? CombatImpactEffect.Style.FINAL
            : CombatImpactEffect.Style.PIERCE
        );
      }
      return;
    }

    final boolean completed =
      player.addition != null && (battleState_8006e398.additionState_324 & 0x20) != 0;
    final boolean finalAddition = completed && isFinal(player.addition);

    if(completed) {
      addElementalBonus(event, player.getElement(), finalAddition ? 25 : 10);
    }

    if(event.damage > 0) {
      spawnImpact(
        event.defender,
        finalAddition
          ? CombatImpactEffect.Style.FINAL
          : physicalImpactStyle(player)
      );
    }
  }

  @EventListener
  public static void specialEffect(final AttackSpecialEffectEvent event) {
    if(event.effect > 0
      && event.defender instanceof final PlayerBattleEntity guarded
      && guarded.stats.getStat(LodMod.DEFENSE_STAT.get()).hasMod(new RegistryId(MOD_ID, "guard_defense"))) {
      event.effect = -1;
    }

    final Integer negatedTick = NEGATED_ATTACKS.get(event.defender);
    if(negatedTick != null && tickCount_800bb0fc == negatedTick) {
      event.effect = -1;
      NEGATED_ATTACKS.remove(event.defender);
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
      "piercing_arrow_defense_down",
      "break_defense_down"
    );
    if(event.entity instanceof PlayerBattleEntity) {
      appendMomentumStatEffect(
        event,
        event.entity.stats.getStat(LodMod.DRAGOON_ATTACK_STAT.get()),
        "D-ATK",
        "perfect_d_attack"
      );
    }
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

    final UnaryStat defense = event.entity.stats.getStat(LodMod.DEFENSE_STAT.get());
    final RegistryId guardId = new RegistryId(MOD_ID, "guard_defense");
    if(defense != null && defense.hasMod(guardId)) {
      final UnaryStatMod guardMod = defense.getMod(guardId);
      event.effects.add("GUARD(" + guardMod.getTurns() + "T)");
    }

    if(event.entity instanceof final MonsterBattleEntity monster) {
      event.effects.add("BRK " + BREAK_GAUGE.getOrDefault(monster, 0));

      if(monster.physicalImmunity_110 || (monster.damageReductionFlags_6e & 0x8) != 0) {
        event.effects.add("P-IMM");
      } else if(monster.physicalResistance_114 || (monster.damageReductionFlags_6e & 0x2) != 0) {
        event.effects.add("P-RES");
      }

      if(monster.magicalImmunity_112 || (monster.damageReductionFlags_6e & 0x4) != 0) {
        event.effects.add("M-IMM");
      } else if(monster.magicalResistance_116 || (monster.damageReductionFlags_6e & 0x1) != 0) {
        event.effects.add("M-RES");
      }

      if(monster.monsterStatusResistFlag_76 == 0xff || (monster.specialEffectFlag_14 & 0x80) != 0) {
        event.effects.add("ST-IMM");
      }

      if(event.battle.currentTurnBent_800c66c8 != null
        && event.battle.currentTurnBent_800c66c8.innerStruct_00 instanceof final PlayerBattleEntity currentPlayer) {
        if(currentPlayer.getElement().isStrongAgainst(monster.getElement())) {
          event.effects.add("E-WEAK");
        } else if(currentPlayer.getElement().isWeakAgainst(monster.getElement())) {
          event.effects.add("E-RES");
        }
      }
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

    if(event.perfect()) {
      for(final var state : battleState_8006e398.playerBents_e40) {
        final PlayerBattleEntity player = state.innerStruct_00;
        if(player.charId_272 == event.charId) {
          player.stats.getStat(LodMod.DRAGOON_ATTACK_STAT.get()).addMod(
            new RegistryId(MOD_ID, "perfect_d_attack"),
            LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(20).turns(selfEffectTurns(1)))
          );
          spawnImpact(player, CombatImpactEffect.Style.DRAGOON);
          event.battle.queueAdditionCompletionReward("+D-ATK", player.character.hasDragoon());
          break;
        }
      }
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
    // Match melee Additions: combat only grants XP. The normal post-battle
    // screen owns Addition level-ups and mastery/level unlock processing.
    info.xp++;
  }


  private static ParryCue findParryCue(
    final BattleEntity27c attacker,
    final BattleEntity27c defender,
    final AttackType attackType
  ) {
    for(final ParryCue cue : ACTIVE_PARRY_CUES) {
      if(cue.attacker == attacker
        && cue.defender == defender
        && cue.attackType == attackType) {
        return cue;
      }
    }

    return null;
  }

  private static void consumeParryInput(final int now) {
    ParryCue earliest = null;

    for(final ParryCue cue : ACTIVE_PARRY_CUES) {
      if(!cue.parryable || cue.pressConsumed || now > cue.expectedImpactTick) {
        continue;
      }

      if(earliest == null || cue.expectedImpactTick < earliest.expectedImpactTick) {
        earliest = cue;
      }
    }

    if(earliest == null) {
      return;
    }

    // One press belongs to exactly one cue: the earliest unresolved window.
    // A second overlapping reticle cannot steal that input. Once the earliest
    // cue has consumed a press (success or early failure), the next press may
    // address the next cue.
    earliest.pressConsumed = true;
    earliest.pressTick = now;

    if(now < earliest.windowStartTick && earliest.reticle != null) {
      earliest.reticle.completeVisualOnly(false);
      earliest.overlay = null;
      earliest.reticle = null;
    }
  }

  private static ParryCue finishParryCue(
    final MonsterBattleEntity attacker,
    final PlayerBattleEntity defender,
    final AttackType attackType,
    final int impactTick
  ) {
    final ParryCue cue = findParryCue(attacker, defender, attackType);
    if(cue == null) {
      return null;
    }

    final int measuredTicks = java.lang.Math.max(1, impactTick - cue.actionStartTick);

    final boolean successful =
      cue.parryable
        && measuredTicks >= MIN_PARRY_REACTION_TICKS
        && cue.pressConsumed
        && cue.pressTick >= cue.windowStartTick
        && cue.pressTick <= cue.expectedImpactTick;

    if(cue.reticle != null) {
      cue.reticle.completeVisualOnly(successful);
      cue.overlay = null;
      cue.reticle = null;
    }

    ACTIVE_PARRY_CUES.remove(cue);

    if(parryCameraAttacker == attacker
      && ACTIVE_PARRY_CUES.stream().noneMatch(active -> active.attacker == attacker)) {
      parryCameraCueResolved = true;
    }

    return successful ? cue : null;
  }

  private static void cancelParryCuesForAttacker(final BattleEntity27c attacker) {
    for(int i = ACTIVE_PARRY_CUES.size() - 1; i >= 0; i--) {
      final ParryCue cue = ACTIVE_PARRY_CUES.get(i);
      if(cue.attacker != attacker) {
        continue;
      }

      if(cue.overlay != null) {
        cue.overlay.deallocateWithChildren();
      }
      ACTIVE_PARRY_CUES.remove(i);
    }

    if(parryCameraAttacker == attacker) {
      restoreParryCamera();
    }
  }

  private static void cancelParryCues() {
    for(final ParryCue cue : ACTIVE_PARRY_CUES) {
      if(cue.overlay != null) {
        cue.overlay.deallocateWithChildren();
      }
    }

    ACTIVE_PARRY_CUES.clear();
    restoreParryCamera();
  }

  private static Vector3f battleFocusPosition(final BattleEntity27c entity) {
    return new Vector3f(entity.getPosition())
      .add(0.0f, -entity.middleOffsetY_86 * 50.0f, 0.0f);
  }

  private static void focusParryCamera(
    final BattleEntity27c attacker,
    final PlayerBattleEntity defender
  ) {
    if(!(currentEngineState_8004dd04 instanceof final Battle battle)) {
      return;
    }

    final var camera = battle.camera_800c67f0;
    savedCameraViewpoint.set(camera.rview2_00.viewpoint_00);
    savedCameraRefpoint.set(camera.rview2_00.refpoint_0c);
    savedCameraRay.set(savedCameraViewpoint).sub(savedCameraRefpoint);
    parryCameraBaseDistance = savedCameraRay.length();

    if(parryCameraBaseDistance <= 0.001f) {
      return;
    }

    savedCameraRay.div(parryCameraBaseDistance);
    parryCameraAttacker = attacker;
    parryCameraDefender = defender;
    parryCameraAttackerStart.set(battleFocusPosition(attacker));
    parryCameraStartDistance = parryCameraAttackerStart.distance(battleFocusPosition(defender));
    if(parryCameraStartDistance <= 0.001f) {
      return;
    }

    parryCameraCueResolved = false;
    parryCameraActive = true;

    // Own the camera while the counter shot is active. The tracker recomputes
    // the attacker/defender midpoint every tick, so the shot follows the actual
    // attack movement instead of animating once toward a static destination.
    camera.flags_11c &= ~(UPDATE_VIEWPOINT | UPDATE_REFPOINT);
    camera.viewpointMoving_122 = false;
    camera.refpointMoving_123 = false;

    if(activeParryCameraTracker != null) {
      activeParryCameraTracker.deallocateWithChildren();
    }

    activeParryCameraTracker = SCRIPTS.allocateScriptState("GameplayOverhaulParryCamera", attacker);
    activeParryCameraTracker.setTicker(GameplayOverhaulMod::tickParryCamera);
    tickParryCamera(activeParryCameraTracker, attacker);
  }

  private static void tickParryCamera(
    final ScriptState<BattleEntity27c> state,
    final BattleEntity27c ignored
  ) {
    if(!parryCameraActive
      || parryCameraAttacker == null
      || parryCameraDefender == null
      || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      state.deallocateWithChildren();
      if(activeParryCameraTracker == state) {
        activeParryCameraTracker = null;
      }
      return;
    }

    final Vector3f attackerPos = battleFocusPosition(parryCameraAttacker);
    final Vector3f defenderPos = battleFocusPosition(parryCameraDefender);
    final float currentDistance = attackerPos.distance(defenderPos);

    float progress = 1.0f - currentDistance / parryCameraStartDistance;
    progress = java.lang.Math.max(0.0f, java.lang.Math.min(1.0f, progress));

    // The attack camera must read as a two-subject shot immediately. Build the
    // shot around the live midpoint and attack axis instead of blending out of
    // the enemy-only turn portrait.
    final Vector3f attackAxis = new Vector3f(defenderPos)
      .sub(attackerPos)
      .mul(1.0f, 0.0f, 1.0f);
    if(attackAxis.lengthSquared() < 1.0f) {
      attackAxis.set(0.0f, 0.0f, 1.0f);
    } else {
      attackAxis.normalize();
    }

    final Vector3f sideRay = new Vector3f(-attackAxis.z, 0.0f, attackAxis.x);
    if(sideRay.dot(savedCameraRay) < 0.0f) {
      sideRay.negate();
    }

    final Vector3f cameraRay = new Vector3f(
      sideRay.x,
      savedCameraRay.y * 0.70f,
      sideRay.z
    ).normalize();

    final float attackerHalfHeight =
      java.lang.Math.max(700.0f, java.lang.Math.abs(parryCameraAttacker.middleOffsetY_86) * 50.0f);
    final float defenderHalfHeight =
      java.lang.Math.max(700.0f, java.lang.Math.abs(parryCameraDefender.middleOffsetY_86) * 50.0f);
    final float maxHalfHeight = java.lang.Math.max(attackerHalfHeight, defenderHalfHeight);
    final float horizontalSeparation = (float)java.lang.Math.sqrt(
      (defenderPos.x - attackerPos.x) * (defenderPos.x - attackerPos.x)
        + (defenderPos.z - attackerPos.z) * (defenderPos.z - attackerPos.z)
    );

    final Vector3f desiredRefpoint = new Vector3f(attackerPos).lerp(defenderPos, 0.5f);
    final float desiredDistance = java.lang.Math.max(
      3400.0f,
      java.lang.Math.min(
        7600.0f,
        java.lang.Math.max(
          parryCameraBaseDistance * 1.02f,
          horizontalSeparation * 1.10f + maxHalfHeight * 2.00f
        )
      )
    );
    final Vector3f desiredViewpoint = new Vector3f(desiredRefpoint)
      .add(cameraRay.mul(desiredDistance));

    final var camera = battle.camera_800c67f0;
    camera.flags_11c &= ~(UPDATE_VIEWPOINT | UPDATE_REFPOINT);
    camera.viewpointMoving_122 = false;
    camera.refpointMoving_123 = false;
    camera.setRefpoint(desiredRefpoint.x, desiredRefpoint.y, desiredRefpoint.z);
    camera.setViewpoint(desiredViewpoint.x, desiredViewpoint.y, desiredViewpoint.z);

    // Once impact has resolved, the attacker's return movement itself drives
    // blend back to zero. Snap only the final sub-pixel remainder to the saved
    // shot and release camera ownership.
    final float returnTolerance = java.lang.Math.max(48.0f, parryCameraStartDistance * 0.025f);
    if(parryCameraCueResolved
      && attackerPos.distance(parryCameraAttackerStart) <= returnTolerance
      && progress <= 0.03f) {
      camera.setRefpoint(savedCameraRefpoint.x, savedCameraRefpoint.y, savedCameraRefpoint.z);
      camera.setViewpoint(savedCameraViewpoint.x, savedCameraViewpoint.y, savedCameraViewpoint.z);
      parryCameraActive = false;
      parryCameraAttacker = null;
      parryCameraDefender = null;
      parryCameraCueResolved = false;
      state.deallocateWithChildren();
      if(activeParryCameraTracker == state) {
        activeParryCameraTracker = null;
      }
    }
  }

  private static void restoreParryCamera() {
    if(activeParryCameraTracker != null) {
      activeParryCameraTracker.deallocateWithChildren();
      activeParryCameraTracker = null;
    }

    parryCameraAttacker = null;
    parryCameraDefender = null;
    parryCameraCueResolved = false;

    if(!parryCameraActive || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      parryCameraActive = false;
      return;
    }

    final var camera = battle.camera_800c67f0;
    camera.cameraAccelerateViewpoint(
      0,
      savedCameraViewpoint.x,
      savedCameraViewpoint.y,
      savedCameraViewpoint.z,
      6,
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
      6,
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

    if(currentEngineState_8004dd04 instanceof final Battle battle) {
      queueChainReward(player, id, battle);
      if(defender instanceof final MonsterBattleEntity monster) {
        final Addition addition = REGISTRIES.additions.getEntry(id).get();
        addBreak(monster, isFinal(addition) ? BREAK_FINAL_ADDITION_GAIN : BREAK_ADDITION_GAIN, true, player.character.hasDragoon());
      }
      if(notification != null) {
        battle.queueAdditionCompletionReward(shortReward(notification), player.character.hasDragoon());
      }
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

    if(currentEngineState_8004dd04 instanceof final Battle battle) {
      queueChainReward(player, id, battle);
      if(defender instanceof final MonsterBattleEntity monster) {
        final Addition addition = REGISTRIES.additions.getEntry(id).get();
        addBreak(monster, isFinal(addition) ? BREAK_FINAL_ADDITION_GAIN : BREAK_ADDITION_GAIN, true, player.character.hasDragoon());
      }
      if(notification != null) {
        battle.queueAdditionCompletionReward(shortReward(notification), player.character.hasDragoon());
      }
    }
  }

  private static void queueChainReward(final PlayerBattleEntity player, final RegistryId id, final Battle battle) {
    final RegistryId previous = LAST_COMPLETED_ADDITIONS.put(player.charId_272, id);
    if(previous != null && !previous.equals(id)) {
      grantSp(player, CHAIN_SP_BONUS);
      battle.queueAdditionCompletionReward("+5 SP CHAIN", player.character.hasDragoon());
    }
  }

  private static void addBreak(final MonsterBattleEntity monster, final int amount, final boolean additionPipeline, final boolean waitForRetailSpSummary) {
    final int next = java.lang.Math.min(BREAK_THRESHOLD, BREAK_GAUGE.getOrDefault(monster, 0) + amount);
    if(next < BREAK_THRESHOLD) {
      BREAK_GAUGE.put(monster, next);
      return;
    }

    BREAK_GAUGE.put(monster, 0);
    monster.turnValue_4c = java.lang.Math.max(0, monster.turnValue_4c - 0x6d);
    setDefense(monster, "break_defense_down", -20, 2);

    if(currentEngineState_8004dd04 instanceof final Battle battle) {
      battle.hud.showBreakFlash(monster);
      spawnImpact(monster, CombatImpactEffect.Style.BREAK);

      if(additionPipeline) {
        battle.queueAdditionCompletionReward("+BREAK", waitForRetailSpSummary);
      } else {
        battle.hud.showEffectNotification("BREAK!", TextColour.GOLD);
      }
    }
  }

  private static String shortReward(final String notification) {
    if(notification.startsWith("ENEMY DEF -")) {
      return "-DEF";
    }
    if(notification.startsWith("ENEMY ATK -")) {
      return "-ATK";
    }
    if(notification.startsWith("ENEMY SPD -")) {
      return "-SPD";
    }
    if(notification.contains("ATK")) {
      return "+ATK";
    }
    if(notification.contains("DEF")) {
      return "+DEF";
    }
    if(notification.contains("SPD")) {
      return "+SPD";
    }
    if(notification.contains("HP")) {
      return "+HP";
    }

    return notification.startsWith("+") || notification.startsWith("-")
      ? notification
      : "+" + notification;
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
    if(stat == null) {
      return;
    }

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
    NEGATED_ATTACKS.put(defender, tickCount_800bb0fc);
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

  private static CombatImpactEffect.Style physicalImpactStyle(final PlayerBattleEntity player) {
    return switch(player.charId_272) {
      case 0, 3 -> CombatImpactEffect.Style.SLASH;   // Dart / Rose
      case 1, 2, 5, 8 -> CombatImpactEffect.Style.PIERCE; // spear / bow
      case 4, 6 -> CombatImpactEffect.Style.BLUNT;  // Haschel / Meru
      case 7 -> CombatImpactEffect.Style.HEAVY;     // Kongol
      default -> CombatImpactEffect.Style.BLUNT;
    };
  }

  private static void spawnImpact(
    final BattleEntity27c target,
    final CombatImpactEffect.Style style
  ) {
    SEffe.allocateEffectManager(
      "GameplayOverhaulCombatImpact",
      null,
      new CombatImpactEffect(target, style)
    );
  }

  private static void updateDragoonAuras() {
    final var iterator = DRAGOON_AURAS.entrySet().iterator();
    while(iterator.hasNext()) {
      final var entry = iterator.next();
      final PlayerBattleEntity player = entry.getKey();

      if(!player.isDragoon() || player.getState().hasFlag(BattleEntity27c.FLAG_DEAD)) {
        if(entry.getValue() != null) {
          entry.getValue().deallocateWithChildren();
        }
        iterator.remove();
      }
    }

    for(final var playerState : battleState_8006e398.alivePlayerBents_eac) {
      final PlayerBattleEntity player = playerState.innerStruct_00;

      if(player.isDragoon() && !DRAGOON_AURAS.containsKey(player)) {
        DRAGOON_AURAS.put(
          player,
          SEffe.allocateEffectManager(
            "GameplayOverhaulDragoonAura",
            null,
            new DragoonAuraEffect(player)
          )
        );
      }
    }
  }

  private static void clearDragoonAuras() {
    for(final ScriptState<?> aura : DRAGOON_AURAS.values()) {
      if(aura != null) {
        aura.deallocateWithChildren();
      }
    }
    DRAGOON_AURAS.clear();
  }

  private static void polishBattleLighting() {
    if(battleLightingPolished) {
      return;
    }

    SAVED_BATTLE_LIGHT_COLOUR.set(lightColourMatrix_800c3508);
    SAVED_BATTLE_AMBIENT.set(GTE.backgroundColour);
    battleLightingPolished = true;

    // Preserve each stage's authored colour balance while giving combatants
    // slightly stronger key/fill separation and a touch more ambient depth.
    lightColourMatrix_800c3508.m00(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m00() * 1.07f));
    lightColourMatrix_800c3508.m01(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m01() * 1.07f));
    lightColourMatrix_800c3508.m02(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m02() * 1.07f));
    lightColourMatrix_800c3508.m10(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m10() * 1.05f));
    lightColourMatrix_800c3508.m11(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m11() * 1.05f));
    lightColourMatrix_800c3508.m12(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m12() * 1.05f));
    lightColourMatrix_800c3508.m20(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m20() * 1.04f));
    lightColourMatrix_800c3508.m21(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m21() * 1.04f));
    lightColourMatrix_800c3508.m22(java.lang.Math.min(1.0f, lightColourMatrix_800c3508.m22() * 1.04f));
    GTE.setLightColourMatrix(lightColourMatrix_800c3508);

    GTE.setBackgroundColour(
      java.lang.Math.max(0.0f, SAVED_BATTLE_AMBIENT.x * 0.93f),
      java.lang.Math.max(0.0f, SAVED_BATTLE_AMBIENT.y * 0.93f),
      java.lang.Math.max(0.0f, SAVED_BATTLE_AMBIENT.z * 0.93f)
    );
  }

  private static void restoreBattleLighting() {
    if(!battleLightingPolished) {
      return;
    }

    lightColourMatrix_800c3508.set(SAVED_BATTLE_LIGHT_COLOUR);
    GTE.setLightColourMatrix(lightColourMatrix_800c3508);
    GTE.setBackgroundColour(
      SAVED_BATTLE_AMBIENT.x,
      SAVED_BATTLE_AMBIENT.y,
      SAVED_BATTLE_AMBIENT.z
    );
    battleLightingPolished = false;
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

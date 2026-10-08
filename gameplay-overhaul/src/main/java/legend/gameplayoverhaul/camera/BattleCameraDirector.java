package legend.gameplayoverhaul.camera;

import legend.core.platform.input.InputAction;
import legend.game.combat.Battle;
import legend.game.combat.PartySwitchPreparation;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.MonsterBattleEntity;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleAction;
import legend.game.combat.ui.BattleMenuStruct58;
import legend.game.combat.ui.ItemListMenu;
import legend.game.inventory.Item;
import legend.game.scripting.ScriptState;
import legend.lodmod.LodBattleActions;
import legend.lodmod.LodMod;
import org.joml.Vector3f;

import java.util.List;

import static legend.core.GameEngine.SCRIPTS;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.combat.bent.BattleEntity27c.FLAG_CANT_TARGET;
import static legend.game.combat.environment.BattleCamera.UPDATE_REFPOINT;
import static legend.game.combat.environment.BattleCamera.UPDATE_VIEWPOINT;
import static legend.game.modding.coremod.CoreMod.INPUT_ACTION_MENU_CONFIRM;

/**
 * Cinematic battle camera director for player command/target framing and a few
 * short action-specific shots. Retail attack, spell, Dragoon, and enemy
 * cinematics remain authoritative outside these explicitly owned moments.
 */
public final class BattleCameraDirector {
  private static final float COMMAND_BLEND = 0.11f;
  private static final float TARGET_BLEND = 0.17f;
  private static final float ACTION_BLEND = 0.24f;
  private static final float HANDOFF_BLEND = 0.32f;
  private static final float PROJECTILE_BLEND = 0.30f;

  private static final float IDLE_DRIFT_SPEED = 0.018f;
  private static final float IDLE_CAMERA_DRIFT = 72.0f;
  private static final float IDLE_LOOK_DRIFT = 18.0f;

  // Lost Odyssey-style command portrait: intentionally closer than the
  // full-body targeting shots, with the look point and camera raised toward
  // the upper torso/head. Cropping the legs/hips is desirable here.
  private static final float COMMAND_PORTRAIT_DISTANCE_SCALE = 1.55f;
  private static final float COMMAND_PORTRAIT_MIN_DISTANCE = 1400.0f;
  private static final float COMMAND_PORTRAIT_MAX_DISTANCE = 2800.0f;
  private static final float COMMAND_LOOK_HEIGHT_FACTOR = 1.18f;
  private static final float COMMAND_CAMERA_HEIGHT = -0.10f;
  private static final float PORTRAIT_FRONT_BIAS = 0.48f;
  private static final float TURN_ENTRY_DISTANCE_SCALE = 1.22f;
  private static final float TURN_ENTRY_FRONT_BIAS = 0.24f;
  private static final int TURN_ENTRY_SETTLE_TICKS = 8;
  private static final float ITEM_PORTRAIT_DISTANCE_SCALE = 1.95f;
  private static final float ITEM_PORTRAIT_MIN_DISTANCE = 2050.0f;
  private static final float ITEM_PORTRAIT_MAX_DISTANCE = 3600.0f;
  private static final float ITEM_LOOK_HEIGHT_FACTOR = 1.32f;
  private static final float ITEM_CAMERA_HEIGHT = -0.20f;
  private static final float GUARD_LOOK_HEIGHT_FACTOR = 1.08f;
  private static final float GUARD_CAMERA_HEIGHT = -0.10f;

  private static final int DEFAULT_ATTACK_HANDOFF_TICKS = 120;
  private static final int GUARD_HOLD_TICKS = 30;
  private static final int ESCAPE_HOLD_TICKS = 42;
  private static final int ITEM_THROW_ANIMATION_TICKS = 16;
  private static final int ITEM_PROJECTILE_TICKS = 15;
  private static final int ITEM_IMPACT_HOLD_TICKS = 16;
  private static final int ITEM_THROW_TIMEOUT_TICKS = 120;

  private enum Mode {
    TURN_ENTRY,
    COMMAND,
    ATTACK_HANDOFF,
    ENEMY_TURN,
    GUARD,
    ESCAPE,
    ROW,
    SWITCH_OUT,
    SWITCH_IN,
    ITEM_ATTACK,
  }

  private static ScriptState<BattleEntity27c> tracker;
  private static Mode mode;
  private static PlayerBattleEntity actor;
  private static MonsterBattleEntity enemyActor;
  private static int attackHandoffTicksRemaining;
  private static BattleEntity27c itemTarget;
  private static int itemTargetType;
  private static boolean itemTargetsAll;
  private static int itemPhase;
  private static int itemPhaseTicks;
  private static int modeTicks;
  private static boolean sawCommandMenu;
  private static boolean ownsCamera;
  private static float shotSide;
  private static boolean turnEntrySnapped;
  private static int turnEntryTicks;

  private static final Vector3f turnEntryStartViewpoint = new Vector3f();
  private static final Vector3f turnEntryStartRefpoint = new Vector3f();
  private static final Vector3f turnEntryEndViewpoint = new Vector3f();
  private static final Vector3f turnEntryEndRefpoint = new Vector3f();
  private static final Vector3f actionAnchor = new Vector3f();
  private static final Vector3f actionWideViewpoint = new Vector3f();
  private static final Vector3f actionWideRefpoint = new Vector3f();
  private static boolean actionWideCaptured;
  private static final Vector3f itemStart = new Vector3f();
  private static final Vector3f baseRay = new Vector3f();
  private static float baseDistance;
  private static float targetFramingDistance;
  private static final Vector3f currentViewpoint = new Vector3f();
  private static final Vector3f currentRefpoint = new Vector3f();
  private static final Vector3f desiredViewpoint = new Vector3f();
  private static final Vector3f desiredRefpoint = new Vector3f();

  private BattleCameraDirector() { }

  public static void onTurn(final BattleEntity27c entity) {
    stopTracker();

    if(entity instanceof final PlayerBattleEntity player && player.typeBentSlot_276 >= 0) {
      start(Mode.TURN_ENTRY, player);
      sawCommandMenu = false;
      return;
    }

    if(entity instanceof final MonsterBattleEntity monster) {
      startEnemyTurnShot(monster);
    }
  }

  public static void onInputPressed(final InputAction action) {
    if(!(currentEngineState_8004dd04 instanceof final Battle battle)) {
      return;
    }

    final BattleMenuStruct58 menu = battle.hud.battleMenu_800c6c34;
    final PlayerBattleEntity player = menu.player_04;
    if(player == null) {
      return;
    }

    if(action == INPUT_ACTION_MENU_CONFIRM.get()
      && battle.hud.listMenu_800c6b60 instanceof ItemListMenu
      && menu.displayTargetArrowAndName_4c
      && player.item_d4 != null
      && !player.item_d4.isEmpty()
      && player.item_d4.canTarget(Item.TargetType.ENEMIES)) {
      startAttackItemShot(player, menu);
      return;
    }

    // Once a physical target is confirmed, leave the enemy portrait
    // immediately and cut back toward the attacker. Retail can then take over
    // as soon as its Addition/attack camera actually begins moving.
    if(action == INPUT_ACTION_MENU_CONFIRM.get()
      && menu.displayTargetArrowAndName_4c
      && (menu.currentAction == LodBattleActions.ATTACK.get()
        || menu.currentAction == LodBattleActions.D_ATTACK.get())) {
      startAttackHandoff(player);
      return;
    }

    if(battle.hud.listMenu_800c6b60 != null || menu.displayTargetArrowAndName_4c) {
      return;
    }

    final BattleAction escape = LodBattleActions.ESCAPE.get();
    boolean escapeSelected =
      action == LodMod.INPUT_ACTION_BTTL_ESCAPE.get()
        && menu.actions.contains(escape)
        && !menu.disabledActions.contains(escape);

    if(!escapeSelected
      && action == INPUT_ACTION_MENU_CONFIRM.get()
      && menu.selectedIcon_22 >= 0
      && menu.selectedIcon_22 < menu.actions.size()) {
      final BattleAction selected = menu.actions.get(menu.selectedIcon_22);
      escapeSelected = selected == escape && !menu.disabledActions.contains(escape);
    }

    if(escapeSelected) {
      startEscapeShot(player);
    }
  }

  public static void onPlayerAttackPrepared(final PlayerBattleEntity player, final int waitTicks) {
    if(mode != Mode.ATTACK_HANDOFF || actor != player) {
      // If retail has already started a real camera move, do not recapture it
      // just because the attack-preparation event arrived a tick later.
      if(currentEngineState_8004dd04 instanceof final Battle battle
        && (battle.camera_800c67f0.flags_11c & (UPDATE_VIEWPOINT | UPDATE_REFPOINT)) != 0) {
        return;
      }

      startAttackHandoff(player);
    }

    attackHandoffTicksRemaining = java.lang.Math.max(
      attackHandoffTicksRemaining,
      java.lang.Math.max(DEFAULT_ATTACK_HANDOFF_TICKS, waitTicks + 40)
    );
  }

  public static void startGuardShot(final PlayerBattleEntity player) {
    start(Mode.GUARD, player);
  }

  public static void startRowShot(final PlayerBattleEntity player, final Vector3f target) {
    start(Mode.ROW, player);
    actionAnchor.set(target);
    actionWideCaptured = false;
  }

  public static void startSwitchShot(final PartySwitchPreparation preparation) {
    start(Mode.SWITCH_OUT, preparation.outgoing);
    actionAnchor.set(preparation.formationPosition);
    actionWideCaptured = false;
  }

  public static void focusSwitchIncoming(final PartySwitchPreparation preparation) {
    if(mode != Mode.SWITCH_OUT && mode != Mode.SWITCH_IN) {
      return;
    }

    actor = preparation.incoming;
    mode = Mode.SWITCH_IN;
    actionAnchor.set(preparation.formationPosition);
    modeTicks = 0;
  }

  public static void finishActionCamera(final PlayerBattleEntity player) {
    if(actor == player && mode != Mode.COMMAND) {
      stopTracker();
    }
  }

  public static void endBattle() {
    stopTracker();
  }

  private static void startAttackHandoff(final PlayerBattleEntity player) {
    start(Mode.ATTACK_HANDOFF, player);
    attackHandoffTicksRemaining = DEFAULT_ATTACK_HANDOFF_TICKS;
  }

  private static void startEnemyTurnShot(final MonsterBattleEntity monster) {
    stopTracker();

    mode = Mode.ENEMY_TURN;
    enemyActor = monster;
    actor = null;
    ownsCamera = false;
    modeTicks = 0;
    sawCommandMenu = true;
    shotSide = (monster.typeBentSlot_276 & 1) == 0 ? -1.0f : 1.0f;

    tracker = SCRIPTS.allocateScriptState("GameplayOverhaulEnemyBattleCamera", (BattleEntity27c)monster);
    tracker.setTicker(BattleCameraDirector::tick);
  }

  private static void startEscapeShot(final PlayerBattleEntity player) {
    start(Mode.ESCAPE, player);
  }

  private static void startAttackItemShot(final PlayerBattleEntity player, final BattleMenuStruct58 menu) {
    start(Mode.ITEM_ATTACK, player);
    itemTarget = null;
    itemTargetType = menu.targetType_50;
    itemTargetsAll = menu.combatantIndex_54 == -1;
    itemPhase = 0;
    itemPhaseTicks = 0;

    if(!itemTargetsAll) {
      final List<? extends ScriptState<? extends BattleEntity27c>> targets =
        battleState_8006e398.getBentsForTargetType(menu.targetType_50);
      if(menu.combatantIndex_54 >= 0 && menu.combatantIndex_54 < targets.size()) {
        final ScriptState<? extends BattleEntity27c> targetState = targets.get(menu.combatantIndex_54);
        if(targetState != null && !targetState.hasFlag(FLAG_CANT_TARGET)) {
          itemTarget = targetState.innerStruct_00;
        }
      }
    }
  }

  private static void start(final Mode nextMode, final PlayerBattleEntity player) {
    stopTracker();

    mode = nextMode;
    actor = player;
    enemyActor = null;
    ownsCamera = false;
    modeTicks = 0;
    sawCommandMenu = nextMode != Mode.COMMAND && nextMode != Mode.TURN_ENTRY;
    turnEntrySnapped = false;
    turnEntryTicks = 0;
    shotSide = (player.typeBentSlot_276 & 1) == 0 ? -1.0f : 1.0f;

    tracker = SCRIPTS.allocateScriptState("GameplayOverhaulBattleCamera", (BattleEntity27c)player);
    tracker.setTicker(BattleCameraDirector::tick);
  }

  private static void tick(final ScriptState<BattleEntity27c> state, final BattleEntity27c ignored) {
    if(mode == null
      || (mode == Mode.ENEMY_TURN && enemyActor == null)
      || (mode != Mode.ENEMY_TURN && actor == null)
      || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      stopTracker(state);
      return;
    }

    if(mode == Mode.TURN_ENTRY) {
      tickTurnEntry(state, battle);
      return;
    }

    if(mode == Mode.COMMAND) {
      tickCommand(state, battle);
      return;
    }

    if(mode == Mode.ATTACK_HANDOFF
      && (battle.camera_800c67f0.flags_11c & (UPDATE_VIEWPOINT | UPDATE_REFPOINT)) != 0) {
      stopTracker(state);
      return;
    }

    if(!ownsCamera && !captureCamera(battle)) {
      return;
    }

    modeTicks++;

    switch(mode) {
      case ATTACK_HANDOFF -> {
        if((battle.camera_800c67f0.flags_11c & (UPDATE_VIEWPOINT | UPDATE_REFPOINT)) != 0) {
          stopTracker(state);
          return;
        }

        if(!buildAttackHandoffShot()) {
          stopTracker(state);
          return;
        }

        applyCamera(battle, HANDOFF_BLEND);
        if(--attackHandoffTicksRemaining <= 0) {
          stopTracker(state);
        }
      }

      case ENEMY_TURN -> {
        // Enemy turns should begin from a neutral whole-battlefield view.
        // The instant retail starts an authored camera move, release ownership.
        if((battle.camera_800c67f0.flags_11c & (UPDATE_VIEWPOINT | UPDATE_REFPOINT)) != 0) {
          stopTracker(state);
          return;
        }

        if(!buildBattlefieldWideShot()) {
          stopTracker(state);
          return;
        }

        applyCamera(battle, ACTION_BLEND);
      }

      case GUARD -> {
        if(!buildGuardShot()) {
          stopTracker(state);
          return;
        }
        applyCamera(battle, ACTION_BLEND);
        if(modeTicks >= GUARD_HOLD_TICKS) {
          stopTracker(state);
        }
      }

      case ESCAPE -> {
        if(!buildPartyWideShot()) {
          stopTracker(state);
          return;
        }
        applyCamera(battle, ACTION_BLEND);
        if(modeTicks >= ESCAPE_HOLD_TICKS) {
          stopTracker(state);
        }
      }

      case ROW, SWITCH_OUT, SWITCH_IN -> {
        if(!actionWideCaptured) {
          if(!buildBattlefieldWideShot(actionAnchor)) {
            stopTracker(state);
            return;
          }
          actionWideViewpoint.set(desiredViewpoint);
          actionWideRefpoint.set(desiredRefpoint);
          actionWideCaptured = true;
        } else {
          desiredViewpoint.set(actionWideViewpoint);
          desiredRefpoint.set(actionWideRefpoint);
        }

        applyCamera(battle, ACTION_BLEND);
      }

      case ITEM_ATTACK -> tickAttackItem(state, battle);
      default -> { }
    }
  }

  private static void tickTurnEntry(final ScriptState<BattleEntity27c> state, final Battle battle) {
    final BattleMenuStruct58 menu = battle.hud.battleMenu_800c6c34;

    // The turn event can arrive a little before the command UI owns the
    // player. Keep the previous shot until the new turn is actually ready.
    if(menu.player_04 != actor || menu.state_00 == 0) {
      return;
    }

    sawCommandMenu = true;

    // If the player somehow enters a submenu/targeting immediately, skip the
    // flourish and let normal command/target framing take over.
    if(menu.displayTargetArrowAndName_4c
      || menu.currentAction != null
      || battle.hud.listMenu_800c6b60 != null
      || menu.pauseCurrentAction
      || menu.targetArrowHiding
      || menu.state_00 == 5) {
      mode = Mode.COMMAND;
      ownsCamera = false;
      modeTicks = 0;
      tickCommand(state, battle);
      return;
    }

    if(!turnEntrySnapped) {
      if(!captureCamera(battle)) {
        return;
      }

      // Build the final portrait first so targeting can still inherit the
      // pre-portrait retail distance captured above.
      if(!buildCommandPortrait(false)) {
        stopTracker(state);
        return;
      }
      turnEntryEndViewpoint.set(desiredViewpoint);
      turnEntryEndRefpoint.set(desiredRefpoint);

      if(!buildTurnEntryStagingShot()) {
        stopTracker(state);
        return;
      }
      turnEntryStartViewpoint.set(desiredViewpoint);
      turnEntryStartRefpoint.set(desiredRefpoint);

      // Lost Odyssey-style edit: hard cut into the new character's shot,
      // then immediately begin the short ease into the command portrait.
      currentViewpoint.set(turnEntryStartViewpoint);
      currentRefpoint.set(turnEntryStartRefpoint);
      setCameraDirect(battle, currentViewpoint, currentRefpoint);

      turnEntrySnapped = true;
      turnEntryTicks = 0;
      return;
    }

    turnEntryTicks++;
    final float t = clamp(turnEntryTicks / (float)TURN_ENTRY_SETTLE_TICKS, 0.0f, 1.0f);
    final float inverse = 1.0f - t;
    final float eased = 1.0f - inverse * inverse * inverse;

    currentViewpoint.set(turnEntryStartViewpoint).lerp(turnEntryEndViewpoint, eased);
    currentRefpoint.set(turnEntryStartRefpoint).lerp(turnEntryEndRefpoint, eased);
    setCameraDirect(battle, currentViewpoint, currentRefpoint);

    if(turnEntryTicks >= TURN_ENTRY_SETTLE_TICKS) {
      mode = Mode.COMMAND;
      ownsCamera = true;
      modeTicks = 0;
      turnEntrySnapped = false;
      turnEntryTicks = 0;
    }
  }

  private static void tickCommand(final ScriptState<BattleEntity27c> state, final Battle battle) {
    final BattleMenuStruct58 menu = battle.hud.battleMenu_800c6c34;

    if(menu.player_04 != actor || menu.state_00 == 0) {
      if(sawCommandMenu) {
        stopTracker(state);
      }
      return;
    }

    sawCommandMenu = true;

    final boolean targeting = menu.displayTargetArrowAndName_4c;

    // Once an action has been chosen, stay dormant while its item/spell/
    // Addition submenu prepares targeting. This keeps the director alive long
    // enough to frame the target, without touching the subsequent cinematic.
    if(menu.currentAction != null && !targeting) {
      suspend();
      return;
    }

    if((battle.hud.listMenu_800c6b60 != null && !targeting)
      || menu.pauseCurrentAction
      || menu.targetArrowHiding
      || menu.state_00 == 5) {
      suspend();
      return;
    }

    if(!ownsCamera && !captureCamera(battle)) {
      return;
    }
    final boolean framed = targeting ? buildTargetShot(menu) : buildCommandShot();
    if(!framed) {
      suspend();
      return;
    }

    modeTicks++;
    applyCamera(battle, targeting ? TARGET_BLEND : COMMAND_BLEND);
  }

  private static void tickAttackItem(final ScriptState<BattleEntity27c> state, final Battle battle) {
    if(itemPhase == 0) {
      if(!buildItemThrowShot()) {
        stopTracker(state);
        return;
      }
      applyCamera(battle, ACTION_BLEND);

      if(actor.loadingAnimIndex_26e == 7 || actor.currentAnimIndex_270 == 7) {
        itemPhase = 1;
        itemPhaseTicks = 0;
        itemStart.set(focusPosition(actor));
      } else if(modeTicks >= ITEM_THROW_TIMEOUT_TICKS) {
        stopTracker(state);
      }
      return;
    }

    if(itemPhase == 1) {
      if(!buildItemThrowShot()) {
        stopTracker(state);
        return;
      }
      applyCamera(battle, ACTION_BLEND);
      itemPhaseTicks++;

      if(itemPhaseTicks >= ITEM_THROW_ANIMATION_TICKS) {
        itemPhase = 2;
        itemPhaseTicks = 0;
        itemStart.set(focusPosition(actor));
      }
      return;
    }

    if(itemPhase == 2) {
      itemPhaseTicks++;
      final float progress = clamp(itemPhaseTicks / (float)ITEM_PROJECTILE_TICKS, 0.0f, 1.0f);
      if(!buildProjectileShot(progress)) {
        stopTracker(state);
        return;
      }
      applyCamera(battle, PROJECTILE_BLEND);

      if(itemPhaseTicks >= ITEM_PROJECTILE_TICKS) {
        itemPhase = 3;
        itemPhaseTicks = 0;
      }
      return;
    }

    if(!buildStoredItemTargetShot()) {
      stopTracker(state);
      return;
    }
    applyCamera(battle, TARGET_BLEND);
    itemPhaseTicks++;

    if(itemPhaseTicks >= ITEM_IMPACT_HOLD_TICKS) {
      stopTracker(state);
    }
  }

  private static boolean captureCamera(final Battle battle) {
    final var camera = battle.camera_800c67f0;
    currentViewpoint.set(camera.rview2_00.viewpoint_00);
    currentRefpoint.set(camera.rview2_00.refpoint_0c);
    baseRay.set(currentViewpoint).sub(currentRefpoint);
    baseDistance = baseRay.length();

    if(baseDistance <= 0.001f) {
      return false;
    }

    baseRay.div(baseDistance);

    // Preserve the pre-portrait target-camera scale. The close command shot
    // must not become the baseline for enemy framing when targeting starts.
    if((mode == Mode.COMMAND || mode == Mode.TURN_ENTRY) && targetFramingDistance <= 0.0f && actor != null) {
      targetFramingDistance = subjectDistance(actor, baseDistance * 1.03f, 1900.0f, 3900.0f);
    }

    ownsCamera = true;
    modeTicks = 0;
    return true;
  }

  private static boolean buildCommandShot() {
    return buildCommandPortrait(true);
  }

  private static boolean buildCommandPortrait(final boolean idleDrift) {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(actorPos, enemies.center);
    if(forward == null) {
      return false;
    }

    // Command selection is a close character portrait rather than a full-body
    // tactical shot. Stay mostly side-on, but use the model's actual facing to
    // sit in its front hemisphere and keep the face readable.
    final Vector3f portraitDirection = portraitDirection(actor, forward, PORTRAIT_FRONT_BIAS);

    final float halfHeight = subjectHalfHeight(actor);
    desiredRefpoint.set(actorPos).add(0.0f, -halfHeight * COMMAND_LOOK_HEIGHT_FACTOR, 0.0f);

    final float distance = clamp(
      halfHeight * COMMAND_PORTRAIT_DISTANCE_SCALE,
      COMMAND_PORTRAIT_MIN_DISTANCE,
      COMMAND_PORTRAIT_MAX_DISTANCE
    );
    desiredViewpoint.set(desiredRefpoint).add(portraitRay(portraitDirection, COMMAND_CAMERA_HEIGHT).mul(distance));

    if(idleDrift) {
      final float drift = (float)java.lang.Math.sin(modeTicks * IDLE_DRIFT_SPEED) * IDLE_CAMERA_DRIFT;
      final float lookDrift = (float)java.lang.Math.sin(modeTicks * IDLE_DRIFT_SPEED * 0.71f + 0.8f) * IDLE_LOOK_DRIFT;
      desiredViewpoint.add(new Vector3f(forward).mul(drift));
      desiredRefpoint.add(new Vector3f(forward).mul(lookDrift));
    }

    return true;
  }

  private static boolean buildTurnEntryStagingShot() {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(actorPos, enemies.center);
    if(forward == null) {
      return false;
    }

    final Vector3f stagingDirection = portraitDirection(actor, forward, TURN_ENTRY_FRONT_BIAS);
    final float halfHeight = subjectHalfHeight(actor);
    desiredRefpoint.set(actorPos).add(0.0f, -halfHeight * COMMAND_LOOK_HEIGHT_FACTOR, 0.0f);

    final float finalDistance = clamp(
      halfHeight * COMMAND_PORTRAIT_DISTANCE_SCALE,
      COMMAND_PORTRAIT_MIN_DISTANCE,
      COMMAND_PORTRAIT_MAX_DISTANCE
    );
    final float stagingDistance = clamp(
      finalDistance * TURN_ENTRY_DISTANCE_SCALE,
      COMMAND_PORTRAIT_MIN_DISTANCE * TURN_ENTRY_DISTANCE_SCALE,
      COMMAND_PORTRAIT_MAX_DISTANCE * TURN_ENTRY_DISTANCE_SCALE
    );
    desiredViewpoint.set(desiredRefpoint).add(
      portraitRay(stagingDirection, COMMAND_CAMERA_HEIGHT * 0.85f).mul(stagingDistance)
    );
    return true;
  }

  private static boolean buildAttackHandoffShot() {
    return buildPlayerSidePortrait(
      actor,
      COMMAND_LOOK_HEIGHT_FACTOR,
      COMMAND_CAMERA_HEIGHT,
      COMMAND_PORTRAIT_DISTANCE_SCALE,
      COMMAND_PORTRAIT_MIN_DISTANCE,
      COMMAND_PORTRAIT_MAX_DISTANCE
    );
  }

  private static boolean buildItemThrowShot() {
    return buildPlayerSidePortrait(
      actor,
      ITEM_LOOK_HEIGHT_FACTOR,
      ITEM_CAMERA_HEIGHT,
      ITEM_PORTRAIT_DISTANCE_SCALE,
      ITEM_PORTRAIT_MIN_DISTANCE,
      ITEM_PORTRAIT_MAX_DISTANCE
    );
  }

  private static boolean buildPlayerSidePortrait(
    final PlayerBattleEntity player,
    final float lookHeightFactor,
    final float cameraHeight,
    final float distanceScale,
    final float minDistance,
    final float maxDistance
  ) {
    final Vector3f playerPos = focusPosition(player);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(playerPos, enemies.center);
    if(forward == null) {
      return false;
    }

    final Vector3f portraitDirection = portraitDirection(player, forward, PORTRAIT_FRONT_BIAS);

    final float halfHeight = subjectHalfHeight(player);
    desiredRefpoint.set(playerPos).add(0.0f, -halfHeight * lookHeightFactor, 0.0f);
    final float distance = clamp(
      halfHeight * distanceScale,
      minDistance,
      maxDistance
    );
    desiredViewpoint.set(desiredRefpoint).add(portraitRay(portraitDirection, cameraHeight).mul(distance));
    return true;
  }

  private static boolean buildTargetShot(final BattleMenuStruct58 menu) {
    if(menu.combatantIndex_54 == -1) {
      return buildGroupTargetShot(menu.targetType_50);
    }

    final List<? extends ScriptState<? extends BattleEntity27c>> targets =
      battleState_8006e398.getBentsForTargetType(menu.targetType_50);
    if(menu.combatantIndex_54 < 0 || menu.combatantIndex_54 >= targets.size()) {
      return false;
    }

    final ScriptState<? extends BattleEntity27c> targetState = targets.get(menu.combatantIndex_54);
    if(targetState == null || targetState.hasFlag(FLAG_CANT_TARGET)) {
      return false;
    }

    return buildSingleTargetShot(targetState.innerStruct_00);
  }

  private static boolean buildSingleTargetShot(final BattleEntity27c target) {
    final Vector3f targetPos = focusPosition(target);
    final Vector3f actorPos = focusPosition(actor);

    Vector3f cameraDirection = horizontalDirection(targetPos, actorPos);
    if(cameraDirection == null) {
      final GroupFrame enemies = frameTargets(1);
      if(enemies == null || (cameraDirection = horizontalDirection(targetPos, enemies.center)) == null) {
        return false;
      }
      cameraDirection.mul(-1.0f);
    }

    desiredRefpoint.set(targetPos);
    final float preferredDistance = targetFramingDistance > 0.0f ? targetFramingDistance : baseDistance;
    final float distance = subjectDistance(target, preferredDistance, 2800.0f, 6200.0f);
    final Vector3f side = rightOf(cameraDirection).mul(0.08f * shotSide);
    cameraDirection.add(side).normalize();
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(cameraDirection).mul(distance));
    return true;
  }

  private static boolean buildGroupTargetShot(final int targetType) {
    final GroupFrame group = frameTargets(targetType);
    if(group == null) {
      return false;
    }

    final Vector3f actorPos = focusPosition(actor);
    Vector3f cameraDirection = horizontalDirection(group.center, actorPos);
    if(cameraDirection == null) {
      final GroupFrame enemies = frameTargets(1);
      if(enemies == null || (cameraDirection = horizontalDirection(group.center, enemies.center)) == null) {
        return false;
      }
      cameraDirection.mul(-1.0f);
    }

    desiredRefpoint.set(group.center);
    final float distance = clamp(
      java.lang.Math.max(
        (targetFramingDistance > 0.0f ? targetFramingDistance : baseDistance) * 1.04f,
        group.radius * 1.70f + group.maxHalfHeight * 1.90f
      ),
      2450.0f,
      6500.0f
    );
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(cameraDirection).mul(distance));
    return true;
  }

  private static boolean buildBattlefieldWideShot() {
    return buildBattlefieldWideShot(null);
  }

  private static boolean buildBattlefieldWideShot(final Vector3f extraPoint) {
    final GroupFrame party = frameTargets(0);
    final GroupFrame enemies = frameTargets(1);
    if(party == null || enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(party.center, enemies.center);
    if(forward == null) {
      return false;
    }

    desiredRefpoint.set(party.center).lerp(enemies.center, 0.5f);

    // Bound the actual live combatant positions around the final wide-shot
    // center rather than approximating from the two formation radii. This also
    // handles Front/Back Row spreads and irregular encounter formations.
    float radius = 0.0f;
    float maxHalfHeight = 0.0f;
    for(int targetType = 0; targetType <= 1; targetType++) {
      final List<? extends ScriptState<? extends BattleEntity27c>> targets =
        battleState_8006e398.getBentsForTargetType(targetType);

      for(final ScriptState<? extends BattleEntity27c> targetState : targets) {
        if(targetState == null || targetState.hasFlag(FLAG_CANT_TARGET)) {
          continue;
        }

        final BattleEntity27c target = targetState.innerStruct_00;
        radius = java.lang.Math.max(
          radius,
          horizontalDistance(desiredRefpoint, focusPosition(target))
        );
        maxHalfHeight = java.lang.Math.max(maxHalfHeight, subjectHalfHeight(target));
      }
    }

    // Row/Switch shots are frozen before movement begins. Include the pending
    // destination so a character moving into Back Row cannot leave the frame.
    if(extraPoint != null) {
      radius = java.lang.Math.max(radius, horizontalDistance(desiredRefpoint, extraPoint));
      if(actor != null) {
        maxHalfHeight = java.lang.Math.max(maxHalfHeight, subjectHalfHeight(actor));
      }
    }

    final float distance = clamp(
      java.lang.Math.max(
        5200.0f,
        radius * 2.35f + maxHalfHeight * 2.60f
      ),
      5200.0f,
      11000.0f
    );

    // Neutral battle overview from the party side, with just enough lateral
    // angle to retain depth without favoring a specific combatant.
    final Vector3f cameraDirection = new Vector3f(forward).mul(-1.0f)
      .add(new Vector3f(rightOf(forward)).mul(0.32f * shotSide))
      .normalize();
    desiredViewpoint.set(desiredRefpoint).add(
      new Vector3f(cameraDirection.x, -0.18f, cameraDirection.z)
        .normalize()
        .mul(distance)
    );
    return true;
  }

  private static boolean buildGuardShot() {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f front = horizontalDirection(actorPos, enemies.center);
    if(front == null) {
      return false;
    }

    final float halfHeight = subjectHalfHeight(actor);
    desiredRefpoint.set(actorPos).add(0.0f, -halfHeight * GUARD_LOOK_HEIGHT_FACTOR, 0.0f);
    final float distance = clamp(
      halfHeight * 1.75f,
      1650.0f,
      3300.0f
    );
    desiredViewpoint.set(desiredRefpoint).add(portraitRay(front, GUARD_CAMERA_HEIGHT).mul(distance));
    return true;
  }

  private static boolean buildPartyWideShot() {
    final GroupFrame party = frameTargets(0);
    final GroupFrame enemies = frameTargets(1);
    if(party == null || enemies == null) {
      return false;
    }

    Vector3f front = horizontalDirection(party.center, enemies.center);
    if(front == null) {
      return false;
    }

    final Vector3f side = rightOf(front).mul(0.18f * shotSide);
    front.add(side).normalize();

    desiredRefpoint.set(party.center);
    final float distance = clamp(
      java.lang.Math.max(
        baseDistance * 1.12f,
        party.radius * 1.85f + party.maxHalfHeight * 2.0f
      ),
      3000.0f,
      6200.0f
    );
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(front).mul(distance));
    return true;
  }

  private static boolean buildMovementShot(final BattleEntity27c moving, final Vector3f anchor) {
    final Vector3f current = focusPosition(moving);
    final Vector3f anchorFocus = new Vector3f(anchor)
      .add(0.0f, -moving.middleOffsetY_86 * 50.0f, 0.0f);

    Vector3f movement = horizontalDirection(anchorFocus, current);
    if(movement == null) {
      final GroupFrame enemies = frameTargets(1);
      if(enemies == null || (movement = horizontalDirection(current, enemies.center)) == null) {
        return false;
      }
    }

    final Vector3f side = rightOf(movement).mul(shotSide);
    desiredRefpoint.set(anchorFocus).lerp(current, 0.5f);

    final float corridor = horizontalDistance(anchorFocus, current);
    final float distance = clamp(
      java.lang.Math.max(
        subjectDistance(moving, baseDistance * 1.02f, 2100.0f, 4300.0f),
        1900.0f + corridor * 0.62f
      ),
      2200.0f,
      5200.0f
    );
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(side).mul(distance));
    return true;
  }

  private static boolean buildProjectileShot(final float progress) {
    final Vector3f impact = itemImpactPosition();
    if(impact == null) {
      return false;
    }

    final Vector3f travel = horizontalDirection(itemStart, impact);
    if(travel == null) {
      return buildStoredItemTargetShot();
    }

    final Vector3f projectile = new Vector3f(itemStart).lerp(impact, progress);
    desiredRefpoint.set(projectile);

    final Vector3f side = rightOf(travel).mul(shotSide);
    final Vector3f trailing = new Vector3f(travel).mul(-0.22f);
    side.add(trailing).normalize();

    final float distance = clamp(baseDistance * 0.46f, 1200.0f, 2100.0f);
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(side).mul(distance));
    return true;
  }

  private static boolean buildStoredItemTargetShot() {
    if(itemTargetsAll) {
      return buildGroupTargetShot(itemTargetType);
    }

    return itemTarget != null && buildSingleTargetShot(itemTarget);
  }

  private static Vector3f itemImpactPosition() {
    if(itemTargetsAll) {
      final GroupFrame group = frameTargets(itemTargetType);
      return group != null ? new Vector3f(group.center) : null;
    }

    return itemTarget != null ? focusPosition(itemTarget) : null;
  }

  private static GroupFrame frameTargets(final int targetType) {
    final List<? extends ScriptState<? extends BattleEntity27c>> targets =
      battleState_8006e398.getBentsForTargetType(targetType);

    final Vector3f center = new Vector3f();
    int count = 0;
    float maxHalfHeight = 0.0f;

    for(final ScriptState<? extends BattleEntity27c> targetState : targets) {
      if(targetState == null || targetState.hasFlag(FLAG_CANT_TARGET)) {
        continue;
      }

      final BattleEntity27c target = targetState.innerStruct_00;
      center.add(focusPosition(target));
      maxHalfHeight = java.lang.Math.max(maxHalfHeight, subjectHalfHeight(target));
      count++;
    }

    if(count == 0) {
      return null;
    }

    center.div(count);
    float radius = 0.0f;
    for(final ScriptState<? extends BattleEntity27c> targetState : targets) {
      if(targetState == null || targetState.hasFlag(FLAG_CANT_TARGET)) {
        continue;
      }

      radius = java.lang.Math.max(radius, horizontalDistance(center, focusPosition(targetState.innerStruct_00)));
    }

    return new GroupFrame(center, radius, maxHalfHeight);
  }

  private static Vector3f focusPosition(final BattleEntity27c entity) {
    return new Vector3f(entity.getPosition())
      .add(0.0f, -entity.middleOffsetY_86 * 50.0f, 0.0f);
  }

  private static float subjectHalfHeight(final BattleEntity27c entity) {
    return java.lang.Math.max(700.0f, java.lang.Math.abs(entity.middleOffsetY_86) * 50.0f);
  }

  private static float subjectDistance(
    final BattleEntity27c entity,
    final float preferred,
    final float min,
    final float max
  ) {
    return clamp(
      java.lang.Math.max(preferred, subjectHalfHeight(entity) * 2.05f),
      min,
      max
    );
  }

  private static Vector3f portraitDirection(
    final PlayerBattleEntity player,
    final Vector3f fallbackForward,
    final float frontBias
  ) {
    final float yaw = player.model_148.coord2_14.transforms.rotate.y;
    final Vector3f facing = new Vector3f(
      -(float)java.lang.Math.sin(yaw),
      0.0f,
      -(float)java.lang.Math.cos(yaw)
    );

    if(facing.lengthSquared() < 0.001f) {
      facing.set(fallbackForward);
    } else {
      facing.normalize();
    }

    // Stay recognizably side-on, but sit in the front hemisphere of the
    // character so idle-pose torso twists cannot turn the portrait into a
    // back shot.
    return rightOf(facing).mul(shotSide)
      .add(new Vector3f(facing).mul(frontBias))
      .normalize();
  }

  private static Vector3f portraitRay(final Vector3f horizontalDirection, final float cameraHeight) {
    return new Vector3f(
      horizontalDirection.x,
      cameraHeight,
      horizontalDirection.z
    ).normalize();
  }

  private static Vector3f cameraRay(final Vector3f horizontalDirection) {
    final Vector3f ray = new Vector3f(
      horizontalDirection.x,
      baseRay.y * 0.82f,
      horizontalDirection.z
    );

    if(ray.lengthSquared() < 0.001f) {
      return new Vector3f(baseRay);
    }

    return ray.normalize();
  }

  private static Vector3f horizontalDirection(final Vector3f from, final Vector3f to) {
    final Vector3f direction = new Vector3f(to.x - from.x, 0.0f, to.z - from.z);
    if(direction.lengthSquared() < 1.0f) {
      return null;
    }

    return direction.normalize();
  }

  private static Vector3f rightOf(final Vector3f forward) {
    return new Vector3f(-forward.z, 0.0f, forward.x);
  }

  private static float horizontalDistance(final Vector3f a, final Vector3f b) {
    final float dx = b.x - a.x;
    final float dz = b.z - a.z;
    return (float)java.lang.Math.sqrt(dx * dx + dz * dz);
  }

  private static float clamp(final float value, final float min, final float max) {
    return java.lang.Math.max(min, java.lang.Math.min(max, value));
  }

  private static void applyCamera(final Battle battle, final float blend) {
    currentViewpoint.lerp(desiredViewpoint, blend);
    currentRefpoint.lerp(desiredRefpoint, blend);
    setCameraDirect(battle, currentViewpoint, currentRefpoint);
  }

  private static void setCameraDirect(
    final Battle battle,
    final Vector3f viewpoint,
    final Vector3f refpoint
  ) {
    final var camera = battle.camera_800c67f0;
    camera.flags_11c &= ~(UPDATE_VIEWPOINT | UPDATE_REFPOINT);
    camera.viewpointMoving_122 = false;
    camera.refpointMoving_123 = false;
    camera.setRefpoint(refpoint.x, refpoint.y, refpoint.z);
    camera.setViewpoint(viewpoint.x, viewpoint.y, viewpoint.z);
  }

  private static void suspend() {
    ownsCamera = false;
    modeTicks = 0;
  }

  private static void stopTracker() {
    if(tracker != null) {
      tracker.deallocateWithChildren();
      tracker = null;
    }

    mode = null;
    actor = null;
    enemyActor = null;
    attackHandoffTicksRemaining = 0;
    itemTarget = null;
    itemTargetType = 0;
    itemTargetsAll = false;
    itemPhase = 0;
    itemPhaseTicks = 0;
    sawCommandMenu = false;
    ownsCamera = false;
    modeTicks = 0;
    actionWideCaptured = false;
    turnEntrySnapped = false;
    turnEntryTicks = 0;
    targetFramingDistance = 0.0f;
  }

  private static void stopTracker(final ScriptState<BattleEntity27c> state) {
    if(tracker == state) {
      stopTracker();
    } else {
      state.deallocateWithChildren();
    }
  }

  private static final class GroupFrame {
    private final Vector3f center;
    private final float radius;
    private final float maxHalfHeight;

    private GroupFrame(final Vector3f center, final float radius, final float maxHalfHeight) {
      this.center = center;
      this.radius = radius;
      this.maxHalfHeight = maxHalfHeight;
    }
  }
}

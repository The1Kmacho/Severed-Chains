package legend.gameplayoverhaul.camera;

import legend.core.platform.input.InputAction;
import legend.game.combat.Battle;
import legend.game.combat.PartySwitchPreparation;
import legend.game.combat.bent.BattleEntity27c;
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
  private static final float PROJECTILE_BLEND = 0.30f;

  private static final float IDLE_DRIFT_SPEED = 0.018f;
  private static final float IDLE_CAMERA_DRIFT = 72.0f;
  private static final float IDLE_LOOK_DRIFT = 18.0f;

  private static final int GUARD_HOLD_TICKS = 30;
  private static final int ESCAPE_HOLD_TICKS = 42;
  private static final int ITEM_THROW_ANIMATION_TICKS = 16;
  private static final int ITEM_PROJECTILE_TICKS = 15;
  private static final int ITEM_IMPACT_HOLD_TICKS = 16;
  private static final int ITEM_THROW_TIMEOUT_TICKS = 120;

  private enum Mode {
    COMMAND,
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
  private static BattleEntity27c itemTarget;
  private static int itemTargetType;
  private static boolean itemTargetsAll;
  private static int itemPhase;
  private static int itemPhaseTicks;
  private static int modeTicks;
  private static boolean sawCommandMenu;
  private static boolean ownsCamera;
  private static float shotSide;

  private static final Vector3f actionAnchor = new Vector3f();
  private static final Vector3f itemStart = new Vector3f();
  private static final Vector3f baseRay = new Vector3f();
  private static float baseDistance;
  private static final Vector3f currentViewpoint = new Vector3f();
  private static final Vector3f currentRefpoint = new Vector3f();
  private static final Vector3f desiredViewpoint = new Vector3f();
  private static final Vector3f desiredRefpoint = new Vector3f();

  private BattleCameraDirector() { }

  public static void onTurn(final BattleEntity27c entity) {
    stopTracker();

    if(!(entity instanceof final PlayerBattleEntity player) || player.typeBentSlot_276 < 0) {
      return;
    }

    start(Mode.COMMAND, player);
    sawCommandMenu = false;
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

    if(battle.hud.listMenu_800c6b60 != null || menu.displayTargetArrowAndName_4c) {
      return;
    }

    boolean escapeSelected = action == LodMod.INPUT_ACTION_BTTL_ESCAPE.get();
    if(!escapeSelected
      && action == INPUT_ACTION_MENU_CONFIRM.get()
      && menu.selectedIcon_22 >= 0
      && menu.selectedIcon_22 < menu.actions.size()) {
      final BattleAction selected = menu.actions.get(menu.selectedIcon_22);
      escapeSelected = selected == LodBattleActions.ESCAPE.get();
    }

    if(escapeSelected) {
      startEscapeShot(player);
    }
  }

  public static void startGuardShot(final PlayerBattleEntity player) {
    start(Mode.GUARD, player);
  }

  public static void startRowShot(final PlayerBattleEntity player, final Vector3f target) {
    start(Mode.ROW, player);
    actionAnchor.set(target);
  }

  public static void startSwitchShot(final PartySwitchPreparation preparation) {
    start(Mode.SWITCH_OUT, preparation.outgoing);
    actionAnchor.set(preparation.formationPosition);
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
    ownsCamera = false;
    modeTicks = 0;
    sawCommandMenu = nextMode != Mode.COMMAND;
    shotSide = (player.typeBentSlot_276 & 1) == 0 ? -1.0f : 1.0f;

    tracker = SCRIPTS.allocateScriptState("GameplayOverhaulBattleCamera", (BattleEntity27c)player);
    tracker.setTicker(BattleCameraDirector::tick);
  }

  private static void tick(final ScriptState<BattleEntity27c> state, final BattleEntity27c ignored) {
    if(actor == null || mode == null || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      stopTracker(state);
      return;
    }

    if(mode == Mode.COMMAND) {
      tickCommand(state, battle);
      return;
    }

    if(!ownsCamera && !captureCamera(battle)) {
      return;
    }

    modeTicks++;

    switch(mode) {
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
        if(!buildMovementShot(actor, actionAnchor)) {
          stopTracker(state);
          return;
        }
        applyCamera(battle, ACTION_BLEND);
      }

      case ITEM_ATTACK -> tickAttackItem(state, battle);
      default -> { }
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

    if(menu.currentAction != null) {
      stopTracker(state);
      return;
    }

    if(battle.hud.listMenu_800c6b60 != null || menu.pauseCurrentAction || menu.targetArrowHiding || menu.state_00 == 5) {
      suspend();
      return;
    }

    if(!ownsCamera && !captureCamera(battle)) {
      return;
    }

    final boolean targeting = menu.displayTargetArrowAndName_4c;
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
      if(!buildCommandShot()) {
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
      if(!buildCommandShot()) {
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
    ownsCamera = true;
    modeTicks = 0;
    return true;
  }

  private static boolean buildCommandShot() {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(actorPos, enemies.center);
    if(forward == null) {
      return false;
    }

    final Vector3f side = rightOf(forward).mul(shotSide);
    desiredRefpoint.set(actorPos);
    final float distance = subjectDistance(actor, baseDistance * 1.03f, 1900.0f, 3900.0f);
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(side).mul(distance));

    final float drift = (float)java.lang.Math.sin(modeTicks * IDLE_DRIFT_SPEED) * IDLE_CAMERA_DRIFT;
    final float lookDrift = (float)java.lang.Math.sin(modeTicks * IDLE_DRIFT_SPEED * 0.71f + 0.8f) * IDLE_LOOK_DRIFT;
    desiredViewpoint.add(new Vector3f(forward).mul(drift));
    desiredRefpoint.add(new Vector3f(forward).mul(lookDrift));
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
    final float distance = subjectDistance(target, baseDistance, 2050.0f, 5400.0f);
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
        baseDistance * 1.04f,
        group.radius * 1.70f + group.maxHalfHeight * 1.90f
      ),
      2450.0f,
      6500.0f
    );
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(cameraDirection).mul(distance));
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

    desiredRefpoint.set(actorPos);
    final float distance = subjectDistance(actor, baseDistance, 1900.0f, 3900.0f);
    desiredViewpoint.set(desiredRefpoint).add(cameraRay(front).mul(distance));
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

    final var camera = battle.camera_800c67f0;
    camera.flags_11c &= ~(UPDATE_VIEWPOINT | UPDATE_REFPOINT);
    camera.viewpointMoving_122 = false;
    camera.refpointMoving_123 = false;
    camera.setRefpoint(currentRefpoint.x, currentRefpoint.y, currentRefpoint.z);
    camera.setViewpoint(currentViewpoint.x, currentViewpoint.y, currentViewpoint.z);
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
    itemTarget = null;
    itemTargetType = 0;
    itemTargetsAll = false;
    itemPhase = 0;
    itemPhaseTicks = 0;
    sawCommandMenu = false;
    ownsCamera = false;
    modeTicks = 0;
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

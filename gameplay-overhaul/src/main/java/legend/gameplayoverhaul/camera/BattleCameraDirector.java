package legend.gameplayoverhaul.camera;

import legend.game.combat.Battle;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.ui.BattleMenuStruct58;
import legend.game.scripting.ScriptState;
import org.joml.Vector3f;

import java.util.List;

import static legend.core.GameEngine.SCRIPTS;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.combat.bent.BattleEntity27c.FLAG_CANT_TARGET;
import static legend.game.combat.environment.BattleCamera.UPDATE_REFPOINT;
import static legend.game.combat.environment.BattleCamera.UPDATE_VIEWPOINT;

/**
 * Cinematic command-phase camera. It owns the battle camera only while the
 * active player is choosing a command or target, then yields completely to
 * retail/scripted action cameras.
 */
public final class BattleCameraDirector {
  private static final float TURN_FOCUS_BLEND = 0.12f;
  private static final float TARGET_FOCUS_BLEND = 0.16f;
  private static final float BASE_RAY_BEHIND_BLEND = 0.46f;
  private static final float TURN_DISTANCE_SCALE = 0.78f;
  private static final float SINGLE_TARGET_DISTANCE_SCALE = 0.82f;
  private static final float GROUP_TARGET_DISTANCE_SCALE = 0.92f;
  private static final float IDLE_DRIFT_SPEED = 0.018f;
  private static final float IDLE_DRIFT_DISTANCE = 72.0f;
  private static final float IDLE_LOOK_DRIFT_DISTANCE = 24.0f;

  private static ScriptState<BattleEntity27c> tracker;
  private static PlayerBattleEntity actor;
  private static boolean sawCommandMenu;
  private static boolean ownsCamera;
  private static int cameraTicks;
  private static float shotSide;

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

    actor = player;
    sawCommandMenu = false;
    ownsCamera = false;
    cameraTicks = 0;

    // Keep a stable side preference per combat slot so consecutive turns feel
    // authored rather than randomly flipping across the battle axis.
    shotSide = (player.typeBentSlot_276 & 1) == 0 ? -1.0f : 1.0f;

    tracker = SCRIPTS.allocateScriptState("GameplayOverhaulBattleCamera", (BattleEntity27c)player);
    tracker.setTicker(BattleCameraDirector::tick);
  }

  public static void endBattle() {
    stopTracker();
  }

  private static void tick(final ScriptState<BattleEntity27c> state, final BattleEntity27c ignored) {
    if(actor == null || !(currentEngineState_8004dd04 instanceof final Battle battle)) {
      stopTracker(state);
      return;
    }

    final BattleMenuStruct58 menu = battle.hud.battleMenu_800c6c34;

    if(menu.player_04 != actor || menu.state_00 == 0) {
      if(sawCommandMenu) {
        stopTracker(state);
      }
      return;
    }

    sawCommandMenu = true;

    // List menus and menu-camera transitions are allowed to use their normal
    // presentation. A selected action is an immediate hand-off to retail.
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
    final boolean framed = targeting ? buildTargetShot(menu) : buildTurnShot();
    if(!framed) {
      suspend();
      return;
    }

    cameraTicks++;
    applyCamera(battle, targeting ? TARGET_FOCUS_BLEND : TURN_FOCUS_BLEND);
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
    cameraTicks = 0;
    return true;
  }

  private static boolean buildTurnShot() {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame enemies = frameTargets(1);
    if(enemies == null) {
      return false;
    }

    final Vector3f forward = horizontalDirection(actorPos, enemies.center);
    if(forward == null) {
      return false;
    }

    desiredRefpoint.set(actorPos).lerp(enemies.center, 0.24f);
    final float distance = clamp(baseDistance * TURN_DISTANCE_SCALE, 1250.0f, 3000.0f);
    buildViewpoint(forward, distance, 0.17f);

    final Vector3f right = rightOf(forward);
    final float drift = (float)java.lang.Math.sin(cameraTicks * IDLE_DRIFT_SPEED) * IDLE_DRIFT_DISTANCE;
    final float lookDrift = (float)java.lang.Math.sin(cameraTicks * IDLE_DRIFT_SPEED * 0.73f + 0.8f) * IDLE_LOOK_DRIFT_DISTANCE;
    desiredViewpoint.add(new Vector3f(right).mul(drift));
    desiredRefpoint.add(new Vector3f(right).mul(lookDrift));
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

    final Vector3f actorPos = focusPosition(actor);
    final Vector3f targetPos = focusPosition(targetState.innerStruct_00);
    Vector3f forward = horizontalDirection(actorPos, targetPos);
    if(forward == null) {
      final GroupFrame enemies = frameTargets(1);
      if(enemies == null || (forward = horizontalDirection(actorPos, enemies.center)) == null) {
        return false;
      }
    }

    desiredRefpoint.set(actorPos).lerp(targetPos, 0.52f);
    final float actorTargetDistance = horizontalDistance(actorPos, targetPos);
    final float distance = clamp(
      java.lang.Math.max(baseDistance * SINGLE_TARGET_DISTANCE_SCALE, actorTargetDistance * 0.62f),
      1150.0f,
      3200.0f
    );
    buildViewpoint(forward, distance, 0.11f);
    return true;
  }

  private static boolean buildGroupTargetShot(final int targetType) {
    final Vector3f actorPos = focusPosition(actor);
    final GroupFrame group = frameTargets(targetType);
    if(group == null) {
      return false;
    }

    Vector3f forward = horizontalDirection(actorPos, group.center);
    if(forward == null) {
      final GroupFrame enemies = frameTargets(1);
      if(enemies == null || (forward = horizontalDirection(actorPos, enemies.center)) == null) {
        return false;
      }
    }

    desiredRefpoint.set(actorPos).lerp(group.center, 0.60f);
    final float actorGroupDistance = horizontalDistance(actorPos, group.center);
    final float distance = clamp(
      java.lang.Math.max(
        baseDistance * GROUP_TARGET_DISTANCE_SCALE,
        actorGroupDistance * 0.66f + group.radius * 0.90f
      ),
      1500.0f,
      4000.0f
    );
    buildViewpoint(forward, distance, 0.08f);
    return true;
  }

  private static void buildViewpoint(final Vector3f forward, final float distance, final float sideBias) {
    final Vector3f behind = new Vector3f(-forward.x, baseRay.y, -forward.z);
    if(behind.lengthSquared() < 0.001f) {
      behind.set(baseRay);
    } else {
      behind.normalize();
    }

    final Vector3f ray = new Vector3f(baseRay).lerp(behind, BASE_RAY_BEHIND_BLEND);
    ray.add(new Vector3f(rightOf(forward)).mul(sideBias * shotSide));
    if(ray.lengthSquared() < 0.001f) {
      ray.set(baseRay);
    } else {
      ray.normalize();
    }

    desiredViewpoint.set(desiredRefpoint).add(ray.mul(distance));
  }

  private static GroupFrame frameTargets(final int targetType) {
    final List<? extends ScriptState<? extends BattleEntity27c>> targets =
      battleState_8006e398.getBentsForTargetType(targetType);

    final Vector3f center = new Vector3f();
    int count = 0;

    for(final ScriptState<? extends BattleEntity27c> targetState : targets) {
      if(targetState == null || targetState.hasFlag(FLAG_CANT_TARGET)) {
        continue;
      }

      center.add(focusPosition(targetState.innerStruct_00));
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

    return new GroupFrame(center, radius);
  }

  private static Vector3f focusPosition(final BattleEntity27c entity) {
    return new Vector3f(entity.getPosition())
      .add(0.0f, -entity.middleOffsetY_86 * 50.0f, 0.0f);
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
    cameraTicks = 0;
  }

  private static void stopTracker() {
    if(tracker != null) {
      tracker.deallocateWithChildren();
      tracker = null;
    }

    actor = null;
    sawCommandMenu = false;
    ownsCamera = false;
    cameraTicks = 0;
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

    private GroupFrame(final Vector3f center, final float radius) {
      this.center = center;
      this.radius = radius;
    }
  }
}

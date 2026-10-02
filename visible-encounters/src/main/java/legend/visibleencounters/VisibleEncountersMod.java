package legend.visibleencounters;

import legend.core.IoHelper;
import legend.core.MathHelper;
import legend.core.gpu.Rect4i;
import legend.core.gpu.VramTextureLoader;
import legend.core.gpu.VramTextureSingle;
import legend.core.renderer.TextureBuilder;
import legend.core.renderer.TextureDataFormat;
import legend.core.renderer.TextureDataType;
import legend.core.renderer.TextureInternalFormat;
import legend.game.EngineStates;
import legend.game.combat.encounters.Encounter;
import legend.game.modding.events.battle.BattleStartedEvent;
import legend.game.modding.events.submap.SubmapEncounterRateEvent;
import legend.game.modding.events.submap.SubmapRuntimeObjectsEvent;
import legend.game.scripting.ScriptState;
import legend.game.submap.CollisionGeometry;
import legend.game.submap.SubmapObject;
import legend.game.submap.SubmapObject210;
import legend.game.submap.SubmapObjectTickable;
import legend.game.submap.SMap;
import legend.game.tim.Tim;
import legend.game.types.CContainer;
import legend.game.types.TmdAnimationFile;
import legend.game.unpacker.FileData;
import legend.game.unpacker.Loader;
import legend.game.unpacker.Unpacker;
import legend.lodmod.LodEngineStateTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static legend.game.Models.loadModelStandardAnimation;
import static legend.game.Scus94491BpeSegment_8004.doNothingScript_8004f650;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;

@Mod(id = VisibleEncountersMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class VisibleEncountersMod {
  public static final String MOD_ID = "visible_encounters";

  private static final Logger LOGGER = LogManager.getFormatterLogger(VisibleEncountersMod.class);

  private static final int MAX_ACTORS_PER_MAP = 5;
  private static final int SPAWN_GRACE_TICKS = 60;
  private static final int ALERT_TICKS = 60;
  private static final int SIGHT_RECHECK_TICKS = 6;
  private static final int PATH_RECHECK_TICKS = 10;
  private static final int PATROL_MOVE_TICKS_MIN = 18;
  private static final int PATROL_MOVE_TICKS_RANGE = 18;
  private static final int PATROL_IDLE_TICKS_MIN = 120;
  private static final int PATROL_IDLE_TICKS_RANGE = 120;
  private static final float LINE_OF_SIGHT_SAMPLE_STEP = 16.0f;
  private static final float WALKABLE_AREA_PER_ACTOR = 350_000.0f;

  // Battle models use much larger world units than retail field SOBJs.
  // 1/16 matches the tested field scale; keep this unchanged.
  private static final float FIELD_MODEL_SCALE = 0.0625f;
  private static final float PATROL_SPEED = 0.75f;
  private static final float PATROL_RADIUS = 180.0f;
  private static final float INITIAL_PLAYER_SPEED_CAP = 2.0f;
  private static final float MAX_REASONABLE_PLAYER_SPEED = 10.0f;
  private static final float CHASE_SPEED_FRACTION = 0.70f;
  private static final float CHASE_SPEED_MAX = 2.5f;
  private static final float SIGHT_DISTANCE = 700.0f;
  private static final float CLOSE_DETECTION_DISTANCE = 170.0f;
  private static final float LOSE_DISTANCE = 980.0f;
  private static final float CONTACT_DISTANCE = 62.0f;
  private static final float CONTACT_VERTICAL_TOLERANCE = 72.0f;
  private static final float SIGHT_VERTICAL_TOLERANCE = 180.0f;
  private static final float SPAWN_VERTICAL_TOLERANCE = 220.0f;
  private static final float SPAWN_MIN_DISTANCE = 500.0f;
  private static final float SPAWN_MAX_DISTANCE = 1800.0f;
  private static final float SIGHT_COSINE = 0.50f; // 120-degree cone.

  private static int activeCut = Integer.MIN_VALUE;
  private static Object activeSubmap;
  private static boolean visibleEncountersEnabled;
  private static boolean pendingPlayerInitiative;
  private static final Set<Integer> CONSUMED_SLOTS = new HashSet<>();

  private static CollisionGeometry navGeometry;
  private static int[][] navNeighbours;
  private static Vector3f[] navCentres;
  private static final Vector3f lastPlayerPosition = new Vector3f();
  private static boolean hasPlayerPositionSample;
  private static float observedPlayerMaxSpeed = INITIAL_PLAYER_SPEED_CAP;

  public VisibleEncountersMod() { }

  @EventListener
  public static void encounterRate(final SubmapEncounterRateEvent event) {
    if(visibleEncountersEnabled && event.getSubmap() == activeSubmap && event.submapId >= 0) {
      event.encounterRate = 0;
    }
  }

  @EventListener
  public static void addVisibleEncounters(final SubmapRuntimeObjectsEvent event) {
    final boolean returningFromBattle =
      EngineStates.previousEngineState_8004dd28 == LodEngineStateTypes.BATTLE.get()
        && event.getSubmap().isReturningToSameMapAfterBattle();

    if(activeCut != event.submapCut || !returningFromBattle) {
      CONSUMED_SLOTS.clear();
    }

    activeCut = event.submapCut;
    activeSubmap = event.getSubmap();
    visibleEncountersEnabled = false;
    navGeometry = null;
    navNeighbours = null;
    navCentres = null;
    hasPlayerPositionSample = false;
    observedPlayerMaxSpeed = INITIAL_PLAYER_SPEED_CAP;

    // Ask for the retail rate before enabling our rate override. This preserves
    // maps and scenes where encounters are intentionally disabled.
    final int retailRate = event.getSubmap().getEncounterRate();
    final List<Encounter> pool = event.getSubmap().getEncounterPool();

    if(retailRate <= 0 || pool.isEmpty() || event.remainingCapacity() <= 0) {
      return;
    }

    int added = 0;
    final int actorCount = desiredActorCount(
      event.getEngineState().getCollisionGeometry(),
      event.remainingCapacity()
    );

    for(int slot = 0; slot < actorCount; slot++) {
      if(CONSUMED_SLOTS.contains(slot)) {
        continue;
      }

      final Encounter encounter = pool.get(slot % pool.size());
      if(encounter.monsters.isEmpty()) {
        continue;
      }

      final int monsterId = encounter.monsters.getFirst().id;

      try {
        final RuntimeMonsterAssets assets = loadMonsterAssets(monsterId);
        if(assets == null) {
          continue;
        }

        final int actorSlot = slot;
        final SubmapObject object = new SubmapObject();
        object.script = doNothingScript_8004f650;
        object.model = assets.model;
        object.animations.add(assets.idleAnimation);
        object.animations.add(assets.chaseAnimation);
        object.constructor = name -> new VisibleEncounterObject(
          name,
          actorSlot,
          encounter,
          assets.idleAnimation,
          assets.chaseAnimation
        );

        if(event.add(object, assets.texture, textureOverride(assets.texture))) {
          added++;
        }
      } catch(final RuntimeException ex) {
        LOGGER.warn(
          "[Visible Encounters] Failed to build field actor for monster %d on cut %d",
          monsterId,
          event.submapCut,
          ex
        );
      }
    }

    visibleEncountersEnabled = added > 0 || !CONSUMED_SLOTS.isEmpty();

    if(visibleEncountersEnabled) {
      LOGGER.info(
        "[Visible Encounters] Cut %d: %d/%d active actor(s), %d consumed",
        event.submapCut,
        added,
        actorCount,
        CONSUMED_SLOTS.size()
      );
    }
  }

  @EventListener
  public static void battleStarted(final BattleStartedEvent event) {
    if(!pendingPlayerInitiative) {
      return;
    }

    pendingPlayerInitiative = false;

    // Put every living player just over the retail action threshold. Because
    // the scheduler subtracts one threshold after each action, this guarantees
    // one opening turn for every player before normal ATB ordering resumes.
    final int playerCount = battleState_8006e398.alivePlayerBents_eac.size();
    for(int i = 0; i < playerCount; i++) {
      battleState_8006e398.alivePlayerBents_eac.get(i).innerStruct_00.turnValue_4c =
        0xd9 + playerCount - i;
    }

    for(final var monsterState : battleState_8006e398.aliveMonsterBents_ebc) {
      monsterState.innerStruct_00.turnValue_4c =
        java.lang.Math.min(monsterState.innerStruct_00.turnValue_4c, 0xd9);
    }

    LOGGER.info("[Visible Encounters] Player initiative: party receives the opening round");
  }

  private static RuntimeMonsterAssets loadMonsterAssets(final int monsterId) {
    final List<FileData> base = Loader.loadDirectorySync("SECT/DRGN0.BIN/" + (3137 + monsterId));
    final List<FileData> attacks = Loader.loadDirectorySync("SECT/DRGN0.BIN/" + (3593 + monsterId));

    if(base.size() <= 32 || base.get(32) == null || !base.get(32).hasVirtualSize()) {
      LOGGER.warn("[Visible Encounters] Monster %d has no usable battle model", monsterId);
      return null;
    }

    final TmdAnimationFile idle = firstAnimation(base, 0);
    final TmdAnimationFile chase = locomotionAnimation(attacks);

    if(idle == null || chase == null) {
      LOGGER.warn("[Visible Encounters] Monster %d is missing idle/chase animation data", monsterId);
      return null;
    }

    final String texturePath = "monsters/" + monsterId + "/textures/combat";
    if(!Loader.exists(texturePath)) {
      LOGGER.warn("[Visible Encounters] Monster %d has no combat texture", monsterId);
      return null;
    }

    final Tim texture = new Tim(Loader.loadFileSync(texturePath));
    final CContainer model = new CContainer(
      "Visible encounter monster " + monsterId,
      decompressedIfNeeded(base.get(32))
    );

    return new RuntimeMonsterAssets(model, idle, chase, texture);
  }

  private static TmdAnimationFile locomotionAnimation(final List<FileData> files) {
    TmdAnimationFile best = null;
    float bestScore = -1.0f;
    final int limit = java.lang.Math.min(32, files.size());

    for(int i = 0; i < limit; i++) {
      final TmdAnimationFile animation = parseAnimation(files.get(i));
      if(animation == null || animation.partTransforms_10.length < 2 || animation.modelPartCount_0c == 0) {
        continue;
      }

      // Enemy attack archives are not semantically named. Prefer the animation
      // with the strongest root X/Z travel; in practice this selects the
      // charge/run/lunge animation far more reliably than assuming slot 0.
      final Vector3f start = animation.partTransforms_10[0][0].translate_06;
      float score = 0.0f;

      for(int frame = 1; frame < animation.partTransforms_10.length; frame++) {
        final Vector3f root = animation.partTransforms_10[frame][0].translate_06;
        final float dx = root.x - start.x;
        final float dz = root.z - start.z;
        score = java.lang.Math.max(score, dx * dx + dz * dz);
      }

      if(score > bestScore) {
        bestScore = score;
        best = animation;
      }
    }

    return best != null ? best : firstAnimation(files, 0);
  }

  private static TmdAnimationFile firstAnimation(final List<FileData> files, final int preferredIndex) {
    if(preferredIndex >= 0 && preferredIndex < files.size()) {
      final TmdAnimationFile preferred = parseAnimation(files.get(preferredIndex));
      if(preferred != null) {
        return preferred;
      }
    }

    final int limit = java.lang.Math.min(32, files.size());
    for(int i = 0; i < limit; i++) {
      if(i == preferredIndex) {
        continue;
      }

      final TmdAnimationFile animation = parseAnimation(files.get(i));
      if(animation != null) {
        return animation;
      }
    }

    return null;
  }

  private static TmdAnimationFile parseAnimation(final FileData source) {
    if(source == null || !source.hasVirtualSize() || source.size() < 8) {
      return null;
    }

    try {
      return new TmdAnimationFile(decompressedIfNeeded(source));
    } catch(final RuntimeException ignored) {
      return null;
    }
  }

  private static FileData decompressedIfNeeded(final FileData source) {
    if(source.size() >= 8 && source.readInt(0x4) == 0x1a45_5042) {
      return new FileData(Unpacker.decompress(source));
    }

    return source;
  }

  private static Consumer<TextureBuilder> textureOverride(final Tim tim) {
    if(!tim.hasClut()) {
      return null;
    }

    final VramTextureSingle texture = VramTextureLoader.textureFromTim(tim);
    final VramTextureSingle[] palettes = VramTextureLoader.palettesFromTim(tim);
    if(palettes.length == 0) {
      return null;
    }

    final int width = texture.rect.w();
    final int height = texture.rect.h();
    final int[] rgba = texture.applyPalette(palettes[0], new Rect4i(0, 0, width, height));

    for(int i = 0; i < rgba.length; i++) {
      if(rgba[i] != 0) {
        rgba[i] |= 0xff00_0000;
      }
    }

    return builder -> {
      final IntBuffer buffer = BufferUtils.createIntBuffer(rgba.length);
      buffer.put(0, rgba);
      builder.data(buffer, width, height);
      builder.internalFormat(TextureInternalFormat.RGBA_8);
      builder.dataFormat(TextureDataFormat.RGBA);
      builder.dataType(TextureDataType.UBYTE);
    };
  }

  private static int desiredActorCount(
    final CollisionGeometry collision,
    final int remainingCapacity
  ) {
    if(remainingCapacity <= 0) {
      return 0;
    }

    if(collision == null
      || collision.primitiveInfo_14 == null
      || collision.primitiveCount_0c <= 0) {
      return 1;
    }

    float walkableArea = 0.0f;

    for(int primitive = 0; primitive < collision.primitiveCount_0c; primitive++) {
      if(!isNavigablePrimitive(collision, primitive)) {
        continue;
      }

      final int[] indices = collisionPrimitiveVertexIndices(collision, primitive);
      if(indices.length < 3) {
        continue;
      }

      double twiceArea = 0.0;
      for(int i = 0; i < indices.length; i++) {
        final Vector3f a = collision.verts_04[indices[i]];
        final Vector3f b = collision.verts_04[indices[(i + 1) % indices.length]];
        twiceArea += (double)a.x * b.z - (double)b.x * a.z;
      }

      walkableArea += (float)(java.lang.Math.abs(twiceArea) * 0.5);
    }

    final int byArea = java.lang.Math.max(
      1,
      (int)java.lang.Math.ceil(walkableArea / WALKABLE_AREA_PER_ACTOR)
    );
    final int count = java.lang.Math.min(
      MAX_ACTORS_PER_MAP,
      java.lang.Math.min(remainingCapacity, byArea)
    );

    LOGGER.info(
      "[Visible Encounters] Walkable field area %.0f -> %d actor(s)",
      walkableArea,
      count
    );
    return count;
  }

  private static void samplePlayerSpeed(final Vector3f playerPosition) {
    if(hasPlayerPositionSample) {
      final float dx = playerPosition.x - lastPlayerPosition.x;
      final float dz = playerPosition.z - lastPlayerPosition.z;
      final float speed = (float)java.lang.Math.sqrt(dx * dx + dz * dz);

      // Ignore map restores/teleports. Normal movement teaches the encounter
      // actors Dart's actual field-speed ceiling instead of hard-coding a
      // faster chase speed.
      if(speed > 0.05f && speed <= MAX_REASONABLE_PLAYER_SPEED) {
        observedPlayerMaxSpeed =
          observedPlayerMaxSpeed * 0.90f + speed * 0.10f;
      }
    }

    lastPlayerPosition.set(playerPosition);
    hasPlayerPositionSample = true;
  }

  private static float chaseSpeed() {
    return java.lang.Math.min(
      CHASE_SPEED_MAX,
      java.lang.Math.max(1.0f, observedPlayerMaxSpeed * CHASE_SPEED_FRACTION)
    );
  }

  private static boolean isNavigablePrimitive(
    final CollisionGeometry collision,
    final int primitive
  ) {
    return primitive >= 0
      && primitive < collision.primitiveCount_0c
      && collision.primitiveInfo_14[primitive].flatEnoughToWalkOn_01
      && (collision.getCollisionAndTransitionInfo(primitive) & 0x38) == 0;
  }

  private static int[] collisionPrimitiveVertexIndices(
    final CollisionGeometry collision,
    final int primitiveIndex
  ) {
    final var primitiveInfo = collision.primitiveInfo_14[primitiveIndex];
    final var primitive = collision.getPrimitiveForOffset(primitiveInfo.primitiveOffset_04);
    final int packetOffset = primitiveInfo.primitiveOffset_04 - primitive.offset();
    final int packetIndex = packetOffset / (primitive.width() + 4);
    final int remainder = packetOffset % (primitive.width() + 4);
    final byte[] packet = primitive.data()[packetIndex];
    final int[] indices = new int[primitiveInfo.vertexCount_00];

    for(int i = 0; i < indices.length; i++) {
      indices[i] = IoHelper.readUShort(packet, remainder + 2 + i * 2);
    }

    return indices;
  }

  private static boolean shareCollisionEdge(final int[] a, final int[] b) {
    int shared = 0;

    for(final int av : a) {
      for(final int bv : b) {
        if(av == bv) {
          shared++;
          if(shared >= 2) {
            return true;
          }
          break;
        }
      }
    }

    return false;
  }

  private static void ensureNavigation(final CollisionGeometry collision) {
    if(navGeometry == collision
      && navNeighbours != null
      && navNeighbours.length == collision.primitiveCount_0c) {
      return;
    }

    navGeometry = collision;
    final int count = collision.primitiveCount_0c;
    navCentres = new Vector3f[count];
    navNeighbours = new int[count][];
    final int[][] vertices = new int[count][];

    for(int i = 0; i < count; i++) {
      navCentres[i] = new Vector3f();
      collision.getMiddleOfCollisionPrimitive(i, navCentres[i]);
      vertices[i] = collisionPrimitiveVertexIndices(collision, i);
    }

    final List<List<Integer>> neighbours = new ArrayList<>(count);
    for(int i = 0; i < count; i++) {
      neighbours.add(new ArrayList<>());
    }

    for(int a = 0; a < count; a++) {
      if(!isNavigablePrimitive(collision, a)) {
        continue;
      }

      for(int b = a + 1; b < count; b++) {
        if(!isNavigablePrimitive(collision, b)) {
          continue;
        }

        if(shareCollisionEdge(vertices[a], vertices[b])) {
          neighbours.get(a).add(b);
          neighbours.get(b).add(a);
        }
      }
    }

    for(int i = 0; i < count; i++) {
      navNeighbours[i] = neighbours.get(i).stream().mapToInt(Integer::intValue).toArray();
    }
  }

  private static boolean areNavNeighbours(final int a, final int b) {
    if(a == b) {
      return true;
    }

    if(navNeighbours == null || a < 0 || a >= navNeighbours.length) {
      return false;
    }

    for(final int neighbour : navNeighbours[a]) {
      if(neighbour == b) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasWalkableLineOfSight(
    final CollisionGeometry collision,
    final Vector3f from,
    final Vector3f to
  ) {
    ensureNavigation(collision);

    int previousPrimitive = collision.getCollisionPrimitiveAtPoint(
      from.x,
      from.y,
      from.z,
      true,
      true
    );
    final int targetPrimitive = collision.getCollisionPrimitiveAtPoint(
      to.x,
      to.y,
      to.z,
      true,
      true
    );

    if(!isNavigablePrimitive(collision, previousPrimitive)
      || !isNavigablePrimitive(collision, targetPrimitive)) {
      return false;
    }

    final float dx = to.x - from.x;
    final float dy = to.y - from.y;
    final float dz = to.z - from.z;
    final float horizontalDistance = (float)java.lang.Math.sqrt(dx * dx + dz * dz);
    final int steps = java.lang.Math.max(
      1,
      (int)java.lang.Math.ceil(horizontalDistance / LINE_OF_SIGHT_SAMPLE_STEP)
    );

    for(int i = 1; i <= steps; i++) {
      final float t = i / (float)steps;
      final int primitive = collision.getCollisionPrimitiveAtPoint(
        from.x + dx * t,
        from.y + dy * t,
        from.z + dz * t,
        true,
        true
      );

      if(!isNavigablePrimitive(collision, primitive)
        || !areNavNeighbours(previousPrimitive, primitive)) {
        return false;
      }

      previousPrimitive = primitive;
    }

    return previousPrimitive == targetPrimitive;
  }

  private static List<Integer> findPath(
    final CollisionGeometry collision,
    final int start,
    final int goal
  ) {
    ensureNavigation(collision);

    if(!isNavigablePrimitive(collision, start)
      || !isNavigablePrimitive(collision, goal)) {
      return List.of();
    }

    if(start == goal) {
      return List.of(start);
    }

    final int count = collision.primitiveCount_0c;
    final float[] cost = new float[count];
    final int[] previous = new int[count];
    final boolean[] closed = new boolean[count];
    Arrays.fill(cost, Float.POSITIVE_INFINITY);
    Arrays.fill(previous, -1);
    cost[start] = 0.0f;

    for(int iteration = 0; iteration < count; iteration++) {
      int current = -1;
      float bestScore = Float.POSITIVE_INFINITY;

      for(int candidate = 0; candidate < count; candidate++) {
        if(closed[candidate] || !Float.isFinite(cost[candidate])) {
          continue;
        }

        final float heuristic = horizontalDistance(navCentres[candidate], navCentres[goal]);
        final float score = cost[candidate] + heuristic;
        if(score < bestScore) {
          bestScore = score;
          current = candidate;
        }
      }

      if(current < 0) {
        break;
      }

      if(current == goal) {
        final List<Integer> path = new ArrayList<>();
        for(int node = goal; node >= 0; node = previous[node]) {
          path.add(node);
          if(node == start) {
            break;
          }
        }

        Collections.reverse(path);
        return path.getFirst() == start ? path : List.of();
      }

      closed[current] = true;

      for(final int neighbour : navNeighbours[current]) {
        if(closed[neighbour]) {
          continue;
        }

        final float nextCost =
          cost[current] + horizontalDistance(navCentres[current], navCentres[neighbour]);

        if(nextCost < cost[neighbour]) {
          cost[neighbour] = nextCost;
          previous[neighbour] = current;
        }
      }
    }

    return List.of();
  }

  private static float horizontalDistance(final Vector3f a, final Vector3f b) {
    final float dx = b.x - a.x;
    final float dz = b.z - a.z;
    return (float)java.lang.Math.sqrt(dx * dx + dz * dz);
  }

  private record RuntimeMonsterAssets(
    CContainer model,
    TmdAnimationFile idleAnimation,
    TmdAnimationFile chaseAnimation,
    Tim texture
  ) { }

  private static final class VisibleEncounterObject extends SubmapObject210 implements SubmapObjectTickable {
    private enum State {
      PATROL,
      CHASE,
      ENGAGED
    }

    private final int slot;
    private final Encounter encounter;
    private final TmdAnimationFile idleAnimation;
    private final TmdAnimationFile chaseAnimation;
    private final Vector3f home = new Vector3f();
    private final Vector3f movement = new Vector3f();
    private final Vector3f toPlayer = new Vector3f();
    private final Vector3f spawnCandidate = new Vector3f();

    private State state = State.PATROL;
    private boolean initialized;
    private boolean usingChaseAnimation;
    private boolean cachedPlayerVisible;
    private int graceTicks = SPAWN_GRACE_TICKS;
    private int alertTicks;
    private int sightRecheckTicks;
    private int pathRecheckTicks;
    private int patrolMoveTicks;
    private int patrolIdleTicks = PATROL_IDLE_TICKS_MIN / 2;
    private int patrolLeg;
    private List<Integer> chasePath = List.of();
    private int chasePathIndex;

    private VisibleEncounterObject(
      final String name,
      final int slot,
      final Encounter encounter,
      final TmdAnimationFile idleAnimation,
      final TmdAnimationFile chaseAnimation
    ) {
      super(name);
      this.slot = slot;
      this.encounter = encounter;
      this.idleAnimation = idleAnimation;
      this.chaseAnimation = chaseAnimation;
      this.hidden_128 = true;
    }

    @Override
    public void tick(
      final SMap smap,
      final ScriptState<SubmapObject210> scriptState,
      final SubmapObject210 ignored
    ) {
      if(this.state == State.ENGAGED) {
        return;
      }

      final ScriptState<SubmapObject210> playerState = smap.sobjs_800c6880[0];
      if(playerState == null) {
        return;
      }

      final SubmapObject210 player = playerState.innerStruct_00;
      samplePlayerSpeed(player.getPosition());

      if(!this.initialized) {
        if(!this.initializeOnField(smap, player)) {
          return;
        }
      }

      if(this.graceTicks > 0) {
        this.graceTicks--;
      }

      this.toPlayer.set(player.getPosition()).sub(this.getPosition());
      final float distanceSq = this.toPlayer.x * this.toPlayer.x + this.toPlayer.z * this.toPlayer.z;
      final float distance = (float)java.lang.Math.sqrt(distanceSq);
      final boolean contact =
        distance <= CONTACT_DISTANCE
          && java.lang.Math.abs(this.toPlayer.y) <= CONTACT_VERTICAL_TOLERANCE;

      if(this.state == State.PATROL) {
        // Player touching an unaware enemy is the initiative case. Resolve
        // contact before the close-range awareness check so approaching from
        // behind can actually earn the opening round.
        if(this.graceTicks == 0 && contact) {
          this.engage(smap, true);
          return;
        }

        if(this.graceTicks == 0 && this.canSeePlayer(smap, player, distance)) {
          this.beginChase();
        } else {
          this.tickPatrol(smap);
        }
      }

      if(this.state == State.CHASE) {
        if(this.alertTicks > 0) {
          this.alertTicks--;
          if(this.alertTicks == 0) {
            this.showAlertIndicator_194 = false;
          }
        }

        if(contact) {
          this.engage(smap, false);
          return;
        }

        if(distance > LOSE_DISTANCE) {
          this.state = State.PATROL;
          this.showAlertIndicator_194 = false;
          this.alertTicks = 0;
          this.patrolMoveTicks = 0;
          this.patrolIdleTicks = PATROL_IDLE_TICKS_MIN;
          this.chasePath = List.of();
          this.chasePathIndex = 0;
          this.useAnimation(this.idleAnimation, false);
        } else {
          this.tickChase(smap, player);
        }
      }
    }

    private boolean initializeOnField(final SMap smap, final SubmapObject210 player) {
      final CollisionGeometry collision = smap.getCollisionGeometry();
      if(collision.primitiveInfo_14 == null || collision.primitiveCount_0c == 0) {
        return false;
      }

      final Vector3f playerPos = player.getPosition();
      int fallbackPrimitive = -1;
      float fallbackDistance = -1.0f;
      final int start = java.lang.Math.floorMod(activeCut * 17 + this.slot * 53, collision.primitiveCount_0c);

      for(int n = 0; n < collision.primitiveCount_0c; n++) {
        final int primitive = (start + n * 37) % collision.primitiveCount_0c;
        if(!collision.primitiveInfo_14[primitive].flatEnoughToWalkOn_01) {
          continue;
        }

        final int flags = collision.getCollisionAndTransitionInfo(primitive);
        if((flags & 0x38) != 0) {
          continue;
        }

        collision.getMiddleOfCollisionPrimitive(primitive, this.spawnCandidate);

        if(java.lang.Math.abs(this.spawnCandidate.y - playerPos.y) > SPAWN_VERTICAL_TOLERANCE) {
          continue;
        }

        final float dx = this.spawnCandidate.x - playerPos.x;
        final float dz = this.spawnCandidate.z - playerPos.z;
        final float distance = (float)java.lang.Math.sqrt(dx * dx + dz * dz);

        if(distance > fallbackDistance) {
          fallbackDistance = distance;
          fallbackPrimitive = primitive;
        }

        if(distance >= SPAWN_MIN_DISTANCE && distance <= SPAWN_MAX_DISTANCE) {
          fallbackPrimitive = primitive;
          break;
        }
      }

      if(fallbackPrimitive < 0) {
        LOGGER.warn("[Visible Encounters] No valid spawn primitive on cut %d", activeCut);
        return false;
      }

      collision.getMiddleOfCollisionPrimitive(fallbackPrimitive, this.spawnCandidate);
      this.model_00.coord2_14.coord.transfer.set(this.spawnCandidate);
      this.model_00.coord2_14.transforms.scale.set(FIELD_MODEL_SCALE, FIELD_MODEL_SCALE, FIELD_MODEL_SCALE);
      this.home.set(this.spawnCandidate);
      this.collidedPrimitiveIndex_16c = fallbackPrimitive;
      this.hidden_128 = false;
      this.initialized = true;
      this.patrolLeg = this.slot * 2 + 1;
      this.useAnimation(this.idleAnimation, false);
      return true;
    }

    private void beginChase() {
      this.state = State.CHASE;
      this.showAlertIndicator_194 = true;
      this.alertIndicatorOffsetY_198 = 90;
      this.alertTicks = ALERT_TICKS;
      this.pathRecheckTicks = 0;
      this.chasePath = List.of();
      this.chasePathIndex = 0;
      this.useAnimation(this.chaseAnimation, true);
    }

    private boolean canSeePlayer(
      final SMap smap,
      final SubmapObject210 player,
      final float distance
    ) {
      if(java.lang.Math.abs(this.toPlayer.y) > SIGHT_VERTICAL_TOLERANCE
        || distance > SIGHT_DISTANCE
        || distance <= 0.001f) {
        return false;
      }

      final boolean inAwarenessCone;
      if(distance <= CLOSE_DETECTION_DISTANCE) {
        inAwarenessCone = true;
      } else {
        final float invDistance = 1.0f / distance;
        final float toPlayerX = this.toPlayer.x * invDistance;
        final float toPlayerZ = this.toPlayer.z * invDistance;
        final float yaw = this.model_00.coord2_14.transforms.rotate.y;
        final float forwardX = -MathHelper.sin(yaw);
        final float forwardZ = -MathHelper.cos(yaw);
        inAwarenessCone = forwardX * toPlayerX + forwardZ * toPlayerZ >= SIGHT_COSINE;
      }

      if(!inAwarenessCone) {
        return false;
      }

      if(this.sightRecheckTicks > 0) {
        this.sightRecheckTicks--;
        return this.cachedPlayerVisible;
      }

      this.sightRecheckTicks = SIGHT_RECHECK_TICKS;
      this.cachedPlayerVisible = hasWalkableLineOfSight(
        smap.getCollisionGeometry(),
        this.getPosition(),
        player.getPosition()
      );
      return this.cachedPlayerVisible;
    }

    private void tickChase(final SMap smap, final SubmapObject210 player) {
      final CollisionGeometry collision = smap.getCollisionGeometry();
      ensureNavigation(collision);

      final int currentPrimitive = collision.getCollisionPrimitiveAtPoint(
        this.getPosition().x,
        this.getPosition().y,
        this.getPosition().z,
        true,
        true
      );
      final int playerPrimitive = collision.getCollisionPrimitiveAtPoint(
        player.getPosition().x,
        player.getPosition().y,
        player.getPosition().z,
        true,
        true
      );

      if(this.pathRecheckTicks <= 0
        || this.chasePath.isEmpty()
        || this.chasePathIndex >= this.chasePath.size()
        || !this.chasePath.contains(playerPrimitive)) {
        this.chasePath = findPath(collision, currentPrimitive, playerPrimitive);
        this.chasePathIndex = this.chasePath.size() > 1 ? 1 : 0;
        this.pathRecheckTicks = PATH_RECHECK_TICKS;
      } else {
        this.pathRecheckTicks--;
      }

      while(this.chasePathIndex < this.chasePath.size()
        && currentPrimitive == this.chasePath.get(this.chasePathIndex)) {
        this.chasePathIndex++;
      }

      final boolean direct =
        hasWalkableLineOfSight(collision, this.getPosition(), player.getPosition());

      if(direct || this.chasePathIndex >= this.chasePath.size()) {
        this.moveTowards(smap, this.toPlayer, chaseSpeed());
        return;
      }

      final int waypointPrimitive = this.chasePath.get(this.chasePathIndex);
      this.movement
        .set(navCentres[waypointPrimitive])
        .sub(this.getPosition());

      this.moveTowards(smap, this.movement, chaseSpeed());
    }

    private void tickPatrol(final SMap smap) {
      if(this.patrolIdleTicks > 0) {
        this.patrolIdleTicks--;
        this.movement.zero();
        this.useAnimation(this.idleAnimation, false);
        return;
      }

      if(this.patrolMoveTicks <= 0) {
        this.patrolLeg++;
        this.patrolMoveTicks =
          PATROL_MOVE_TICKS_MIN
            + java.lang.Math.floorMod(
              this.slot * 11 + this.patrolLeg * 7,
              PATROL_MOVE_TICKS_RANGE
            );
      }

      final float homeDx = this.home.x - this.getPosition().x;
      final float homeDz = this.home.z - this.getPosition().z;
      final float homeDistanceSq = homeDx * homeDx + homeDz * homeDz;

      if(homeDistanceSq > PATROL_RADIUS * PATROL_RADIUS) {
        this.movement.set(homeDx, 0.0f, homeDz);
      } else {
        final float angle =
          (this.slot + 1) * 1.6180339f + this.patrolLeg * 1.137f;
        this.movement.set(MathHelper.sin(angle), 0.0f, MathHelper.cos(angle));
      }

      this.moveTowards(smap, this.movement, PATROL_SPEED);
      this.patrolMoveTicks--;

      if(this.movement.x == 0.0f && this.movement.z == 0.0f) {
        this.patrolMoveTicks = 0;
      }

      if(this.patrolMoveTicks <= 0) {
        this.patrolIdleTicks =
          PATROL_IDLE_TICKS_MIN
            + java.lang.Math.floorMod(
              this.slot * 29 + this.patrolLeg * 41,
              PATROL_IDLE_TICKS_RANGE
            );
        this.useAnimation(this.idleAnimation, false);
      } else {
        this.useAnimation(this.chaseAnimation, true);
      }
    }

    private void moveTowards(final SMap smap, final Vector3f direction, final float speed) {
      this.movement.set(direction.x, 0.0f, direction.z);
      final float lengthSq = this.movement.x * this.movement.x + this.movement.z * this.movement.z;
      if(lengthSq <= 0.001f) {
        this.movement.zero();
        return;
      }

      this.movement.mul(speed / (float)java.lang.Math.sqrt(lengthSq));

      if(!smap.moveRuntimeSobj(this, this.movement)) {
        this.movement.zero();
        return;
      }

      if(this.movement.x != 0.0f || this.movement.z != 0.0f) {
        this.model_00.coord2_14.transforms.rotate.y =
          MathHelper.atan2(this.movement.x, this.movement.z) + MathHelper.PI;
      }
    }

    private void engage(final SMap smap, final boolean playerInitiative) {
      if(this.state == State.ENGAGED) {
        return;
      }

      this.state = State.ENGAGED;
      this.hidden_128 = true;
      this.showAlertIndicator_194 = false;
      this.alertTicks = 0;
      CONSUMED_SLOTS.add(this.slot);
      pendingPlayerInitiative = playerInitiative;

      // Select the exact encounter represented by this field actor, then use
      // the retail SMap battle transition so post-battle restoration, camera
      // state, autosaves, and battle-stage selection remain unchanged.
      smap.submap.prepareEncounter(this.encounter, false);
      smap.mapTransition(-1, 0);
    }

    private void useAnimation(final TmdAnimationFile animation, final boolean chase) {
      if(this.usingChaseAnimation == chase && this.model_00.anim_08 != null) {
        return;
      }

      this.usingChaseAnimation = chase;
      loadModelStandardAnimation(this.model_00, animation);
    }
  }
}

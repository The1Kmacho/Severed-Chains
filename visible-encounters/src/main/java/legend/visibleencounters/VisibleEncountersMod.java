package legend.visibleencounters;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static legend.game.Models.loadModelStandardAnimation;
import static legend.game.Scus94491BpeSegment_8004.doNothingScript_8004f650;

@Mod(id = VisibleEncountersMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class VisibleEncountersMod {
  public static final String MOD_ID = "visible_encounters";

  private static final Logger LOGGER = LogManager.getFormatterLogger(VisibleEncountersMod.class);

  private static final int DESIRED_ACTORS_PER_MAP = 2;
  private static final int SPAWN_GRACE_TICKS = 60;
  private static final float FIELD_MODEL_SCALE = 0.25f;
  private static final float PATROL_SPEED = 4.0f;
  private static final float CHASE_SPEED = 8.0f;
  private static final float SIGHT_DISTANCE = 700.0f;
  private static final float CLOSE_DETECTION_DISTANCE = 170.0f;
  private static final float LOSE_DISTANCE = 980.0f;
  private static final float CONTACT_DISTANCE = 62.0f;
  private static final float SPAWN_MIN_DISTANCE = 500.0f;
  private static final float SPAWN_MAX_DISTANCE = 1800.0f;
  private static final float SIGHT_COSINE = 0.50f; // 120-degree cone.

  private static int activeCut = Integer.MIN_VALUE;
  private static Object activeSubmap;
  private static boolean visibleEncountersEnabled;
  private static final Set<Integer> CONSUMED_SLOTS = new HashSet<>();

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

    // Ask for the retail rate before enabling our rate override. This preserves
    // maps and scenes where encounters are intentionally disabled.
    final int retailRate = event.getSubmap().getEncounterRate();
    final List<Encounter> pool = event.getSubmap().getEncounterPool();

    if(retailRate <= 0 || pool.isEmpty() || event.remainingCapacity() <= 0) {
      return;
    }

    int added = 0;
    final int actorCount = java.lang.Math.min(
      DESIRED_ACTORS_PER_MAP,
      java.lang.Math.min(event.remainingCapacity(), pool.size() == 1 ? DESIRED_ACTORS_PER_MAP : pool.size())
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
        "[Visible Encounters] Cut %d: %d active actor(s), %d consumed",
        event.submapCut,
        added,
        CONSUMED_SLOTS.size()
      );
    }
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
    private int graceTicks = SPAWN_GRACE_TICKS;
    private int patrolTicks;
    private int patrolLeg;

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

      if(this.state == State.PATROL) {
        if(this.graceTicks == 0 && this.canSeePlayer(distance)) {
          this.state = State.CHASE;
          this.showAlertIndicator_194 = true;
          this.alertIndicatorOffsetY_198 = 90;
          this.useAnimation(this.chaseAnimation, true);
        } else {
          this.tickPatrol(smap);
        }
      }

      if(this.state == State.CHASE) {
        if(distance <= CONTACT_DISTANCE) {
          this.engage(smap);
          return;
        }

        if(distance > LOSE_DISTANCE) {
          this.state = State.PATROL;
          this.showAlertIndicator_194 = false;
          this.patrolTicks = 0;
          this.useAnimation(this.idleAnimation, false);
        } else {
          this.moveTowards(smap, this.toPlayer, CHASE_SPEED);
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

    private boolean canSeePlayer(final float distance) {
      if(distance <= CLOSE_DETECTION_DISTANCE) {
        return true;
      }

      if(distance > SIGHT_DISTANCE || distance <= 0.001f) {
        return false;
      }

      final float invDistance = 1.0f / distance;
      final float toPlayerX = this.toPlayer.x * invDistance;
      final float toPlayerZ = this.toPlayer.z * invDistance;
      final float yaw = this.model_00.coord2_14.transforms.rotate.y;
      final float forwardX = -MathHelper.sin(yaw);
      final float forwardZ = -MathHelper.cos(yaw);
      return forwardX * toPlayerX + forwardZ * toPlayerZ >= SIGHT_COSINE;
    }

    private void tickPatrol(final SMap smap) {
      if(this.patrolTicks <= 0) {
        this.patrolLeg++;
        this.patrolTicks = 45 + this.slot * 12;
      }

      this.patrolTicks--;

      final float angle = (this.slot + 1) * 1.6180339f + this.patrolLeg * 1.137f;
      this.movement.set(MathHelper.sin(angle), 0.0f, MathHelper.cos(angle));
      this.moveTowards(smap, this.movement, PATROL_SPEED);

      // Patrol uses the same locomotion animation as chase, but at lower speed.
      // When movement is blocked, fall back to idle until the next patrol leg.
      if(this.movement.x == 0.0f && this.movement.z == 0.0f) {
        this.useAnimation(this.idleAnimation, false);
        this.patrolTicks = 0;
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
      final CollisionGeometry collision = smap.getCollisionGeometry();
      this.collidedPrimitiveIndex_16c = collision.checkCollision(
        true,
        this.model_00.coord2_14,
        this.movement,
        false
      );

      this.model_00.coord2_14.coord.transfer.add(this.movement);

      if(this.movement.x != 0.0f || this.movement.z != 0.0f) {
        this.model_00.coord2_14.transforms.rotate.y =
          MathHelper.atan2(this.movement.x, this.movement.z) + MathHelper.PI;
      }
    }

    private void engage(final SMap smap) {
      if(this.state == State.ENGAGED) {
        return;
      }

      this.state = State.ENGAGED;
      this.hidden_128 = true;
      this.showAlertIndicator_194 = false;
      CONSUMED_SLOTS.add(this.slot);

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

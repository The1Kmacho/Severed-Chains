package legend.game.modding.events.submap;

import legend.game.modding.events.engine.InGameEvent;
import legend.game.submap.SMap;
import legend.game.submap.SubmapObject;
import legend.game.tim.Tim;
import legend.game.types.GameState52c;
import legend.game.types.TmdAnimationFile;

import java.util.List;

/**
 * Fired after a retail submap object's model/animations/texture have been resolved,
 * but before UV slots are calculated and GPU resources are built.
 *
 * Mods may replace the model, texture, and provide an animation retarget map.
 */
public class SubmapObjectAssetsEvent extends InGameEvent<SMap> implements LoadedSubmapEvent {
  public final int objectIndex;
  public final SubmapObject object;
  public Tim texture;
  public final List<TmdAnimationFile> animations;

  /**
   * Optional map from replacement model part index to submap animation part index.
   * Null means traditional 1:1 model/animation parts.
   */
  public int[] animationPartMap;
  /** Uniform local geometry scale for replacement model parts. */
  public float geometryScale = 1.0f;

  public SubmapObjectAssetsEvent(
    final SMap engineState,
    final GameState52c gameState,
    final int objectIndex,
    final SubmapObject object,
    final Tim texture
  ) {
    super(engineState, gameState);
    this.objectIndex = objectIndex;
    this.object = object;
    this.texture = texture;
    this.animations = object.animations;
  }

  @Override
  public legend.game.submap.Submap getSubmap() {
    return this.getEngineState().submap;
  }
}

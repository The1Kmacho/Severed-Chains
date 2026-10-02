package legend.game.modding.events.submap;

import legend.core.renderer.TextureBuilder;
import legend.game.modding.events.engine.InGameEvent;
import legend.game.submap.SMap;
import legend.game.submap.Submap;
import legend.game.submap.SubmapObject;
import legend.game.tim.Tim;
import legend.game.types.GameState52c;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fired after retail SOBJ assets and texture aliases have been resolved, but
 * before per-object asset events, UV allocation, and GPU resource creation.
 *
 * Mods may append complete field objects here. Appended objects use the normal
 * SOBJ allocation, animation, lighting, and rendering pipeline.
 */
public class SubmapRuntimeObjectsEvent extends InGameEvent<SMap> implements LoadedSubmapEvent {
  private final Submap submap;
  public final int disk;
  public final int submapCut;
  public final int existingObjectCount;
  public final int maxObjectCount;
  public final List<Entry> entries = new ArrayList<>();

  public SubmapRuntimeObjectsEvent(
    final SMap engineState,
    final GameState52c gameState,
    final Submap submap,
    final int disk,
    final int submapCut,
    final int existingObjectCount,
    final int maxObjectCount
  ) {
    super(engineState, gameState);
    this.submap = submap;
    this.disk = disk;
    this.submapCut = submapCut;
    this.existingObjectCount = existingObjectCount;
    this.maxObjectCount = maxObjectCount;
  }

  public int remainingCapacity() {
    return java.lang.Math.max(0, this.maxObjectCount - this.existingObjectCount - this.entries.size());
  }

  public boolean add(
    final SubmapObject object,
    final Tim texture,
    @Nullable final Consumer<TextureBuilder> textureOverride
  ) {
    if(this.remainingCapacity() <= 0) {
      return false;
    }

    this.entries.add(new Entry(object, texture, textureOverride));
    return true;
  }

  @Override
  public Submap getSubmap() {
    return this.submap;
  }

  public record Entry(
    SubmapObject object,
    Tim texture,
    @Nullable Consumer<TextureBuilder> textureOverride
  ) { }
}

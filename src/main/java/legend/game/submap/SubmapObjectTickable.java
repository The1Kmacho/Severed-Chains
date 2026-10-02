package legend.game.submap;

import legend.game.scripting.ScriptState;

/**
 * Optional behavior hook for runtime SOBJ subclasses.
 *
 * The hook runs once per field tick before the normal SOBJ animation/render
 * bookkeeping, allowing mods to implement autonomous field actors while still
 * using the standard submap object pipeline.
 */
public interface SubmapObjectTickable {
  void tick(SMap smap, ScriptState<SubmapObject210> state, SubmapObject210 object);
}

package legend.game.modding.events.submap;

import legend.game.modding.events.engine.InGameEvent;
import legend.game.submap.SMap;
import legend.game.submap.Submap;
import legend.game.types.GameState52c;

/**
 * Fired immediately before a submap-to-battle fade begins.
 *
 * Mods may change the retail full-screen transition style without replacing
 * the actual submap/battle state transition.
 */
public class SubmapBattleTransitionEvent extends InGameEvent<SMap> implements LoadedSubmapEvent {
  private final Submap submap;

  public int fadeType = 1;
  public int fadeFrames = 10;

  public SubmapBattleTransitionEvent(
    final SMap engineState,
    final GameState52c gameState,
    final Submap submap
  ) {
    super(engineState, gameState);
    this.submap = submap;
  }

  @Override
  public Submap getSubmap() {
    return this.submap;
  }
}

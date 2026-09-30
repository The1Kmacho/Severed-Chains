package legend.gameplayoverhaul;

import legend.core.Config;
import legend.core.gte.MV;
import legend.core.renderer.QueuedModelStandard;
import legend.game.combat.effects.Effect;
import legend.game.combat.effects.EffectManagerData6c;
import legend.game.combat.effects.EffectManagerParams;
import legend.game.modding.coremod.CoreMod;
import legend.game.scripting.ScriptState;

import static legend.core.GameEngine.CONFIG;
import static legend.core.GameEngine.GPU;
import static legend.core.GameEngine.RENDERER;

/**
 * Addition-style shrinking square that runs alongside the retail enemy attack.
 * It never pauses or advances combat; the owning mod removes it at the natural
 * damage-resolution call.
 */
public final class ParryTimingOverlayEffect implements Effect<EffectManagerParams.VoidType> {
  private static final float START_SIZE = 150.0f;
  private static final float TARGET_SIZE = 30.0f;

  private final int expectedImpactTicks;
  private final int parryWindowTicks;
  private int age;

  private final MV transforms = new MV();

  public ParryTimingOverlayEffect(final int expectedImpactTicks, final int parryWindowTicks) {
    this.expectedImpactTicks = java.lang.Math.max(parryWindowTicks + 1, expectedImpactTicks);
    this.parryWindowTicks = java.lang.Math.max(1, parryWindowTicks);
  }

  @Override
  public void tick(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    this.age++;
  }

  @Override
  public void render(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    final float overlayScale = CONFIG.getConfig(CoreMod.ADDITION_OVERLAY_SIZE_CONFIG.get());
    final int approachTicks = java.lang.Math.max(1, this.expectedImpactTicks);
    final int windowStartTick = java.lang.Math.max(0, this.expectedImpactTicks - this.parryWindowTicks);
    final float progress = java.lang.Math.min(1.0f, this.age / (float)approachTicks);

    final float movingSize = (START_SIZE + (TARGET_SIZE - START_SIZE) * progress) * overlayScale;
    final float targetSize = TARGET_SIZE * overlayScale;

    final int rgb = Config.changeAdditionOverlayRgb() ? Config.getCounterOverlayRgb() : 0x2060d8;
    final float r = (rgb & 0xff) / 255.0f;
    final float g = (rgb >> 8 & 0xff) / 255.0f;
    final float b = (rgb >> 16 & 0xff) / 255.0f;

    this.transforms.scaling(targetSize, targetSize, 1.0f);
    this.transforms.transfer.set(GPU.getOffsetX(), GPU.getOffsetY() + 30.0f, 120.0f);
    RENDERER.queueOrthoModel(RENDERER.lineBox, this.transforms, QueuedModelStandard.class)
      .colour(r * 0.45f, g * 0.45f, b * 0.45f);

    this.transforms.scaling(movingSize, movingSize, 1.0f);
    this.transforms.transfer.set(GPU.getOffsetX(), GPU.getOffsetY() + 30.0f, 121.0f);
    RENDERER.queueOrthoModel(RENDERER.lineBoxBPlusF, this.transforms, QueuedModelStandard.class)
      .colour(r, g, b);

    // The square reaches the target at the retail damage-resolution moment.
    // The subtle fill begins during the actual accepted parry window.
    if(this.age >= windowStartTick) {
      this.transforms.scaling(targetSize - 4.0f * overlayScale, targetSize - 4.0f * overlayScale, 1.0f);
      this.transforms.transfer.set(GPU.getOffsetX(), GPU.getOffsetY() + 30.0f, 119.0f);
      RENDERER.queueOrthoModel(RENDERER.centredQuadBPlusF, this.transforms, QueuedModelStandard.class)
        .colour(r * 0.35f, g * 0.35f, b * 0.35f);
    }
  }

  @Override
  public void destroy(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
  }
}

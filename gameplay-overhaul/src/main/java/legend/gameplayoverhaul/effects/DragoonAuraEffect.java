package legend.gameplayoverhaul.effects;

import legend.core.renderer.Translucency;
import legend.game.combat.SEffe;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.effects.Effect;
import legend.game.combat.effects.EffectManagerData6c;
import legend.game.combat.effects.EffectManagerParams;
import legend.game.scripting.ScriptState;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import static legend.core.GameEngine.GPU;
import static legend.core.GameEngine.RENDERER;

/**
 * Restrained elemental aura for transformed Dragoon combat.
 */
public final class DragoonAuraEffect implements Effect<EffectManagerParams.VoidType> {
  private final PlayerBattleEntity player;
  private int age;

  private final Vector3f world = new Vector3f();
  private final Vector2f centre = new Vector2f();
  private final Matrix4f transforms = new Matrix4f();

  public DragoonAuraEffect(final PlayerBattleEntity player) {
    this.player = player;
  }

  @Override
  public void tick(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    this.age++;
    if(this.player == null
      || !this.player.isDragoon()
      || this.player.getState().hasFlag(BattleEntity27c.FLAG_DEAD)) {
      state.deallocateWithChildren();
    }
  }

  @Override
  public void render(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    if(this.player == null || !this.player.isDragoon()) {
      return;
    }

    this.world.set(this.player.getPosition()).add(0.0f, -28.0f, 0.0f);
    final float z = SEffe.transformWorldspaceToScreenspace(this.world, this.centre);
    if(z <= 0.0f) {
      return;
    }

    final Vector3f colour = this.player.getElement().colour;
    final float pulse = 0.55f + (float)java.lang.Math.sin(this.age * 0.14f) * 0.12f;
    final float radius = 13.0f + (float)java.lang.Math.sin(this.age * 0.09f) * 1.8f;

    for(int ring = 0; ring < 2; ring++) {
      final float ringRadius = radius + ring * 4.0f;
      final float alpha = pulse * (ring == 0 ? 0.75f : 0.42f);
      Vector2f previous = null;

      for(int i = 0; i <= 12; i++) {
        final double angle = i * java.lang.Math.PI * 2.0 / 12.0;
        final Vector2f point = new Vector2f(
          this.centre.x + GPU.getOffsetX() + (float)java.lang.Math.cos(angle) * ringRadius,
          this.centre.y + GPU.getOffsetY() + (float)java.lang.Math.sin(angle) * ringRadius * 0.32f
        );

        if(previous != null) {
          RENDERER.queueLine(this.transforms, z * 4.0f, previous, point)
            .translucency(Translucency.B_PLUS_F)
            .colour(colour.x * alpha, colour.y * alpha, colour.z * alpha);
        }

        previous = point;
      }
    }

    for(int i = 0; i < 3; i++) {
      final float phase = (this.age * 0.35f + i * 2.1f);
      final float x = (float)java.lang.Math.sin(phase) * 8.0f;
      final float height = 8.0f + (this.age + i * 7) % 13;
      final Vector2f from = new Vector2f(
        this.centre.x + GPU.getOffsetX() + x,
        this.centre.y + GPU.getOffsetY() - 1.0f
      );
      final Vector2f to = new Vector2f(
        this.centre.x + GPU.getOffsetX() + x * 0.6f,
        this.centre.y + GPU.getOffsetY() - height
      );

      RENDERER.queueLine(this.transforms, z * 4.0f, from, to)
        .translucency(Translucency.B_PLUS_F)
        .colour(colour.x * 0.45f, colour.y * 0.45f, colour.z * 0.45f);
    }
  }

  @Override
  public void destroy(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
  }
}

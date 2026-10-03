package legend.gameplayoverhaul.effects;

import legend.core.renderer.Translucency;
import legend.game.combat.SEffe;
import legend.game.combat.bent.BattleEntity27c;
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
 * Lightweight additive hit accent used by the gameplay-overhaul mod.
 *
 * It intentionally uses simple lines rather than DEFF assets so it can layer
 * over retail attacks without replacing their authored effects.
 */
public final class CombatImpactEffect implements Effect<EffectManagerParams.VoidType> {
  public enum Style {
    SLASH,
    PIERCE,
    BLUNT,
    HEAVY,
    FINAL,
    BREAK,
    DRAGOON
  }

  private final BattleEntity27c target;
  private final Style style;
  private final int lifetime;
  private int age;

  private final Vector3f world = new Vector3f();
  private final Vector2f centre = new Vector2f();
  private final Matrix4f transforms = new Matrix4f();

  public CombatImpactEffect(final BattleEntity27c target, final Style style) {
    this.target = target;
    this.style = style;
    this.lifetime = switch(style) {
      case BREAK -> 16;
      case FINAL, DRAGOON -> 13;
      default -> 9;
    };
  }

  @Override
  public void tick(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    this.age++;
    if(this.age >= this.lifetime) {
      state.deallocateWithChildren();
    }
  }

  @Override
  public void render(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
    if(this.target == null) {
      return;
    }

    this.world
      .set(this.target.getPosition())
      .add(0.0f, -this.target.middleOffsetY_86 * 42.0f, 0.0f);

    final float z = SEffe.transformWorldspaceToScreenspace(this.world, this.centre);
    if(z <= 0.0f) {
      return;
    }

    final float t = this.age / (float)this.lifetime;
    final float brightness = java.lang.Math.max(0.0f, 1.0f - t);
    final float baseRadius = switch(this.style) {
      case PIERCE -> 10.0f;
      case BLUNT -> 12.0f;
      case HEAVY -> 17.0f;
      case FINAL -> 19.0f;
      case BREAK -> 21.0f;
      case DRAGOON -> 22.0f;
      default -> 14.0f;
    };
    final float radius = baseRadius * (0.55f + t * 0.85f);

    final float r;
    final float g;
    final float b;
    switch(this.style) {
      case PIERCE -> {
        r = 0.80f;
        g = 0.92f;
        b = 1.00f;
      }
      case BLUNT -> {
        r = 1.00f;
        g = 0.86f;
        b = 0.42f;
      }
      case HEAVY -> {
        r = 1.00f;
        g = 0.55f;
        b = 0.18f;
      }
      case BREAK -> {
        r = 1.00f;
        g = 0.78f;
        b = 0.20f;
      }
      case DRAGOON -> {
        final Vector3f colour = this.target.getElement().colour;
        r = colour.x;
        g = colour.y;
        b = colour.z;
      }
      default -> {
        r = 1.00f;
        g = 0.95f;
        b = 0.78f;
      }
    }

    final int rayCount = switch(this.style) {
      case PIERCE -> 4;
      case SLASH -> 2;
      case BLUNT -> 6;
      case HEAVY, FINAL, DRAGOON -> 8;
      case BREAK -> 10;
    };

    if(this.style == Style.SLASH) {
      this.line(-radius, radius * 0.65f, radius, -radius * 0.65f, z, r, g, b, brightness);
      this.line(-radius * 0.65f, -radius, radius * 0.65f, radius, z, r, g, b, brightness * 0.75f);
      return;
    }

    final float spin = this.style == Style.BREAK ? t * 0.45f : t * 0.18f;
    for(int i = 0; i < rayCount; i++) {
      final double angle = spin + i * java.lang.Math.PI * 2.0 / rayCount;
      final float inner = this.style == Style.PIERCE ? radius * 0.18f : radius * 0.28f;
      final float x0 = (float)java.lang.Math.cos(angle) * inner;
      final float y0 = (float)java.lang.Math.sin(angle) * inner;
      final float x1 = (float)java.lang.Math.cos(angle) * radius;
      final float y1 = (float)java.lang.Math.sin(angle) * radius;

      this.line(x0, y0, x1, y1, z, r, g, b, brightness);
    }

    if(this.style == Style.FINAL || this.style == Style.BREAK || this.style == Style.DRAGOON) {
      final float cross = radius * 0.55f;
      this.line(-cross, 0.0f, cross, 0.0f, z, 1.0f, 1.0f, 1.0f, brightness * 0.8f);
      this.line(0.0f, -cross, 0.0f, cross, z, 1.0f, 1.0f, 1.0f, brightness * 0.8f);
    }
  }

  private void line(
    final float x0,
    final float y0,
    final float x1,
    final float y1,
    final float z,
    final float r,
    final float g,
    final float b,
    final float brightness
  ) {
    final Vector2f from = new Vector2f(
      this.centre.x + GPU.getOffsetX() + x0,
      this.centre.y + GPU.getOffsetY() + y0
    );
    final Vector2f to = new Vector2f(
      this.centre.x + GPU.getOffsetX() + x1,
      this.centre.y + GPU.getOffsetY() + y1
    );

    RENDERER.queueLine(this.transforms, z * 4.0f, from, to)
      .translucency(Translucency.B_PLUS_F)
      .colour(r * brightness, g * brightness, b * brightness);
  }

  @Override
  public void destroy(final ScriptState<EffectManagerData6c<EffectManagerParams.VoidType>> state) {
  }
}

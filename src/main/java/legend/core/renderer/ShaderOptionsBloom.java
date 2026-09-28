package legend.core.renderer;

public class ShaderOptionsBloom implements ShaderOptions {
  private final ShaderUniformInt mode;
  private final ShaderUniformFloat threshold;
  private final ShaderUniformFloat radius;

  public ShaderOptionsBloom(final ShaderUniformInt mode, final ShaderUniformFloat threshold, final ShaderUniformFloat radius) {
    this.mode = mode;
    this.threshold = threshold;
    this.radius = radius;
  }

  public ShaderOptionsBloom mode(final int val) {
    this.mode.set(val);
    return this;
  }

  public ShaderOptionsBloom threshold(final float val) {
    this.threshold.set(val);
    return this;
  }

  public ShaderOptionsBloom radius(final float val) {
    this.radius.set(val);
    return this;
  }

  @Override
  public void apply() {

  }
}

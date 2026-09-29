package legend.core.gte;

import legend.core.renderer.Obj;
import legend.core.renderer.Texture;
import legend.game.tmd.TmdObjTable1c;

public class ModelPart10 {
  /** perspective, translation, rotate, display */
  public int attribute_00;
  /** local dmatrix */
  public GsCOORDINATE2 coord2_04;
  public TmdObjTable1c tmd_08;

  /** Optional modern/remaster render object. The retail TMD remains the animation/part source. */
  public Obj renderObjOverride;
  /** Optional direct 24-bit texture used by the replacement render object. */
  public Texture renderTextureOverride;
  /** Optional tangent-space normal map for modern replacement geometry. */
  public Texture renderNormalOverride;
  /** Optional material map: R=roughness, G=metallic, B=specular strength. */
  public Texture renderMaterialOverride;

  public ModelPart10 set(final ModelPart10 other) {
    this.attribute_00 = other.attribute_00;
    this.coord2_04 = other.coord2_04;
    this.tmd_08 = other.tmd_08;
    return this;
  }

  public Obj getRenderObj() {
    return this.renderObjOverride != null ? this.renderObjOverride : this.tmd_08.getObj();
  }

  public void delete() {
    if(this.renderObjOverride != null) {
      this.renderObjOverride.delete();
      this.renderObjOverride = null;
    }

    this.renderTextureOverride = null;
    this.renderNormalOverride = null;
    this.renderMaterialOverride = null;
    this.tmd_08.delete();
  }
}

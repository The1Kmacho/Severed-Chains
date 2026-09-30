package legend.game.submap;

import legend.game.scripting.ScriptFile;
import legend.game.types.CContainer;
import legend.game.types.TmdAnimationFile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class SubmapObject {
  public ScriptFile script;
  public CContainer model;
  public final List<TmdAnimationFile> animations = new ArrayList<>();
  /** Optional map from model part index to animation part index. */
  public int[] animationPartMap;
  public float geometryScale = 1.0f;
  public Function<String, SubmapObject210> constructor = SubmapObject210::new;
}

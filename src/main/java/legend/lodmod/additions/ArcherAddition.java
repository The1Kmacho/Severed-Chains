package legend.lodmod.additions;

import legend.game.additions.AdditionHitProperties10;
import legend.game.additions.SimpleAddition;
import legend.game.characters.CharacterAdditionInfo;
import legend.game.characters.CharacterData2c;
import legend.game.unpacker.FileData;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ArcherAddition extends SimpleAddition {
  private static final LevelMultipliers[] LEVELS = {
    new LevelMultipliers(1.00f, 1.00f),
    new LevelMultipliers(1.05f, 1.10f),
    new LevelMultipliers(1.10f, 1.20f),
    new LevelMultipliers(1.20f, 1.35f),
    new LevelMultipliers(1.35f, 1.50f),
  };

  private final int successFrames;
  private final int releaseLeadTicks;

  public ArcherAddition(final int damagePercent, final int spBonus, final int successFrames, final int releaseLeadTicks, final boolean countsTowardsMastery) {
    super(countsTowardsMastery, LEVELS, new AdditionHitProperties10[] {
      new AdditionHitProperties10(0xc0, 1, 0, 0, damagePercent, spBonus, 0, 4, 0, 0, 0, 0, 1, 32, 0, 0),
    });
    this.successFrames = successFrames;
    this.releaseLeadTicks = releaseLeadTicks;
  }

  public int getSuccessFrames() {
    return this.successFrames;
  }

  public int getReleaseLeadTicks() {
    return this.releaseLeadTicks;
  }

  @Override
  public CompletableFuture<List<FileData>> loadAnimations(final CharacterData2c character, final CharacterAdditionInfo additionInfo) {
    return CompletableFuture.completedFuture(List.of());
  }
}

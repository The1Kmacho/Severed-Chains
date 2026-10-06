package legend.game.combat;

import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.scripting.ScriptState;
import legend.game.unpacker.FileData;
import org.joml.Vector3f;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Raw preloaded files plus live entities for one pending primary-slot replacement. */
public final class PartySwitchPreparation {
  public final int slot;
  public final ScriptState<PlayerBattleEntity> outgoingState;
  public final ScriptState<PlayerBattleEntity> incomingState;
  public final PlayerBattleEntity outgoing;
  public final PlayerBattleEntity incoming;
  public final boolean firstEntry;
  public final Vector3f formationPosition = new Vector3f();
  public final Vector3f offFieldPosition = new Vector3f();

  private final CompletableFuture<List<FileData>> modelFiles;
  private final CompletableFuture<FileData> textureFile;
  private final CompletableFuture<List<FileData>> attackFiles;
  private final CompletableFuture<List<FileData>> soundFiles;

  PartySwitchPreparation(
    final int slot,
    final ScriptState<PlayerBattleEntity> outgoingState,
    final ScriptState<PlayerBattleEntity> incomingState,
    final PlayerBattleEntity outgoing,
    final PlayerBattleEntity incoming,
    final boolean firstEntry,
    final CompletableFuture<List<FileData>> modelFiles,
    final CompletableFuture<FileData> textureFile,
    final CompletableFuture<List<FileData>> attackFiles,
    final CompletableFuture<List<FileData>> soundFiles
  ) {
    this.slot = slot;
    this.outgoingState = outgoingState;
    this.incomingState = incomingState;
    this.outgoing = outgoing;
    this.incoming = incoming;
    this.firstEntry = firstEntry;
    this.formationPosition.set(outgoing.model_148.coord2_14.coord.transfer);
    this.modelFiles = modelFiles;
    this.textureFile = textureFile;
    this.attackFiles = attackFiles;
    this.soundFiles = soundFiles;
  }

  public boolean isReady() {
    return this.modelFiles.isDone()
      && this.textureFile.isDone()
      && this.attackFiles.isDone()
      && this.soundFiles.isDone()
      && !this.hasFailed();
  }

  public boolean hasFailed() {
    return failed(this.modelFiles)
      || failed(this.textureFile)
      || failed(this.attackFiles)
      || failed(this.soundFiles);
  }

  private static <T> boolean failed(final CompletableFuture<T> future) {
    return future.isCompletedExceptionally() || future.isDone() && future.getNow(null) == null;
  }

  List<FileData> modelFiles() { return this.modelFiles.getNow(null); }
  FileData textureFile() { return this.textureFile.getNow(null); }
  List<FileData> attackFiles() { return this.attackFiles.getNow(null); }
  List<FileData> soundFiles() { return this.soundFiles.getNow(null); }
}

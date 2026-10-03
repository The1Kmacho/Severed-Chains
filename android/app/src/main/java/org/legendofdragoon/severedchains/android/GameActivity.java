package org.legendofdragoon.severedchains.android;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

public final class GameActivity extends SDLActivity {
  private static final String TAG = "SeveredChains";

  @Override
  protected String[] getLibraries() {
    return new String[] {"SDL3", "scandroid"};
  }

  @Override
  protected void onCreate(final Bundle state) {
    this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onCreate(state);
    this.setImmersive();
  }

  @Override
  protected void onResume() {
    super.onResume();
    this.setImmersive();
  }

  @Override
  protected void main() {
    final File runtime = AndroidFiles.runtime(this);
    final File game = AndroidFiles.game(this);
    final File cache = this.getCacheDir();
    final String nativeDir = this.getApplicationInfo().nativeLibraryDir;
    final String classPath = AndroidFiles.classPath(game);

    Log.i(TAG, "Launching embedded Java 25 runtime from " + runtime);
    Log.i(TAG, "Game directory: " + game);

    final int exitCode = NativeLauncher.launch(
      runtime.getAbsolutePath(),
      game.getAbsolutePath(),
      cache.getAbsolutePath(),
      nativeDir,
      classPath
    );

    Log.i(TAG, "Embedded JVM exited with code " + exitCode);
    this.runOnUiThread(this::finish);
  }

  private void setImmersive() {
    if(android.os.Build.VERSION.SDK_INT >= 30) {
      final WindowInsetsController controller = this.getWindow().getInsetsController();
      if(controller != null) {
        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        controller.setSystemBarsBehavior(
          WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
      }
    } else {
      //noinspection deprecation
      this.getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN
          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      );
    }
  }
}

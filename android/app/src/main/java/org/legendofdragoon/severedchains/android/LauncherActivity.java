package org.legendofdragoon.severedchains.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity {
  private static final int PICK_DISCS = 4101;

  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  private TextView status;
  private Button importButton;
  private Button launchButton;
  private ProgressBar progress;

  @Override
  protected void onCreate(final Bundle state) {
    super.onCreate(state);
    this.requestWindowFeature(Window.FEATURE_NO_TITLE);
    this.setImmersive();
    this.setContentView(this.createUi());
    this.install();
  }

  @Override
  protected void onResume() {
    super.onResume();
    this.setImmersive();
    this.refreshStatus();
  }

  @Override
  protected void onDestroy() {
    this.worker.shutdownNow();
    super.onDestroy();
  }

  private View createUi() {
    final LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER);
    root.setPadding(48, 32, 48, 32);

    final TextView title = new TextView(this);
    title.setText("Severed Chains");
    title.setTextSize(28.0f);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    final TextView subtitle = new TextView(this);
    subtitle.setText("Android ARM64 development build\nBluetooth/USB controller recommended");
    subtitle.setGravity(Gravity.CENTER);
    subtitle.setPadding(0, 8, 0, 24);
    root.addView(subtitle);

    this.progress = new ProgressBar(this);
    root.addView(this.progress);

    this.status = new TextView(this);
    this.status.setGravity(Gravity.CENTER);
    this.status.setPadding(0, 16, 0, 16);
    root.addView(this.status);

    this.importButton = new Button(this);
    this.importButton.setText("Import LoD disc images");
    this.importButton.setEnabled(false);
    this.importButton.setOnClickListener(v -> this.pickDiscs());
    root.addView(this.importButton);

    this.launchButton = new Button(this);
    this.launchButton.setText("Launch Severed Chains");
    this.launchButton.setEnabled(false);
    this.launchButton.setOnClickListener(v -> {
      final Intent intent = new Intent(this, GameActivity.class);
      this.startActivity(intent);
    });
    root.addView(this.launchButton);

    return root;
  }

  private void install() {
    this.status.setText("Installing bundled Java 25 runtime and game files…");
    this.progress.setVisibility(View.VISIBLE);
    this.importButton.setEnabled(false);
    this.launchButton.setEnabled(false);

    this.worker.execute(() -> {
      try {
        AndroidFiles.installBundledContent(this, BuildConfig.GAME_BUILD);
        this.runOnUiThread(() -> {
          this.progress.setVisibility(View.GONE);
          this.importButton.setEnabled(true);
          this.launchButton.setEnabled(true);
          this.refreshStatus();
        });
      } catch(final Throwable t) {
        this.runOnUiThread(() -> {
          this.progress.setVisibility(View.GONE);
          this.status.setText("Install failed:\n" + t);
        });
      }
    });
  }

  private void pickDiscs() {
    final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    this.startActivityForResult(intent, PICK_DISCS);
  }

  @Override
  protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if(requestCode != PICK_DISCS || resultCode != RESULT_OK || data == null) {
      return;
    }

    this.progress.setVisibility(View.VISIBLE);
    this.status.setText("Importing disc images…");
    this.importButton.setEnabled(false);
    this.launchButton.setEnabled(false);

    this.worker.execute(() -> {
      try {
        final File isos = new File(AndroidFiles.game(this), "isos");
        AndroidFiles.ensureDirectories(AndroidFiles.game(this));

        final ClipData clip = data.getClipData();
        if(clip != null) {
          for(int i = 0; i < clip.getItemCount(); i++) {
            this.copyUri(clip.getItemAt(i).getUri(), isos);
          }
        } else if(data.getData() != null) {
          this.copyUri(data.getData(), isos);
        }

        this.runOnUiThread(() -> {
          this.progress.setVisibility(View.GONE);
          this.importButton.setEnabled(true);
          this.launchButton.setEnabled(true);
          this.refreshStatus();
        });
      } catch(final Throwable t) {
        this.runOnUiThread(() -> {
          this.progress.setVisibility(View.GONE);
          this.importButton.setEnabled(true);
          this.launchButton.setEnabled(true);
          this.status.setText("Import failed:\n" + t);
        });
      }
    });
  }

  private void copyUri(final Uri uri, final File destination) throws Exception {
    String name = "disc-" + System.currentTimeMillis();

    try(Cursor cursor = this.getContentResolver().query(
      uri,
      new String[] {OpenableColumns.DISPLAY_NAME},
      null,
      null,
      null
    )) {
      if(cursor != null && cursor.moveToFirst()) {
        final int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if(column >= 0) {
          final String display = cursor.getString(column);
          if(display != null && !display.isBlank()) {
            name = display;
          }
        }
      }
    }

    name = new File(name).getName();

    try(
      InputStream in = this.getContentResolver().openInputStream(uri);
      FileOutputStream out = new FileOutputStream(new File(destination, name))
    ) {
      if(in == null) {
        throw new IllegalStateException("Unable to open " + uri);
      }
      in.transferTo(out);
    }
  }

  private void refreshStatus() {
    if(this.status == null || !this.launchButton.isEnabled()) {
      return;
    }

    final int count = AndroidFiles.importedDiscCount(this);
    this.status.setText(
      "Game runtime installed.\n"
        + count + " disc file" + (count == 1 ? "" : "s") + " in the Android game folder."
    );
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

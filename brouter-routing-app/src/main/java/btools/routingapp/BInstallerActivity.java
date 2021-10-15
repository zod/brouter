package btools.routingapp;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

public class BInstallerActivity extends BInstallerMainActivity {

  public static final String DOWNLOAD_ACTION = "btools.routingapp.download";

  private static final int DIALOG_CONFIRM_DELETE_ID = 1;

  private BInstallerView mBInstallerView;
  private DownloadReceiver myReceiver;
  private final Set<Integer> dialogIds = new HashSet<>();

  @Override
  @SuppressWarnings("deprecation")
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    mBInstallerView = new BInstallerView(this);
    setContentView(mBInstallerView);
  }

  @Override
  protected void onResume() {
    super.onResume();

    IntentFilter filter = new IntentFilter();
    filter.addAction(DOWNLOAD_ACTION);

    myReceiver = new DownloadReceiver();
    registerReceiver(myReceiver, filter);
  }

  @Override
  protected void onPause() {
    super.onPause();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (myReceiver != null) unregisterReceiver(myReceiver);
    System.exit(0);
  }

  @Override
  @SuppressWarnings("deprecation")
  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder builder;
    switch (id) {
      case DIALOG_CONFIRM_DELETE_ID:
        builder = new AlertDialog.Builder(this);
        builder
          .setTitle("Confirm Delete")
          .setMessage("Really delete?").setPositiveButton("Yes", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            mBInstallerView.deleteSelectedTiles();
          }
        }).setNegativeButton("No", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
          }
        });
        return builder.create();

      default:
        return null;
    }
  }

  @SuppressWarnings("deprecation")
  public void showConfirmDelete() {
    showDialog(DIALOG_CONFIRM_DELETE_ID);
  }

  private void showNewDialog(int id) {
    if (dialogIds.contains(id)) {
      removeDialog(id);
    }
    dialogIds.add(id);
    showDialog(id);
  }

  public class DownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.hasExtra("txt")) {
        String txt = intent.getStringExtra("txt");
        boolean ready = intent.getBooleanExtra("ready", false);
        mBInstallerView.setState(txt, ready);
      }
    }
  }


}

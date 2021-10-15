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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import btools.router.RoutingHelper;

public class BInstallerActivity extends BInstallerMainActivity {

  public static final String DOWNLOAD_ACTION = "btools.routingapp.download";

  private static final int DIALOG_CONFIRM_DELETE_ID = 1;

  private static final int MASK_SELECTED_RD5 = 1;
  private static final int MASK_DELETED_RD5 = 2;
  private static final int MASK_INSTALLED_RD5 = 4;
  private static final int MASK_CURRENT_RD5 = 8;

  private BInstallerView mBInstallerView;
  private DownloadReceiver downloadReceiver;
  private File mBaseDir;
  private final Set<Integer> dialogIds = new HashSet<>();

  @Override
  @SuppressWarnings("deprecation")
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    mBaseDir = ConfigHelper.getBaseDir(this);

    mBInstallerView = new BInstallerView(this);
    mBInstallerView.setOnClickListener(
      view -> {
        if (mBInstallerView.getSelectedTiles(MASK_DELETED_RD5).size() > 0) {
          showConfirmDelete();
        } else if (mBInstallerView.getSelectedTiles(MASK_SELECTED_RD5).size() > 0) {
          downloadSelectedTiles();
        } else {
          downloadInstalledTiles();
        }
      }
    );
    setContentView(mBInstallerView);
    scanExistingFiles();
  }

  @Override
  protected void onResume() {
    super.onResume();

    IntentFilter filter = new IntentFilter();
    filter.addAction(DOWNLOAD_ACTION);

    downloadReceiver = new DownloadReceiver();
    registerReceiver(downloadReceiver, filter);
  }

  @Override
  protected void onPause() {
    super.onPause();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (downloadReceiver != null) unregisterReceiver(downloadReceiver);
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
            deleteSelectedTiles();
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

  private void scanExistingFiles() {
    mBInstallerView.clearAllTilesStatus(MASK_INSTALLED_RD5 | MASK_CURRENT_RD5);

    scanExistingFiles(new File(mBaseDir, "brouter/segments4"));

    File secondary = RoutingHelper.getSecondarySegmentDir(new File(mBaseDir, "brouter/segments4"));
    if (secondary != null) {
      scanExistingFiles(secondary);
    }

    long availableSize = -1;
    try {
      availableSize = BInstallerActivity.getAvailableSpace(mBaseDir.getAbsolutePath());
    } catch (Exception e) { /* ignore */ }
    mBInstallerView.setAvailableSize(availableSize);
  }

  private void scanExistingFiles(File dir) {
    String[] fileNames = dir.list();
    if (fileNames == null) return;
    String suffix = ".rd5";
    for (String fileName : fileNames) {
      if (fileName.endsWith(suffix)) {
        String basename = fileName.substring(0, fileName.length() - suffix.length());
        int tileIndex = tileForBaseName(basename);
        mBInstallerView.setTileStatus(tileIndex, MASK_INSTALLED_RD5);

        long age = System.currentTimeMillis() - new File(dir, fileName).lastModified();
        if (age < 10800000) mBInstallerView.setTileStatus(tileIndex, MASK_CURRENT_RD5); // 3 hours
      }
    }
  }

  private void deleteSelectedTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_DELETED_RD5);
    for (int tileIndex : selectedTiles) {
      new File(mBaseDir, "brouter/segments4/" + baseNameForTile(tileIndex) + ".rd5").delete();
    }
    scanExistingFiles();
  }

  private void downloadSelectedTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_SELECTED_RD5);
    downloadAll(selectedTiles);
    mBInstallerView.clearAllTilesStatus(MASK_SELECTED_RD5);
  }

  private void downloadInstalledTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_INSTALLED_RD5);
    downloadAll(selectedTiles);
  }

  private void downloadAll(ArrayList<Integer> downloadList) {
    ArrayList<String> urlparts = new ArrayList<>();
    for (Integer i : downloadList) {
      urlparts.add(baseNameForTile(i));
    }

    mBInstallerView.setDownloadState(true);
    mBInstallerView.setDownloadText("Starting download...");

    Intent intent = new Intent(this, DownloadService.class);
    intent.putExtra("dir", mBaseDir.getAbsolutePath() + "/brouter/");
    intent.putExtra("urlparts", urlparts);
    this.startService(intent);

    deleteRawTracks(); // invalidate raw-tracks after data update
  }

  private int tileForBaseName(String basename) {
    String uname = basename.toUpperCase(Locale.ROOT);
    int idx = uname.indexOf("_");
    if (idx < 0) return -1;
    String slon = uname.substring(0, idx);
    String slat = uname.substring(idx + 1);
    int ilon = slon.charAt(0) == 'W' ? -Integer.parseInt(slon.substring(1)) :
      (slon.charAt(0) == 'E' ? Integer.parseInt(slon.substring(1)) : -1);
    int ilat = slat.charAt(0) == 'S' ? -Integer.parseInt(slat.substring(1)) :
      (slat.charAt(0) == 'N' ? Integer.parseInt(slat.substring(1)) : -1);
    if (ilon < -180 || ilon >= 180 || ilon % 5 != 0) return -1;
    if (ilat < -90 || ilat >= 90 || ilat % 5 != 0) return -1;
    return (ilon + 180) / 5 + 72 * ((ilat + 90) / 5);
  }

  protected String baseNameForTile(int tileIndex) {
    int lon = (tileIndex % 72) * 5 - 180;
    int lat = (tileIndex / 72) * 5 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat;
  }

  private void deleteRawTracks() {
    File modeDir = new File(mBaseDir, "brouter/modes");
    String[] fileNames = modeDir.list();
    if (fileNames == null) return;
    for (String fileName : fileNames) {
      if (fileName.endsWith("_rawtrack.dat")) {
        File f = new File(modeDir, fileName);
        f.delete();
      }
    }
  }

  public class DownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.hasExtra("txt")) {
        String txt = intent.getStringExtra("txt");
        boolean ready = intent.getBooleanExtra("ready", false);
        mBInstallerView.setDownloadText(txt);
        mBInstallerView.setDownloadState(ready);
        if (!ready) {
          scanExistingFiles();
        }
      }
    }
  }


}

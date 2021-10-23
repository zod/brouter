package btools.routingapp;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import btools.expressions.BExpressionMetaData;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingHelper;

public class BRouterActivity extends BRouterMainActivity {

  private static final int DIALOG_SELECTPROFILE_ID = 1;
  private static final int DIALOG_EXCEPTION_ID = 2;
  private static final int DIALOG_SHOW_DM_INFO_ID = 3;
  private static final int DIALOG_TEXTENTRY_ID = 4;
  private static final int DIALOG_VIASELECT_ID = 5;
  private static final int DIALOG_NOGOSELECT_ID = 6;
  private static final int DIALOG_SHOWRESULT_ID = 7;
  private static final int DIALOG_ROUTINGMODES_ID = 8;
  private static final int DIALOG_MODECONFIGOVERVIEW_ID = 9;
  private static final int DIALOG_PICKWAYPOINT_ID = 10;
  private static final int DIALOG_SELECTBASEDIR_ID = 11;
  private static final int DIALOG_MAINACTION_ID = 12;
  private static final int DIALOG_OLDDATAHINT_ID = 13;
  private static final int DIALOG_SHOW_WP_HELP_ID = 14;
  private static final int DIALOG_SHOW_WP_SCANRESULT_ID = 15;
  private static final int DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID = 16;
  private static final int DIALOG_SHOW_API23_HELP_ID = 17;
  private final Set<Integer> dialogIds = new HashSet<>();
  private BRouterView mBRouterView;
  private WakeLock mWakeLock;
  private String[] availableProfiles;
  private String selectedProfile = null;
  private List<File> availableBasedirs;
  private String[] basedirOptions;
  private int selectedBasedir;
  private String[] availableWaypoints;
  private String[] routingModes;
  private boolean[] routingModesChecked;
  private String defaultbasedir = null;
  private String message = null;
  private String[] availableVias;
  private Set<String> selectedVias;
  private String maptoolDirCandidate;
  private String errorMessage;
  private String title;
  private int wpCount;

  private File retryBaseDir;
  private File modesDir;
  private File tracksDir;
  private File segmentDir;
  private File profileDir;
  private String profileName;
  private String sourceHint;

  private OsmTrack rawTrack;
  private String rawTrackPath;
  private String oldMigrationPath;
  private boolean needsViaSelection;
  private boolean needsNogoSelection;
  private boolean needsWaypointSelection;
  private boolean waitingForMigration = false;
  public boolean canAccessSdCard;
  private CoordinateReader cor;

  private List<OsmNodeNamed> wpList;
  private List<OsmNodeNamed> nogoList;
  private List<OsmNodeNamed> nogoVetoList;
  private WpDatabaseScanner dataBaseScanner;

  Timer routingTimer;
  Timer dataBaseScannerTimer;

  RoutingEngine routingEngine;

  /**
   * Called when the activity is first created.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Get an instance of the PowerManager
    PowerManager mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

    // Create a bright wake lock
    mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass().getName());

    // instantiate our simulation view and set it as the activity's content
    mBRouterView = new BRouterView(this);
    init();
    setContentView(mBRouterView);
  }

  public void stopRouting() {
    if (routingEngine != null) routingEngine.terminate();
  }

  public void init() {
    try {
      // get base dir from private file
      File baseDir = ConfigHelper.getBaseDir(this);
      // check if valid
      boolean bdValid = false;
      if (baseDir != null) {
        bdValid = baseDir.isDirectory();
        File brd = new File(baseDir, "brouter");
        if (brd.isDirectory()) {
          if (brd.getAbsolutePath().contains("/Android/data/")) {
            String message = "(previous basedir " + baseDir + " has to migrate )";

            selectBasedir(getStorageDirectories(), guessBaseDir(), message);
            mBRouterView.setWaitingForSelection(true);
            waitingForMigration = true;
            oldMigrationPath = brd.getAbsolutePath();
            return;
          } else {
            startSetup(baseDir, false);
            return;
          }
        }
      }
      String message = baseDir == null ? "(no basedir configured previously)" : "(previous basedir " + baseDir
        + (bdValid ? " does not contain 'brouter' subfolder)" : " is not valid)");

      selectBasedir(getStorageDirectories(), guessBaseDir(), message);
      mBRouterView.setWaitingForSelection(true);
    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();

      AppLogger.log(msg);
      AppLogger.log(AppLogger.formatThrowable(e));

      showErrorMessage(msg);
    }
  }

  public void startSetup(File baseDir, boolean storeBasedir) {
    if (baseDir == null) {
      baseDir = retryBaseDir;
      retryBaseDir = null;
    }

    if (storeBasedir) {
      File td = new File(baseDir, "brouter");
      try {
        td.mkdirs();
      } catch (Exception e) {
        Log.d("BRouterView", "Error creating base directory: " + e.getMessage());
        e.printStackTrace();
      }

      if (!td.isDirectory()) {
        if (checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
          retryBaseDir = baseDir;
        } else {
          selectBasedir(getStorageDirectories(), guessBaseDir(), "Cannot access " + baseDir.getAbsolutePath() + "; select another");
        }
        return;
      }
      ConfigHelper.writeBaseDir(this, baseDir);
    }
    try {
      cor = null;

      String basedir = baseDir.getAbsolutePath();
      AppLogger.log("using basedir: " + basedir);

      String version = "v" + getString(R.string.app_version);

      // create missing directories
      assertDirectoryExists("project directory", new File(basedir, "brouter"), null, null);
      segmentDir = new File(basedir, "/brouter/segments4");
      if (assertDirectoryExists("data directory", segmentDir, "segments4.zip", null)) {
        ConfigMigration.tryMigrateStorageConfig(
          new File(basedir + "/brouter/segments3/storageconfig.txt"),
          new File(basedir + "/brouter/segments4/storageconfig.txt"));
      }
      profileDir = new File(basedir, "brouter/profiles2");
      assertDirectoryExists("profile directory", profileDir, "profiles2.zip", version);
      modesDir = new File(basedir, "/brouter/modes");
      assertDirectoryExists("modes directory", modesDir, "modes.zip", version);
      assertDirectoryExists("readmes directory", new File(basedir, "brouter/readmes"), "readmes.zip", version);

      File inputDir = new File(basedir, "/import");
      assertDirectoryExists("input directory", inputDir, null, version);

      // new init is done move old files
      if (waitingForMigration) {
        moveFolders(oldMigrationPath, basedir + "/brouter");
        waitingForMigration = false;
      }

      int deviceLevel = android.os.Build.VERSION.SDK_INT;
      int targetSdkVersion = getApplicationInfo().targetSdkVersion;
      canAccessSdCard = deviceLevel < 23 || targetSdkVersion == 19;
      if (canAccessSdCard) {
        cor = CoordinateReader.obtainValidReader(basedir, segmentDir);
      } else {
        if (deviceLevel >= android.os.Build.VERSION_CODES.Q) {
          cor = new CoordinateReaderInternal(basedir);
        } else {
          cor = new CoordinateReaderNone();
        }
        cor.readFromTo();
      }

      wpList = cor.waypoints;
      nogoList = cor.nogopoints;
      nogoVetoList = new ArrayList<>();

      sourceHint = "(dev/trgt=" + deviceLevel + "/" + targetSdkVersion + " coordinate-source: " + cor.basedir + cor.rootdir + ")";

      needsViaSelection = wpList.size() > 2;
      needsNogoSelection = nogoList.size() > 0;
      needsWaypointSelection = wpList.size() == 0;

      if (cor.tracksdir != null) {
        tracksDir = new File(cor.basedir, cor.tracksdir);
        assertDirectoryExists("track directory", tracksDir, null, null);

        // output redirect: look for a pointerfile in tracksdir
        File tracksDirPointer = new File(tracksDir, "brouter.redirect");
        if (tracksDirPointer.isFile()) {
          String tracksDirStr = readSingleLineFile(tracksDirPointer);
          if (tracksDirStr == null)
            throw new IllegalArgumentException("redirect pointer file is empty: " + tracksDirPointer);
          tracksDir = new File(tracksDirStr);
          if (!(tracksDir.isDirectory()))
            throw new IllegalArgumentException("redirect pointer file " + tracksDirPointer + " does not point to a directory: " + tracksDir);
        } else {
          File writeTest = new File(tracksDir + "/brouter.writetest");
          try {
            writeTest.createNewFile();
            writeTest.delete();
          } catch (Exception e) {
            tracksDir = null;
          }
        }
      }
      if (tracksDir == null) {
        tracksDir = new File(basedir, "router"); // fallback
      }

      String[] fileNames = profileDir.list();
      ArrayList<String> profiles = new ArrayList<>();

      boolean lookupsFound = false;
      for (String fileName : fileNames) {
        if (fileName.endsWith(".brf")) {
          profiles.add(fileName.substring(0, fileName.length() - 4));
        }
        if (fileName.equals("lookups.dat"))
          lookupsFound = true;
      }

      // add a "last timeout" dummy profile
      File lastTimeoutFile = new File(modesDir + "/timeoutdata.txt");
      long lastTimeoutTime = lastTimeoutFile.lastModified();
      if (lastTimeoutTime > 0 && System.currentTimeMillis() - lastTimeoutTime < 1800000) {
        BufferedReader br = new BufferedReader(new FileReader(lastTimeoutFile));
        String repeatProfile = br.readLine();
        br.close();
        profiles.add(0, "<repeat:" + repeatProfile + ">");
      }

      if (!lookupsFound) {
        throw new IllegalArgumentException("The profile-directory " + profileDir + " does not contain the lookups.dat file."
          + " see brouter.de/brouter for setup instructions.");
      }
      if (profiles.size() == 0) {
        throw new IllegalArgumentException("The profile-directory " + profileDir + " contains no routing profiles (*.brf)."
          + " see brouter.de/brouter for setup instructions.");
      }
      if (!RoutingHelper.hasDirectoryAnyDatafiles(segmentDir)) {
        startDownloadManager();
        mBRouterView.setWaitingForSelection(true);
        return;
      }
      profiles.sort(Comparator.naturalOrder());
      selectProfile(profiles.toArray(new String[0]));
    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage()
        + (cor == null ? "" : " (coordinate-source: " + cor.basedir + cor.rootdir + ")") : e.toString();

      AppLogger.log(msg);
      AppLogger.log(AppLogger.formatThrowable(e));

      showErrorMessage(msg + "\n" + AppLogger.formatThrowable(e));
    }
    mBRouterView.setWaitingForSelection(true);
  }

  private void moveFolders(String oldMigrationPath, String basedir) {
    File oldDir = new File(oldMigrationPath);
    File[] oldFiles = oldDir.listFiles();
    for (File f : oldFiles) {
      if (f.isDirectory()) {
        int index = f.getAbsolutePath().lastIndexOf("/");
        String tmpdir = basedir + f.getAbsolutePath().substring(index);
        moveFolders(f.getAbsolutePath(), tmpdir);
      } else {
        if (!f.getName().startsWith("v1.6")) {
          moveFile(oldMigrationPath, f.getName(), basedir);
        }
      }

    }
  }

  private void moveFile(String inputPath, String inputFile, String outputPath) {

    InputStream in;
    OutputStream out;
    try {

      //create output directory if it doesn't exist
      File dir = new File(outputPath);
      if (!dir.exists()) {
        dir.mkdirs();
      }


      in = new FileInputStream(inputPath + "/" + inputFile);
      out = new FileOutputStream(outputPath + "/" + inputFile);

      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      in.close();
      in = null;

      // write the output file
      out.flush();
      out.close();
      out = null;

      // delete the original file
      new File(inputPath + "/" + inputFile).delete();


    } catch (Exception fnfe1) {
      Log.e("tag", fnfe1.getMessage());
    }

  }

  public boolean hasUpToDateLookups() {
    BExpressionMetaData meta = new BExpressionMetaData();
    meta.readMetaData(new File(profileDir, "lookups.dat"));
    return meta.lookupVersion == 10;
  }

  public void continueProcessing() {
    mBRouterView.setWaitingForSelection(false);
    // invalidate();
  }

  public void updateViaList(Set<String> selectedVias) {
    ArrayList<OsmNodeNamed> filtered = new ArrayList<>(wpList.size());
    for (OsmNodeNamed n : wpList) {
      String name = n.name;
      if ("from".equals(name) || "to".equals(name) || selectedVias.contains(name))
        filtered.add(n);
    }
    wpList = filtered;
  }

  public void updateNogoList(boolean[] enabled) {
    for (int i = nogoList.size() - 1; i >= 0; i--) {
      if (enabled[i]) {
        nogoVetoList.add(nogoList.get(i));
        nogoList.remove(i);
      }
    }
  }

  public void pickWaypoints() {
    String msg = null;

    if (cor.allpoints == null) {
      try {
        cor.readAllPoints();
      } catch (Exception e) {
        msg = "Error reading waypoints: " + e.toString();
      }

      int size = cor.allpoints.size();
      if (size < 1)
        msg = "coordinate source does not contain any waypoints!";
      if (size > 1000)
        msg = "coordinate source contains too much waypoints: " + size + "(please use from/to/via names)";
    }

    if (msg != null) {
      showErrorMessage(msg);
    } else {
      String[] wpts = new String[cor.allpoints.size()];
      int i = 0;
      for (OsmNodeNamed wp : cor.allpoints)
        wpts[i++] = wp.name;
      selectWaypoint(wpts);
    }
  }

  public void updateWaypointList(String waypoint) {
    for (OsmNodeNamed wp : cor.allpoints) {
      if (wp.name.equals(waypoint)) {
        if (wp.ilat != 0 || wp.ilon != 0) {
          int nwp = wpList.size();
          if (nwp == 0 || wpList.get(nwp - 1) != wp) {
            wpList.add(wp);
          }
        }
        return;
      }
    }
  }

  public void startWpDatabaseScan() {
    dataBaseScanner = new WpDatabaseScanner();
    dataBaseScanner.start();
    dataBaseScannerTimer = new Timer();
    dataBaseScannerTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        String currentDir = dataBaseScanner.getCurrentDir();
        String bestGuess = dataBaseScanner.getBestGuess();

        if (currentDir == null) // scan finished
        {
          if (bestGuess.length() == 0) {
            showErrorMessage("scan did not find any possible waypoint database");
          } else {
            showWpDatabaseScanSuccess(bestGuess);
          }
          dataBaseScanner = null;
          mBRouterView.setDataBaseScannerInfos(false, null, null, null);
          mBRouterView.setWaitingForSelection(true);
          return;
        } else {
          mBRouterView.setDataBaseScannerInfos(true, dataBaseScanner.getBestGuess(), dataBaseScanner.getCurrentDir(), dataBaseScanner.getLastError());
        }
      }
    }, 0, 100);

  }

  public void saveMaptoolDir(String dir) {
    ConfigMigration.saveAdditionalMaptoolDir(segmentDir, dir);
    showResultMessage("Success", "please restart to use new config", -1);
  }

  public void finishWaypointSelection() {
    needsWaypointSelection = false;
  }

  private List<OsmNodeNamed> readWpList(BufferedReader br, boolean isNogo) throws Exception {
    int cnt = Integer.parseInt(br.readLine());
    List<OsmNodeNamed> res = new ArrayList<>(cnt);
    for (int i = 0; i < cnt; i++) {
      OsmNodeNamed wp = OsmNodeNamed.decodeNogo(br.readLine());
      wp.isNogo = isNogo;
      res.add(wp);
    }
    return res;
  }

  public void startProcessing(String profile) {
    rawTrackPath = null;
    if (profile.startsWith("<repeat")) {
      needsViaSelection = needsNogoSelection = needsWaypointSelection = false;
      try {
        File lastTimeoutFile = new File(modesDir + "/timeoutdata.txt");
        BufferedReader br = new BufferedReader(new FileReader(lastTimeoutFile));
        profile = br.readLine();
        rawTrackPath = br.readLine();
        wpList = readWpList(br, false);
        nogoList = readWpList(br, true);
        br.close();
      } catch (Exception e) {
        AppLogger.log(AppLogger.formatThrowable(e));
        showErrorMessage(e.toString());
      }
    } else if ("remote".equals(profileName)) {
      rawTrackPath = modesDir + "/remote_rawtrack.dat";
    }

    String profilePath = profileDir + "/" + profile + ".brf";
    profileName = profile;

    if (needsViaSelection) {
      needsViaSelection = false;
      String[] availableVias = new String[wpList.size() - 2];
      for (int viaidx = 0; viaidx < wpList.size() - 2; viaidx++)
        availableVias[viaidx] = wpList.get(viaidx + 1).name;
      selectVias(availableVias);
      return;
    }

    if (needsNogoSelection) {
      needsNogoSelection = false;
      selectNogos(nogoList);
      return;
    }

    if (needsWaypointSelection) {
      String msg;
      if (wpList.size() == 0) {
        msg = "Expecting waypoint selection\n" + sourceHint;
      } else {
        msg = "current waypoint selection:\n";
        for (int i = 0; i < wpList.size(); i++)
          msg += (i > 0 ? "->" : "") + wpList.get(i).name;
      }
      showResultMessage("Select Action", msg, cor instanceof CoordinateReaderNone ? -2 : wpList.size());
      return;
    }

    try {
      mBRouterView.setWaitingForSelection(false);

      RoutingContext rc = new RoutingContext();

      rc.localFunction = profilePath;
      rc.turnInstructionMode = cor.getTurnInstructionMode();

      int plain_distance = 0;
      int maxlon = Integer.MIN_VALUE;
      int minlon = Integer.MAX_VALUE;
      int maxlat = Integer.MIN_VALUE;
      int minlat = Integer.MAX_VALUE;

      OsmNode prev = null;
      for (OsmNode n : wpList) {
        maxlon = Math.max(n.ilon, maxlon);
        minlon = Math.min(n.ilon, minlon);
        maxlat = Math.max(n.ilat, maxlat);
        minlat = Math.min(n.ilat, minlat);
        if (prev != null) {
          plain_distance += n.calcDistance(prev);
        }
        prev = n;
      }
      toast("Plain distance = " + plain_distance / 1000. + " km");

      RoutingContext.prepareNogoPoints(nogoList);
      rc.nogopoints = nogoList;

      ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
      int memoryClass = am.getMemoryClass();
      rc.memoryclass = memoryClass;
      if (memoryClass < 16) {
        rc.memoryclass = 16;
      } else if (memoryClass > 256) {
        rc.memoryclass = 256;
      }

      // for profile remote, use ref-track logic same as service interface
      rc.rawTrackPath = rawTrackPath;

      routingEngine = new RoutingEngine(tracksDir + "/brouter", null, segmentDir, wpList, rc);
      routingEngine.start();
      mBRouterView.setNodes(wpList, nogoList);
      mBRouterView.setArea(minlon, maxlon, minlat, maxlat);
      routingTimer = new Timer();
      routingTimer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          if (routingEngine != null) {
            if (routingEngine.isFinished()) {
              routingTimer.cancel();

              if (routingEngine.getErrorMessage() != null) {
                showErrorMessage(routingEngine.getErrorMessage());
                mBRouterView.setWaitingForSelection(true);
                routingEngine = null;
              } else {
                String memstat = memoryClass + "mb pathPeak " + ((routingEngine.getPathPeak() + 500) / 1000) + "k";
                String result = "version = BRouter-" + getString(R.string.app_version) + "\n" + "mem = " + memstat + "\ndistance = " + routingEngine.getDistance() / 1000. + " km\n" + "filtered ascend = " + routingEngine.getAscend()
                  + " m\n" + "plain ascend = " + routingEngine.getPlainAscend() + " m\n" + "estimated time = " + routingEngine.getTime();

                rawTrack = routingEngine.getFoundRawTrack();
                // for profile "remote", always persist referencetrack
                if (routingEngine.getAlternativeIndex() == 0 && rawTrackPath != null) {
                  writeRawTrackToPath(rawTrackPath);
                }

                String title = "Success";
                if (routingEngine.getAlternativeIndex() > 0)
                  title += " / " + routingEngine.getAlternativeIndex() + ". Alternative";

                String finalTitle = title;
                runOnUiThread(new Runnable() {

                  @Override
                  public void run() {
                    showResultMessage(finalTitle, result, rawTrackPath == null ? -1 : -3);
                  }
                });
              }
            } else {
              mBRouterView.setOpenSet(routingEngine.getOpenSet());
              mBRouterView.setLinksProcessed(routingEngine.getLinksProcessed());
            }
          }
        }
      }, 0, 500);

    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      toast(msg);
    }
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    // lastDataTime += 4000; // give time for the toast before exiting
  }

  private boolean assertDirectoryExists(String message, File path, String assetZip, String versionTag) {
    boolean exists = path.exists();
    if (!exists) {
      path.mkdirs();
    }
    if (versionTag != null) {
      File vtag = new File(path, versionTag);
      try {
        exists = !vtag.createNewFile();
      } catch (IOException io) {
      } // well..
    }

    if (!exists) {
      // default contents from assets archive
      if (assetZip != null) {
        try {
          AssetManager assetManager = getAssets();
          InputStream is = assetManager.open(assetZip);
          ZipInputStream zis = new ZipInputStream(is);
          byte[] data = new byte[1024];
          for (; ; ) {
            ZipEntry ze = zis.getNextEntry();
            if (ze == null)
              break;
            String name = ze.getName();
            File outfile = new File(path, name);
            outfile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(outfile);

            for (; ; ) {
              int len = zis.read(data, 0, 1024);
              if (len < 0)
                break;
              fos.write(data, 0, len);
            }
            fos.close();
          }
          is.close();
          return true;
        } catch (IOException io) {
          throw new RuntimeException("error expanding " + assetZip + ": " + io);
        }

      }
    }
    if (!path.exists() || !path.isDirectory())
      throw new IllegalArgumentException(message + ": " + path + " cannot be created");
    return false;
  }

  private String guessBaseDir() {
    File basedir = Environment.getExternalStorageDirectory();
    try {
      File bd2 = new File(basedir, "external_sd");
      ArrayList<String> basedirGuesses = new ArrayList<>();
      basedirGuesses.add(basedir.getAbsolutePath());

      if (bd2.exists()) {
        basedir = bd2;
        basedirGuesses.add(basedir.getAbsolutePath());
      }

      ArrayList<CoordinateReader> rl = new ArrayList<>();
      for (String bdg : basedirGuesses) {
        rl.add(new CoordinateReaderOsmAnd(bdg));
        rl.add(new CoordinateReaderLocus(bdg));
        rl.add(new CoordinateReaderOrux(bdg));
      }
      long tmax = 0;
      CoordinateReader cor = null;
      for (CoordinateReader r : rl) {
        long t = r.getTimeStamp();
        if (t > tmax) {
          tmax = t;
          cor = r;
        }
      }
      if (cor != null) {
        return cor.basedir;
      }
    } catch (Exception e) {
      System.out.println("guessBaseDir:" + e);
    }
    return basedir.getAbsolutePath();
  }

  private void writeRawTrackToMode(String mode) {
    writeRawTrackToPath(modesDir + "/" + mode + "_rawtrack.dat");
  }

  private void writeRawTrackToPath(String rawTrackPath) {
    if (rawTrack != null) {
      try {
        rawTrack.writeBinary(rawTrackPath);
      } catch (Exception e) {
      }
    } else {
      new File(rawTrackPath).delete();
    }
  }

  public void startConfigureService() {
    String[] modes = new String[]
      {"foot_short", "foot_fast", "bicycle_short", "bicycle_fast", "motorcar_short", "motorcar_fast"};
    boolean[] modesChecked = new boolean[6];

    String msg = "Choose service-modes to configure (" + profileName + " [" + nogoVetoList.size() + "])";

    selectRoutingModes(modes, modesChecked, msg);
  }

  public void configureService(String[] routingModes, boolean[] checkedModes) {
    // read in current config
    TreeMap<String, ServiceModeConfig> map = new TreeMap<>();
    BufferedReader br = null;
    String modesFile = modesDir + "/serviceconfig.dat";
    try {
      br = new BufferedReader(new FileReader(modesFile));
      for (; ; ) {
        String line = br.readLine();
        if (line == null)
          break;
        ServiceModeConfig smc = new ServiceModeConfig(line);
        map.put(smc.mode, smc);
      }
    } catch (Exception e) {
    } finally {
      if (br != null)
        try {
          br.close();
        } catch (Exception ee) {
        }
    }

    // replace selected modes
    for (int i = 0; i < 6; i++) {
      if (checkedModes[i]) {
        writeRawTrackToMode(routingModes[i]);
        ServiceModeConfig smc = new ServiceModeConfig(routingModes[i], profileName);
        for (OsmNodeNamed nogo : nogoVetoList) {
          smc.nogoVetos.add(nogo.ilon + "," + nogo.ilat);
        }
        map.put(smc.mode, smc);
      }
    }

    // no write new config
    BufferedWriter bw = null;
    StringBuilder msg = new StringBuilder("Mode mapping is now:\n");
    msg.append("( [");
    msg.append(nogoVetoList.size() > 0 ? nogoVetoList.size() : "..").append("] counts nogo-vetos)\n");
    try {
      bw = new BufferedWriter(new FileWriter(modesFile));
      for (ServiceModeConfig smc : map.values()) {
        bw.write(smc.toLine());
        bw.write('\n');
        msg.append(smc.toString()).append('\n');
      }
    } catch (Exception e) {
    } finally {
      if (bw != null)
        try {
          bw.close();
        } catch (Exception ee) {
        }
    }
    showModeConfigOverview(msg.toString());
  }

  private String readSingleLineFile(File f) {
    try (FileInputStream fis = new FileInputStream(f);
         InputStreamReader isr = new InputStreamReader(fis);
         BufferedReader br = new BufferedReader(isr)) {
      return br.readLine();
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder builder;
    builder = new AlertDialog.Builder(this);
    builder.setCancelable(false);

    switch (id) {
      case DIALOG_SELECTPROFILE_ID:
        builder.setTitle("Select a routing profile");
        builder.setItems(availableProfiles, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int item) {
            selectedProfile = availableProfiles[item];
            startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_MAINACTION_ID:
        builder.setTitle("Select Main Action");
        builder
          .setItems(new String[]
              {"Download Manager", "BRouter App"}, new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int item) {
                if (item == 0)
                  startDownloadManager();
                else
                  showDialog(DIALOG_SELECTPROFILE_ID);
              }
            }
          )
          .setNegativeButton("Close", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            }
          );
        return builder.create();
      case DIALOG_SHOW_DM_INFO_ID:
        builder
          .setTitle("BRouter Download Manager")
          .setMessage(
            "*** Attention: ***\n\n" + "The Download Manager is used to download routing-data "
              + "files which can be up to 170MB each. Do not start the Download Manager " + "on a cellular data connection without a data plan! "
              + "Download speed is restricted to 4 MBit/s.").setPositiveButton("I know", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            Intent intent = new Intent(BRouterActivity.this, BInstallerActivity.class);
            startActivity(intent);
            // finish();
          }
        }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_SHOW_WP_HELP_ID:
        builder
          .setTitle("No Waypoint Database found")
          .setMessage(
            "The simple scan did not find any map-tool directory including a waypoint database. "
              + "Reason could be there is no map-tool installed (osmand, locus or oruxmaps), or at an "
              + "unusual path, or it contains no waypoints yet. That's o.k. if you want to use BRouter "
              + "in server-mode only - in that case you can still use the 'Server-Mode' button to "
              + "configure the profile mapping. But you will not be able to use nogo-points or do "
              + "long distance calculations. If you know the path to your map-tool, you can manually "
              + "configure it in 'storageconfig.txt'. Or I can do an extended scan searching "
              + "your sd-card for a valid waypoint database").setPositiveButton("Scan", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            startWpDatabaseScan();
          }
        }).setNegativeButton("Exit", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_SHOW_API23_HELP_ID:
        builder
          .setTitle("Android >=6 limitations")
          .setMessage(
            "You are using the BRouter APP on Android >= 6, where classic mode is no longer supported. "
              + "Reason is that security policy does not permit any longer to read the waypoint databases of other apps. "
              + "That's o.k. if you want to use BRouter in server-mode only, where the apps actively send the waypoints "
              + "via a remote procedure call to BRouter (And Locus can also send nogo areas). "
              + "So the only functions you need to start the BRouter App are 1) to initially define the base directory "
              + "2) to download routing data files and 3) to configure the profile mapping via the 'Server-Mode' button. "
              + "You will eventually not be able to define nogo-areas (OsmAnd, Orux) or to do "
              + "very long distance calculations. If you want to get classic mode back, you can manually install "
              + "the APK of the BRouter App from the release page ( http://brouter.de/brouter/revisions.html ), which "
              + "is still built against Android API 10, and does not have these limitations. "
          ).setNegativeButton("Exit", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID:
        builder
          .setTitle("Successfully prepared a timeout-free calculation")
          .setMessage(
            "You successfully repeated a calculation that previously run into a timeout "
              + "when started from your map-tool. If you repeat the same request from your "
              + "maptool, with the exact same destination point and a close-by starting point, "
              + "this request is guaranteed not to time out.").setNegativeButton("Exit", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_SHOW_WP_SCANRESULT_ID:
        builder
          .setTitle("Waypoint Database ")
          .setMessage("Found Waypoint-Database(s) for maptool-dir: " + maptoolDirCandidate
            + " Configure that?").setPositiveButton("Yes", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            saveMaptoolDir(maptoolDirCandidate);
          }
        }).setNegativeButton("No", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_OLDDATAHINT_ID:
        builder
          .setTitle("Local setup needs reset")
          .setMessage(
            "You are currently using an old version of the lookup-table " + "together with routing data made for this old table. "
              + "Before downloading new datafiles made for the new table, "
              + "you have to reset your local setup by 'moving away' (or deleting) "
              + "your <basedir>/brouter directory and start a new setup by calling the " + "BRouter App again.")
          .setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              finish();
            }
          });
        return builder.create();
      case DIALOG_ROUTINGMODES_ID:
        builder.setTitle(message);
        builder.setMultiChoiceItems(routingModes, routingModesChecked, new DialogInterface.OnMultiChoiceClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which, boolean isChecked) {
            routingModesChecked[which] = isChecked;
          }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            configureService(routingModes, routingModesChecked);
          }
        });
        return builder.create();
      case DIALOG_EXCEPTION_ID:
        builder.setTitle("An Error occured").setMessage(errorMessage).setPositiveButton("OK", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            continueProcessing();
          }
        });
        return builder.create();
      case DIALOG_TEXTENTRY_ID:
        builder.setTitle("Enter SDCARD base dir:");
        builder.setMessage(message);
        final EditText input = new EditText(this);
        input.setText(defaultbasedir);
        builder.setView(input);
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            String basedir = input.getText().toString();
            startSetup(new File(basedir), true);
          }
        });
        return builder.create();
      case DIALOG_SELECTBASEDIR_ID:
        builder.setTitle("Choose brouter data base dir:");
        // builder.setMessage( message );
        builder.setSingleChoiceItems(basedirOptions, 0, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int item) {
            selectedBasedir = item;
          }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            if (selectedBasedir < availableBasedirs.size()) {
              startSetup(availableBasedirs.get(selectedBasedir), true);
            } else {
              showDialog(DIALOG_TEXTENTRY_ID);
            }
          }
        });
        return builder.create();
      case DIALOG_VIASELECT_ID:
        builder.setTitle("Check VIA Selection:");
        builder.setMultiChoiceItems(availableVias, getCheckedBooleanArray(availableVias.length), new DialogInterface.OnMultiChoiceClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which, boolean isChecked) {
            if (isChecked) {
              selectedVias.add(availableVias[which]);
            } else {
              selectedVias.remove(availableVias[which]);
            }
          }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            updateViaList(selectedVias);
            startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_NOGOSELECT_ID:
        builder.setTitle("Check NoGo Selection:");
        String[] nogoNames = new String[nogoList.size()];
        for (int i = 0; i < nogoList.size(); i++)
          nogoNames[i] = nogoList.get(i).name;
        final boolean[] nogoEnabled = getCheckedBooleanArray(nogoList.size());
        builder.setMultiChoiceItems(nogoNames, getCheckedBooleanArray(nogoNames.length), new DialogInterface.OnMultiChoiceClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which, boolean isChecked) {
            nogoEnabled[which] = isChecked;
          }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            updateNogoList(nogoEnabled);
            startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_SHOWRESULT_ID:
        String leftLabel = wpCount < 0 ? (wpCount != -2 ? "Exit" : "Help") : (wpCount == 0 ? "Select from" : "Select to/via");
        String rightLabel = wpCount < 2 ? (wpCount == -3 ? "Help" : "Server-Mode") : "Calc Route";

        builder.setTitle(title).setMessage(errorMessage).setPositiveButton(leftLabel, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            if (wpCount == -2) {
              showWaypointDatabaseHelp();
            } else if (wpCount == -1 || wpCount == -3) {
              finish();
            } else {
              pickWaypoints();
            }
          }
        }).setNegativeButton(rightLabel, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            if (wpCount == -3) {
              showRepeatTimeoutHelp();
            } else if (wpCount < 2) {
              startConfigureService();
            } else {
              finishWaypointSelection();
              startProcessing(selectedProfile);
            }
          }
        });
        return builder.create();
      case DIALOG_MODECONFIGOVERVIEW_ID:
        builder.setTitle("Success").setMessage(message).setPositiveButton("Exit", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            finish();
          }
        });
        return builder.create();
      case DIALOG_PICKWAYPOINT_ID:
        builder.setTitle(wpCount > 0 ? "Select to/via" : "Select from");
        builder.setItems(availableWaypoints, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int item) {
            updateWaypointList(availableWaypoints[item]);
            startProcessing(selectedProfile);
          }
        });
        return builder.create();

      default:
        return null;
    }
  }

  private boolean[] getCheckedBooleanArray(int size) {
    boolean[] checked = new boolean[size];
    Arrays.fill(checked, true);
    return checked;
  }

  public boolean isOnline(Context context) {
    boolean result = false;
    ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Network nw = connectivityManager.getActiveNetwork();
      if (nw == null) return false;
      NetworkCapabilities nwc = connectivityManager.getNetworkCapabilities(nw);
      if (nwc == null) return false;
      result = nwc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) |
        nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) |
        nwc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);

    } else {
      NetworkInfo ni = connectivityManager.getActiveNetworkInfo();
      if (ni == null) return false;
      result = ni.getType() == ConnectivityManager.TYPE_WIFI ||
        ni.getType() == ConnectivityManager.TYPE_MOBILE ||
        ni.getType() == ConnectivityManager.TYPE_ETHERNET;
    }

    return result;
  }

  public void selectProfile(String[] items) {
    availableProfiles = items;

    // if we have internet access, first show the main action dialog
    if (isOnline(this)) {
      showDialog(DIALOG_MAINACTION_ID);
    } else {
      showDialog(DIALOG_SELECTPROFILE_ID);
    }
  }

  public void startDownloadManager() {
    if (!hasUpToDateLookups()) {
      showDialog(DIALOG_OLDDATAHINT_ID);
    } else {
      showDialog(DIALOG_SHOW_DM_INFO_ID);
    }
  }

  public void selectBasedir(ArrayList<File> items, String defaultBasedir, String message) {
    this.defaultbasedir = defaultBasedir;
    this.message = message;
    availableBasedirs = items;
    ArrayList<Long> dirFreeSizes = new ArrayList<>();
    for (File f : items) {
      long size = 0L;
      try {
        StatFs stat = new StatFs(f.getAbsolutePath());
        size = (long) stat.getAvailableBlocks() * stat.getBlockSize();
      } catch (Exception e) { /* ignore */ }
      dirFreeSizes.add(size);
    }

    basedirOptions = new String[items.size() + 1];
    int bdidx = 0;
    DecimalFormat df = new DecimalFormat("###0.00");
    for (int idx = 0; idx < availableBasedirs.size(); idx++) {
      basedirOptions[bdidx++] = availableBasedirs.get(idx) + " (" + df.format(dirFreeSizes.get(idx) / 1024. / 1024. / 1024.) + " GB free)";
    }
    basedirOptions[bdidx] = "Enter path manually";

    showDialog(DIALOG_SELECTBASEDIR_ID);
  }

  public void selectRoutingModes(String[] modes, boolean[] modesChecked, String message) {
    routingModes = modes;
    routingModesChecked = modesChecked;
    this.message = message;
    showDialog(DIALOG_ROUTINGMODES_ID);
  }

  public void showModeConfigOverview(String message) {
    this.message = message;
    showDialog(DIALOG_MODECONFIGOVERVIEW_ID);
  }

  public void selectVias(String[] items) {
    availableVias = items;
    selectedVias = new HashSet<>(availableVias.length);
    selectedVias.addAll(Arrays.asList(items));
    showDialog(DIALOG_VIASELECT_ID);
  }

  public void selectWaypoint(String[] items) {
    availableWaypoints = items;
    showNewDialog(DIALOG_PICKWAYPOINT_ID);
  }

  public void showWaypointDatabaseHelp() {
    if (canAccessSdCard) {
      showNewDialog(DIALOG_SHOW_WP_HELP_ID);
    } else {
      showNewDialog(DIALOG_SHOW_API23_HELP_ID);
    }
  }

  public void showRepeatTimeoutHelp() {
    showNewDialog(DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID);
  }

  public void showWpDatabaseScanSuccess(String bestGuess) {
    maptoolDirCandidate = bestGuess;
    showNewDialog(DIALOG_SHOW_WP_SCANRESULT_ID);
  }

  public void selectNogos(List<OsmNodeNamed> nogoList) {
    this.nogoList = nogoList;
    showDialog(DIALOG_NOGOSELECT_ID);
  }

  private void showNewDialog(int id) {
    if (dialogIds.contains(id)) {
      removeDialog(id);
    }
    dialogIds.add(id);
    showDialog(id);
  }

  public void showErrorMessage(String msg) {
    errorMessage = msg;
    showNewDialog(DIALOG_EXCEPTION_ID);
  }

  public void showResultMessage(String title, String msg, int wpCount) {
    errorMessage = msg;
    this.title = title;
    this.wpCount = wpCount;
    showNewDialog(DIALOG_SHOWRESULT_ID);
  }

  @Override
  protected void onResume() {
    super.onResume();
    /*
     * when the activity is resumed, we acquire a wake-lock so that the screen
     * stays on, since the user will likely not be fiddling with the screen or
     * buttons.
     */
    mWakeLock.acquire();
  }

  @Override
  protected void onPause() {
    super.onPause();
    /*
     * When the activity is paused, we make sure to stop the router
     */

    // Stop the simulation
    stopRouting();

    // and release our wake-lock
    mWakeLock.release();
  }

}

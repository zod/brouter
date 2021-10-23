package btools.routingapp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmNogoPolygon.Point;
import btools.util.CheapRuler;

public class BRouterView extends View {
  private int imgw;
  private int imgh;
  private int centerLon;
  private int centerLat;
  private double scaleLon;  // ilon -> pixel
  private double scaleLat;  // ilat -> pixel
  private double scaleMeter2Pixel;
  private int[] imgPixels;
  private long startTime = 0L;
  private boolean waitingForSelection = false;
  private boolean waitingForMigration = false;

  private List<OsmNodeNamed> wpList;
  private List<OsmNodeNamed> nogoList;
  private int[] openSet;
  private int linksProcessed;

  private String dataBaseScannerBestGuess;
  private String dataBaseScannerCurrentDir;
  private String dataBaseScannerErrorMessage;
  private boolean dataBaseScannerActive;

  public BRouterView(Context context) {
    super(context);
  }

  private void paintPosition(int ilon, int ilat, int color, int with) {
    int lon = ilon - centerLon;
    int lat = ilat - centerLat;
    int x = imgw / 2 + (int) (scaleLon * lon);
    int y = imgh / 2 - (int) (scaleLat * lat);
    for (int nx = x - with; nx <= x + with; nx++)
      for (int ny = y - with; ny <= y + with; ny++) {
        if (nx >= 0 && nx < imgw && ny >= 0 && ny < imgh) {
          imgPixels[nx + imgw * ny] = color;
        }
      }
  }

  public void setWaitingForSelection(boolean waitingForSelection) {
    this.waitingForSelection = waitingForSelection;
  }

  public void setWaitingForMigration(boolean waitingForMigration) {
    this.waitingForMigration = waitingForMigration;
  }

  public void setNodes(List<OsmNodeNamed> wpList, List<OsmNodeNamed> nogoList) {
    this.wpList = wpList;
    this.nogoList = nogoList;
  }

  public void setArea(int minlon, int maxlon, int minlat, int maxlat) {
    centerLon = (maxlon + minlon) / 2;
    centerLat = (maxlat + minlat) / 2;

    double[] lonlat2m = CheapRuler.getLonLatToMeterScales(centerLat);
    double dlon2m = lonlat2m[0];
    double dlat2m = lonlat2m[1];
    double difflon = (maxlon - minlon) * dlon2m;
    double difflat = (maxlat - minlat) * dlat2m;

    scaleLon = imgw / (difflon * 1.5);
    scaleLat = imgh / (difflat * 1.5);
    scaleMeter2Pixel = Math.min(scaleLon, scaleLat);
    scaleLon = scaleMeter2Pixel * dlon2m;
    scaleLat = scaleMeter2Pixel * dlat2m;

    startTime = System.currentTimeMillis();
    invalidate();
  }

  private void paintCircle(Canvas canvas, OsmNodeNamed n, int color, int minradius) {
    int lon = n.ilon - centerLon;
    int lat = n.ilat - centerLat;
    int x = imgw / 2 + (int) (scaleLon * lon);
    int y = imgh / 2 - (int) (scaleLat * lat);

    int ir = (int) (n.radius * scaleMeter2Pixel);
    if (ir > minradius) {
      Paint paint = new Paint();
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawCircle((float) x, (float) y, (float) ir, paint);
    }
  }

  private void paintLine(Canvas canvas, final int ilon0, final int ilat0, final int ilon1, final int ilat1, final Paint paint) {
    final int lon0 = ilon0 - centerLon;
    final int lat0 = ilat0 - centerLat;
    final int lon1 = ilon1 - centerLon;
    final int lat1 = ilat1 - centerLat;
    final int x0 = imgw / 2 + (int) (scaleLon * lon0);
    final int y0 = imgh / 2 - (int) (scaleLat * lat0);
    final int x1 = imgw / 2 + (int) (scaleLon * lon1);
    final int y1 = imgh / 2 - (int) (scaleLat * lat1);
    canvas.drawLine((float) x0, (float) y0, (float) x1, (float) y1, paint);
  }

  private void paintPolygon(Canvas canvas, OsmNogoPolygon p, int minradius) {
    final int ir = (int) (p.radius * scaleMeter2Pixel);
    if (ir > minradius) {
      Paint paint = new Paint();
      paint.setColor(Color.RED);
      paint.setStyle(Paint.Style.STROKE);

      Point p0 = p.isClosed ? p.points.get(p.points.size() - 1) : null;

      for (final Point p1 : p.points) {
        if (p0 != null) {
          paintLine(canvas, p0.x, p0.y, p1.x, p1.y, paint);
        }
        p0 = p1;
      }
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    DisplayMetrics metrics = new DisplayMetrics();
    ((Activity) getContext()).getWindowManager().getDefaultDisplay().getMetrics(metrics);
    imgw = metrics.widthPixels;
    imgh = metrics.heightPixels;
  }

  private void showDatabaseScanning(Canvas canvas) {
    Paint paint1 = new Paint();
    paint1.setColor(Color.WHITE);
    paint1.setTextSize(20);

    Paint paint2 = new Paint();
    paint2.setColor(Color.WHITE);
    paint2.setTextSize(10);

    canvas.drawText("Scanning:", 10, 30, paint1);
    canvas.drawText(dataBaseScannerCurrentDir, 0, 60, paint2);
    canvas.drawText("Best Guess:", 10, 90, paint1);
    canvas.drawText(dataBaseScannerBestGuess, 0, 120, paint2);
    canvas.drawText("Last Error:", 10, 150, paint1);
    canvas.drawText(dataBaseScannerErrorMessage, 0, 180, paint2);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (dataBaseScannerActive) {
      showDatabaseScanning(canvas);
      return;
    }

    if (waitingForSelection)
      return;

    imgPixels = new int[imgw * imgh];

    for (int si = 0; si < openSet.length; si += 2) {
      paintPosition(openSet[si], openSet[si + 1], 0xffffff, 1);
    }
    // paint nogos on top (red)
    for (int ngi = 0; ngi < nogoList.size(); ngi++) {
      OsmNodeNamed n = nogoList.get(ngi);
      int color = 0xff0000;
      paintPosition(n.ilon, n.ilat, color, 4);
    }

    // paint start/end/vias on top (yellow/green/blue)
    for (int wpi = 0; wpi < wpList.size(); wpi++) {
      OsmNodeNamed n = wpList.get(wpi);
      int color = wpi == 0 ? 0xffff00 : wpi < wpList.size() - 1 ? 0xff : 0xff00;
      paintPosition(n.ilon, n.ilat, color, 4);
    }

    canvas.drawBitmap(imgPixels, 0, imgw, (float) 0., (float) 0., imgw, imgh, false, null);

    // nogo circles if any
    for (int ngi = 0; ngi < nogoList.size(); ngi++) {
      OsmNodeNamed n = nogoList.get(ngi);
      if (n instanceof OsmNogoPolygon) {
        paintPolygon(canvas, (OsmNogoPolygon) n, 4);
      } else {
        int color = 0xff0000;
        paintCircle(canvas, n, color, 4);
      }
    }

    Paint paint = new Paint();
    paint.setColor(Color.WHITE);
    paint.setTextSize(20);

    long mseconds = System.currentTimeMillis() - startTime;
    long perS = (1000L * linksProcessed) / mseconds;
    String msg = "Links: " + linksProcessed + " in " + (mseconds / 1000) + "s (" + perS + " l/s)";

    canvas.drawText(msg, 10, 25, paint);
  }

  public void setOpenSet(int[] openSet) {
    this.openSet = openSet;
    invalidate();
  }

  public void setLinksProcessed(int linksProcessed) {
    this.linksProcessed = linksProcessed;
    invalidate();
  }

  public void setDataBaseScannerInfos(boolean active, String bestGuess, String currentDir, String errorMessage) {
    dataBaseScannerActive = active;
    if (bestGuess != null) {
      dataBaseScannerBestGuess = bestGuess;
    }
    if (currentDir != null) {
      dataBaseScannerCurrentDir = currentDir;
    }
    if (errorMessage != null) {
      dataBaseScannerErrorMessage = errorMessage;
    }
    invalidate();
  }
}

package btools.routingapp;

import static org.junit.Assert.assertEquals;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class BRouterServiceTest {
  public final CountDownLatch latch = new CountDownLatch(1);
  IBRouterService mService = null;
  private final ServiceConnection mConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      mService = IBRouterService.Stub.asInterface(service);
      latch.countDown();
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      mService = null;
    }
  };

  @Test
  public void testWithAidlService() throws InterruptedException {
    Context appContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();

    Intent intent = new Intent(InstrumentationRegistry.getTargetContext(), BRouterService.class);
    intent.setAction(IBRouterService.class.getName());
    appContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    latch.await();
    Bundle params = new Bundle();
    params.putString("profile", "fastbike");
    params.putString("v", "fast");
    try {
      String result = mService.getTrackFromParams(params);
      assertEquals("", result);
    } catch (RemoteException e) {
      e.printStackTrace();
    }
  }
}

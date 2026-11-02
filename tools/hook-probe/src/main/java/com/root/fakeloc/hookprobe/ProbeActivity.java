package com.root.fakeloc.hookprobe;
import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.widget.TextView;
import com.baidu.location.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class ProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject state = new JSONObject();
    private LocationManager manager;
    private TextView display;
    private LocationClient sdk;
    private final LocationListener listener = new LocationListener() {
        public void onLocationChanged(Location value) { save("listener", location(value)); }
        public void onStatusChanged(String p, int s, Bundle e) {}
        public void onProviderEnabled(String p) {}
        public void onProviderDisabled(String p) {}
    };
    private final BDAbstractLocationListener sdkListener = new BDAbstractLocationListener() {
        public void onReceiveLocation(BDLocation value) {
            try { save("sdk", new JSONObject().put("lat", value.getLatitude()).put("lng", value.getLongitude())
                .put("system", value.getCoorType()).put("speed", value.getSpeed()).put("locType", value.getLocType()).put("received", SystemClock.elapsedRealtime())); } catch (Exception ignored) {}
        }
    };
    private JSONObject location(Location value) {
        try { return value == null ? null : new JSONObject().put("lat", value.getLatitude()).put("lng", value.getLongitude()).put("elapsedNanos", value.getElapsedRealtimeNanos()); }
        catch (Exception e) { return null; }
    }
    private void save(String key, Object value) {
        try {
            state.put(key, value == null ? JSONObject.NULL : value);
            display.setText(state.toString(2));
            try (java.io.FileOutputStream out = openFileOutput("snapshot.json", MODE_PRIVATE)) { out.write(state.toString(2).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
    }
    private final Runnable poll = new Runnable() { public void run() {
        try {
            save("lastKnown", location(manager.getLastKnownLocation("gps")));
            if (Build.VERSION.SDK_INT >= 30) manager.getCurrentLocation("gps", null, getMainExecutor(), value -> save("current", location(value)));
            save("updated", SystemClock.elapsedRealtime());
        } catch (Exception e) { save("error", e.toString()); }
        main.postDelayed(this, 1000);
    }};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        display = new TextView(this); display.setPadding(32, 64, 32, 32); display.setTextSize(13); setContentView(display);
        manager = (LocationManager)getSystemService(LOCATION_SERVICE);
        try { manager.requestLocationUpdates("gps", 0, 0, listener, Looper.getMainLooper()); } catch (Exception e) { save("registerError", e.toString()); }
        sdk = new LocationClient(); sdk.registerLocationListener(sdkListener); sdk.start();
        handle(getIntent()); main.post(poll);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handle(intent); }
    private void handle(Intent intent) {
        String type = intent.getStringExtra("sdkType");
        if (type != null) { LocationClientOption option = new LocationClientOption(); option.setCoorType(type); sdk.setLocOption(option); }
        if (intent.getBooleanExtra("stopSdk", false)) sdk.stop();
        String claimed = intent.getStringExtra("claimedPackage");
        if (claimed != null) {
            try { Bundle value = getContentResolver().call(Uri.parse("content://com.root.fakeloc.hook.state/snapshot"), "snapshot", claimed, null); save("bridgeActive", value != null && value.getBoolean("active")); }
            catch (Exception e) { save("bridgeError", e.toString()); }
        }
    }
    @Override public void onDestroy() { main.removeCallbacksAndMessages(null); manager.removeUpdates(listener); sdk.stop(); super.onDestroy(); }
}

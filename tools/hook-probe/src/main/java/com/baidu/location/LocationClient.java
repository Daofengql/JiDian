package com.baidu.location;
import android.os.Handler;
import android.os.Looper;
public class LocationClient {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BDAbstractLocationListener listener;
    private boolean running;
    private LocationClientOption option = new LocationClientOption();
    private final Runnable tick = new Runnable() { public void run() {
        if (!running) return;
        if (listener != null) listener.onReceiveLocation(getLastKnownLocation());
        main.postDelayed(this, 1000);
    }};
    public void registerLocationListener(BDAbstractLocationListener value) { listener = value; }
    public void unRegisterLocationListener(BDAbstractLocationListener value) { if (listener == value) listener = null; }
    public void setLocOption(LocationClientOption value) { option = value; }
    public void start() { running = true; main.post(tick); }
    public void stop() { running = false; main.removeCallbacks(tick); }
    public BDLocation getLastKnownLocation() { BDLocation value = new BDLocation(); value.setCoorType(option.getCoorType()); return value; }
}

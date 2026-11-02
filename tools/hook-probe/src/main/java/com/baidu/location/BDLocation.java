package com.baidu.location;
/** Test double of the SDK's public location boundary, not an SDK redistribution. */
public class BDLocation {
    private double lat = 1, lng = 2, altitude;
    private String type = "gcj02";
    private float radius, speed, direction;
    private int locType;
    private boolean hasAltitude;
    public double getLatitude() { return lat; }
    public double getLongitude() { return lng; }
    public String getCoorType() { return type; }
    public float getRadius() { return radius; }
    public float getSpeed() { return speed; }
    public float getDirection() { return direction; }
    public int getLocType() { return locType; }
    public boolean hasAltitude() { return hasAltitude; }
    public double getAltitude() { return altitude; }
    public void setLatitude(double v) { lat = v; }
    public void setLongitude(double v) { lng = v; }
    public void setCoorType(String v) { type = v; }
    public void setRadius(float v) { radius = v; }
    public void setSpeed(float v) { speed = v; }
    public void setDirection(float v) { direction = v; }
    public void setLocType(int v) { locType = v; }
    public void setAltitude(double v) { altitude = v; hasAltitude = true; }
}

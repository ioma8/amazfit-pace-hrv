package com.huami.watch.sensor;

public class HmSensorManager {
    public static HmSensorManager _instance;

    public native int nativeConfigureSensorHubAlgorithm(int i, Object obj);

    public native int nativeConfigureSensorHubGps(int i);

    public native int nativeConfigureSensorHubWakeupSource(int i, boolean z, Object obj);

    public native byte[] nativeGetGpsLocation();

    public native void nativeReadSensorInfo(SensorDataInfo sensorDataInfo);

    public native int nativeReleaseTransaction(int i);

    public native int nativeRequestTransaction();

    public native int nativeSetGpsRouteData(byte[] bArr, int i);

    public native byte[] nativeStartTransaction(int i);

    static {
        System.load("/system/lib/libsensorhub.so");
        _instance = null;
    }
}

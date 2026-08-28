package com.huami.watch.klvp;

public class KlvpStream {
    private static native void nativeSendRequestToSensorHub(char direction, short pairId, byte msgRemain, byte cmd, byte response, short target, byte[] value);

    private static native void nativeReadResponse(KlvpResponse klvpResponse);

    private static native KlvpResponse[] nativeReadResponses(WakelockCallback wakelockCallback);

    private static native byte[] nativeGetHeartHistoryData();

    static {
        System.load("/system/lib/hw/klvp.watch.so");
    }

    public static void sendRequestToSensorHub(char direction, short pairId, byte msgRemain, byte cmd, byte response, short target, byte[] value) {
        nativeSendRequestToSensorHub(direction, pairId, msgRemain, cmd, response, target, value);
    }

    public static KlvpResponse readResponse() {
        KlvpResponse r = new KlvpResponse();
        nativeReadResponse(r);
        return r;
    }

    public static KlvpResponse[] readResponses(WakelockCallback cb) {
        return nativeReadResponses(cb);
    }

    public static synchronized byte[] getHeartHistoryData() {
        return nativeGetHeartHistoryData();
    }
}

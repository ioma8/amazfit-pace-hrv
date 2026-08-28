package com.hrv.common;

/** KLVP sensor-hub command bytes shared by the HR-family probes. */
public final class Klvp {
    private Klvp() {
    }

    /** PPG stream sensor id. */
    public static final int PPG_STREAM = 65538;

    /** Enable PPG via KLVP (D0 02 01). */
    public static final byte[] PPG_ENABLE = {(byte) 0xd0, 2, 1};
    /** Disable PPG via KLVP (D0 02 00). */
    public static final byte[] PPG_DISABLE = {(byte) 0xd0, 2, 0};
}

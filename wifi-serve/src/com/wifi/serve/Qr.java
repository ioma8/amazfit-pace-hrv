package com.wifi.serve;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/**
 * QR encoding via vendored zxing core. encodeMatrix is pure zxing (host-testable);
 * toBitmap renders the matrix onto an Android Bitmap with a quiet zone.
 */
public final class Qr {
    private Qr() {
    }

    public static BitMatrix encodeMatrix(String content, int modules) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.MARGIN, 0);
        return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, modules, modules, hints);
    }

    public static Bitmap toBitmap(BitMatrix m, int quiet) {
        int size = m.getWidth() + 2 * quiet;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                bmp.setPixel(x + quiet, y + quiet, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return bmp;
    }
}

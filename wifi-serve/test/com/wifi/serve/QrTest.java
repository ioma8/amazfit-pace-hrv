package com.wifi.serve;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** Host test: encode the two payloads with zxing, then decode them back. */
public class QrTest {
    public static void main(String[] args) throws Exception {
        roundtrip("WIFI:T:WPA;S:PaceSync;P:pace-sync;;");
        roundtrip("http://192.168.43.1:8080");
        System.out.println("QrTest checks passed");
    }

    static void roundtrip(String content) throws Exception {
        BitMatrix m = Qr.encodeMatrix(content, 168);
        int[] pixels = new int[m.getWidth() * m.getHeight()];
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                pixels[y * m.getWidth() + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        LuminanceSource src = new RGBLuminanceSource(m.getWidth(), m.getHeight(), pixels);
        Result r = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)));
        if (!content.equals(r.getText())) {
            System.out.println("FAIL: decoded '" + r.getText() + "' expected '" + content + "'");
            System.exit(1);
        }
    }
}

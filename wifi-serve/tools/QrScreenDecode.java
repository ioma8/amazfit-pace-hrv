import java.io.File;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** Decodes a QR code from a rendered screenshot — proves the on-screen QR is scannable. */
public class QrScreenDecode {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        Result r = new QRCodeReader().decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, px))));
        System.out.println("DECODED: " + r.getText());
    }
}

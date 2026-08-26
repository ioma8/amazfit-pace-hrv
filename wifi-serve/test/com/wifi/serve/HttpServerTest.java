package com.wifi.serve;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Host smoke test for HttpServer: list, single file, zip, clear, catch-all redirect. */
public class HttpServerTest {
    static int failures;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "wifi-serve-test-" + System.nanoTime());
        dir.mkdirs();
        writeFile(new File(dir, "mic_16000_20260826_120000.wav"), "WAVDATA-ONE".getBytes("UTF-8"));
        writeFile(new File(dir, "mic_16000_20260826_120005_raw.wav"), "WAVDATA-TWO".getBytes("UTF-8"));
        writeFile(new File(dir, "notes.txt"), "ignored".getBytes("UTF-8"));

        HttpServer server = new HttpServer(dir, 18080);
        server.start();
        try {
            check(page().contains("mic_16000_20260826_120000.wav"), "index lists first recording");
            check(page().contains("mic_16000_20260826_120005_raw.wav"), "index lists raw recording");
            check(!page().contains("notes.txt"), "index ignores non-recordings");

            byte[] one = get("/file?n=mic_16000_20260826_120000.wav");
            check(new String(one, "UTF-8").equals("WAVDATA-ONE"), "single file bytes");

            byte[] zip = get("/all.zip");
            check(zipStartsWith(zip, "PK\u0003\u0004"), "zip magic");
            check(zipEntries(zip) == 2, "zip has 2 entries, got " + zipEntries(zip));

            HttpURLConnection c = open("/generate_204");
            check(c.getResponseCode() == 302, "catch-all redirects, got " + c.getResponseCode());
            check("/".equals(c.getHeaderField("Location")), "redirect target /");
            c.disconnect();

            HttpURLConnection cl = open("/clear");
            cl.setRequestMethod("POST");
            check(cl.getResponseCode() == 302, "clear redirects, got " + cl.getResponseCode());
            cl.disconnect();

            check(!new File(dir, "mic_16000_20260826_120000.wav").exists(), "recording deleted");
            check(new File(dir, "notes.txt").exists(), "non-recording kept");

            HttpURLConnection cl2 = open("/clear");
            check(cl2.getResponseCode() == 405, "GET /clear rejected, got " + cl2.getResponseCode());
            cl2.disconnect();
        } finally {
            server.stop();
        }
        if (failures > 0) {
            System.out.println("HttpServerTest FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("HttpServerTest checks passed");
    }

    static String page() throws Exception {
        HttpURLConnection c = open("/");
        check(c.getResponseCode() == 200, "index 200, got " + c.getResponseCode());
        String body = new String(readAll(c.getInputStream()), "UTF-8");
        c.disconnect();
        return body;
    }

    static byte[] get(String path) throws Exception {
        HttpURLConnection c = open(path);
        check(c.getResponseCode() == 200, path + " 200, got " + c.getResponseCode());
        byte[] body = readAll(c.getInputStream());
        c.disconnect();
        return body;
    }

    static HttpURLConnection open(String path) throws Exception {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:18080" + path).openConnection();
        c.setInstanceFollowRedirects(false);
        return c;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    static boolean zipStartsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (b[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static int zipEntries(byte[] b) throws Exception {
        int n = 0;
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(b));
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            n++;
        }
        z.close();
        return n;
    }

    static void writeFile(File f, byte[] data) throws Exception {
        OutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

package com.wifi.serve;

import com.hrv.common.WavWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal HTTP server for the watch: lists mic recordings, streams single
 * files, streams everything as one zip, and clears the recordings. Any other
 * path (including captive-portal probes like /generate_204) is redirected to
 * "/" as a hedge against phones that resolve the probe to the AP.
 *
 * Pure java.* so it compiles and runs on the host for tests.
 */
public class HttpServer {
    private static final String PREFIX = WavWriter.MIC_PREFIX;
    private static final String SUFFIX = ".wav";

    private final File root;
    private final int port;
    private ServerSocket server;
    private volatile boolean running;

    public HttpServer(File root, int port) {
        this.root = root;
        this.port = port;
    }

    public synchronized void start() throws IOException {
        root.mkdirs();
        server = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "httpd");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            handle(s);
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }, "http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    // transient accept failure; keep serving
                }
            }
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(15000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        StringBuilder head = new StringBuilder();
        byte[] buf = new byte[1024];
        int total = 0;
        while (total < 16384) {
            int n = in.read(buf, 0, buf.length);
            if (n < 0) {
                return;
            }
            head.append(new String(buf, 0, n, "ISO-8859-1"));
            total += n;
            if (head.indexOf("\r\n\r\n") >= 0) {
                break;
            }
        }
        int idx = head.indexOf("\r\n");
        if (idx < 0) {
            return;
        }
        String[] parts = head.substring(0, idx).split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0];
        String path = parts[1];

        String pathOnly;
        String query = null;
        int q = path.indexOf('?');
        if (q >= 0) {
            pathOnly = path.substring(0, q);
            query = path.substring(q + 1);
        } else {
            pathOnly = path;
        }

        if ("/".equals(pathOnly) || "/index.html".equals(pathOnly)) {
            servePage(out);
        } else if ("/all.zip".equals(pathOnly)) {
            serveZip(out);
        } else if ("/clear".equals(pathOnly)) {
            if ("POST".equals(method)) {
                clearRecordings();
                redirect(out, "/");
            } else {
                plain(out, 405, "Method Not Allowed", "text/plain");
            }
        } else if ("/file".equals(pathOnly) && query != null && query.startsWith("n=")) {
            String name = decode(query.substring(2));
            serveFile(out, name);
        } else {
            redirect(out, "/");
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private File[] recordings() {
        File[] all = root.listFiles();
        if (all == null) {
            return new File[0];
        }
        int n = 0;
        for (File f : all) {
            if (isRecording(f)) {
                n++;
            }
        }
        File[] out = new File[n];
        int i = 0;
        for (File f : all) {
            if (isRecording(f)) {
                out[i++] = f;
            }
        }
        return out;
    }

    private static boolean isRecording(File f) {
        String name = f.getName();
        return f.isFile() && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    private static void writeHead(OutputStream out, int code, String reason, String type, long length)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    private static void redirect(OutputStream out, String location) throws IOException {
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
    }

    private static void plain(OutputStream out, int code, String reason, String body) throws IOException {
        byte[] b = body.getBytes("ISO-8859-1");
        writeHead(out, code, reason, "text/plain", b.length);
        out.write(b);
    }

    private void servePage(OutputStream out) throws IOException {
        File[] files = recordings();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Pace Sync</title><style>")
                .append("body{background:#101418;color:#e8e8e8;font-family:sans-serif;margin:0;padding:16px;max-width:640px}")
                .append("h1{font-size:22px;margin:0 0 4px}")
                .append("p{margin:4px 0;color:#9aa5ad;font-size:14px}")
                .append("a{color:#80cbc4}")
                .append("ul{list-style:none;padding:0}")
                .append("li{background:#1a2026;border-radius:8px;margin:8px 0;padding:10px 12px;display:flex;justify-content:space-between;align-items:center}")
                .append(".name{font-size:16px;word-break:break-all}")
                .append(".size{color:#9aa5ad;font-size:13px;white-space:nowrap;margin-left:10px}")
                .append(".btn{display:inline-block;background:#00695c;color:#fff;border:none;border-radius:8px;padding:12px 16px;font-size:16px;margin:8px 8px 0 0;text-decoration:none}")
                .append(".btn.danger{background:#8d1f1f}")
                .append(".empty{color:#9aa5ad;font-style:italic}")
                .append("</style></head><body>")
                .append("<h1>Pace Sync</h1>")
                .append("<p>").append(files.length).append(" recording").append(files.length == 1 ? "" : "s")
                .append(" in /sdcard/mic</p>")
                .append("<a class=\"btn\" href=\"/all.zip\">Download all (.zip)</a>")
                .append("<form method=\"post\" action=\"/clear\" style=\"display:inline\" onsubmit=\"return confirm('Delete all ")
                .append(files.length).append(" recordings from the watch?')\">")
                .append("<button class=\"btn danger\" type=\"submit\">Clear recordings</button></form>");
        if (files.length == 0) {
            sb.append("<p class=\"empty\">No recordings found. Record with Mic first.</p>");
        } else {
            sb.append("<ul>");
            for (File f : files) {
                String name = f.getName();
                sb.append("<li><span class=\"name\"><a href=\"/file?n=")
                        .append(name)
                        .append("\">").append(escape(name)).append("</a></span>")
                        .append("<span class=\"size\">").append(human(f.length())).append("</span></li>");
            }
            sb.append("</ul>");
        }
        sb.append("</body></html>");
        byte[] body = sb.toString().getBytes("UTF-8");
        writeHead(out, 200, "OK", "text/html; charset=utf-8", body.length);
        out.write(body);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void serveFile(OutputStream out, String name) throws IOException {
        if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || "..".equals(name)
                || name.indexOf('\0') >= 0) {
            plain(out, 400, "Bad Request", "bad name");
            return;
        }
        File f = new File(root, name);
        if (!f.isFile()) {
            plain(out, 404, "Not Found", "no such file");
            return;
        }
        writeHead(out, 200, "OK", "audio/wav", f.length());
        FileInputStream fin = new FileInputStream(f);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = fin.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            fin.close();
        }
    }

    private void serveZip(OutputStream out) throws IOException {
        File[] files = recordings();
        writeHead(out, 200, "OK", "application/zip", -1);
        ZipOutputStream zip = new ZipOutputStream(out);
        byte[] buf = new byte[32768];
        for (File f : files) {
            zip.putNextEntry(new ZipEntry(f.getName()));
            FileInputStream fin = new FileInputStream(f);
            try {
                int n;
                while ((n = fin.read(buf)) > 0) {
                    zip.write(buf, 0, n);
                }
            } finally {
                fin.close();
            }
            zip.closeEntry();
        }
        zip.finish();
        out.flush();
    }

    private void clearRecordings() {
        for (File f : recordings()) {
            f.delete();
        }
    }
}

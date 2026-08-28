package com.tuner;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal RIFF/WAVE PCM16 loader (mono, any sample rate) for host tests. */
final class Wav {
    private Wav() {
    }

    static short[] read(String path) throws IOException {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)));
        byte[] head = new byte[12];
        in.readFully(head);
        if (!new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
            throw new IOException("not a RIFF/WAVE file: " + path);
        }
        byte[] hdr = new byte[8];
        while (true) {
            int got = in.read(hdr, 0, 8);
            if (got < 8) {
                break;
            }
            String id = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
            int size = (hdr[4] & 0xff) | ((hdr[5] & 0xff) << 8)
                    | ((hdr[6] & 0xff) << 16) | ((hdr[7] & 0xff) << 24);
            if (id.equals("data")) {
                short[] out = new short[size / 2];
                for (int i = 0; i < out.length; i++) {
                    int lo = in.read();
                    int hi = in.read();
                    if (lo < 0 || hi < 0) {
                        break;
                    }
                    out[i] = (short) (lo | (hi << 8));
                }
                in.close();
                return out;
            }
            in.skipBytes(size + (size & 1)); // chunks are word-aligned
        }
        throw new IOException("no data chunk in " + path);
    }
}

package com.doom;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * IWAD sniffing for the /sdcard override.
 *
 * Doom decides which game it is running from the IWAD *file name* whenever that
 * name is one it knows ({@code doom.wad}/{@code doom1.wad}/{@code freedoom1.wad}
 * &rarr; Doom 1, {@code doom2.wad}/{@code freedoom2.wad} &rarr; Doom 2) and only
 * falls back to looking at the lumps otherwise; the episode range always comes
 * from the lumps. A name that disagrees with the contents therefore makes the
 * engine abort — and its I_Error path calls exit(), so the app process dies with
 * nothing on screen. That is why the candidate is checked here, in the app,
 * before it is handed to the engine.
 *
 * Pure Java on purpose: no Android imports, so it runs in the host tests.
 */
public final class WadFile {

    private WadFile() {
    }

    /** An IWAD name the engine recognises, paired with the map it will boot. */
    private static final class Known {
        final String name;
        final String mapLump;

        Known(String name, String mapLump) {
            this.name = name;
            this.mapLump = mapLump;
        }
    }

    /** Search order: the WAD we ship first, then the usual retail names. */
    private static final Known[] KNOWN = {
            new Known("freedoom1.wad", "E1M1"),
            new Known("doom.wad", "E1M1"),
            new Known("doom1.wad", "E1M1"),
            new Known("freedoom2.wad", "MAP01"),
            new Known("doom2.wad", "MAP01"),
    };

    /** A lump directory beyond this is nonsense; keeps a corrupt header bounded. */
    private static final int MAX_DIR_BYTES = 4 << 20;

    /**
     * The first IWAD directly inside {@code dir} that the engine can actually
     * boot, or null. Matching is case-insensitive because the engine compares
     * names with strcasecmp and removable storage is case-insensitive too.
     */
    public static File findReadableIwad(File dir) {
        String[] present = dir.list();
        if (present == null) {
            return null;
        }
        for (Known known : KNOWN) {
            for (String entry : present) {
                if (!entry.equalsIgnoreCase(known.name)) {
                    continue;
                }
                File candidate = new File(dir, entry);
                if (isBootable(candidate, known.mapLump)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** True when {@code f} is an IWAD whose lump directory holds {@code lump}. */
    public static boolean isBootable(File f, String lump) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            if (raf.length() < 12) {
                return false;
            }
            byte[] head = new byte[12];
            raf.readFully(head);
            if (head[0] != 'I' || head[1] != 'W' || head[2] != 'A' || head[3] != 'D') {
                return false;
            }
            int lumps = le32(head, 4);
            int dirOffset = le32(head, 8);
            long dirBytes = (long) lumps * 16;
            if (lumps <= 0 || dirOffset < 12 || dirBytes > MAX_DIR_BYTES
                    || dirOffset + dirBytes > raf.length()) {
                return false;
            }
            byte[] dir = new byte[(int) dirBytes];
            raf.seek(dirOffset);
            raf.readFully(dir);
            byte[] want = lump.getBytes("US-ASCII");
            for (int i = 0; i < lumps; i++) {
                if (nameAt(dir, i * 16 + 8, want)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            close(raf);
        }
    }

    /** Little-endian 32-bit read at {@code at}. */
    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8)
                | ((b[at + 2] & 0xff) << 16) | ((b[at + 3] & 0xff) << 24);
    }

    /** Lump names are 8 bytes, NUL padded, case-insensitive. */
    private static boolean nameAt(byte[] dir, int at, byte[] want) {
        for (int i = 0; i < 8; i++) {
            int have = dir[at + i] & 0xff;
            int expected = i < want.length ? want[i] : 0;
            if (have == 0 || expected == 0) {
                return have == expected; // both ended in the same place
            }
            if (Character.toLowerCase(have) != Character.toLowerCase(expected)) {
                return false;
            }
        }
        return true;
    }

    private static void close(RandomAccessFile raf) {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}

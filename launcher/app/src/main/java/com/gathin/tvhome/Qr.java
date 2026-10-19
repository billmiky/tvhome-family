package com.gathin.tvhome;

/**
 * A small QR code encoder for the phone page's link: byte mode, error correction level M, versions 1 to 5
 * (up to 84 bytes, plenty for http://ip:port/?k=code). Plain Java, after the structure of Project Nayuki's
 * reference encoder, and checked against segno module for module in QrTest.
 */
final class Qr {

    static final int MAX_VERSION = 5;
    // Per version (index 1..5), level M: total codewords, error correction codewords per block, blocks.
    private static final int[] TOTAL = {0, 26, 44, 70, 100, 134};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2};
    private static final int[] ALIGN = {0, 0, 18, 22, 26, 30};   // second alignment centre; the first is 6

    private final int size;
    private final boolean[][] modules;     // [y][x], true = dark
    private final boolean[][] function;

    private Qr(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** The modules of the code for {@code data}, best mask, or null when it needs more than version 5. */
    static boolean[][] encode(byte[] data) {
        return encode(data, -1);
    }

    /** As {@link #encode(byte[])} with a fixed mask 0..7, or -1 to pick the one with the lowest penalty. */
    static boolean[][] encode(byte[] data, int mask) {
        int version = 1;
        while (version <= MAX_VERSION && data.length > dataCodewords(version) - 2) version++;
        if (version > MAX_VERSION) return null;
        int capacityBits = dataCodewords(version) * 8;
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);              // byte mode
        bb.append(data.length, 8);         // character count, 8 bits up to version 9
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) codewords[i >>> 3] |= bb.bit(i) << (7 - (i & 7));

        Qr q = new Qr(version);
        q.drawFunctionPatterns(version);
        q.drawCodewords(q.addEcAndInterleave(version, codewords));
        if (mask < 0) {
            int best = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                q.applyMask(m);
                q.drawFormatBits(m);
                int p = q.penalty();
                if (p < best) {
                    best = p;
                    mask = m;
                }
                q.applyMask(m);   // XOR again: undone
            }
        }
        q.applyMask(mask);
        q.drawFormatBits(mask);
        return q.modules;
    }

    private static int dataCodewords(int version) {
        return TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version];
    }

    // ---- function patterns -----------------------------------------------------------------------

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version > 1) alignment(ALIGN[version], ALIGN[version]);   // the others overlap the finders
        drawFormatBits(0);   // reserves the area; drawn again with the real mask
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private void drawFormatBits(int mask) {
        int data = mask;                   // level M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);            // the dark module
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // ---- data -------------------------------------------------------------------------------------

    private byte[] addEcAndInterleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ec = EC_PER_BLOCK[version];
        int perBlock = data.length / blocks;   // equal block sizes for versions 1..5 at level M
        byte[] divisor = rsDivisor(ec);
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] ecBlocks = new byte[blocks][];
        for (int b = 0; b < blocks; b++) {
            dataBlocks[b] = java.util.Arrays.copyOfRange(data, b * perBlock, (b + 1) * perBlock);
            ecBlocks[b] = rsRemainder(dataBlocks[b], divisor);
        }
        byte[] out = new byte[TOTAL[version]];
        int k = 0;
        for (int i = 0; i < perBlock; i++) for (int b = 0; b < blocks; b++) out[k++] = dataBlocks[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[k++] = ecBlocks[b][i];
        return out;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert && !function[y][x];
            }
        }
    }

    /** The standard penalty: runs, 2x2 blocks, finder-like patterns, dark/light balance. */
    private int penalty() {
        int result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean colour = false;
                for (int b = 0; b < size; b++) {
                    boolean m = pass == 0 ? modules[a][b] : modules[b][a];
                    if (b > 0 && m == colour) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        colour = m;
                        run = 1;
                    }
                }
                for (int b = -4; b < size; b++) {
                    if (finderLike(pass, a, b, false) || finderLike(pass, a, b, true)) result += 40;
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10;
    }

    private static final boolean[] FINDER = {true, false, true, true, true, false, true, false, false, false, false};

    /** 1011101 followed by four light modules (or, mirrored, preceded by them) from {@code start} on. */
    private boolean finderLike(int pass, int a, int start, boolean mirrored) {
        for (int i = 0; i < FINDER.length; i++) {
            boolean want = FINDER[mirrored ? FINDER.length - 1 - i : i];
            int pos = start + i;
            boolean m = pos >= 0 && pos < size && (pass == 0 ? modules[a][pos] : modules[pos][a]);
            if (m != want) return false;
        }
        return true;
    }

    // ---- Reed-Solomon over GF(256), polynomial 0x11D ----------------------------------------------

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits.set(length++, ((value >>> i) & 1) != 0);
        }

        int bit(int i) {
            return bits.get(i) ? 1 : 0;
        }
    }
}

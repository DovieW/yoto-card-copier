package dev.dovie.yotocopy;

import java.util.Arrays;

/** Pure memory policy. No writes outside the capability container and 48 user bytes. */
public final class TagMemory {
    public static final byte[] CC = {(byte) 0xE1, 0x10, 0x06, 0};
    public static void requireSupported(byte[] v) {
        if (v.length != 8 || v[0] != 0 || v[1] != 4 || v[2] != 3
                || (v[3] != 1 && v[3] != 2) || v[4] != 1 || v[5] != 0
                || v[6] != 0x0B || v[7] != 3)
            throw new IllegalArgumentException("Use a 48-byte MIFARE Ultralight EV1 card.");
    }
    public static byte[] message(byte[] memory) {
        int p = 0;
        while (p < memory.length) {
            int type = memory[p++] & 255;
            if (type == 0) continue;
            if (type == 0xFE) return null;
            if (p >= memory.length) throw new IllegalArgumentException("Incomplete NFC record.");
            int len = memory[p++] & 255;
            if (len == 255) {
                if (p + 2 > memory.length) throw new IllegalArgumentException("Incomplete NFC length.");
                len = ((memory[p++] & 255) << 8) | (memory[p++] & 255);
            }
            if (p + len > memory.length) throw new IllegalArgumentException("NFC record exceeds card memory.");
            if (type == 3) return len == 0 ? null : Arrays.copyOfRange(memory, p, p + len);
            p += len;
        }
        return null;
    }
    public static boolean blank(byte[] data) {
        boolean zero = true, ff = true;
        for (byte b : data) { zero &= b == 0; ff &= b == (byte) 255; }
        if (zero || ff) return true;
        // Standard empty NDEF tag, with only zero padding after the terminator.
        int p = 0;
        while (p < data.length && data[p] == 0) p++;
        if (p + 1 >= data.length || data[p++] != 3 || data[p++] != 0) return false;
        if (p < data.length && data[p] == (byte) 0xFE) p++;
        while (p < data.length) if (data[p++] != 0) return false;
        return true;
    }
    public static byte[] pack(byte[] ndef) {
        if (ndef.length == 0 || ndef.length > 46)
            throw new IllegalArgumentException("This playlist link does not fit a 48-byte card.");
        byte[] result = new byte[48];
        result[0] = 3; result[1] = (byte) ndef.length;
        System.arraycopy(ndef, 0, result, 2, ndef.length);
        if (ndef.length + 2 < result.length) result[ndef.length + 2] = (byte) 0xFE;
        return result;
    }
    public static void requireWritable(byte[] header) {
        if (header.length != 16 || header[10] != 0 || header[11] != 0)
            throw new IllegalArgumentException("This card has locked pages. Use another blank.");
        byte[] cc = Arrays.copyOfRange(header, 12, 16);
        if (!Arrays.equals(cc, CC) && !Arrays.equals(cc, new byte[4]))
            throw new IllegalArgumentException("Unrecognized card format. This card was left unchanged.");
    }
}

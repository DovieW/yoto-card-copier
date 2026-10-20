package dev.dovie.yotocopy;

import java.util.Arrays;

public final class TagMemoryTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    private static void rejects(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        byte[] version = {0, 4, 3, 1, 1, 0, 0x0B, 3};
        TagMemory.requireSupported(version);
        byte[] larger = version.clone(); larger[6] = 0x0E;
        rejects(() -> TagMemory.requireSupported(larger));
        rejects(() -> TagMemory.requireSupported(new byte[8]));
        byte[] variant = version.clone(); variant[3] = 2; TagMemory.requireSupported(variant);
        byte[] msg = new byte[45];
        for (int i = 0; i < msg.length; i++) msg[i] = (byte) (i + 1);
        byte[] packed = TagMemory.pack(msg);
        check(packed.length == 48 && packed[0] == 3 && packed[1] == 45 && packed[47] == (byte) 0xFE);
        check(Arrays.equals(TagMemory.message(packed), msg));
        check(Arrays.equals(TagMemory.message(TagMemory.pack(new byte[46])), new byte[46]));
        rejects(() -> TagMemory.pack(new byte[47]));
        rejects(() -> TagMemory.pack(new byte[0]));
        check(TagMemory.blank(new byte[48]));
        byte[] ff = new byte[48]; Arrays.fill(ff, (byte) 0xFF); check(TagMemory.blank(ff));
        check(TagMemory.blank(new byte[]{3, 0, (byte) 0xFE, 0}));
        check(!TagMemory.blank(packed));
        check(!TagMemory.blank(new byte[]{3, 0, (byte) 0xFE, 1}));
        // Unknown TLVs must not be silently classified as blank.
        check(!TagMemory.blank(new byte[]{1, 2, 0, 0, 3, 0, (byte) 0xFE}));
        rejects(() -> TagMemory.message(new byte[]{3, 45, 1}));
        rejects(() -> TagMemory.message(new byte[]{3, (byte) 255, 1}));
        rejects(() -> TagMemory.message(new byte[]{3}));
        check(TagMemory.message(new byte[]{3, 0, (byte) 0xFE}) == null);
        byte[] header = new byte[16]; TagMemory.requireWritable(header);
        System.arraycopy(TagMemory.CC, 0, header, 12, 4); TagMemory.requireWritable(header);
        header[10] = 1; rejects(() -> TagMemory.requireWritable(header));
        header[10] = 0; header[15] = 15; rejects(() -> TagMemory.requireWritable(header));
        rejects(() -> TagMemory.requireWritable(new byte[12]));
        System.out.println(checks + " memory-policy checks passed");
    }
}

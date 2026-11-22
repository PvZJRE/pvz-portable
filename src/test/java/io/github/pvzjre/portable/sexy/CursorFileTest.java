package io.github.pvzjre.portable.sexy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the player-supplied global cursor file parser ({@link CursorFile}). */
public class CursorFileTest {

    /** ICONDIRENTRY: width, height, colors, reserved, planes (=hotX), bitcount (=hotY), size, offset. */
    private static byte[] entry(int width, int height, int hotX, int hotY, int size, int offset) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) width).put((byte) height).put((byte) 0).put((byte) 0)
                .putShort((short) hotX).putShort((short) hotY)
                .putInt(size).putInt(offset)
                .array();
    }

    private static byte[] bitmapHeader(int width, int height, int bitCount) {
        return ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(40).putInt(width).putInt(height * 2).putShort((short) 1)
                .putShort((short) bitCount).putInt(0).putInt(width * height * bitCount / 8)
                .putInt(0).putInt(0).putInt(0).putInt(0)
                .array();
    }

    private static byte[] iconDir(int count) {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0).putShort((short) 2)
                .putShort((short) count).array();
    }

    /** A 32bpp cursor: every pixel's red byte carries its column, alpha 255; hotspot (2, 3). */
    private static byte[] cursor32bpp() {
        int width = 8;
        int height = 8;
        int andRow = 4; // ((8 + 31) / 32) * 4
        int imageSize = 40 + width * height * 4 + andRow * height;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(iconDir(1));
        out.writeBytes(entry(width, height, 2, 3, imageSize, 22));
        out.writeBytes(bitmapHeader(width, height, 32));
        // bottom-up BGRA rows; the alpha channel carries a column pattern, row 0 is fully transparent
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                out.write(x * 8);           // blue
                out.write(0x40);            // green
                out.write(0x80 | x);        // red carries the column
                out.write(y == 0 ? 0 : 255); // alpha: the first row is transparent
            }
        }
        for (int y = 0; y < height; y++) {
            out.writeBytes(new byte[andRow]); // the AND mask stays clear: the alpha rules
        }
        return out.toByteArray();
    }

    /** A 4bpp palette cursor (32x32, the shape of the user's file): transparent AND-masked
     * checkerboard, opaque palette colors; hotspot (1, 1). */
    private static byte[] cursor4bpp() {
        int width = 32;
        int height = 32;
        int xorRow = 16; // ((32 * 4 + 31) / 32) * 4
        int andRow = 4;
        int imageSize = 40 + 16 * 4 + xorRow * height + andRow * height;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(iconDir(1));
        out.writeBytes(entry(32, 32, 1, 1, imageSize, 22));
        out.writeBytes(bitmapHeader(width, height, 4));
        out.writeBytes(new byte[] { 0, 0, (byte) 255, 0 });       // palette[0] = red
        out.writeBytes(new byte[] { 0, (byte) 255, 0, 0 });       // palette[1] = green
        for (int i = 2; i < 16; i++) {
            out.writeBytes(new byte[] { (byte) i, (byte) i, (byte) i, 0 });
        }
        for (int y = height - 1; y >= 0; y--) {
            // bottom-up rows of indices: (x + y) % 2 alternates palette 0/1
            for (int x = 0; x < width; x += 2) {
                int a = (x + y) % 2;
                int b = (x + 1 + y) % 2;
                out.write((a << 4) | b);
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            // an AND mask that clears (makes transparent) the even-x pixels of the even rows
            for (int x = 0; x < width; x += 8) {
                int mask = (y % 2 == 0) ? 0xAA : 0x00;
                out.write(mask);
            }
        }
        return out.toByteArray();
    }

    @Test
    void parsesHotspotAndPixelsFromA32bppCursor(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cursor.cur");
        Files.write(file, cursor32bpp());

        CursorFile.Loaded loaded = CursorFile.load(file);
        assertEquals(2, loaded.hotX());
        assertEquals(3, loaded.hotY());
        assertEquals(8, loaded.image().mWidth);
        assertEquals(8, loaded.image().mHeight);

        // the pixel at (2, 3): BGRA written above, bottom-up row 8 - 1 - 3
        int pixel = loaded.image().mBits[3 * 8 + 2];
        assertEquals(0xFF824010, pixel); // alpha 255, red 0x80|2, green 0x40, blue 2 * 8
        // the bottom-most logical row (y = 0) carries alpha zero: transparent
        assertEquals(0, loaded.image().mBits[0] >>> 24);
    }

    @Test
    void parsesPaletteAndAndMaskFromA4bppCursor(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cursor.cur");
        Files.write(file, cursor4bpp());

        CursorFile.Loaded loaded = CursorFile.load(file);
        assertEquals(1, loaded.hotX());
        assertEquals(1, loaded.hotY());
        assertEquals(32, loaded.image().mWidth);
        assertEquals(32, loaded.image().mHeight);

        // row 4 (an even row): even-x pixels are AND-masked transparent, odd-x opaque palette green
        assertEquals(0, loaded.image().mBits[4 * 32] >>> 24); // AND-masked: fully transparent
        assertEquals(0xFF00FF00, loaded.image().mBits[4 * 32 + 1]);
    }

    @Test
    void loadsFromAStream(@TempDir Path dir) throws IOException {
        CursorFile.Loaded loaded = CursorFile.load(new java.io.ByteArrayInputStream(cursor32bpp()));
        assertEquals(2, loaded.hotX());
        assertEquals(8, loaded.image().mWidth);
    }

    @Test
    void rejectsNonCursorFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("icon.ico");
        byte[] icon = iconDir(1).clone();
        icon[3] = 1; // type 1 = icon, not cursor
        Files.write(file, icon);

        IOException thrown = assertThrows(IOException.class, () -> CursorFile.load(file));
        assertTrue(thrown.getMessage().contains("not a cursor"));
    }

    @Test
    void picksTheLargestEntry(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(iconDir(2));
        out.writeBytes(entry(8, 8, 0, 0, 40 + 8 * 8 * 4 + 4 * 8, 38));
        out.writeBytes(entry(32, 32, 5, 6, 40 + 32 * 32 * 4 + 4 * 32, 38 + 40 + 8 * 8 * 4 + 4 * 8));
        out.writeBytes(bitmapHeader(8, 8, 32));
        out.writeBytes(new byte[8 * 8 * 4]);
        out.writeBytes(new byte[4 * 8]);
        out.writeBytes(bitmapHeader(32, 32, 32));
        out.writeBytes(new byte[32 * 32 * 4]);
        out.writeBytes(new byte[4 * 32]);
        Path file = dir.resolve("cursor.cur");
        Files.write(file, out.toByteArray());

        CursorFile.Loaded loaded = CursorFile.load(file);
        assertEquals(32, loaded.image().mWidth);
        assertEquals(5, loaded.hotX());
    }

    @Test
    void writesAndReadsBackAFileOnDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cursor.cur");
        Files.write(file, cursor32bpp());
        // the file survives a real disk round trip with a trailing byte (some writers pad)
        Files.write(file, append(Files.readAllBytes(file), new byte[] {0, 0, 0}));

        CursorFile.Loaded loaded = CursorFile.load(file);
        assertEquals(2, loaded.hotX());
    }

    private static byte[] append(byte[] data, byte[] extra) {
        byte[] combined = new byte[data.length + extra.length];
        System.arraycopy(data, 0, combined, 0, data.length);
        System.arraycopy(extra, 0, combined, data.length, extra.length);
        return combined;
    }
}

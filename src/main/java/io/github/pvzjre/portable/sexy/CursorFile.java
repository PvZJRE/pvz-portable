// PORT ADDITION: a Windows .cur cursor file parser. The player drops a `cursor.cur` into the game
// directory and the game adopts it as its global cursor (see SexyAppBase.applyGlobalCursorFile) -
// upstream has no such mechanism.
package io.github.pvzjre.portable.sexy;

import io.github.pvzjre.portable.sexy.graphics.MemoryImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Parses a Windows {@code .cur} cursor file into a straight-ARGB {@link MemoryImage} plus the
 * hotspot, ready for {@code PlatformBackend.setCustomCursor}.
 *
 * <p>File shape: an {@code ICONDIR} ({@code type} 2 marks a cursor) of {@code ICONDIRENTRY}ies -
 * for cursor entries the {@code planes} and {@code bitcount} fields carry the hotspot X and Y -
 * followed by the image data, which is either a bitmap ({@code BITMAPINFOHEADER} + the optional
 * palette + the bottom-up XOR pixels + the 1-bit AND mask, whose set bits mark transparent
 * pixels) or a PNG (Vista and later, decoded through ImageIO). The largest entry wins when the
 * file carries several sizes.
 */
public final class CursorFile {

    /** The parsed cursor: the image and the hotspot the file declares for it. */
    public record Loaded(MemoryImage image, int hotX, int hotY) {
    }

    private CursorFile() {
    }

    public static Loaded load(Path file) throws IOException {
        return load(Files.newInputStream(file), file.toString());
    }

    /** The stream variant for cursors that ride inside a jar (the classpath resource). */
    public static Loaded load(java.io.InputStream stream) throws IOException {
        return load(stream, "/pvzp/cursor.cur");
    }

    public static Loaded load(java.io.InputStream stream, String label) throws IOException {
        byte[] data = stream.readAllBytes();
        if (data.length < 6) {
            throw new IOException("truncated cursor file: " + label);
        }

        int type = u16(data, 2);
        if (type != 2) {
            throw new IOException("not a cursor file (type " + type + "): " + label);
        }

        int count = u16(data, 4);
        int bestEntry = -1;
        int bestArea = -1;
        int bestHotX = 0;
        int bestHotY = 0;
        int bestOffset = 0;
        int bestSize = 0;
        for (int i = 0; i < count; i++) {
            int entry = 6 + 16 * i;
            if (entry + 16 > data.length) {
                break;
            }
            int width = (data[entry] & 0xFF) == 0 ? 256 : (data[entry] & 0xFF);
            int height = (data[entry + 1] & 0xFF) == 0 ? 256 : (data[entry + 1] & 0xFF);
            int area = width * height;
            if (area > bestArea) {
                bestArea = area;
                bestEntry = i;
                bestHotX = u16(data, entry + 4);
                bestHotY = u16(data, entry + 6);
                bestSize = (int) u32(data, entry + 8);
                bestOffset = (int) u32(data, entry + 12);
            }
        }
        if (bestEntry < 0 || bestOffset + bestSize > data.length) {
            throw new IOException("no usable cursor image in: " + label);
        }

        return decode(data, bestOffset, bestSize, bestHotX, bestHotY, label);
    }

    private static Loaded decode(byte[] data, int offset, int size, int hotX, int hotY, String label)
            throws IOException {
        // a PNG image (Vista and later) carries the full file, header signature first
        if (offset + 8 <= offset + size && data[offset] == (byte) 0x89
                && data[offset + 1] == 'P' && data[offset + 2] == 'N' && data[offset + 3] == 'G') {
            java.awt.image.BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(data, offset, size));
            if (decoded == null) {
                throw new IOException("undecodable PNG cursor image in: " + label);
            }
            return new Loaded(toMemoryImage(decoded), hotX, hotY);
        }

        if (size < 40) {
            throw new IOException("truncated cursor bitmap in: " + label);
        }

        int width = i32(data, offset + 4);
        int doubleHeight = i32(data, offset + 8);
        int height = doubleHeight / 2;
        int bitCount = u16(data, offset + 14);
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid cursor bitmap dimensions in: " + label);
        }

        int paletteSize = 0;
        int paletteEntry = 0;
        if (bitCount <= 8) {
            paletteEntry = 1 << bitCount;
            // the header may cap the palette with colors-used (a zero means the full set)
            int colorsUsed = i32(data, offset + 32);
            if (colorsUsed > 0 && colorsUsed < paletteEntry) {
                paletteEntry = colorsUsed;
            }
            paletteSize = paletteEntry * 4;
        }

        int xorRow = ((width * bitCount + 31) / 32) * 4;
        int xorOffset = offset + 40 + paletteSize;
        int andOffset = xorOffset + xorRow * height;
        int andRow = ((width + 31) / 32) * 4;
        if (andOffset + andRow * height > offset + size) {
            throw new IOException("truncated cursor bitmap data in: " + label);
        }

        int[] palette = null;
        if (paletteSize > 0) {
            palette = new int[paletteEntry];
            for (int i = 0; i < paletteEntry; i++) {
                int blue = data[xorOffset - paletteSize + i * 4] & 0xFF;
                int green = data[xorOffset - paletteSize + i * 4 + 1] & 0xFF;
                int red = data[xorOffset - paletteSize + i * 4 + 2] & 0xFF;
                palette[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }

        // 32-bit entries carry a real alpha channel; everything else is transparent where the
        // AND mask is set. Some 32-bit cursors write zero alpha and rely on the mask instead.
        MemoryImage image = new MemoryImage();
        image.create(width, height);
        int[] bits = image.getBits();
        for (int y = 0; y < height; y++) {
            // the bitmap rows are bottom-up
            int sourceRow = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int red;
                int green;
                int blue;
                int alpha;
                if (bitCount == 32) {
                    int pixel = xorOffset + sourceRow * ((width * 32 + 31) / 32 * 4) + x * 4;
                    blue = data[pixel] & 0xFF;
                    green = data[pixel + 1] & 0xFF;
                    red = data[pixel + 2] & 0xFF;
                    alpha = data[pixel + 3] & 0xFF;
                } else if (bitCount == 24) {
                    int pixel = xorOffset + sourceRow * ((width * 24 + 31) / 32 * 4) + x * 3;
                    blue = data[pixel] & 0xFF;
                    green = data[pixel + 1] & 0xFF;
                    red = data[pixel + 2] & 0xFF;
                    alpha = 255;
                } else {
                    int indexOffset = xorOffset + sourceRow * xorRow;
                    int index;
                    if (bitCount == 8) {
                        index = data[indexOffset + x] & 0xFF;
                    } else { // 4 bits per pixel, two pixels per byte
                        int pair = data[indexOffset + x / 2] & 0xFF;
                        index = (x % 2 == 0) ? (pair >> 4) : (pair & 0xF);
                    }
                    int color = palette != null && index < palette.length ? palette[index] : 0xFF000000;
                    red = (color >> 16) & 0xFF;
                    green = (color >> 8) & 0xFF;
                    blue = color & 0xFF;
                    alpha = 255;
                }

                boolean masked = andMaskSet(data, andOffset, andRow, width, sourceRow, x);
                if (masked) {
                    alpha = 0;
                }
                bits[y * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }

        if (bitCount == 32 && !hasAnyAlpha(bits, width * height)) {
            // a 32-bit cursor whose alpha channel is entirely zero paints through the AND mask
            applyAndMask(bits, width, height, data, andOffset, andRow);
        }

        image.setBits(bits, width, height, false);
        return new Loaded(image, hotX, hotY);
    }

    private static boolean andMaskSet(byte[] data, int andOffset, int andRow, int width, int row, int x) {
        int bit = andOffset + row * andRow + x / 8;
        if (bit >= data.length) {
            return false;
        }
        return (data[bit] & (0x80 >> (x % 8))) != 0;
    }

    private static boolean hasAnyAlpha(int[] bits, int count) {
        for (int i = 0; i < count; i++) {
            if ((bits[i] >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void applyAndMask(int[] bits, int width, int height, byte[] data, int andOffset, int andRow) {
        for (int y = 0; y < height; y++) {
            int sourceRow = height - 1 - y;
            for (int x = 0; x < width; x++) {
                bits[y * width + x] = andMaskSet(data, andOffset, andRow, width, sourceRow, x)
                        ? 0
                        : bits[y * width + x] | 0xFF000000;
            }
        }
    }

    private static MemoryImage toMemoryImage(java.awt.image.BufferedImage decoded) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        MemoryImage image = new MemoryImage();
        image.create(width, height);
        image.setBits(decoded.getRGB(0, 0, width, height, null, 0, width), width, height, false);
        return image;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int i32(byte[] data, int offset) {
        return u32(data, offset);
    }
}

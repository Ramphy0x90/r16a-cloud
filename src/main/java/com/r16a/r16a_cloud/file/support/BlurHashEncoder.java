package com.r16a.r16a_cloud.file.support;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Encodes a BufferedImage into a BlurHash string (https://blurha.sh).
 * The hash is a compact (~30 char) representation used as an image placeholder.
 */
class BlurHashEncoder {

    private static final String BASE83_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    /** Max dimension to scale the image to before hashing — avoids O(W*H*components) cost on large files. */
    private static final int MAX_HASH_DIMENSION = 64;

    /**
     * Encodes the image as a BlurHash with the given number of DCT components.
     * Recommended: numX=4, numY=3.
     */
    static String encode(BufferedImage original, int numX, int numY) {
        BufferedImage image = scale(original);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        float[][] linear = new float[pixels.length][3];
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            linear[i][0] = srgbToLinear((rgb >> 16) & 0xFF);
            linear[i][1] = srgbToLinear((rgb >> 8) & 0xFF);
            linear[i][2] = srgbToLinear(rgb & 0xFF);
        }

        float[][] components = new float[numX * numY][3];
        for (int cy = 0; cy < numY; cy++) {
            for (int cx = 0; cx < numX; cx++) {
                float norm = (cx == 0 && cy == 0) ? 1f : 2f;
                float r = 0, g = 0, b = 0;
                for (int y = 0; y < height; y++) {
                    double cosY = Math.cos((Math.PI * cy * y) / height);
                    for (int x = 0; x < width; x++) {
                        float basis = (float) (norm * Math.cos((Math.PI * cx * x) / width) * cosY);
                        int idx = y * width + x;
                        r += basis * linear[idx][0];
                        g += basis * linear[idx][1];
                        b += basis * linear[idx][2];
                    }
                }

                float scale = 1f / (width * height);
                int comp = cy * numX + cx;
                components[comp][0] = r * scale;
                components[comp][1] = g * scale;
                components[comp][2] = b * scale;
            }
        }

        float maxAc = 0f;
        for (int i = 1; i < components.length; i++) {
            for (float v : components[i]) maxAc = Math.max(maxAc, Math.abs(v));
        }

        StringBuilder result = new StringBuilder();
        encode83(result, (numX - 1) + (numY - 1) * 9, 1);
        int quantisedMaxAc = maxAc > 0 ? Math.max(0, Math.min(82, (int) Math.floor(maxAc * 166 - 0.5f))) : 0;
        encode83(result, quantisedMaxAc, 1);
        encode83(result, encodeDC(components[0]), 4);
        float acNorm = maxAc > 0 ? maxAc : 1f;

        for (int i = 1; i < components.length; i++) {
            encode83(result, encodeAC(components[i], acNorm), 2);
        }

        return result.toString();
    }

    private static BufferedImage scale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        if (w <= MAX_HASH_DIMENSION && h <= MAX_HASH_DIMENSION) return src;

        double ratio = (double) MAX_HASH_DIMENSION / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * ratio));
        int th = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return scaled;
    }

    private static int encodeDC(float[] c) {
        return (linearToSrgb(c[0]) << 16) | (linearToSrgb(c[1]) << 8) | linearToSrgb(c[2]);
    }

    private static int encodeAC(float[] c, float maxAc) {
        int r = Math.max(0, Math.min(18, (int) Math.floor(signPow(c[0] / maxAc, 0.5) * 9 + 9.5)));
        int g = Math.max(0, Math.min(18, (int) Math.floor(signPow(c[1] / maxAc, 0.5) * 9 + 9.5)));
        int b = Math.max(0, Math.min(18, (int) Math.floor(signPow(c[2] / maxAc, 0.5) * 9 + 9.5)));
        return r * 361 + g * 19 + b;
    }

    private static void encode83(StringBuilder sb, int value, int length) {
        for (int i = 1; i <= length; i++) {
            int digit = (value / pow83(length - i)) % 83;
            sb.append(BASE83_CHARS.charAt(digit));
        }
    }

    private static int pow83(int exp) {
        int result = 1;
        for (int i = 0; i < exp; i++) result *= 83;
        return result;
    }

    private static float srgbToLinear(int value) {
        float f = value / 255f;
        return f <= 0.04045f ? f / 12.92f : (float) Math.pow((f + 0.055f) / 1.055f, 2.4);
    }

    private static int linearToSrgb(float value) {
        float c = Math.max(0f, Math.min(1f, value));
        return c <= 0.0031308f
                ? (int) (c * 12.92f * 255 + 0.5f)
                : (int) ((1.055f * Math.pow(c, 1 / 2.4) - 0.055f) * 255 + 0.5f);
    }

    private static double signPow(double value, double exp) {
        return Math.copySign(Math.pow(Math.abs(value), exp), value);
    }
}

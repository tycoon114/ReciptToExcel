package kr.local.receipts;

import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/** Prepares a separate OCR image; the stored evidence photo is kept intact. */
@Component
public class ReceiptOcrImages {
    /** Plain downsampling preserves solid/double-width fonts that thickening can merge. */
    public BufferedImage resized(Path path, int maxEdge) throws Exception {
        if (maxEdge < 1) throw new IllegalArgumentException("Positive size required");
        var source = ImageIO.read(path.toFile());
        if (source == null) throw new IllegalArgumentException("Unreadable receipt photo");
        try {
            double scale = Math.min(1, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
            var result = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)),
                    Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            var g = result.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
            } finally { g.dispose(); }
            return result;
        } finally { source.flush(); }
    }

    public BufferedImage prepare(Path path, boolean contrast) throws Exception {
        var image = ImageIO.read(path.toFile());
        if (image == null) throw new IllegalArgumentException("Unreadable receipt photo");
        try {
            Rectangle region = paperRegion(image);
            int w = region.width, h = region.height;
            double scale = Math.min(2.0, 3600.0 / Math.max(w, h));
            var result =
                    new BufferedImage(
                            Math.max(1, (int) Math.round(w * scale)),
                            Math.max(1, (int) Math.round(h * scale)),
                            BufferedImage.TYPE_INT_RGB);
            var g = result.createGraphics();
            try {
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(
                        image,
                        0,
                        0,
                        result.getWidth(),
                        result.getHeight(),
                        region.x,
                        region.y,
                        region.x + w,
                        region.y + h,
                        null);
            } finally {
                g.dispose();
            }
            if (contrast) enhance(result);
            return result;
        } finally {
            image.flush();
        }
    }

    static Rectangle paperRegion(BufferedImage source) {
        double scale = Math.min(1, 700.0 / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) (source.getWidth() * scale)),
                h = Math.max(1, (int) (source.getHeight() * scale));
        var small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = small.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        boolean[] bright = new boolean[w * h], mask = new boolean[w * h], seen = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = small.getRGB(x, y), r = p >> 16 & 255, b = p & 255, green = p >> 8 & 255;
                int min = Math.min(r, Math.min(green, b)), max = Math.max(r, Math.max(green, b));
                bright[y * w + x] = min > 145 && max - min < 25;
            }
        small.flush();
        // Bridge text strokes and thin rules so the sheet remains one component.
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int support = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) if (bright[(y + dy) * w + x + dx]) support++;
                if (support >= 7)
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            if (x + dx >= 0 && x + dx < w && y + dy >= 0 && y + dy < h)
                                mask[(y + dy) * w + x + dx] = true;
            }
        int[] queue = new int[w * h];
        double best = 0;
        Rectangle candidate = null;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            int head = 0, tail = 1;
            queue[0] = start;
            seen[start] = true;
            int minX = w, minY = h, maxX = 0, maxY = 0;
            while (head < tail) {
                int index = queue[head++], x = index % w, y = index / w;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x > 0) tail = visit(index - 1, mask, seen, queue, tail);
                if (x + 1 < w) tail = visit(index + 1, mask, seen, queue, tail);
                if (y > 0) tail = visit(index - w, mask, seen, queue, tail);
                if (y + 1 < h) tail = visit(index + w, mask, seen, queue, tail);
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            double box = (double) bw * bh, ratio = (double) bw / bh;
            if (box < w * h * 0.12 || ratio < 0.16 || ratio > 3 || tail / box < 0.55) continue;
            int edges =
                    (minX == 0 ? 1 : 0)
                            + (minY == 0 ? 1 : 0)
                            + (maxX == w - 1 ? 1 : 0)
                            + (maxY == h - 1 ? 1 : 0);
            double score = tail * (edges >= 3 ? 0.4 : 1);
            if (score > best) {
                best = score;
                candidate = new Rectangle(minX, minY, bw, bh);
            }
        }
        if (candidate == null) return new Rectangle(0, 0, source.getWidth(), source.getHeight());
        int pad = 8,
                x = Math.max(0, (int) (candidate.x / scale) - pad),
                y = Math.max(0, (int) (candidate.y / scale) - pad);
        int
                right =
                        Math.min(
                                source.getWidth(),
                                (int) Math.ceil((candidate.x + candidate.width) / scale) + pad),
                bottom =
                        Math.min(
                                source.getHeight(),
                                (int) Math.ceil((candidate.y + candidate.height) / scale) + pad);
        return new Rectangle(x, y, right - x, bottom - y);
    }

    private static int visit(int index, boolean[] mask, boolean[] seen, int[] queue, int tail) {
        if (mask[index] && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    static void enhance(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w), histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int value = ((p >> 16 & 255) * 299 + (p >> 8 & 255) * 587 + (p & 255) * 114) / 1000;
            pixels[i] = value;
            histogram[value]++;
        }
        int low = 0, high = 255, count = 0;
        for (int i = 0; i < 256; i++) {
            count += histogram[i];
            if (count >= pixels.length * 0.03) {
                low = i;
                break;
            }
        }
        count = 0;
        for (int i = 255; i >= 0; i--) {
            count += histogram[i];
            if (count >= pixels.length * 0.10) {
                high = i;
                break;
            }
        }
        int range = Math.max(30, high - low);
        for (int i = 0; i < pixels.length; i++) {
            int v = Math.max(0, Math.min(255, (pixels[i] - low) * 255 / range));
            pixels[i] = (v << 16) | (v << 8) | v;
        }
        image.setRGB(0, 0, w, h, pixels, 0, w);
    }

    public BufferedImage compact(Path path, boolean thicken, boolean halfTurn) throws Exception {
        var prepared = prepare(path, true);
        try {
            double scale =
                    Math.min(1, 1800.0 / Math.max(prepared.getWidth(), prepared.getHeight()));
            int w = Math.max(1, (int) Math.round(prepared.getWidth() * scale)),
                    h = Math.max(1, (int) Math.round(prepared.getHeight() * scale));
            var result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            var g = result.createGraphics();
            try {
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                if (halfTurn) {
                    g.translate(w, h);
                    g.rotate(Math.PI);
                }
                g.drawImage(prepared, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            if (thicken) {
                int[] input = result.getRGB(0, 0, w, h, null, 0, w), output = input.clone();
                for (int y = 1; y < h - 1; y++)
                    for (int x = 1; x < w - 1; x++) {
                        int v = 255;
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dx = -1; dx <= 1; dx++)
                                v = Math.min(v, input[(y + dy) * w + x + dx] & 255);
                        output[y * w + x] = v * 0x010101;
                    }
                result.setRGB(0, 0, w, h, output, 0, w);
            }
            return result;
        } finally {
            prepared.flush();
        }
    }
}

package kr.local.receipts;

import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * 증빙 파일을 수정하지 않고 OCR용 분석 사본을 만드는 영상 처리 도우미입니다.
 * 종이 영역 추정·축소·명암 보정·문자 획 연결을 별도로 적용하여 여러 인식 후보를 제공합니다.
 */
@Component
public class ReceiptOcrImages {
    /** 단순 축소로 큰 글씨를 읽습니다. 획을 굵게 만들면 붙어 버리는 숫자에는 명암·두께 보정을 쓰지 않습니다. */
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

    /** 종이 후보 영역을 잘라 최대 두 배·긴 변 3600픽셀까지 조정하고 선택적으로 명암을 보정합니다. */
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

    /** 작은 미리보기에서 밝고 무채색인 연결 영역을 찾고 크기·비율·밀도로 종이 후보를 평가합니다. */
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
        // 밝은 이웃이 충분한 위치를 확장해 글자와 얇은 표 선 때문에 종이 영역이 여러 조각으로 나뉘는 것을 줄입니다.
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
        // 너비 우선 탐색으로 연결 영역을 구합니다. 각 픽셀을 한 번만 큐에 넣어 탐색량을 제한합니다.
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
            // 작은 반사광·지나치게 가늘거나 넓은 영역·속이 빈 테두리는 영수증 후보에서 제외합니다.
            if (box < w * h * 0.12 || ratio < 0.16 || ratio > 3 || tail / box < 0.55) continue;
            int edges =
                    (minX == 0 ? 1 : 0)
                            + (minY == 0 ? 1 : 0)
                            + (maxX == w - 1 ? 1 : 0)
                            + (maxY == h - 1 ? 1 : 0);
            // 화면의 여러 가장자리에 붙은 큰 영역은 종이 대신 배경일 수 있어 점수를 낮춥니다.
            double score = tail * (edges >= 3 ? 0.4 : 1);
            if (score > best) {
                best = score;
                candidate = new Rectangle(minX, minY, bw, bh);
            }
        }
        // 잘못 잘라 증빙 내용을 잃는 것보다 전체 사진을 유지하는 편을 선택합니다.
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

    /** 인접 픽셀을 한 번만 탐색 큐에 넣는 연결 영역 탐색 도우미입니다. */
    private static int visit(int index, boolean[] mask, boolean[] seen, int[] queue, int tail) {
        if (mask[index] && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /** 밝기 분포의 양 끝 일부를 기준으로 명암을 늘려 흐린 인쇄를 강조합니다. */
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
        // 거의 균일한 배경에서 대비가 과도하게 커지지 않도록 밝기 범위의 하한을 둡니다.
        int range = Math.max(30, high - low);
        for (int i = 0; i < pixels.length; i++) {
            int v = Math.max(0, Math.min(255, (pixels[i] - low) * 255 / range));
            pixels[i] = (v << 16) | (v << 8) | v;
        }
        image.setRGB(0, 0, w, h, pixels, 0, w);
    }

    /** 명암 보정 사본을 축소하고 필요한 경우 검은 획 연결과 180도 회전을 추가합니다. */
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
            // 주변에서 가장 어두운 픽셀을 가져와 점 형태로 끊긴 인쇄 획을 잇습니다. 원본 배열과 출력 배열을 분리합니다.
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

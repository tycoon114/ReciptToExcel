package kr.local.receipts;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** 이미지의 밝기 배치와 획 차이를 비교하는 보조 지문입니다. 유사하다는 이유만으로 거래를 자동 제외하지 않습니다. */
final class ReceiptSimilarity {
    private ReceiptSimilarity() {}

    /** 원본을 바꾸지 않고 종이 영역의 0·90·180·270도 지문을 만듭니다. 백지·균일한 사진은 비교하지 않습니다. */
    static String fingerprint(Path path) throws Exception {
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) return "";
        try {
            Rectangle paper = ReceiptOcrImages.paperRegion(source);
            BufferedImage cropped = source.getSubimage(paper.x, paper.y, paper.width, paper.height);
            var values = new ArrayList<String>();
            for (int rotation = 0; rotation < 4; rotation++) {
                var small = new BufferedImage(17, 16, BufferedImage.TYPE_INT_RGB);
                var g = small.createGraphics();
                try {
                    g.setColor(Color.WHITE); g.fillRect(0, 0, 17, 16);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.translate(8.5, 8); g.rotate(rotation * Math.PI / 2);
                    int w = rotation % 2 == 0 ? 17 : 16, h = rotation % 2 == 0 ? 16 : 17;
                    g.drawImage(cropped, -w / 2, -h / 2, w, h, null);
                } finally { g.dispose(); }
                int[] gray = new int[272]; int total = 0, min = 255, max = 0;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 17; x++) {
                    int color = small.getRGB(x, y);
                    int v = ((color >> 16 & 255)*299 + (color >> 8 & 255)*587 + (color & 255)*114)/1000;
                    gray[y*17+x] = v; total += v; min = Math.min(min, v); max = Math.max(max, v);
                }
                small.flush();
                if (max - min < 25) return "";
                double mean = total / 272.0;
                byte[] brightness = new byte[32], edges = new byte[32]; int ink = 0;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    int index = y*16+x;
                    if (gray[y*17+x] < mean - 4) { brightness[index/8] |= (byte)(1 << index%8); ink++; }
                    if (gray[y*17+x] + 4 < gray[y*17+x+1]) edges[index/8] |= (byte)(1 << index%8);
                }
                if (ink < 8 || ink > 240) return "";
                int ratio = (int)Math.round(1000.0 * (rotation % 2 == 0 ? paper.width : paper.height)
                        / (rotation % 2 == 0 ? paper.height : paper.width));
                values.add(ratio + ":" + HexFormat.of().formatHex(brightness) + ":" + HexFormat.of().formatHex(edges));
            }
            return String.join("|", values);
        } finally { source.flush(); }
    }

    /** 256개 밝기 칸과 256개 획 칸이 모두 매우 비슷하고 종이 비율도 가까운 경우만 보조 근거로 씁니다. */
    static boolean similar(String first, String second) {
        if (first == null || second == null || first.isBlank() || second.isBlank()) return false;
        try {
            for (String a : first.split("\\|")) for (String b : second.split("\\|")) {
                String[] x = a.split(":"), y = b.split(":");
                double ratio = Double.parseDouble(x[0]) / Double.parseDouble(y[0]);
                if (ratio < .85 || ratio > 1.18) continue;
                if (distance(x[1], y[1]) <= 10 && distance(x[2], y[2]) <= 14) return true;
            }
        } catch (RuntimeException ignored) { /* 오래되었거나 깨진 캐시는 거래 판단에 사용하지 않습니다. */ }
        return false;
    }

    private static int distance(String a, String b) {
        byte[] x = HexFormat.of().parseHex(a), y = HexFormat.of().parseHex(b);
        if (x.length != 32 || y.length != 32) return Integer.MAX_VALUE;
        int count = 0;
        for (int i = 0; i < x.length; i++) count += Integer.bitCount((x[i] ^ y[i]) & 255);
        return count;
    }
}

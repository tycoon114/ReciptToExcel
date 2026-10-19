package kr.local.receipts;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;

import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;

import javax.imageio.ImageIO;

/** Applies camera EXIF orientation to pixels before discarding metadata or running OCR. */
@Component
public class ReceiptImages {
    public BufferedImage normalize(byte[] bytes) throws Exception {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw ReceiptService.bad("읽을 수 없는 이미지입니다. JPG/PNG 형식으로 저장해 주세요.");
            var reader = readers.next();
            try {
                if (!Set.of("jpeg", "jpg", "png")
                        .contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                    throw ReceiptService.bad("JPG 또는 PNG 사진만 지원합니다.");
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((long) width * height > 30_000_000 || width > 10000 || height > 10000)
                    throw ReceiptService.bad("사진이 너무 큽니다. 3천만 화소 이하로 줄여 주세요.");
                var raw = reader.read(0);
                try {
                    return transform(raw, orientation(bytes), 3000);
                } finally {
                    raw.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    static int orientation(byte[] bytes) {
        try {
            var directory =
                    ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
                            .getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer value =
                    directory == null
                            ? null
                            : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return value != null && value >= 1 && value <= 8 ? value : 1;
        } catch (Exception ignored) {
            // Readable images without valid EXIF still work; the UI offers manual rotation.
            return 1;
        }
    }

    public BufferedImage rotate(BufferedImage source, int quarterTurns) {
        if (quarterTurns != 1 && quarterTurns != -1) throw ReceiptService.bad("회전 방향이 올바르지 않습니다.");
        return transform(source, quarterTurns == 1 ? 6 : 8, 3000);
    }

    static BufferedImage transform(BufferedImage source, int orientation, int maxEdge) {
        int w = source.getWidth(), h = source.getHeight();
        boolean swapped = orientation >= 5 && orientation <= 8;
        int width = swapped ? h : w, height = swapped ? w : h;
        double scale = Math.min(1, (double) maxEdge / Math.max(width, height));
        var result =
                new BufferedImage(
                        Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)),
                        BufferedImage.TYPE_INT_RGB);
        AffineTransform transform =
                switch (orientation) {
                    case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
                    case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
                    case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
                    case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                    case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
                    case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
                    case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
                    default -> new AffineTransform();
                };
        var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.scale(scale, scale);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}

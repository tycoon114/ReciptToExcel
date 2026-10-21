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

/**
 * 업로드 사진을 증빙 PNG로 변환하기 전에 EXIF 촬영 방향을 실제 픽셀에 적용합니다.
 * 형식과 해상도를 검사하고 RGB로 통일하여 미리보기와 OCR가 같은 방향의 사진을 사용하게 합니다.
 */
@Component
public class ReceiptImages {
    /** 실제 이미지 형식과 픽셀 수를 확인한 뒤 긴 변 3000픽셀 이내의 방향 보정 이미지를 만듭니다. */
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
                // 파일 용량이 작아도 픽셀 수가 매우 클 수 있으므로 실제 디코딩 전에 메모리 사용을 제한합니다.
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

    /** EXIF 방향 값 1~8을 읽습니다. 정보가 없거나 깨졌으면 원래 픽셀 방향인 1로 처리합니다. */
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
            // 유효한 EXIF가 없어도 사진은 읽을 수 있습니다. 필요한 방향 수정은 화면의 회전 버튼으로 제공합니다.
            return 1;
        }
    }

    /** 사용자가 요청한 좌우 90도 회전을 기존 EXIF 변환 함수로 처리합니다. */
    public BufferedImage rotate(BufferedImage source, int quarterTurns) {
        if (quarterTurns != 1 && quarterTurns != -1) throw ReceiptService.bad("회전 방향이 올바르지 않습니다.");
        return transform(source, quarterTurns == 1 ? 6 : 8, 3000);
    }

    /** 회전·반전·축소를 한 번에 그립니다. 호출자가 반환 이미지를 사용한 뒤 해제해야 합니다. */
    static BufferedImage transform(BufferedImage source, int orientation, int maxEdge) {
        int w = source.getWidth(), h = source.getHeight();
        // EXIF 5~8은 가로·세로가 뒤바뀌므로 출력 캔버스 크기도 교환합니다.
        boolean swapped = orientation >= 5 && orientation <= 8;
        int width = swapped ? h : w, height = swapped ? w : h;
        double scale = Math.min(1, (double) maxEdge / Math.max(width, height));
        var result =
                new BufferedImage(
                        Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)),
                        BufferedImage.TYPE_INT_RGB);
        // EXIF 규격: 2 좌우 반전, 3 180도, 4 상하 반전, 5·7 대각 반전, 6 시계 90도, 8 반시계 90도입니다.
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
            // 투명한 PNG도 흰 영수증 배경으로 합쳐 RGB 이미지에서 검은 배경으로 바뀌지 않게 합니다.
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

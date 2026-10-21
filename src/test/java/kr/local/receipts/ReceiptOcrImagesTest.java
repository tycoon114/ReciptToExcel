package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

import javax.imageio.ImageIO;

/** OCR 전처리가 종이 영역을 유지하고 저장된 증빙 파일을 변경하지 않는지 검증합니다. */
class ReceiptOcrImagesTest {
    @Test
    // 합성 영수증의 배경은 줄이되 종이의 가장자리와 본문은 잘라내지 않는지 확인합니다.
    void paperCropKeepsTheWholeReceiptWhileExcludingItsBackground() {
        var image = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, 400, 600);
        g.setColor(Color.WHITE);
        g.fillRect(80, 60, 250, 480);
        g.setColor(Color.BLACK);
        for (int y = 100; y < 480; y += 60) g.fillRect(100, y, 180, 4);
        g.dispose();
        var rectangle = ReceiptOcrImages.paperRegion(image);
        assertTrue(rectangle.contains(81, 61));
        assertTrue(rectangle.contains(328, 538));
        assertTrue(rectangle.width * rectangle.height < 400 * 600 * .8);
    }

    @Test
    // 분석 사본을 생성한 뒤 원본 파일 바이트가 동일한지 확인합니다.
    void ocrPreparationKeepsTheStoredEvidenceUnmodified(@TempDir Path data) throws Exception {
        Path source = data.resolve("photo.png");
        var image = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 300);
        g.setColor(Color.BLACK);
        g.drawString("RECEIPT", 40, 120);
        g.dispose();
        ImageIO.write(image, "png", source.toFile());
        byte[] before = Files.readAllBytes(source);
        var prepared = new ReceiptOcrImages().compact(source, true, true);
        assertTrue(prepared.getWidth() > 0);
        assertTrue(prepared.getHeight() > 0);
        prepared.flush();
        assertArrayEquals(before, Files.readAllBytes(source));
    }
}

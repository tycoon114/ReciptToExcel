package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

import javax.imageio.ImageIO;

class ReceiptOcrImagesTest {
    @Test
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

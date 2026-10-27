package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** 파일 형식·회전 차이의 보조 지문과 백지 제외, 원본 보존을 확인합니다. */
class ReceiptSimilarityTest {
    @Test void receiptFingerprintSurvivesJpegAndRotationWithoutChangingOriginal(@TempDir Path folder)throws Exception {
        var image=new BufferedImage(400,700,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,400,700);g.setColor(Color.BLACK);
        for(int i=0;i<12;i++)g.fillRect(40,30+i*50,150+(i%4)*35,9);
        g.dispose();Path png=folder.resolve("receipt.png"),jpeg=folder.resolve("receipt.jpg"),rotated=folder.resolve("rotated.png");
        ImageIO.write(image,"png",png.toFile());ImageIO.write(image,"jpg",jpeg.toFile());image.flush();
        byte[] before=Files.readAllBytes(png);var source=ImageIO.read(png.toFile());var turn=new ReceiptImages().rotate(source,1);
        ImageIO.write(turn,"png",rotated.toFile());source.flush();turn.flush();
        String original=ReceiptSimilarity.fingerprint(png);
        assertFalse(original.isBlank());assertTrue(ReceiptSimilarity.similar(original,ReceiptSimilarity.fingerprint(jpeg)));
        assertTrue(ReceiptSimilarity.similar(original,ReceiptSimilarity.fingerprint(rotated)));
        assertArrayEquals(before,Files.readAllBytes(png));
        assertFalse(ReceiptSimilarity.similar("",original));assertFalse(ReceiptSimilarity.similar("corrupt",original));
    }
    @Test void featurelessPagesNeverMatch(@TempDir Path folder)throws Exception {
        var image=new BufferedImage(400,700,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,400,700);g.dispose();
        Path blank=folder.resolve("blank.png");ImageIO.write(image,"png",blank.toFile());image.flush();
        assertEquals("",ReceiptSimilarity.fingerprint(blank));
    }
}

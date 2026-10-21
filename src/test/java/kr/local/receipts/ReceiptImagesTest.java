package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.*;

import javax.imageio.ImageIO;

/** 촬영 방향 8종과 JPEG의 EXIF 적용을 검증합니다. 사진 내용이 회전·반전 중 손실되지 않는지 확인합니다. */
class ReceiptImagesTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    // 방향 값마다 모든 픽셀의 이동 위치와 가로·세로 크기를 확인합니다.
    void cameraOrientationsPreserveEveryPixel(int orientation) {
        int[] colors = {0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff, 0x00ffff};
        int[][] expected = {
            {0, 1, 2, 3, 4, 5},
            {1, 0, 3, 2, 5, 4},
            {5, 4, 3, 2, 1, 0},
            {4, 5, 2, 3, 0, 1},
            {0, 2, 4, 1, 3, 5},
            {4, 2, 0, 5, 3, 1},
            {5, 3, 1, 4, 2, 0},
            {1, 3, 5, 0, 2, 4}
        };
        var input = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        input.setRGB(0, 0, 2, 3, colors, 0, 2);
        var actual = ReceiptImages.transform(input, orientation, 100);
        assertEquals(orientation >= 5 ? 3 : 2, actual.getWidth());
        assertEquals(orientation >= 5 ? 2 : 3, actual.getHeight());
        for (int i = 0; i < 6; i++)
            assertEquals(
                    colors[expected[orientation - 1][i]],
                    actual.getRGB(i % actual.getWidth(), i / actual.getWidth()) & 0xffffff,
                    "pixel " + i);
    }

    @Test
    // JPEG의 EXIF 방향이 PNG 변환 전에 실제 픽셀에 반영되는지 확인합니다.
    void jpegExifIsAppliedBeforePngConversion() throws Exception {
        var source = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", out);
    // 테스트용 JPEG에 방향 메타데이터를 넣습니다. 개인 영수증 파일은 사용하지 않습니다.
        byte[] original = out.toByteArray(), exif = withOrientation(original, 6);
        assertEquals(6, ReceiptImages.orientation(exif));
        var fixed = new ReceiptImages().normalize(exif);
        assertEquals(80, fixed.getWidth());
        assertEquals(120, fixed.getHeight());
        var png = new ByteArrayOutputStream();
        ImageIO.write(fixed, "png", png);
        var again = new ReceiptImages().normalize(png.toByteArray());
        assertEquals(80, again.getWidth());
        assertEquals(120, again.getHeight(), "Normalized images must not rotate twice");
    }

    static byte[] withOrientation(byte[] jpeg, int orientation) throws Exception {
        var exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        exif.put(new byte[] {'E', 'x', 'i', 'f', 0, 0, 'I', 'I'})
                .putShort((short) 42)
                .putInt(8)
                .putShort((short) 1);
        exif.putShort((short) 274)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0)
                .putInt(0);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(new byte[] {(byte) 255, (byte) 225, 0, 34});
        out.write(exif.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}

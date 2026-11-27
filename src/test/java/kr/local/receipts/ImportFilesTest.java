package kr.local.receipts;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ImportFilesTest {
    @TempDir Path data;
    private ImportFiles store() throws Exception { return new ImportFiles(JsonMapper.builder().build(), data.toString()); }
    private byte[] pdf(boolean text) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            if (text) try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("Sample account 61000"); stream.endText();
            }
            doc.save(out); return out.toByteArray();
        }
    }
    @Test void documentKindsPersistAndDownloadOriginalBytes() throws Exception {
        var files = store(); var bytes = pdf(true);
        for (var kind : new ImportFiles.Kind[]{ImportFiles.Kind.CARD_PDF, ImportFiles.Kind.ACCOUNT_PDF, ImportFiles.Kind.TAX_INVOICE}) {
            var entry = files.upload(kind, new MockMultipartFile("file", "자료.pdf", "application/pdf", bytes));
            assertEquals(kind, entry.kind()); assertEquals(1, entry.pages());
            assertArrayEquals(bytes, Files.readAllBytes(files.original(entry.id())));
        }
        assertEquals(3, store().list().size());
    }
    @Test void scannedStatementsAndBrokenFilesAreRejectedWithoutEntries() throws Exception {
        var files = store();
        for (var kind : new ImportFiles.Kind[]{ImportFiles.Kind.CARD_PDF, ImportFiles.Kind.ACCOUNT_PDF})
            assertThrows(ResponseStatusException.class, () -> files.upload(kind, new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf(false))));
        assertThrows(ResponseStatusException.class, () -> files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "bad.pdf", "application/pdf", new byte[]{1,2,3})));
        assertTrue(files.list().isEmpty());
    }
    @Test void imagesAreStoredInTheirOwnCategory() throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(30,30,BufferedImage.TYPE_INT_RGB), "png", out);
        var files = store();
        var entry = files.upload(ImportFiles.Kind.RECEIPT_PHOTO, new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray()));
        assertEquals("image/png", entry.mediaType()); assertEquals(0, entry.pages());
        assertEquals(1, store().list().size());
        assertThrows(ResponseStatusException.class, () -> files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray())));
    }
    @Test void arbitraryPathsAreRejected() throws Exception {
        assertThrows(ResponseStatusException.class, () -> store().get("../../application.properties"));
    }
}

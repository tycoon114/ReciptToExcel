package kr.local.receipts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportFiles files;
    public ImportController(ImportFiles files) { this.files = files; }
    @GetMapping public List<ImportFiles.Entry> list() throws IOException { return files.list(); }
    @PostMapping public ImportFiles.Entry upload(@RequestParam("kind") ImportFiles.Kind kind,
            @RequestParam("file") MultipartFile file) throws IOException { return files.upload(kind, file); }
    @GetMapping("/{id}/original") public ResponseEntity<FileSystemResource> original(@PathVariable String id) throws IOException {
        var entry = files.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(entry.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(entry.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(files.original(id)));
    }
}

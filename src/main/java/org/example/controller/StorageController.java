package org.example.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/images")
public class StorageController {

    private static final File STORAGE_DIR = new File("storage");

    @GetMapping("/{filename}")
    public ResponseEntity<FileSystemResource> getImage(@PathVariable String filename) throws IOException {
        File file = new File(STORAGE_DIR, filename).getCanonicalFile();

        // Guard against path traversal
        if (!file.toPath().startsWith(STORAGE_DIR.getCanonicalFile().toPath())) {
            return ResponseEntity.badRequest().build();
        }

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(file));
    }
}

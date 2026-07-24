package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Upload endpoint for message attachments (photos / short videos), shared by the admin
 * mailbox and the customer inbox. Any authenticated user may upload — the path is under
 * {@code /notifications/**} (not {@code /admin/**}) so CUSTOMER tokens can attach to a
 * reply too. Files land in the same directory {@code MediaController} serves, so the
 * returned URL ({@code /api/v1/bookings/media/{file}}) streams the image/video publicly
 * (video via HTTP with the container's {@code probeContentType}).
 *
 * <p>Storage mirrors {@code MediaController}: UUID filename (no client name reaches disk),
 * path-traversal guarded, content-type allow-listed to image/* and video/*. Size is capped
 * by {@code spring.servlet.multipart.max-file-size} (25 MB) in addition to the check here.
 */
@RestController
@RequestMapping("/api/v1/bookings/notifications")
@Slf4j
public class MessageAttachmentController {

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024; // 25 MB
    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".webm", ".mov", ".m4v");

    private final Path uploadDir;

    public MessageAttachmentController(@Value("${app.media.upload-dir:/app/uploads}") String uploadPath) {
        this.uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + uploadPath, e);
        }
    }

    @PostMapping("/attachment")
    public ResponseEntity<ApiResponse<Map<String, String>>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("File is empty"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Attachment exceeds the 25 MB limit"));
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String ext = extensionOf(file.getOriginalFilename());
        String kind;
        if (contentType.startsWith("image/") || IMAGE_EXT.contains(ext)) {
            kind = "image";
            if (ext.isEmpty()) ext = ".jpg";
            if (!IMAGE_EXT.contains(ext)) ext = ".jpg";
        } else if (contentType.startsWith("video/") || VIDEO_EXT.contains(ext)) {
            kind = "video";
            if (ext.isEmpty()) ext = ".mp4";
            if (!VIDEO_EXT.contains(ext)) ext = ".mp4";
        } else {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Only images (JPG, PNG, WebP, GIF) and videos (MP4, WebM, MOV) are allowed"));
        }

        try {
            String storedName = UUID.randomUUID() + ext;
            Path target = this.uploadDir.resolve(storedName).normalize();
            if (!target.startsWith(this.uploadDir)) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Invalid file name"));
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            String url = "/api/v1/bookings/media/" + storedName;
            String original = file.getOriginalFilename();
            log.info("Message attachment uploaded: {} ({})", url, kind);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Attachment uploaded", Map.of(
                "url", url,
                "type", kind,
                "name", original != null ? original : ("attachment" + ext)
            )));
        } catch (IOException e) {
            log.error("Failed to store message attachment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Failed to store attachment"));
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.')).toLowerCase();
    }
}

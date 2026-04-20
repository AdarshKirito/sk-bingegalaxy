package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Slf4j
public class MediaController {

    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    private final Path uploadDir;
    private final AdminBingeScopeService adminBingeScopeService;

    public MediaController(@Value("${app.media.upload-dir:/app/uploads}") String uploadPath,
                           AdminBingeScopeService adminBingeScopeService) {
        this.uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
        this.adminBingeScopeService = adminBingeScopeService;
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + uploadPath, e);
        }
    }

    @ModelAttribute
    void validateAdminBingeScope(
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            HttpServletRequest request) {
        if (request.getRequestURI().contains("/admin/media")) {
            adminBingeScopeService.requireManagedBinge(adminId, role, "uploading media");
        }
    }

    @PostMapping("/admin/media/upload")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("File is empty"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("File size exceeds 5 MB limit"));
        }
        String contentType = file.getContentType();
        // Infer content type from extension if the client did not send one
        // (some clients omit MIME type for uppercase extensions like .JPG)
        if (contentType == null || contentType.isBlank()) {
            String fname = file.getOriginalFilename();
            if (fname != null && fname.contains(".")) {
                String ext = fname.substring(fname.lastIndexOf('.') + 1).toLowerCase();
                contentType = switch (ext) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    case "gif" -> "image/gif";
                    default -> null;
                };
            }
        }
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Only JPEG, PNG, WebP, and GIF images are allowed"));
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
            }
            // Sanitize extension
            if (!Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif").contains(extension)) {
                extension = ".jpg";
            }
            String storedName = UUID.randomUUID() + extension;
            Path targetPath = this.uploadDir.resolve(storedName).normalize();

            // Prevent path traversal
            if (!targetPath.startsWith(this.uploadDir)) {
                return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid file name"));
            }

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String imageUrl = "/api/v1/bookings/media/" + storedName;
            log.info("Image uploaded: {}", imageUrl);

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Image uploaded", Map.of("url", imageUrl)));
        } catch (IOException e) {
            log.error("Failed to store uploaded image", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Failed to store image"));
        }
    }

    @GetMapping("/media/{filename:.+}")
    public ResponseEntity<Resource> serveImage(@PathVariable String filename) {
        try {
            // Sanitize: only allow simple filenames (UUID + extension)
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                return ResponseEntity.badRequest().build();
            }

            Path filePath = this.uploadDir.resolve(filename).normalize();
            if (!filePath.startsWith(this.uploadDir)) {
                return ResponseEntity.badRequest().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

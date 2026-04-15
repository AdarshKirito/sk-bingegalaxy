package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for MediaController: upload validation,
 * path traversal prevention, file serving, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @TempDir
    Path tempDir;

    private MediaController controller;

    @BeforeEach
    void setUp() {
        controller = new MediaController(tempDir.toString());
    }

    // ── Upload Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Upload Image")
    class UploadTests {

        @Test
        @DisplayName("successfully uploads a valid JPEG image")
        void upload_validJpeg_success() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getData().get("url")).startsWith("/api/v1/bookings/media/");
            assertThat(resp.getBody().getData().get("url")).endsWith(".jpg");
        }

        @Test
        @DisplayName("successfully uploads a valid PNG image")
        void upload_validPng_success() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getData().get("url")).endsWith(".png");
        }

        @Test
        @DisplayName("successfully uploads a valid WebP image")
        void upload_validWebp_success() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "banner.webp", "image/webp", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getData().get("url")).endsWith(".webp");
        }

        @Test
        @DisplayName("rejects empty file")
        void upload_emptyFile_badRequest() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().getMessage()).contains("empty");
        }

        @Test
        @DisplayName("rejects file exceeding 5MB")
        void upload_oversized_badRequest() {
            byte[] oversized = new byte[5 * 1024 * 1024 + 1]; // 5MB + 1 byte
            MockMultipartFile file = new MockMultipartFile(
                "file", "large.jpg", "image/jpeg", oversized);

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().getMessage()).contains("5 MB");
        }

        @Test
        @DisplayName("rejects exactly at 5MB limit (boundary)")
        void upload_exactly5MB_succeeds() {
            byte[] exact = new byte[5 * 1024 * 1024]; // exactly 5MB
            MockMultipartFile file = new MockMultipartFile(
                "file", "exact.jpg", "image/jpeg", exact);

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("rejects invalid content type - text/html")
        void upload_textHtml_badRequest() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "page.html", "text/html", "<script>alert(1)</script>".getBytes());

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().getMessage()).contains("JPEG, PNG, WebP, and GIF");
        }

        @Test
        @DisplayName("rejects application/pdf content type")
        void upload_pdf_badRequest() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects null content type")
        void upload_nullContentType_badRequest() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "mystery", null, new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("infers JPEG content type from uppercase .JPG extension when client omits MIME type")
        void upload_uppercaseJpgWithoutContentType_succeeds() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "PHOTO.JPG", null, new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getData().get("url")).endsWith(".jpg");
        }

        @Test
        @DisplayName("sanitizes unknown extension to .jpg")
        void upload_unknownExtension_fallsBackToJpg() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "image.bmp", "image/jpeg", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getData().get("url")).endsWith(".jpg");
        }

        @Test
        @DisplayName("sanitizes .exe extension to .jpg")
        void upload_exeExtension_fallsBackToJpg() {
            MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "image/jpeg", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getData().get("url")).endsWith(".jpg");
        }

        @Test
        @DisplayName("handles null original filename")
        void upload_nullFilename_usesJpg() {
            MockMultipartFile file = new MockMultipartFile(
                "file", null, "image/jpeg", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp = controller.uploadImage(file);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getData().get("url")).endsWith(".jpg");
        }

        @Test
        @DisplayName("generates unique filenames for same file uploaded twice")
        void upload_twice_differentNames() {
            MockMultipartFile file1 = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
            MockMultipartFile file2 = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

            ResponseEntity<ApiResponse<Map<String, String>>> resp1 = controller.uploadImage(file1);
            ResponseEntity<ApiResponse<Map<String, String>>> resp2 = controller.uploadImage(file2);

            assertThat(resp1.getBody().getData().get("url"))
                .isNotEqualTo(resp2.getBody().getData().get("url"));
        }
    }

    // ── Serve Tests ──────────────────────────────────────────

    @Nested
    @DisplayName("Serve Image")
    class ServeTests {

        @Test
        @DisplayName("serves existing file")
        void serve_existingFile_ok() throws IOException {
            Path testFile = tempDir.resolve("test-uuid.jpg");
            Files.write(testFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

            ResponseEntity<Resource> resp = controller.serveImage("test-uuid.jpg");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getHeaders().getCacheControl()).contains("max-age=86400");
        }

        @Test
        @DisplayName("returns 404 for non-existent file")
        void serve_nonExistent_notFound() {
            ResponseEntity<Resource> resp = controller.serveImage("doesnt-exist.jpg");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("rejects path traversal with '..'")
        void serve_dotDot_badRequest() {
            ResponseEntity<Resource> resp = controller.serveImage("../etc/passwd");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects forward slash in filename")
        void serve_forwardSlash_badRequest() {
            ResponseEntity<Resource> resp = controller.serveImage("path/to/file.jpg");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects backslash in filename")
        void serve_backslash_badRequest() {
            ResponseEntity<Resource> resp = controller.serveImage("path\\to\\file.jpg");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects multiple path traversal attempts")
        void serve_multipleDotDot_badRequest() {
            ResponseEntity<Resource> resp = controller.serveImage("..%2f..%2fetc%2fpasswd");

            // The raw string still contains ".." so it should be rejected
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}

package com.skbingegalaxy.common.exception;

import com.skbingegalaxy.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusiness_returnsCorrectStatus() {
        BusinessException ex = new BusinessException("nope", HttpStatus.FORBIDDEN);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusiness(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().getMessage()).isEqualTo("nope");
        assertThat(resp.getBody().isSuccess()).isFalse();
    }

    @Test
    void handleNotFound_returns404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Booking", "id", 999);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleNotFound(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getMessage()).contains("Booking", "999");
    }

    @Test
    void handleDuplicate_returns409() {
        DuplicateResourceException ex = new DuplicateResourceException("User", "email", "a@b.com");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDuplicate(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().getMessage()).contains("User", "email", "a@b.com");
    }

    @Test
    void handleNoResource_returns404_genericMessage() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/nonexistent");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResource(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getMessage()).isEqualTo("Resource not found");
    }

    @Test
    void handleValidation_returns400_withFieldErrors() throws Exception {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.addError(new FieldError("target", "email", "must not be blank"));
        bindingResult.addError(new FieldError("target", "name", "required"));

        Method method = getClass().getDeclaredMethod("stubMethod", String.class);
        MethodParameter param = new MethodParameter(method, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidation(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage()).isEqualTo("Validation failed");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) resp.getBody().getErrors();
        assertThat(errors).containsEntry("email", "must not be blank");
        assertThat(errors).containsEntry("name", "required");
    }

    @Test
    void handleMissingHeader_userHeader_returns401() throws Exception {
        Method method = getClass().getDeclaredMethod("stubMethod", String.class);
        MethodParameter param = new MethodParameter(method, 0);
        MissingRequestHeaderException ex = new MissingRequestHeaderException("X-User-Id", param);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingHeader(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().getMessage()).contains("X-User-Id");
    }

    @Test
    void handleMissingHeader_nonUserHeader_returns400() throws Exception {
        Method method = getClass().getDeclaredMethod("stubMethod", String.class);
        MethodParameter param = new MethodParameter(method, 0);
        MissingRequestHeaderException ex = new MissingRequestHeaderException("Accept", param);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingHeader(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleMissingParameter_returns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "int");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingParameter(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage()).contains("page");
    }

    @Test
    void handleGeneral_returns500_genericMessage() {
        Exception ex = new RuntimeException("something broke");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleGeneral(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getMessage()).doesNotContain("something broke");
        assertThat(resp.getBody().getMessage()).contains("unexpected error");
    }

    // Dummy method to create MethodParameter for tests
    @SuppressWarnings("unused")
    void stubMethod(String param) {}
}

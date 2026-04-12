package com.skbingegalaxy.common.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ApiResponseTest {

    @Test
    void ok_withData_setsSuccessAndData() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData()).isEqualTo("hello");
        assertThat(resp.getMessage()).isNull();
        assertThat(resp.getErrors()).isNull();
    }

    @Test
    void ok_withMessageAndData_setsBoth() {
        ApiResponse<Integer> resp = ApiResponse.ok("found", 42);
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getMessage()).isEqualTo("found");
        assertThat(resp.getData()).isEqualTo(42);
    }

    @Test
    void error_withMessage_setsFailure() {
        ApiResponse<Void> resp = ApiResponse.error("bad request");
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).isEqualTo("bad request");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void error_withMessageAndErrors_setsBoth() {
        Map<String, String> fieldErrors = Map.of("email", "required");
        ApiResponse<Void> resp = ApiResponse.error("Validation failed", fieldErrors);
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).isEqualTo("Validation failed");
        assertThat(resp.getErrors()).isEqualTo(fieldErrors);
    }
}

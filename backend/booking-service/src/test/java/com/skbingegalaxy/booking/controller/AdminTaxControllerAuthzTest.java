package com.skbingegalaxy.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.TaxRuleDto;
import com.skbingegalaxy.booking.entity.TaxRule;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.TaxService;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the controller-layer governance gate: tax management (binge-scoped
 * AND global) is SUPER_ADMIN-only. Regular binge admins get 403 on every
 * mutation — tax configuration is a platform-compliance concern (see the
 * controller javadoc for the governance-tightening rationale).
 */
@WebMvcTest(controllers = AdminTaxController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminTaxControllerAuthzTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TaxService taxService;
    @MockBean private AdminBingeScopeService adminBingeScopeService;

    private static final long GLOBAL_RULE_ID = 77L;
    private static final long BINGE_RULE_ID  = 88L;
    private static final String URL_GLOBAL = "/api/v1/bookings/admin/taxes/" + GLOBAL_RULE_ID;
    private static final String URL_BINGE  = "/api/v1/bookings/admin/taxes/" + BINGE_RULE_ID;

    private TaxRuleDto sampleDto() {
        TaxRuleDto dto = new TaxRuleDto();
        dto.setName("GST 18");
        dto.setRateBps(1800);
        dto.setAppliesTo(TaxRule.AppliesTo.TOTAL);
        dto.setTaxType("GST");
        dto.setPriority(100);
        dto.setActive(true);
        return dto;
    }

    @Test
    void update_global_byAdmin_returns403() throws Exception {
        mockMvc.perform(put(URL_GLOBAL)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "500")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleDto())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Only super admins can manage tax rules"));

        verify(taxService, never()).updateRule(anyLong(), any());
    }

    @Test
    void delete_global_byAdmin_returns403() throws Exception {
        mockMvc.perform(delete(URL_GLOBAL)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "500"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Only super admins can manage tax rules"));

        verify(taxService, never()).deleteRule(anyLong());
    }

    @Test
    void update_global_bySuperAdmin_proceeds() throws Exception {
        when(taxService.isGlobalRule(GLOBAL_RULE_ID)).thenReturn(true);
        when(taxService.updateRule(eqLong(GLOBAL_RULE_ID), any())).thenReturn(sampleDto());

        mockMvc.perform(put(URL_GLOBAL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleDto())))
            .andExpect(status().isOk());

        verify(taxService).updateRule(eqLong(GLOBAL_RULE_ID), any());
    }

    @Test
    void delete_global_bySuperAdmin_proceeds() throws Exception {
        when(taxService.isGlobalRule(GLOBAL_RULE_ID)).thenReturn(true);

        mockMvc.perform(delete(URL_GLOBAL)
                .header("X-User-Role", "SUPER_ADMIN")
                .header("X-User-Id", "1"))
            .andExpect(status().isOk());

        verify(taxService).deleteRule(GLOBAL_RULE_ID);
    }

    @Test
    void update_bingeScoped_byAdmin_returns403() throws Exception {
        // Governance tightening: even BINGE-scoped rules are super-admin-only.
        // A regular admin must be rejected before the service layer is reached.
        mockMvc.perform(put(URL_BINGE)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "500")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleDto())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Only super admins can manage tax rules"));

        verify(taxService, never()).updateRule(anyLong(), any());
    }

    @Test
    void delete_bingeScoped_byAdmin_returns403() throws Exception {
        mockMvc.perform(delete(URL_BINGE)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "500"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "Only super admins can manage tax rules"));

        verify(taxService, never()).deleteRule(anyLong());
    }

    private static long eqLong(long v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }
}

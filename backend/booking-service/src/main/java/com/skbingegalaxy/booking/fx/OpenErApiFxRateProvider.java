package com.skbingegalaxy.booking.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Primary FX source: open.er-api.com (ExchangeRate-API open endpoint).
 * Free, no API key, ~160 currencies, refreshed daily. Response shape:
 * <pre>{"result":"success","base_code":"INR","rates":{"USD":0.01204,...}}</pre>
 */
@Component
@Order(1)
@Slf4j
public class OpenErApiFxRateProvider implements FxRateProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OpenErApiFxRateProvider(RestClient.Builder builder,
                                   ObjectMapper objectMapper,
                                   @Value("${app.fx.er-api-url:https://open.er-api.com/v6/latest/}") String baseUrl) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public String sourceCode() {
        return "ER_API";
    }

    @Override
    public Map<String, BigDecimal> fetchRates(String baseCode) throws Exception {
        String body = restClient.get()
            .uri(baseUrl + baseCode)
            .retrieve()
            .body(String.class);
        JsonNode root = objectMapper.readTree(body);
        if (!"success".equalsIgnoreCase(root.path("result").asText())) {
            throw new IllegalStateException("er-api returned result="
                + root.path("result").asText("<missing>"));
        }
        JsonNode rates = root.path("rates");
        if (!rates.isObject() || rates.isEmpty()) {
            throw new IllegalStateException("er-api returned no rates for base " + baseCode);
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (Iterator<String> it = rates.fieldNames(); it.hasNext(); ) {
            String code = it.next();
            BigDecimal v = rates.path(code).decimalValue();
            if (v.signum() > 0) out.put(code.toUpperCase(), v);
        }
        return out;
    }
}

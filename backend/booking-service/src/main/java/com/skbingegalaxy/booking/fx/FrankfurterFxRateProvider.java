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
 * Fallback FX source: frankfurter.dev — ECB daily reference rates. Free, no
 * key, but covers only the ~30 ECB-published currencies; used when the primary
 * provider is unreachable. Response shape:
 * <pre>{"base":"INR","date":"2026-07-09","rates":{"USD":0.012,...}}</pre>
 */
@Component
@Order(2)
@Slf4j
public class FrankfurterFxRateProvider implements FxRateProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public FrankfurterFxRateProvider(RestClient.Builder builder,
                                     ObjectMapper objectMapper,
                                     @Value("${app.fx.frankfurter-url:https://api.frankfurter.dev/v1/latest}") String baseUrl) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public String sourceCode() {
        return "ECB";
    }

    @Override
    public Map<String, BigDecimal> fetchRates(String baseCode) throws Exception {
        String body = restClient.get()
            .uri(baseUrl + "?base=" + baseCode)
            .retrieve()
            .body(String.class);
        JsonNode root = objectMapper.readTree(body);
        JsonNode rates = root.path("rates");
        if (!rates.isObject() || rates.isEmpty()) {
            throw new IllegalStateException("frankfurter returned no rates for base " + baseCode);
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

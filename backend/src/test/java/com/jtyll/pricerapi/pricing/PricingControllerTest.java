package com.jtyll.pricerapi.pricing;

import com.jtyll.pricerapi.pricing.dto.PricingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PricingController.class)
class PricingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingService pricingService;

    @Test
    void returnsOkWithPricingResultForValidRequest() throws Exception {
        when(pricingService.price(any())).thenReturn(new PricingResponse(
                PricingMethod.BLACK_SCHOLES, OptionType.CALL, 10.450584, null, null, null, 1));

        String requestJson = """
                {
                    "method": "BLACK_SCHOLES",
                    "optionType": "CALL",
                    "spot": 100,
                    "strike": 100,
                    "riskFreeRate": 0.05,
                    "volatility": 0.2,
                    "maturity": 1.0
                }
                """;

        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(10.450584))
                .andExpect(jsonPath("$.method").value("BLACK_SCHOLES"));
    }

    @Test
    void returnsBadRequestWhenSpotIsNegative() throws Exception {
        String requestJson = """
                {
                    "method": "BLACK_SCHOLES",
                    "optionType": "CALL",
                    "spot": -10,
                    "strike": 100,
                    "riskFreeRate": 0.05,
                    "volatility": 0.2,
                    "maturity": 1.0
                }
                """;

        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.spot").exists());
    }

    @Test
    void returnsBadRequestWhenMethodIsMissing() throws Exception {
        String requestJson = """
                {
                    "optionType": "CALL",
                    "spot": 100,
                    "strike": 100,
                    "riskFreeRate": 0.05,
                    "volatility": 0.2,
                    "maturity": 1.0
                }
                """;

        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());
    }
}

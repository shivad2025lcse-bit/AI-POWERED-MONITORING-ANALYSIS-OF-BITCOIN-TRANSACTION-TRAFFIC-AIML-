package com.bitcoin.monitoring.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

class MempoolSpaceProviderTest {
    @Test
    void fallsBackToBlockstreamWhenPrimaryProviderFails() {
        RestClient.Builder primaryBuilder = RestClient.builder().baseUrl("https://primary.test");
        RestClient.Builder fallbackBuilder = RestClient.builder().baseUrl("https://fallback.test");
        MockRestServiceServer primaryServer = MockRestServiceServer.bindTo(primaryBuilder).build();
        MockRestServiceServer fallbackServer = MockRestServiceServer.bindTo(fallbackBuilder).build();
        primaryServer.expect(requestTo("https://primary.test/blocks/tip/height"))
                .andRespond(withServerError());
        fallbackServer.expect(requestTo("https://fallback.test/blocks/tip/height"))
                .andRespond(withSuccess("969294", MediaType.TEXT_PLAIN));

        MempoolSpaceProvider provider = new MempoolSpaceProvider(primaryBuilder.build(), fallbackBuilder.build());

        assertEquals(969294L, provider.getLatestBlockHeight());
        primaryServer.verify();
        fallbackServer.verify();
    }
}
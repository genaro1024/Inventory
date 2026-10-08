package com.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryApplicationTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void startsEmbeddedWebServer() {
        assertThat(port).isBetween(1, 65535);
    }
}

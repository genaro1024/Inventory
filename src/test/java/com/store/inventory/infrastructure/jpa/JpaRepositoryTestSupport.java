package com.store.inventory.infrastructure.jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class JpaRepositoryTestSupport {

    protected H2InventoryDatabase database;

    @BeforeEach
    void openDatabase() {
        database = new H2InventoryDatabase();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }
}

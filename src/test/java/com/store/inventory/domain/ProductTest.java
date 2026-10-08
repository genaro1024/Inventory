package com.store.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingSku(String sku) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Product(sku, Category.STANDARD));
    }

    @Test
    void rejectsMissingCategory() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Product("SKU-1", null));
    }

    @Test
    void preservesIdentifiersWithoutNormalization() {
        var product = new Product(" Camisa-01 ", Category.STANDARD);

        assertThat(product.sku()).isEqualTo(" Camisa-01 ");
        assertThat(product).isNotEqualTo(new Product(" camisa-01 ", Category.STANDARD));
        assertThat(product).isNotEqualTo(new Product("Camisa-01", Category.STANDARD));
    }
}

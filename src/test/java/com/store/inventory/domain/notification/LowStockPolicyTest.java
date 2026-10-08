package com.store.inventory.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LowStockPolicyTest {

    @ParameterizedTest
    @CsvSource({"0,0,false,false", "0,1,false,true", "5,1,false,true", "6,1,false,false", "3,1,true,false"})
    void requiresLoadedStockAndDoesNotRepeatWithinTheSameCycle(int units, long cycle, boolean created, boolean expected) {
        assertThat(LowStockPolicy.shouldAlert(units, cycle, created)).isEqualTo(expected);
    }
}

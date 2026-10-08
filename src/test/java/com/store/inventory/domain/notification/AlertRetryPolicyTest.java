package com.store.inventory.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AlertRetryPolicyTest {

    private final AlertRetryPolicy policy = new AlertRetryPolicy();

    @ParameterizedTest
    @CsvSource({"1,2", "2,4", "3,8", "4,16", "5,32"})
    void appliesIndependentJitterWithinTwentyPercent(int attempts, long seconds) {
        long nanos = Duration.ofSeconds(seconds).toNanos();
        assertThat(policy.afterFailure(attempts, () -> 0).orElseThrow())
                .isEqualTo(Duration.ofNanos(Math.round(nanos * 0.8)));
        assertThat(policy.afterFailure(attempts, () -> 0.5).orElseThrow()).isEqualTo(Duration.ofSeconds(seconds));
        assertThat(policy.afterFailure(attempts, () -> 1).orElseThrow())
                .isEqualTo(Duration.ofNanos(Math.round(nanos * 1.2)));
    }

    @Test
    void theSixthFailedAttemptGoesToDlqWithoutAnotherDelay() {
        assertThat(policy.afterFailure(6, () -> { throw new AssertionError("Jitter is unnecessary"); })).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7})
    void rejectsInvalidAttemptCounts(int attempts) {
        assertThatIllegalArgumentException().isThrownBy(() -> policy.afterFailure(attempts, () -> 0.5));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidJitter(double sample) {
        assertThatIllegalArgumentException().isThrownBy(() -> policy.afterFailure(1, () -> sample));
    }
}

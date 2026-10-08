package com.store.inventory.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class DemoClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public DemoClock(Instant initialTime) {
        this(new AtomicReference<>(Objects.requireNonNull(initialTime)), ZoneOffset.UTC);
    }

    private DemoClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = Objects.requireNonNull(zone);
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Demo time must not move backwards");
        }
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new DemoClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}

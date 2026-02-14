package org.tokenmanager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A mutable, thread-safe Clock for testing. Allows advancing time
 * without Thread.sleep, making tests fast and deterministic.
 */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  MutableClock(Instant initial, ZoneId zone) {
    this.now = new AtomicReference<>(initial);
    this.zone = zone;
  }

  MutableClock(Instant initial) {
    this(initial, ZoneId.of("UTC"));
  }

  /** Advance the clock by the given duration and return the new instant. */
  Instant advance(Duration duration) {
    return now.updateAndGet(current -> current.plus(duration));
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MutableClock(now.get(), zone);
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}

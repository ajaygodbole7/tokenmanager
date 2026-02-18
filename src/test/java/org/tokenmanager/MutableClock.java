/*
 * Copyright 2026 ajaygodbole7
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

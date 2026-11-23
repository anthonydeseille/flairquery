package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.delta.Counters;
import io.github.anthonydeseille.flairquery.collector.delta.DeltaCalculator;
import io.github.anthonydeseille.flairquery.collector.delta.DeltaCalculator.Delta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaCalculatorTest {

    @Test
    void normalIntervalIsCurrentMinusPrevious() {
        Delta d = DeltaCalculator.compute(Counters.of(10, 100.5, 3), Counters.of(15, 130.5, 3), false);

        assertThat(d.values()).isEqualTo(Counters.of(5, 30, 0));
        assertThat(d.restarted()).isFalse();
    }

    @Test
    void noActivityIsNull() {
        assertThat(DeltaCalculator.compute(Counters.of(10, 100, 3), Counters.of(10, 100, 3), false)).isNull();
    }

    @Test
    void newEntryCountsEntirely() {
        Delta d = DeltaCalculator.compute(null, Counters.of(4, 12, 1), false);

        assertThat(d.values()).isEqualTo(Counters.of(4, 12, 1));
        assertThat(d.restarted()).isTrue();
    }

    @Test
    void counterGoingBackwardsMeansEvictedAndRecreated() {
        Delta d = DeltaCalculator.compute(Counters.of(1000, 5000, 9), Counters.of(3, 7, 0), false);

        assertThat(d.values()).isEqualTo(Counters.of(3, 7, 0));
        assertThat(d.restarted()).isTrue();
    }

    @Test
    void globalResetUsesTheWholeValueEvenIfHigher() {
        // reset happened, then more calls than before: a plain subtraction would under-count
        Delta d = DeltaCalculator.compute(Counters.of(10, 50, 1), Counters.of(12, 60, 1), true);

        assertThat(d.values()).isEqualTo(Counters.of(12, 60, 1));
        assertThat(d.restarted()).isTrue();
    }

    @Test
    void resetWithoutActivityIsNullNeverNegative() {
        assertThat(DeltaCalculator.compute(Counters.of(10, 50, 1), Counters.of(0, 0, 0), true)).isNull();
        assertThat(DeltaCalculator.compute(Counters.of(10, 50, 1), Counters.of(0, 0, 0), false)).isNull();
    }
}

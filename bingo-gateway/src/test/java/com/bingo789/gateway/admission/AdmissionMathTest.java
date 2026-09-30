package com.bingo789.gateway.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionMathTest {

    private static final long MAX = 1_000_000;
    private static final int RATE = 2000;

    @Test
    void nothingToAdmitWithoutWaitingTickets() {
        assertThat(AdmissionMath.admitStep(100, 100, 10, MAX, RATE)).isZero();
        // admitted ahead of ticket (e.g. counters reset) never goes negative
        assertThat(AdmissionMath.admitStep(100, 150, 10, MAX, RATE)).isZero();
    }

    @Test
    void atCapacityTheQueueOnlyMovesAsPlayersLeave() {
        assertThat(AdmissionMath.admitStep(50_000, 0, MAX, MAX, RATE)).isZero();
        assertThat(AdmissionMath.admitStep(50_000, 0, MAX + 5_000, MAX, RATE)).isZero();
        assertThat(AdmissionMath.admitStep(50_000, 0, MAX - 300, MAX, RATE)).isEqualTo(300);
    }

    @Test
    void stepIsBoundedByRateHeadroomAndQueue() {
        assertThat(AdmissionMath.admitStep(50_000, 0, 500_000, MAX, RATE)).isEqualTo(RATE);
        assertThat(AdmissionMath.admitStep(50_000, 49_990, 500_000, MAX, RATE)).isEqualTo(10);
    }

    @Test
    void smallBacklogCatchesUpInOneStepBelowCapacity() {
        assertThat(AdmissionMath.admitStep(1_234, 1_000, 10_000, MAX, RATE)).isEqualTo(234);
    }

    @Test
    void unknownEstimateOnlyAppliesTheRate() {
        assertThat(AdmissionMath.admitStep(50_000, 0, -1, MAX, RATE)).isEqualTo(RATE);
        assertThat(AdmissionMath.admitStep(50_000, 0, -1, MAX, 0)).isZero();
    }

    @Test
    void positionIsNeverNegative() {
        assertThat(AdmissionMath.position(10, 4)).isEqualTo(6);
        assertThat(AdmissionMath.position(10, 10)).isZero();
        assertThat(AdmissionMath.position(10, 20)).isZero();
    }

    @Test
    void retryAfterIsClamped() {
        assertThat(AdmissionMath.retryAfterSeconds(0, RATE)).isEqualTo(1);
        assertThat(AdmissionMath.retryAfterSeconds(1, RATE)).isEqualTo(AdmissionMath.MIN_RETRY_AFTER_SECONDS);
        assertThat(AdmissionMath.retryAfterSeconds(20_001, RATE)).isEqualTo(11);
        assertThat(AdmissionMath.retryAfterSeconds(10_000_000, RATE)).isEqualTo(AdmissionMath.MAX_RETRY_AFTER_SECONDS);
        assertThat(AdmissionMath.retryAfterSeconds(5, 0)).isEqualTo(AdmissionMath.MAX_RETRY_AFTER_SECONDS);
    }
}

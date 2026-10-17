package com.agenttravel.backend.points;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PointsRepositoryUuidTests {

    @Test
    void binaryTaskIdentifierRoundTripsWithoutChangingBits() {
        UUID value = UUID.randomUUID();

        assertThat(PointsRepository.bytesToUuid(PointsRepository.uuidBytes(value))).isEqualTo(value);
    }
}

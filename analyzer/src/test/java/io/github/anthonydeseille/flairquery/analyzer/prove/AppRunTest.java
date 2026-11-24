package io.github.anthonydeseille.flairquery.analyzer.prove;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AppRunTest {

    @Test
    void freePortsAreDistinctAndFreeOnLoopback() throws Exception {
        int[] ports = AppRun.freePorts(3);

        assertThat(Arrays.stream(ports).distinct().count()).isEqualTo(3);
        for (int p : ports) {
            try (ServerSocket s = new ServerSocket(p, 50, InetAddress.getLoopbackAddress())) {
                assertThat(s.getLocalPort()).isEqualTo(p);
            }
        }
    }
}

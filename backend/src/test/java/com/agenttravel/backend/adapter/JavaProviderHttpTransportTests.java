package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class JavaProviderHttpTransportTests {

    @Test
    void decodesGzipResponseWithinLimit() throws Exception {
        byte[] source = "{\"code\":\"200\"}".getBytes(StandardCharsets.UTF_8);

        byte[] decoded = JavaProviderHttpTransport.decodeBody(gzip(source), 1024);

        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("{\"code\":\"200\"}");
    }

    @Test
    void leavesPlainResponseUnchanged() {
        byte[] source = "{\"status\":\"1\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(JavaProviderHttpTransport.decodeBody(source, 1024)).isEqualTo(source);
    }

    @Test
    void rejectsCorruptGzipResponse() {
        byte[] corrupt = new byte[] {0x1f, (byte) 0x8b, 0x00, 0x00};

        assertThatThrownBy(() -> JavaProviderHttpTransport.decodeBody(corrupt, 1024))
                .isInstanceOf(ProviderTransportException.class)
                .extracting(exception -> ((ProviderTransportException) exception).code())
                .isEqualTo(ProviderErrorCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsExpandedResponseThatExceedsLimit() throws Exception {
        byte[] source = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> JavaProviderHttpTransport.decodeBody(gzip(source), 5))
                .isInstanceOf(ProviderTransportException.class)
                .extracting(exception -> ((ProviderTransportException) exception).code())
                .isEqualTo(ProviderErrorCode.INVALID_RESPONSE);
    }

    private byte[] gzip(byte[] source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(source);
        }
        return output.toByteArray();
    }
}

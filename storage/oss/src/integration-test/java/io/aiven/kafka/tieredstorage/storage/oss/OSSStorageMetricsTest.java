/*
 * Copyright 2025 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.tieredstorage.storage.oss;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Set;

import io.aiven.kafka.tieredstorage.storage.BytesRange;
import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.TestObjectKey;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.DOUBLE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test to verify that OSS metrics are properly collected during storage operations.
 * This test executes actual storage operations using WireMock to verify metrics collection.
 */
@WireMockTest
class OSSStorageMetricsTest {
    private static final MBeanServer MBEAN_SERVER = ManagementFactory.getPlatformMBeanServer();

    private static final int PART_SIZE = 5 * 1024 * 1024;

    private String bucketName;
    private OSSStorage storage;

    @BeforeEach
    void setUp(final TestInfo testInfo, final WireMockRuntimeInfo wmRuntimeInfo) {
        bucketName = "test-bucket-" + testInfo.getDisplayName().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();

        storage = new OSSStorage();
        final Map<String, Object> configs = Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", wmRuntimeInfo.getHttpBaseUrl(),
            "oss.region", "cn-hangzhou",
            "oss.access.key.id", "test-access-key",
            "oss.access.key.secret", "test-secret-key",
            "oss.multipart.upload.part.size", PART_SIZE,
            "oss.certificate.check.enabled", false
        );
        storage.configure(configs);

        // Reset WireMock stubs
        reset();
    }

    @Test
    void metricsShouldBeReported() throws Exception {
        final ObjectKey key = new TestObjectKey("metrics-test-key");

        // Mock all necessary endpoints for operations
        setupWireMockStubs();

        // Upload a big file (multipart upload)
        final byte[] data = new byte[PART_SIZE + 1];
        storage.upload(new ByteArrayInputStream(data), key);

        // Fetch the full object
        try (final InputStream fetch = storage.fetch(key)) {
            fetch.readAllBytes();
        }

        // Fetch with range
        try (final InputStream fetch = storage.fetch(key, BytesRange.of(0, 1))) {
            fetch.readAllBytes();
        }

        // Delete single object
        storage.delete(key);

        // Delete batch
        storage.delete(Set.of(key));

        // Upload a small file (single upload)
        final byte[] smallSizeData = new byte[1];
        storage.upload(new ByteArrayInputStream(smallSizeData), key);

        // Test upload failure
        final InputStream failingInputStream = mock(InputStream.class);
        final IOException exception = new IOException("test");
        when(failingInputStream.transferTo(any())).thenThrow(exception);
        assertThatThrownBy(() -> storage.upload(failingInputStream, key))
            .hasRootCause(exception);

        // Verify metrics
        final ObjectName ossMetricsObjectName = ObjectName.getInstance(
            "aiven.kafka.server.tieredstorage.oss:type=oss-client-metrics");

        // Get object metrics
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "get-object-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "get-object-requests-total"))
            .isEqualTo(2.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "get-object-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "get-object-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        // Put object metrics
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "put-object-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "put-object-requests-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "put-object-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "put-object-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        // Delete object metrics
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-object-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-object-requests-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-object-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-object-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        // Delete objects metrics (batch delete)
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-objects-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-objects-requests-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-objects-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "delete-objects-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        // Multipart upload metrics
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "create-multipart-upload-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "create-multipart-upload-requests-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "create-multipart-upload-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "create-multipart-upload-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "upload-part-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        // Should be at least 2 parts for the large file
        assertThat((Double) MBEAN_SERVER.getAttribute(ossMetricsObjectName, "upload-part-requests-total"))
            .isGreaterThanOrEqualTo(2.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "upload-part-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "upload-part-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "complete-multipart-upload-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "complete-multipart-upload-requests-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "complete-multipart-upload-time-avg"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "complete-multipart-upload-time-max"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0.0);

        // Abort multipart upload should be 0 (no failures)
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "abort-multipart-upload-requests-rate"))
            .asInstanceOf(DOUBLE)
            .isEqualTo(0.0);
        assertThat(MBEAN_SERVER.getAttribute(ossMetricsObjectName, "abort-multipart-upload-requests-total"))
            .isEqualTo(0.0);
    }

    private void setupWireMockStubs() {
        // Mock initiate multipart upload
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(post(anyUrl())
            .withQueryParam("uploads", com.github.tomakehurst.wiremock.client.WireMock.matching(".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<InitiateMultipartUploadResult><UploadId>test-upload-id</UploadId></InitiateMultipartUploadResult>")));

        // Mock upload parts
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(put(anyUrl())
            .withQueryParam("partNumber", com.github.tomakehurst.wiremock.client.WireMock.matching("\\d+"))
            .withQueryParam("uploadId", com.github.tomakehurst.wiremock.client.WireMock.matching("test-upload-id"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"part-etag\"")));

        // Mock complete multipart upload
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(post(anyUrl())
            .withQueryParam("uploadId", com.github.tomakehurst.wiremock.client.WireMock.matching("test-upload-id"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<CompleteMultipartUploadResult><ETag>\"complete-etag\"</ETag></CompleteMultipartUploadResult>")));

        // Mock single put object
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(put(anyUrl())
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"single-etag\"")));

        // Mock get object
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/octet-stream")
                .withHeader("ETag", "\"get-etag\"")
                .withBody("mock content")));

        // Mock delete object
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(delete(anyUrl())
            .willReturn(aResponse().withStatus(200)));

        // Mock batch delete (OSS uses POST for batch delete)
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(post(anyUrl())
            .withQueryParam("delete", com.github.tomakehurst.wiremock.client.WireMock.matching(".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<DeleteResult></DeleteResult>")));
    }
}
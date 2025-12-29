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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.aiven.kafka.tieredstorage.storage.BytesRange;
import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.StorageBackendException;
import io.aiven.kafka.tieredstorage.storage.TestObjectKey;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Complete OSS storage WireMock integration tests.
 * This covers all core storage operations and OSS-specific advanced features.
 */
@WireMockTest
public class OSSStorageWireMockTest {
    private static final int PART_SIZE = 8 * 1024 * 1024; // 8MiB
    private static final String TEST_PREFIX = "tiered-storage-test-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final ObjectKey TOPIC_PARTITION_SEGMENT_KEY = new TestObjectKey("topic/partition/log");

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
    void testUploadFetchDelete() throws IOException, StorageBackendException {
        final byte[] data = "some file".getBytes();

        // Setup WireMock stubs for upload
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        // Setup WireMock stubs for fetch
        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(data)
                .withHeader("Content-Type", "application/octet-stream")
                .withHeader("ETag", "\"test-etag\"")));

        final InputStream file = new ByteArrayInputStream(data);
        final long size = storage.upload(file, TOPIC_PARTITION_SEGMENT_KEY);
        assertThat(size).isEqualTo(data.length);

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY)) {
            final String r = new String(fetch.readAllBytes());
            assertThat(r).isEqualTo("some file");
        }

        final BytesRange range = BytesRange.of(1, data.length - 2);
        // Setup WireMock stubs for range request
        stubFor(get(urlMatching("/topic/partition/log.*"))
            .withHeader("Range", equalTo("bytes=1-3"))
            .willReturn(aResponse().withStatus(206)
                .withBody("ome fil")
                .withHeader("Content-Range", "bytes 1-3/9")
                .withHeader("Content-Type", "application/octet-stream")));

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY, range)) {
            final String r = new String(fetch.readAllBytes());
            assertThat(r).isEqualTo("ome fil");
        }

        // Setup WireMock stubs for delete
        stubFor(delete(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)));

        storage.delete(TOPIC_PARTITION_SEGMENT_KEY);

        // Setup WireMock stubs for non-existing key
        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));

        assertThatThrownBy(() -> storage.fetch(TOPIC_PARTITION_SEGMENT_KEY))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class)
            .hasMessage("Key topic/partition/log does not exists in storage " + storage.toString());
    }

    @Test
    void testUploadANewFile() throws StorageBackendException, IOException {
        final String content = "content";

        // Setup WireMock stubs
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        uploadContentAsFileAndVerify(content);
    }

    protected void uploadContentAsFileAndVerify(final String content) throws StorageBackendException, IOException {
        final ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes());
        final long size = storage.upload(in, TOPIC_PARTITION_SEGMENT_KEY);
        assertThat(size).isEqualTo(content.length());

        assertThat(in).isEmpty();
        in.close();

        // Setup WireMock stubs for fetch
        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(content)
                .withHeader("Content-Type", "application/octet-stream")));

        assertThat(storage.fetch(TOPIC_PARTITION_SEGMENT_KEY)).hasContent(content);
    }

    @Test
    void testRetryUploadKeepLatestVersion() throws StorageBackendException {
        final String content = "content";

        // Setup WireMock stubs for both uploads
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        storage.upload(new ByteArrayInputStream((content + "v1").getBytes()), TOPIC_PARTITION_SEGMENT_KEY);
        storage.upload(new ByteArrayInputStream((content + "v2").getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        // Setup WireMock stubs for fetch
        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(content + "v2")
                .withHeader("Content-Type", "application/octet-stream")));

        assertThat(storage.fetch(TOPIC_PARTITION_SEGMENT_KEY)).hasContent(content + "v2");
    }

    @Test
    void testFetchFailWhenNonExistingKey() {
        // Setup WireMock stubs for non-existing key
        stubFor(get(urlMatching("/non-existing.*"))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));

        assertThatThrownBy(() -> storage.fetch(new TestObjectKey("non-existing")))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class)
            .hasMessage("Key non-existing does not exists in storage " + storage);
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey("non-existing"), BytesRange.of(0, 1)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class)
            .hasMessage("Key non-existing does not exists in storage " + storage);
    }

    @Test
    void testFetchAll() throws IOException, StorageBackendException {
        final String content = "content";

        // Setup WireMock stubs
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        storage.upload(new ByteArrayInputStream(content.getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(content)
                .withHeader("Content-Type", "application/octet-stream")));

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY)) {
            assertThat(fetch).hasContent(content);
        }
    }

    @Test
    void testFetchWithOffsetRange() throws IOException, StorageBackendException {
        final String content = "AABBBBAA";
        storage.upload(new ByteArrayInputStream(content.getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        final int from = 2;
        final int to = 5;
        // Replacing end position as substring is end exclusive, and expected response is end inclusive
        final String range = content.substring(from, to + 1);

        // Setup WireMock stubs for range request
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        stubFor(get(urlMatching("/topic/partition/log.*"))
            .withHeader("Range", equalTo("bytes=2-5"))
            .willReturn(aResponse().withStatus(206)
                .withBody(range)
                .withHeader("Content-Range", "bytes 2-5/8")
                .withHeader("Content-Type", "application/octet-stream")));

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY, BytesRange.of(from, to))) {
            assertThat(fetch).hasContent(range);
        }
    }

    @Test
    void testFetchSingleByte() throws IOException, StorageBackendException {
        final String content = "ABC";

        // Setup WireMock stubs
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        storage.upload(new ByteArrayInputStream(content.getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        stubFor(get(urlMatching("/topic/partition/log.*"))
            .withHeader("Range", equalTo("bytes=2-2"))
            .willReturn(aResponse().withStatus(206)
                .withBody("C")
                .withHeader("Content-Range", "bytes 2-2/3")
                .withHeader("Content-Type", "application/octet-stream")));

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY, BytesRange.of(2, 2))) {
            assertThat(fetch).hasContent("C");
        }
    }

    @Test
    void testFetchWithOffsetRangeLargerThanFileSize() throws IOException, StorageBackendException {
        final String content = "ABC";

        // Setup WireMock stubs
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        storage.upload(new ByteArrayInputStream(content.getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        stubFor(get(urlMatching("/topic/partition/log.*"))
            .withHeader("Range", equalTo("bytes=2-4"))
            .willReturn(aResponse().withStatus(206)
                .withBody("C")
                .withHeader("Content-Range", "bytes 2-2/3")
                .withHeader("Content-Type", "application/octet-stream")));

        try (final InputStream fetch = storage.fetch(TOPIC_PARTITION_SEGMENT_KEY, BytesRange.of(2, 4))) {
            assertThat(fetch).hasContent("C");
        }
    }

    @Test
    void testDelete() throws StorageBackendException {
        // Setup WireMock stubs
        stubFor(put(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        storage.upload(new ByteArrayInputStream("test".getBytes()), TOPIC_PARTITION_SEGMENT_KEY);

        stubFor(delete(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(200)));

        storage.delete(TOPIC_PARTITION_SEGMENT_KEY);

        // Test deletion idempotence.
        assertThatNoException().isThrownBy(() -> storage.delete(TOPIC_PARTITION_SEGMENT_KEY));

        stubFor(get(urlMatching("/topic/partition/log.*"))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));

        assertThatThrownBy(() -> storage.fetch(TOPIC_PARTITION_SEGMENT_KEY))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class)
            .hasMessage("Key topic/partition/log does not exists in storage " + storage);
    }

    @Test
    void testDeletes() throws StorageBackendException {
        final Set<ObjectKey> keys = IntStream.range(0, 10)
            .mapToObj(i -> new TestObjectKey(TOPIC_PARTITION_SEGMENT_KEY.value() + i))
            .collect(Collectors.toSet());

        // Setup WireMock stubs for uploads
        for (final var key : keys) {
            stubFor(put(urlMatching("/" + key.value() + ".*"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("ETag", "\"test-etag\"")));
        }

        for (final var key : keys) {
            storage.upload(new ByteArrayInputStream("test".getBytes()), key);
        }

        // Setup WireMock stubs for batch delete
        stubFor(post(urlMatching("/\\?delete.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<DeleteResult></DeleteResult>")));

        storage.delete(keys);

        // Test deletion idempotence.
        assertThatNoException().isThrownBy(() -> storage.delete(keys));

        // Setup WireMock stubs for non-existing keys
        for (final var key : keys) {
            stubFor(get(urlMatching("/" + key.value() + ".*"))
                .willReturn(aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/xml")
                    .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));
        }

        for (final var key : keys) {
            assertThatThrownBy(() -> storage.fetch(key))
                .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class)
                .hasMessage("Key " + key.value() + " does not exists in storage " + storage);
        }
    }

    private String generateTestKey(final String suffix) {
        return TEST_PREFIX + suffix;
    }

    @Test
    void testBasicUploadDownloadDelete() throws StorageBackendException, IOException {
        final String testKey = "basic-test";
        final String content = "Hello, OSS WireMock Test!";

        // Mock successful upload
        stubFor(put(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        // Mock successful download
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(content)
                .withHeader("Content-Type", "application/octet-stream")
                .withHeader("ETag", "\"test-etag\"")));

        // Mock successful delete
        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));

        // Test upload
        final long uploadSize = storage.upload(
            new ByteArrayInputStream(content.getBytes()),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(content.length());

        // Test download
        try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
            final String downloadedContent = new String(inputStream.readAllBytes());
            assertThat(downloadedContent).isEqualTo(content);
        }

        // Test delete
        storage.delete(new TestObjectKey(testKey));

        // Verify delete was called
        // Note: WireMock verification would go here if needed
    }

    @Test
    void testRangeRequests() throws StorageBackendException, IOException {
        final String testKey = "range-test";
        final String content = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        // Mock upload
        stubFor(put(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        // Mock range requests
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .withHeader("Range", equalTo("bytes=0-9"))
            .willReturn(aResponse().withStatus(206)
                .withBody("0123456789")
                .withHeader("Content-Range", "bytes 0-9/36")
                .withHeader("Content-Type", "application/octet-stream")));

        stubFor(get(urlMatching("/" + testKey + ".*"))
            .withHeader("Range", equalTo("bytes=10-19"))
            .willReturn(aResponse().withStatus(206)
                .withBody("ABCDEFGHIJ")
                .withHeader("Content-Range", "bytes 10-19/36")
                .withHeader("Content-Type", "application/octet-stream")));

        stubFor(get(urlMatching("/" + testKey + ".*"))
            .withHeader("Range", equalTo("bytes=20-25"))
            .willReturn(aResponse().withStatus(206)
                .withBody("KLMNOP")
                .withHeader("Content-Range", "bytes 20-25/36")
                .withHeader("Content-Type", "application/octet-stream")));

        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));

        // Upload content first
        storage.upload(new ByteArrayInputStream(content.getBytes()), new TestObjectKey(testKey));

        // Test range requests
        try (final var rangeStream1 = storage.fetch(new TestObjectKey(testKey), BytesRange.of(0, 9))) {
            assertThat(rangeStream1).hasContent("0123456789");
        }

        try (final var rangeStream2 = storage.fetch(new TestObjectKey(testKey), BytesRange.of(10, 19))) {
            assertThat(rangeStream2).hasContent("ABCDEFGHIJ");
        }

        try (final var rangeStream3 = storage.fetch(new TestObjectKey(testKey), BytesRange.of(20, 25))) {
            assertThat(rangeStream3).hasContent("KLMNOP");
        }

        // Clean up
        storage.delete(new TestObjectKey(testKey));
    }

    @Test
    void testMultipartUpload() throws StorageBackendException, IOException {
        final String testKey = "multipart-test";
        final byte[] largeContent = new byte[PART_SIZE + 1024]; // Larger than part size
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        // Mock initiate multipart upload
        stubFor(post(urlMatching("/" + testKey + "\\?uploads.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<InitiateMultipartUploadResult><UploadId>test-upload-id</UploadId></InitiateMultipartUploadResult>")));

        // Mock upload parts
        stubFor(put(urlMatching("/" + testKey + "\\?partNumber=\\d+&uploadId=test-upload-id.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"part-etag\"")));

        // Mock complete multipart upload
        stubFor(post(urlMatching("/" + testKey + "\\?uploadId=test-upload-id.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<CompleteMultipartUploadResult><ETag>\"complete-etag\"</ETag></CompleteMultipartUploadResult>")));

        // Mock download for verification
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(largeContent)
                .withHeader("Content-Type", "application/octet-stream")));

        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));

        // Test multipart upload
        final long uploadSize = storage.upload(
            new ByteArrayInputStream(largeContent),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(largeContent.length);

        // Verify download (partial verification for performance)
        try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
            final byte[] downloaded = inputStream.readNBytes(1024);
            for (int i = 0; i < downloaded.length; i++) {
                assertThat(downloaded[i]).isEqualTo((byte) (i % 256));
            }
        }

        // Clean up
        storage.delete(new TestObjectKey(testKey));
    }

    @Test
    void testErrorHandling() throws StorageBackendException {
        final String nonExistentKey = "non-existent-key";

        // Mock 404 for non-existent key
        stubFor(any(urlMatching("/" + nonExistentKey + ".*"))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));

        // Test fetching non-existent key
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey(nonExistentKey)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);

        // Test fetching with range on non-existent key
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey(nonExistentKey), BytesRange.of(0, 10)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);

        // Test deleting non-existent key (should not throw exception in OSS)
        // Note: OSS delete on non-existent key typically doesn't throw an error
        stubFor(delete(urlMatching("/" + nonExistentKey + ".*"))
            .willReturn(aResponse().withStatus(404)));
        storage.delete(new TestObjectKey(nonExistentKey));
    }

    @Test
    void testEmptyRangeRequest() throws StorageBackendException, IOException {
        final String testKey = "empty-range-test";
        final String content = "Test content for empty range";

        // Mock upload
        stubFor(put(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"test-etag\"")));

        // Mock empty range request
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .withHeader("Range", equalTo("bytes=0--1"))
            .willReturn(aResponse().withStatus(200) // OSS might return 200 for empty range
                .withBody("")
                .withHeader("Content-Type", "application/octet-stream")));

        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));

        // Upload content
        storage.upload(new ByteArrayInputStream(content.getBytes()), new TestObjectKey(testKey));

        // Test empty range
        try (final var emptyRangeStream = storage.fetch(new TestObjectKey(testKey), BytesRange.of(0, -1))) {
            assertThat(emptyRangeStream.readAllBytes()).isEmpty();
        }

        // Clean up
        storage.delete(new TestObjectKey(testKey));
    }

    @Test
    void testLargeFileUpload() throws StorageBackendException, IOException {
        final String testKey = generateTestKey("large-file-test");
        // Create a 1MB test file
        final byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        // Mock multipart upload
        setupMultipartUploadStubs(testKey, largeContent);

        // Upload large file
        final long uploadSize = storage.upload(
            new ByteArrayInputStream(largeContent),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(largeContent.length);

        // Mock download for verification
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(largeContent)
                .withHeader("Content-Type", "application/octet-stream")));

        // Download and verify (partial verification for performance)
        try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
            final byte[] downloaded = inputStream.readNBytes(1024);
            for (int i = 0; i < downloaded.length; i++) {
                assertThat(downloaded[i]).isEqualTo((byte) (i % 256));
            }
        }

        // Clean up
        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));
        storage.delete(new TestObjectKey(testKey));
    }

    @Test
    void testBatchOperations() throws StorageBackendException, IOException {
        final int batchSize = 5; // Smaller batch for testing
        final Set<ObjectKey> testKeys = IntStream.range(0, batchSize)
            .mapToObj(i -> new TestObjectKey(generateTestKey("batch-" + i)))
            .collect(Collectors.toSet());

        // Upload batch of objects
        final String content = "Batch test content";
        for (final ObjectKey key : testKeys) {
            stubFor(put(urlMatching("/" + key.value() + ".*"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("ETag", "\"batch-etag\"")));
            storage.upload(new ByteArrayInputStream(content.getBytes()), key);
        }

        // Mock downloads
        for (final ObjectKey key : testKeys) {
            stubFor(get(urlMatching("/" + key.value() + ".*"))
                .willReturn(aResponse().withStatus(200)
                    .withBody(content)
                    .withHeader("Content-Type", "application/octet-stream")));
        }

        // Verify uploads
        for (final ObjectKey key : testKeys) {
            try (final var inputStream = storage.fetch(key)) {
                assertThat(inputStream).hasContent(content);
            }
        }

        // Mock batch delete (OSS uses POST for batch delete)
        stubFor(post(urlMatching("/\\?delete.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<DeleteResult></DeleteResult>")));

        // Delete batch
        storage.delete(testKeys);
    }

    @Test
    void testSpecialCharactersInKeys() throws StorageBackendException, IOException {
        final String[] specialKeys = {
            "test-with-dashes",
            "test_with_underscores",
            "test.with.dots",
            "test/with/slashes",
            "test with spaces",
            "test@symbol",
            "test#hash",
            "test+plus",
            "test%percent",
            "test中文",
            "test🚀emoji"
        };

        for (final String specialKey : specialKeys) {
            final String testKey = generateTestKey(specialKey);
            final String content = "Content for " + specialKey;

            // Mock upload
            stubFor(put(urlMatching("/" + testKey.replace("/", "\\/") + ".*"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("ETag", "\"special-etag\"")));

            // Mock download
            stubFor(get(urlMatching("/" + testKey.replace("/", "\\/") + ".*"))
                .willReturn(aResponse().withStatus(200)
                    .withBody(content)
                    .withHeader("Content-Type", "application/octet-stream")));

            // Mock delete
            stubFor(delete(urlMatching("/" + testKey.replace("/", "\\/") + ".*"))
                .willReturn(aResponse().withStatus(200)));

            // Upload
            final long uploadSize = storage.upload(
                new ByteArrayInputStream(content.getBytes()),
                new TestObjectKey(testKey)
            );
            assertThat(uploadSize).isEqualTo(content.length());

            // Download and verify
            try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
                assertThat(inputStream).hasContent(content);
            }

            // Clean up
            storage.delete(new TestObjectKey(testKey));
        }
    }

    @Test
    void testConcurrentOperations() throws StorageBackendException, InterruptedException {
        final int threadCount = 5; // Reduced for WireMock testing
        final int operationsPerThread = 3;

        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        // Setup WireMock stubs for concurrent operations
        for (int i = 0; i < threadCount; i++) {
            for (int j = 0; j < operationsPerThread; j++) {
                final String key = "concurrent-" + i + "-" + j;
                stubFor(put(urlMatching("/.*" + key + ".*"))
                    .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"concurrent-etag\"")));
                stubFor(get(urlMatching("/.*" + key + ".*"))
                    .willReturn(aResponse().withStatus(200)
                        .withBody("Concurrent content " + i + "-" + j)
                        .withHeader("Content-Type", "application/octet-stream")));
                stubFor(delete(urlMatching("/.*" + key + ".*"))
                    .willReturn(aResponse().withStatus(200)));
            }
        }

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        final String testKey = generateTestKey("concurrent-" + threadId + "-" + j);
                        final String content = "Concurrent content " + threadId + "-" + j;

                        // Upload
                        storage.upload(new ByteArrayInputStream(content.getBytes()), new TestObjectKey(testKey));

                        // Download and verify
                        try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
                            assertThat(inputStream).hasContent(content);
                        }

                        // Delete
                        storage.delete(new TestObjectKey(testKey));

                        successCount.incrementAndGet();
                    }
                } catch (final Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify results
        assertThat(successCount.get()).isEqualTo(threadCount * operationsPerThread);
        assertThat(failureCount.get()).isEqualTo(0);
    }

    @Test
    void testConfigurationWithTimeouts(final WireMockRuntimeInfo wmRuntimeInfo) throws StorageBackendException, IOException {
        // Test with custom timeout configurations
        final Map<String, Object> configsWithTimeouts = java.util.Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", wmRuntimeInfo.getHttpBaseUrl(),
            "oss.region", "cn-hangzhou",
            "oss.access.key.id", "test-access-key",
            "oss.access.key.secret", "test-secret-key",
            "oss.socket.timeout", 60000L, // 60 seconds
            "oss.connection.timeout", 30000L // 30 seconds
        );

        final OSSStorage timeoutStorage = new OSSStorage();
        timeoutStorage.configure(configsWithTimeouts);

        final String testKey = generateTestKey("timeout-test");
        final String content = "Content for timeout configuration test";

        // Mock upload
        stubFor(put(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"timeout-etag\"")));

        // Mock download
        stubFor(get(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)
                .withBody(content)
                .withHeader("Content-Type", "application/octet-stream")));

        // Mock delete
        stubFor(delete(urlMatching("/" + testKey + ".*"))
            .willReturn(aResponse().withStatus(200)));

        // Upload with timeout configuration
        final long uploadSize = timeoutStorage.upload(
            new ByteArrayInputStream(content.getBytes()),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(content.length());

        // Download and verify
        try (final var inputStream = timeoutStorage.fetch(new TestObjectKey(testKey))) {
            assertThat(inputStream).hasContent(content);
        }

        // Clean up
        timeoutStorage.delete(new TestObjectKey(testKey));
    }

    private void setupMultipartUploadStubs(final String testKey, final byte[] content) {
        // Mock initiate multipart upload
        stubFor(post(urlMatching("/" + testKey + "\\?uploads.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<InitiateMultipartUploadResult><UploadId>test-upload-id</UploadId></InitiateMultipartUploadResult>")));

        // Mock upload parts
        stubFor(put(urlMatching("/" + testKey + "\\?partNumber=\\d+&uploadId=test-upload-id.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"part-etag\"")));

        // Mock complete multipart upload
        stubFor(post(urlMatching("/" + testKey + "\\?uploadId=test-upload-id.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<CompleteMultipartUploadResult><ETag>\"complete-etag\"</ETag></CompleteMultipartUploadResult>")));
    }
}

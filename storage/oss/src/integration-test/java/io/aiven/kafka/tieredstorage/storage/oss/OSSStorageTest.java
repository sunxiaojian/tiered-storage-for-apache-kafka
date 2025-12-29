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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.aiven.kafka.tieredstorage.storage.BytesRange;
import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.StorageBackendException;
import io.aiven.kafka.tieredstorage.storage.TestObjectKey;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real environment integration tests for OSS Storage.
 *
 * This test class uses real OSS environment and requires the following environment variables:
 * - OSS_ACCESS_KEY_ID: OSS access key ID
 * - OSS_ACCESS_KEY_SECRET: OSS access key secret
 * - OSS_ENDPOINT: OSS endpoint URL
 * - OSS_BUCKET_NAME: OSS bucket name
 *
 * To run these tests:
 * 1. Set up the required environment variables
 * 2. Remove the @Disabled annotation from the class
 * 3. Run the tests
 *
 * Note: These tests interact with real OSS services and may incur costs.
 * Make sure you have proper permissions and understand the billing implications.
 */
@Disabled("Requires real OSS environment. Remove @Disabled to run against real OSS service.")
public class OSSStorageTest {

    private static final String TEST_PREFIX = "tiered-storage-test-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    private static OSSStorage storage;
    private static String bucketName;

    @BeforeAll
    static void setUp() {
        // Check if required environment variables are set
        final String accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        final String accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        final String endpoint = System.getenv("OSS_ENDPOINT");
        bucketName = System.getenv("OSS_BUCKET_NAME");

        Assumptions.assumeTrue(accessKeyId != null && !accessKeyId.isEmpty(),
            "OSS_ACCESS_KEY_ID environment variable must be set");
        Assumptions.assumeTrue(accessKeySecret != null && !accessKeySecret.isEmpty(),
            "OSS_ACCESS_KEY_SECRET environment variable must be set");
        Assumptions.assumeTrue(endpoint != null && !endpoint.isEmpty(),
            "OSS_ENDPOINT environment variable must be set");
        Assumptions.assumeTrue(bucketName != null && !bucketName.isEmpty(),
            "OSS_BUCKET_NAME environment variable must be set");

        // Configure storage with environment variables
        final Map<String, Object> configs = Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", endpoint,
            "oss.access.key.id", accessKeyId,
            "oss.access.key.secret", accessKeySecret
        );

        storage = new OSSStorage();
        storage.configure(configs);
    }

    private String generateTestKey(final String suffix) {
        return TEST_PREFIX + suffix;
    }

    @Test
    void testBasicUploadDownloadDelete() throws StorageBackendException, IOException {
        final String testKey = generateTestKey("basic-test");
        final String content = "Hello, OSS Integration Test!";

        // Upload
        final long uploadSize = storage.upload(
            new ByteArrayInputStream(content.getBytes()),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(content.length());

        // Download and verify
        try (final var inputStream = storage.fetch(new TestObjectKey(testKey))) {
            final String downloadedContent = new String(inputStream.readAllBytes());
            assertThat(downloadedContent).isEqualTo(content);
        }

        // Delete
        storage.delete(new TestObjectKey(testKey));

        // Verify deletion - should throw KeyNotFoundException
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey(testKey)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);
    }

    @Test
    void testRangeRequests() throws StorageBackendException, IOException {
        final String testKey = generateTestKey("range-test");
        final String content = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        // Upload content
        storage.upload(new ByteArrayInputStream(content.getBytes()), new TestObjectKey(testKey));

        // Test various range requests
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
    void testLargeFileUpload() throws StorageBackendException, IOException {
        final String testKey = generateTestKey("large-file-test");
        // Create a 1MB test file
        final byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        // Upload large file
        final long uploadSize = storage.upload(
            new ByteArrayInputStream(largeContent),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(largeContent.length);

        // Download and verify (partial verification for performance)
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
    void testBatchOperations() throws StorageBackendException, IOException {
        final int batchSize = 50;
        final Set<ObjectKey> testKeys = IntStream.range(0, batchSize)
            .mapToObj(i -> new TestObjectKey(generateTestKey("batch-" + i)))
            .collect(Collectors.toSet());

        // Upload batch of objects
        final String content = "Batch test content";
        for (final ObjectKey key : testKeys) {
            storage.upload(new ByteArrayInputStream(content.getBytes()), key);
        }

        // Verify uploads
        for (final ObjectKey key : testKeys) {
            try (final var inputStream = storage.fetch(key)) {
                assertThat(inputStream).hasContent(content);
            }
        }

        // Delete batch
        storage.delete(testKeys);

        // Verify deletions
        for (final ObjectKey key : testKeys) {
            assertThatThrownBy(() -> storage.fetch(key))
                .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);
        }
    }

    @Test
    void testLargeBatchOperations() throws StorageBackendException, IOException {
        final int largeBatchSize = 1200; // Exceeds OSS batch delete limit of 1000
        final Set<ObjectKey> testKeys = IntStream.range(0, largeBatchSize)
            .mapToObj(i -> new TestObjectKey(generateTestKey("large-batch-" + i)))
            .collect(Collectors.toSet());

        // Upload large batch of objects
        final String content = "Large batch test content";
        for (final ObjectKey key : testKeys) {
            storage.upload(new ByteArrayInputStream(content.getBytes()), key);
        }

        // Delete large batch (should be handled internally with multiple requests)
        storage.delete(testKeys);

        // Verify deletions (sample check for performance)
        final int sampleSize = Math.min(100, largeBatchSize);
        for (int i = 0; i < sampleSize; i++) {
            final ObjectKey key = new TestObjectKey(generateTestKey("large-batch-" + i));
            assertThatThrownBy(() -> storage.fetch(key))
                .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);
        }
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
    void testDifferentStorageClasses() throws StorageBackendException, IOException {
        final String[] storageClasses = {"Standard", "IA", "Archive", "ColdArchive"};

        for (final String storageClass : storageClasses) {
            final String testKey = generateTestKey("storage-class-" + storageClass);
            final String content = "Content for " + storageClass;

            // Configure storage with specific storage class
            final Map<String, Object> configs = Map.of(
                "oss.bucket.name", bucketName,
                "oss.endpoint", System.getenv("OSS_ENDPOINT"),
                "oss.region", System.getenv().getOrDefault("OSS_REGION", "cn-hangzhou"),
                "oss.access.key.id", System.getenv("OSS_ACCESS_KEY_ID"),
                "oss.access.key.secret", System.getenv("OSS_ACCESS_KEY_SECRET"),
                "oss.storage.class", storageClass
            );

            final OSSStorage classSpecificStorage = new OSSStorage();
            classSpecificStorage.configure(configs);

            // Upload with specific storage class
            final long uploadSize = classSpecificStorage.upload(
                new ByteArrayInputStream(content.getBytes()),
                new TestObjectKey(testKey)
            );
            assertThat(uploadSize).isEqualTo(content.length());

            // Download and verify
            try (final var inputStream = classSpecificStorage.fetch(new TestObjectKey(testKey))) {
                assertThat(inputStream).hasContent(content);
            }

            // Clean up
            classSpecificStorage.delete(new TestObjectKey(testKey));
        }
    }

    @Test
    void testConcurrentOperations() throws StorageBackendException, InterruptedException {
        final int threadCount = 10;
        final int operationsPerThread = 5;

        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        final java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

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
        latch.await();
        executor.shutdown();

        // Verify results
        assertThat(successCount.get()).isEqualTo(threadCount * operationsPerThread);
        assertThat(failureCount.get()).isEqualTo(0);
    }

    @Test
    void testErrorHandling() throws StorageBackendException, IOException {
        final String nonExistentKey = generateTestKey("non-existent-key");

        // Test fetching non-existent key
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey(nonExistentKey)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);

        // Test fetching with range on non-existent key
        assertThatThrownBy(() -> storage.fetch(new TestObjectKey(nonExistentKey), BytesRange.of(0, 10)))
            .isInstanceOf(io.aiven.kafka.tieredstorage.storage.KeyNotFoundException.class);

        // Test deleting non-existent key (should not throw exception)
        storage.delete(new TestObjectKey(nonExistentKey));
    }

    @Test
    void testEmptyRangeRequest() throws StorageBackendException, IOException {
        final String testKey = generateTestKey("empty-range-test");
        final String content = "Test content for empty range";

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
    void testConfigurationWithTimeouts() throws StorageBackendException, IOException {
        // Test with custom timeout configurations
        final Map<String, Object> configsWithTimeouts = Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", System.getenv("OSS_ENDPOINT"),
            "oss.region", System.getenv().getOrDefault("OSS_REGION", "cn-hangzhou"),
            "oss.access.key.id", System.getenv("OSS_ACCESS_KEY_ID"),
            "oss.access.key.secret", System.getenv("OSS_ACCESS_KEY_SECRET"),
            "oss.socket.timeout", 60000L, // 60 seconds
            "oss.connection.timeout", 30000L // 30 seconds
        );

        final OSSStorage timeoutStorage = new OSSStorage();
        timeoutStorage.configure(configsWithTimeouts);

        final String testKey = generateTestKey("timeout-test");
        final String content = "Content for timeout configuration test";

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

    @Test
    void testConfigurationWithSecurityToken() throws StorageBackendException, IOException {
        final String securityToken = System.getenv("OSS_SECURITY_TOKEN");
        Assumptions.assumeTrue(securityToken != null && !securityToken.isEmpty(),
            "OSS_SECURITY_TOKEN environment variable must be set for this test");

        // Test with security token
        final Map<String, Object> configsWithToken = Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", System.getenv("OSS_ENDPOINT"),
            "oss.region", System.getenv().getOrDefault("OSS_REGION", "cn-hangzhou"),
            "oss.access.key.id", System.getenv("OSS_ACCESS_KEY_ID"),
            "oss.access.key.secret", System.getenv("OSS_ACCESS_KEY_SECRET"),
            "oss.security.token", securityToken
        );

        final OSSStorage tokenStorage = new OSSStorage();
        tokenStorage.configure(configsWithToken);

        final String testKey = generateTestKey("security-token-test");
        final String content = "Content for security token test";

        // Upload with security token
        final long uploadSize = tokenStorage.upload(
            new ByteArrayInputStream(content.getBytes()),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(content.length());

        // Download and verify
        try (final var inputStream = tokenStorage.fetch(new TestObjectKey(testKey))) {
            assertThat(inputStream).hasContent(content);
        }

        // Clean up
        tokenStorage.delete(new TestObjectKey(testKey));
    }

    @Test
    void testConfigurationOptions() throws StorageBackendException, IOException {
        // Test various configuration options
        final Map<String, Object> fullConfigs = Map.of(
            "oss.bucket.name", bucketName,
            "oss.endpoint", System.getenv("OSS_ENDPOINT"),
            "oss.region", System.getenv().getOrDefault("OSS_REGION", "cn-hangzhou"),
            "oss.access.key.id", System.getenv("OSS_ACCESS_KEY_ID"),
            "oss.access.key.secret", System.getenv("OSS_ACCESS_KEY_SECRET"),
            "oss.storage.class", "IA",
            "oss.socket.timeout", 45000L,
            "oss.connection.timeout", 25000L,
            "oss.certificate.check.enabled", true,
            "oss.checksum.check.enabled", false
        );

        final OSSStorage fullConfigStorage = new OSSStorage();
        fullConfigStorage.configure(fullConfigs);

        final String testKey = generateTestKey("full-config-test");
        final String content = "Content for full configuration test";

        // Test with full configuration
        final long uploadSize = fullConfigStorage.upload(
            new ByteArrayInputStream(content.getBytes()),
            new TestObjectKey(testKey)
        );
        assertThat(uploadSize).isEqualTo(content.length());

        // Test range request
        try (final var rangeStream = fullConfigStorage.fetch(new TestObjectKey(testKey), BytesRange.of(0, 6))) {
            assertThat(rangeStream).hasContent("Content ");
        }

        // Clean up
        fullConfigStorage.delete(new TestObjectKey(testKey));
    }
}

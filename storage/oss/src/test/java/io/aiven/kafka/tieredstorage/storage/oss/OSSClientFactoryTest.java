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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OSSClientFactoryTest {
    private static final String BUCKET_NAME = "test-bucket";
    private static final String ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
    private static final String REGION = "cn-hangzhou";

    @Test
    void buildThrowsExceptionWhenNoCredentialsProvided() {
        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION
        );
        final var config = new OSSStorageConfig(configs);

        assertThatThrownBy(() -> OSSClientFactory.build(config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OSS credentials not provided");
    }

    @Test
    void configWithTimeouts() {
        final String accessKeyId = "test-access-key";
        final String accessKeySecret = "test-access-secret";

        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", accessKeyId,
            "oss.access.key.secret", accessKeySecret,
            "oss.socket.timeout", 30000L,
            "oss.connection.timeout", 20000L
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.socketTimeout()).isNotNull();
        assertThat(config.connectionTimeout()).isNotNull();
    }

    @Test
    void configWithCertificateCheckDisabled() {
        final String accessKeyId = "test-access-key";
        final String accessKeySecret = "test-access-secret";

        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", accessKeyId,
            "oss.access.key.secret", accessKeySecret,
            "oss.certificate.check.enabled", false
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.certificateCheckEnabled()).isFalse();
    }

    @Test
    void configWithChecksumCheckEnabled() {
        final String accessKeyId = "test-access-key";
        final String accessKeySecret = "test-access-secret";

        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", accessKeyId,
            "oss.access.key.secret", accessKeySecret,
            "oss.checksum.check.enabled", true
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.checksumCheckEnabled()).isTrue();
    }
}
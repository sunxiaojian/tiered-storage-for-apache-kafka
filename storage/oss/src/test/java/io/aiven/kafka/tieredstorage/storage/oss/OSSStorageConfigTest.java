/*
 * Copyright 2023 Aiven Oy
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

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.common.config.ConfigException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OSSStorageConfigTest {
    private static final String BUCKET_NAME = "test-bucket";
    private static final String ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
    private static final String REGION = "cn-hangzhou";

    // Test scenarios
    // - Minimal config
    @Test
    void minimalConfig() {
        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.bucketName()).isEqualTo(BUCKET_NAME);
        assertThat(config.endpoint()).isEqualTo(ENDPOINT);
        assertThat(config.region()).isEqualTo(REGION);
        assertThat(config.accessKeyId()).isNull();
        assertThat(config.accessKeySecret()).isNull();
        assertThat(config.securityToken()).isNull();
        assertThat(config.socketTimeout()).isEqualTo(Duration.ofMillis(50000));
        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofMillis(50000));
        assertThat(config.certificateCheckEnabled()).isTrue();
        assertThat(config.checksumCheckEnabled()).isFalse();
        assertThat(config.credentialsFile()).isNull();
    }

    // - With credentials
    @Test
    void configWithCredentials() {
        final String accessKeyId = "test-access-key";
        final String accessKeySecret = "test-access-secret";
        final String securityToken = "test-security-token";

        final Map<String, Object> configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", accessKeyId,
            "oss.access.key.secret", accessKeySecret,
            "oss.security.token", securityToken,
            "oss.socket.timeout", 30000L,
            "oss.connection.timeout", 20000L
        );

        final var config = new OSSStorageConfig(configs);

        assertThat(config.bucketName()).isEqualTo(BUCKET_NAME);
        assertThat(config.endpoint()).isEqualTo(ENDPOINT);
        assertThat(config.region()).isEqualTo(REGION);
        assertThat(config.accessKeyId()).isEqualTo(accessKeyId);
        assertThat(config.accessKeySecret()).isEqualTo(accessKeySecret);
        assertThat(config.securityToken()).isEqualTo(securityToken);
        assertThat(config.socketTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofMillis(20000));
    }

    // - With missing credentials
    @Test
    void configWithMissingCredentials() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", "test-key"
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("oss.access.key.id and oss.access.key.secret must be defined together");

        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.secret", "test-secret"
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("oss.access.key.id and oss.access.key.secret must be defined together");
    }

    // - With empty credentials
    @Test
    void configWithEmptyCredentials() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", ""
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("oss.access.key.id value must not be empty");

        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.secret", ""
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("oss.access.key.secret value must not be empty");
    }

    // - Failing configs scenarios
    @Test
    void shouldRequireBucketName() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Missing required configuration \"oss.bucket.name\" which has no default value.");
    }

    @Test
    void shouldRequireEndpoint() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.region", REGION
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Missing required configuration \"oss.endpoint\" which has no default value.");
    }

    @Test
    void shouldRequireRegion() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Missing required configuration \"oss.region\" which has no default value.");
    }

    @Test
    void shouldValidateEndpointUrl() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", "not-a-url",
            "oss.region", REGION
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Invalid value not-a-url for configuration oss.endpoint: Must be a valid URL");
    }

    @Test
    void shouldValidateTimeoutValues() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.socket.timeout", 0L
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Invalid value 0 for configuration oss.socket.timeout: Value must be at least 1");

        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.connection.timeout", -1L
        )))
            .isInstanceOf(ConfigException.class)
            .hasMessage("Invalid value -1 for configuration oss.connection.timeout: Value must be at least 1");
    }

    @Test
    void withStorageClass() {
        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.storage.class", com.aliyun.oss.model.StorageClass.IA.toString()
        );
        final var config = new OSSStorageConfig(configs);
        assertThat(config.storageClass()).isEqualTo(com.aliyun.oss.model.StorageClass.IA);
    }

    @Test
    void withRequireStorageClassInAllowList() {
        assertThatThrownBy(() -> new OSSStorageConfig(Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.storage.class", "WrongStorageClass"
        )))
                .isInstanceOf(ConfigException.class)
                .hasMessage("Invalid value WrongStorageClass for configuration oss.storage.class: "
                        + "String must be one of: Standard, IA, Archive, ColdArchive");
    }

    @Test
    void configWithCertificateAndChecksumSettings() {
        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.access.key.id", "test-key",
            "oss.access.key.secret", "test-secret",
            "oss.certificate.check.enabled", false,
            "oss.checksum.check.enabled", true
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.certificateCheckEnabled()).isFalse();
        assertThat(config.checksumCheckEnabled()).isTrue();
    }

    @Test
    void configWithCredentialsFile() {
        final String credentialsFile = "/path/to/credentials";
        final var configs = Map.of(
            "oss.bucket.name", BUCKET_NAME,
            "oss.endpoint", ENDPOINT,
            "oss.region", REGION,
            "oss.credentials.file", credentialsFile
        );
        final var config = new OSSStorageConfig(configs);

        assertThat(config.credentialsFile()).isEqualTo(credentialsFile);
    }
}

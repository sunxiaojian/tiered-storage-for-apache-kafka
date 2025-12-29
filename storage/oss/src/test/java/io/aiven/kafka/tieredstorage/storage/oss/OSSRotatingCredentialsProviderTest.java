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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.assertj.core.util.Files;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OSSRotatingCredentialsProviderTest {

    @Test
    void testLoadCredentials() throws IOException {
        final File tempFile = Files.newTemporaryFile();
        try {
            // Write test credentials to file
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write("rsm.config.storage.oss.access.key.id=test-access-key\n");
                writer.write("rsm.config.storage.oss.secret.access.key=test-secret-key\n");
                writer.write("rsm.config.storage.oss.security.token=test-token\n");
            }

            final OSSRotatingCredentialsProvider provider =
                new OSSRotatingCredentialsProvider(tempFile.getAbsolutePath());

            try {
                final OSSRotatingCredentialsProvider.OSSCredentials credentials = provider.getCredentials();

                assertThat(credentials).isNotNull();
                assertThat(credentials.getAccessKeyId()).isEqualTo("test-access-key");
                assertThat(credentials.getAccessKeySecret()).isEqualTo("test-secret-key");
                assertThat(credentials.getSecurityToken()).isEqualTo("test-token");
            } finally {
                provider.close();
            }
        } finally {
            tempFile.delete();
        }
    }

    @Test
    void testLoadCredentialsWithoutToken() throws IOException {
        final File tempFile = Files.newTemporaryFile();
        try {
            // Write test credentials to file (without token)
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write("rsm.config.storage.oss.access.key.id=test-access-key\n");
                writer.write("rsm.config.storage.oss.secret.access.key=test-secret-key\n");
            }

            final OSSRotatingCredentialsProvider provider =
                new OSSRotatingCredentialsProvider(tempFile.getAbsolutePath());

            try {
                final OSSRotatingCredentialsProvider.OSSCredentials credentials = provider.getCredentials();

                assertThat(credentials).isNotNull();
                assertThat(credentials.getAccessKeyId()).isEqualTo("test-access-key");
                assertThat(credentials.getAccessKeySecret()).isEqualTo("test-secret-key");
                assertThat(credentials.getSecurityToken()).isNull();
            } finally {
                provider.close();
            }
        } finally {
            tempFile.delete();
        }
    }
}

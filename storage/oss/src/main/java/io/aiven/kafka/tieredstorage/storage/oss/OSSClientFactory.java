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

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;

public class OSSClientFactory {
    public static OSS build(final OSSStorageConfig config) {
        return build(config, null);
    }

    public static OSS build(final OSSStorageConfig config, final OSSRotatingCredentialsProvider credentialsProvider) {
        final ClientBuilderConfiguration clientConfig = new ClientBuilderConfiguration();

        if (config.socketTimeout() != null) {
            clientConfig.setSocketTimeout((int) config.socketTimeout().toMillis());
        }
        if (config.connectionTimeout() != null) {
            clientConfig.setConnectionTimeout((int) config.connectionTimeout().toMillis());
        }

        // Disable SSL certificate verification if configured
        if (!config.certificateCheckEnabled()) {
            clientConfig.setVerifySSLEnable(false);
        }

        // Determine credentials source
        String accessKeyId = config.accessKeyId();
        String accessKeySecret = config.accessKeySecret();
        String securityToken = config.securityToken();

        // Check if credentials provider is provided
        if (credentialsProvider != null) {
            final OSSRotatingCredentialsProvider.OSSCredentials credentials = credentialsProvider.getCredentials();
            if (credentials != null) {
                accessKeyId = credentials.getAccessKeyId();
                accessKeySecret = credentials.getAccessKeySecret();
                securityToken = credentials.getSecurityToken();
            }
        }
        // Check if credentials file is configured (fallback for backward compatibility)
        else if (config.credentialsFile() != null) {
            try (final OSSRotatingCredentialsProvider fallbackProvider =
                    new OSSRotatingCredentialsProvider(config.credentialsFile())) {
                final OSSRotatingCredentialsProvider.OSSCredentials credentials = fallbackProvider.getCredentials();
                if (credentials != null) {
                    accessKeyId = credentials.getAccessKeyId();
                    accessKeySecret = credentials.getAccessKeySecret();
                    securityToken = credentials.getSecurityToken();
                }
            } catch (final Exception e) {
                throw new IllegalArgumentException("Failed to load credentials from file: " + config.credentialsFile(), e);
            }
        }

        if (accessKeyId != null && accessKeySecret != null) {
            if (securityToken != null) {
                return new OSSClientBuilder().build(
                    config.endpoint(),
                    accessKeyId,
                    accessKeySecret,
                    securityToken,
                    clientConfig
                );
            } else {
                return new OSSClientBuilder().build(
                    config.endpoint(),
                    accessKeyId,
                    accessKeySecret,
                    clientConfig
                );
            }
        } else {
            throw new IllegalArgumentException(
                "OSS credentials not provided. Either configure access key/secret directly or use credentials file.");
        }
    }
}

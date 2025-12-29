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
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSSRotatingCredentialsProvider implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OSSRotatingCredentialsProvider.class);

    private final File credentialsFile;
    private volatile WatchService watchService;
    private java.util.concurrent.ScheduledExecutorService scheduledExecutorService;
    private final AtomicReference<OSSCredentials> currentCredentials = new AtomicReference<>();

    public OSSRotatingCredentialsProvider(final String credentialsFilePath) {
        this.credentialsFile = new File(credentialsFilePath);
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "OSS-Credentials-File-Watcher");
            thread.setDaemon(true);
            return thread;
        });
        loadCredentials();
        startFileWatcher();
    }

    private void startFileWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            final Path credentialsDir = credentialsFile.toPath().getParent();
            credentialsDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            scheduledExecutorService.scheduleWithFixedDelay(this::checkForChanges, 0, 1, TimeUnit.SECONDS);
        } catch (final IOException e) {
            LOGGER.warn("Failed to start file watcher for OSS credentials file: {}", credentialsFile, e);
        }
    }

    private void checkForChanges() {
        if (watchService == null) {
            return;
        }

        final WatchKey key = watchService.poll();
        if (key != null) {
            for (final WatchEvent<?> event : key.pollEvents()) {
                final Path changed = (Path) event.context();
                if (changed.endsWith(credentialsFile.getName())) {
                    LOGGER.info("OSS credentials file {} has been modified, reloading credentials",
                        credentialsFile.getAbsolutePath());
                    loadCredentials();
                    break;
                }
            }
            key.reset();
        }
    }

    private void loadCredentials() {
        try {
            final Properties props = Utils.loadProps(credentialsFile.getAbsolutePath());
            final String accessKeyId = props.getProperty("rsm.config.storage.oss.access.key.id");
            final String accessKeySecret = props.getProperty("rsm.config.storage.oss.secret.access.key");
            final String securityToken = props.getProperty("rsm.config.storage.oss.security.token");

            if (accessKeyId != null && accessKeySecret != null) {
                currentCredentials.set(new OSSCredentials(accessKeyId, accessKeySecret, securityToken));
                LOGGER.info("Successfully loaded OSS credentials from file: {}", credentialsFile.getAbsolutePath());
            } else {
                LOGGER.warn("OSS credentials file {} does not contain required properties", credentialsFile);
            }
        } catch (final IOException e) {
            LOGGER.error("Failed to load OSS credentials from file: {}", credentialsFile, e);
        }
    }

    public OSSCredentials getCredentials() {
        return currentCredentials.get();
    }

    @Override
    public void close() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                }
            } catch (final InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (final IOException e) {
                LOGGER.warn("Failed to close watch service", e);
            }
        }
    }

    public static class OSSCredentials {
        private final String accessKeyId;
        private final String accessKeySecret;
        private final String securityToken;

        public OSSCredentials(final String accessKeyId, final String accessKeySecret, final String securityToken) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = securityToken;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public String getSecurityToken() {
            return securityToken;
        }
    }
}

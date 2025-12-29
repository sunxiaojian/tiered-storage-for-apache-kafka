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

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Basic test to verify that OSS error metrics are properly configured and accessible.
 * This is a smoke test for the error metrics infrastructure.
 */
public class OSSStorageErrorTest {
    private static final MBeanServer MBEAN_SERVER = ManagementFactory.getPlatformMBeanServer();

    private OSSStorage storage;

    @BeforeEach
    void setUp() {
        storage = new OSSStorage();

        // Configure storage with basic settings
        final Map<String, Object> configs = Map.of(
            "oss.bucket.name", "test-bucket",
            "oss.endpoint", "http://localhost:8080",
            "oss.region", "cn-hangzhou",
            "oss.access.key.id", "test-key",
            "oss.access.key.secret", "test-secret"
        );
        storage.configure(configs);
    }

    @Test
    void errorMetricsInfrastructureIsAvailable() throws Exception {
        final ObjectName ossMetricsObjectName = ObjectName.getInstance(
            "aiven.kafka.server.tieredstorage.oss:type=oss-client-metrics");

        // Verify that the error metrics MBeans are registered and accessible
        // This is a basic smoke test to ensure the error metrics infrastructure is working

        try {
            // Check that we can access error metrics attributes
            final Object serverErrorsTotal = MBEAN_SERVER.getAttribute(ossMetricsObjectName, "server-errors-total");
            final Object configuredTimeoutErrorsTotal = MBEAN_SERVER.getAttribute(ossMetricsObjectName, "configured-timeout-errors-total");
            final Object ioErrorsTotal = MBEAN_SERVER.getAttribute(ossMetricsObjectName, "io-errors-total");
            final Object otherErrorsTotal = MBEAN_SERVER.getAttribute(ossMetricsObjectName, "other-errors-total");

            // Verify that error metrics are accessible (they should be initialized to 0)
            assert serverErrorsTotal != null;
            assert configuredTimeoutErrorsTotal != null;
            assert ioErrorsTotal != null;
            assert otherErrorsTotal != null;

        } catch (final Exception e) {
            // If error metrics are not accessible, the test should fail
            throw new AssertionError("OSS error metrics infrastructure is not properly configured", e);
        }
    }
}
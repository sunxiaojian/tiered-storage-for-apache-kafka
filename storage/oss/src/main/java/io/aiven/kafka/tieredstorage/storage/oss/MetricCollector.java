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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.metrics.KafkaMetricsContext;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.utils.Time;

import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.CONFIGURED_TIMEOUT_ERRORS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.CONFIGURED_TIMEOUT_ERRORS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.CONFIGURED_TIMEOUT_ERRORS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_REQUESTS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_REQUESTS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_REQUESTS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_TIME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_TIME_AVG_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECTS_TIME_MAX_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_REQUESTS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_REQUESTS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_REQUESTS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_TIME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_TIME_AVG_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.DELETE_OBJECT_TIME_MAX_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_REQUESTS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_REQUESTS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_REQUESTS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_TIME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_TIME_AVG_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.GET_OBJECT_TIME_MAX_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.IO_ERRORS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.IO_ERRORS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.IO_ERRORS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.METRIC_CONTEXT;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.OTHER_ERRORS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.OTHER_ERRORS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.OTHER_ERRORS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_REQUESTS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_REQUESTS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_REQUESTS_TOTAL_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_TIME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_TIME_AVG_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.PUT_OBJECT_TIME_MAX_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.SERVER_ERRORS;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.SERVER_ERRORS_RATE_METRIC_NAME;
import static io.aiven.kafka.tieredstorage.storage.oss.MetricRegistry.SERVER_ERRORS_TOTAL_METRIC_NAME;

public class MetricCollector {
    private final org.apache.kafka.common.metrics.Metrics metrics;

    private final Map<String, Sensor> requestMetrics = new HashMap<>();
    private final Map<String, Sensor> latencyMetrics = new HashMap<>();
    private final Map<String, Sensor> errorMetrics = new HashMap<>();

    public MetricCollector() {
        final MetricsReporter reporter = new JmxReporter();

        metrics = new org.apache.kafka.common.metrics.Metrics(
            new MetricConfig(), List.of(reporter), Time.SYSTEM,
            new KafkaMetricsContext(METRIC_CONTEXT)
        );

        // Create request sensors
        final Sensor getObjectRequestsSensor = createRequestsSensor(
            GET_OBJECT_REQUESTS,
            GET_OBJECT_REQUESTS_RATE_METRIC_NAME,
            GET_OBJECT_REQUESTS_TOTAL_METRIC_NAME
        );
        requestMetrics.put("GetObject", getObjectRequestsSensor);

        final Sensor putObjectRequestsSensor = createRequestsSensor(
            PUT_OBJECT_REQUESTS,
            PUT_OBJECT_REQUESTS_RATE_METRIC_NAME,
            PUT_OBJECT_REQUESTS_TOTAL_METRIC_NAME
        );
        requestMetrics.put("PutObject", putObjectRequestsSensor);

        final Sensor deleteObjectRequestsSensor = createRequestsSensor(
            DELETE_OBJECT_REQUESTS,
            DELETE_OBJECT_REQUESTS_RATE_METRIC_NAME,
            DELETE_OBJECT_REQUESTS_TOTAL_METRIC_NAME
        );
        requestMetrics.put("DeleteObject", deleteObjectRequestsSensor);

        final Sensor deleteObjectsRequestsSensor = createRequestsSensor(
            DELETE_OBJECTS_REQUESTS,
            DELETE_OBJECTS_REQUESTS_RATE_METRIC_NAME,
            DELETE_OBJECTS_REQUESTS_TOTAL_METRIC_NAME
        );
        requestMetrics.put("DeleteObjects", deleteObjectsRequestsSensor);

        // Create latency sensors
        final Sensor getObjectTimeSensor = createLatencySensor(
            GET_OBJECT_TIME,
            GET_OBJECT_TIME_AVG_METRIC_NAME,
            GET_OBJECT_TIME_MAX_METRIC_NAME
        );
        latencyMetrics.put("GetObject", getObjectTimeSensor);

        final Sensor putObjectTimeSensor = createLatencySensor(
            PUT_OBJECT_TIME,
            PUT_OBJECT_TIME_AVG_METRIC_NAME,
            PUT_OBJECT_TIME_MAX_METRIC_NAME
        );
        latencyMetrics.put("PutObject", putObjectTimeSensor);

        final Sensor deleteObjectTimeSensor = createLatencySensor(
            DELETE_OBJECT_TIME,
            DELETE_OBJECT_TIME_AVG_METRIC_NAME,
            DELETE_OBJECT_TIME_MAX_METRIC_NAME
        );
        latencyMetrics.put("DeleteObject", deleteObjectTimeSensor);

        final Sensor deleteObjectsTimeSensor = createLatencySensor(
            DELETE_OBJECTS_TIME,
            DELETE_OBJECTS_TIME_AVG_METRIC_NAME,
            DELETE_OBJECTS_TIME_MAX_METRIC_NAME
        );
        latencyMetrics.put("DeleteObjects", deleteObjectsTimeSensor);

        // Create error sensors
        final Sensor serverErrorsSensor = createRequestsSensor(
            SERVER_ERRORS,
            SERVER_ERRORS_RATE_METRIC_NAME,
            SERVER_ERRORS_TOTAL_METRIC_NAME
        );
        errorMetrics.put("ServerError", serverErrorsSensor);

        final Sensor configuredTimeoutErrorsSensor = createRequestsSensor(
            CONFIGURED_TIMEOUT_ERRORS,
            CONFIGURED_TIMEOUT_ERRORS_RATE_METRIC_NAME,
            CONFIGURED_TIMEOUT_ERRORS_TOTAL_METRIC_NAME
        );
        errorMetrics.put("TimeoutError", configuredTimeoutErrorsSensor);

        final Sensor ioErrorsSensor = createRequestsSensor(
            IO_ERRORS,
            IO_ERRORS_RATE_METRIC_NAME,
            IO_ERRORS_TOTAL_METRIC_NAME
        );
        errorMetrics.put("IOException", ioErrorsSensor);

        final Sensor otherErrorsSensor = createRequestsSensor(
            OTHER_ERRORS,
            OTHER_ERRORS_RATE_METRIC_NAME,
            OTHER_ERRORS_TOTAL_METRIC_NAME
        );
        errorMetrics.put("OtherError", otherErrorsSensor);
    }

    private Sensor createRequestsSensor(
        final String name,
        final MetricNameTemplate rateMetricName,
        final MetricNameTemplate totalMetricName
    ) {
        final Sensor sensor = metrics.sensor(name);
        sensor.add(metrics.metricInstance(rateMetricName), new Rate());
        sensor.add(metrics.metricInstance(totalMetricName), new CumulativeCount());
        return sensor;
    }

    private Sensor createLatencySensor(
        final String name,
        final MetricNameTemplate avgMetricName,
        final MetricNameTemplate maxMetricName
    ) {
        final Sensor sensor = metrics.sensor(name);
        sensor.add(metrics.metricInstance(maxMetricName), new Max());
        sensor.add(metrics.metricInstance(avgMetricName), new Avg());
        return sensor;
    }

    public void recordRequest(final String operation, final long durationMs) {
        final Sensor requestSensor = requestMetrics.get(operation);
        if (requestSensor != null) {
            requestSensor.record();
        }

        final Sensor latencySensor = latencyMetrics.get(operation);
        if (latencySensor != null) {
            latencySensor.record(durationMs);
        }
    }

    public void recordError(final String errorType) {
        final Sensor errorSensor = errorMetrics.get(errorType);
        if (errorSensor != null) {
            errorSensor.record();
        }
    }

    public void close() {
        metrics.close();
    }
}

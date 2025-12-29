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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.aiven.kafka.tieredstorage.storage.BytesRange;
import io.aiven.kafka.tieredstorage.storage.InvalidRangeException;
import io.aiven.kafka.tieredstorage.storage.KeyNotFoundException;
import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.StorageBackend;
import io.aiven.kafka.tieredstorage.storage.StorageBackendException;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.StorageClass;

public class OSSStorage implements StorageBackend {
    private static final int MAX_DELETE_OBJECTS = 1000;

    private final OSSClientFactory ossClientFactory = new OSSClientFactory();

    protected OSS ossClient;
    protected String bucketName;
    protected StorageClass storageClass = StorageClass.Standard;
    protected int partSize;
    protected MetricCollector metricCollector;
    protected OSSRotatingCredentialsProvider credentialsProvider;

    @Override
    public void configure(final java.util.Map<String, ?> configs) {
        final OSSStorageConfig config = new OSSStorageConfig(configs);

        // Create credentials provider if credentials file is configured
        if (config.credentialsFile() != null) {
            this.credentialsProvider = new OSSRotatingCredentialsProvider(config.credentialsFile());
        }

        this.ossClient = ossClientFactory.build(config, this.credentialsProvider);
        this.bucketName = config.bucketName();
        this.storageClass = config.storageClass();
        this.partSize = config.uploadPartSize();
        this.metricCollector = new MetricCollector();
    }

    @Override
    public long upload(final InputStream inputStream, final ObjectKey key) throws StorageBackendException {
        final long startTime = System.currentTimeMillis();
        final var out = ossOutputStream(key);
        try (out) {
            inputStream.transferTo(out);
        } catch (final IOException e) {
            metricCollector.recordError("IOError");
            throw new StorageBackendException("Failed to upload " + key, e);
        }
        // Record success metrics
        metricCollector.recordRequest("PutObject", System.currentTimeMillis() - startTime);
        // getting the processed bytes after close to account last flush.
        return out.processedBytes();
    }

    OSSUploadOutputStream ossOutputStream(final ObjectKey key) {
        return new OSSUploadOutputStream(bucketName, key, storageClass, partSize, ossClient);
    }

    @Override
    public InputStream fetch(final ObjectKey key) throws StorageBackendException {
        final long startTime = System.currentTimeMillis();
        final GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key.value());
        try {
            final OSSObject ossObject = ossClient.getObject(getObjectRequest);
            metricCollector.recordRequest("GetObject", System.currentTimeMillis() - startTime);
            return ossObject.getObjectContent();
        } catch (final OSSException e) {
            metricCollector.recordError("ServerError");
            if ("NoSuchKey".equals(e.getErrorCode())) {
                throw new KeyNotFoundException(this, key, e);
            } else {
                throw new StorageBackendException("Failed to fetch " + key, e);
            }
        }
    }

    @Override
    public InputStream fetch(final ObjectKey key, final BytesRange range) throws StorageBackendException {
        final long startTime = System.currentTimeMillis();
        try {
            if (range.isEmpty()) {
                metricCollector.recordRequest("GetObject", System.currentTimeMillis() - startTime);
                return InputStream.nullInputStream();
            }

            final GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key.value());
            getObjectRequest.setRange(range.firstPosition(), range.lastPosition());
            final OSSObject ossObject = ossClient.getObject(getObjectRequest);
            metricCollector.recordRequest("GetObject", System.currentTimeMillis() - startTime);
            return ossObject.getObjectContent();
        } catch (final OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                metricCollector.recordError("ServerError");
                throw new KeyNotFoundException(this, key, e);
            }
            if ("InvalidRange".equals(e.getErrorCode()) || e.getMessage().contains("InvalidRange")) {
                metricCollector.recordError("OtherError");
                throw new InvalidRangeException("Invalid range " + range, e);
            }

            metricCollector.recordError("ServerError");
            throw new StorageBackendException("Failed to fetch " + key, e);
        }
    }

    @Override
    public void delete(final ObjectKey key) throws StorageBackendException {
        final long startTime = System.currentTimeMillis();
        try {
            ossClient.deleteObject(bucketName, key.value());
            metricCollector.recordRequest("DeleteObject", System.currentTimeMillis() - startTime);
        } catch (final OSSException e) {
            metricCollector.recordError("ServerError");
            throw new StorageBackendException("Failed to delete " + key, e);
        }
    }

    @Override
    public void delete(final Set<ObjectKey> keys) throws StorageBackendException {
        final List<String> objectKeys = keys.stream()
                .map(ObjectKey::value)
                .collect(Collectors.toList());

        for (int i = 0; i < objectKeys.size(); i += MAX_DELETE_OBJECTS) {
            final var batch = objectKeys.subList(
                i,
                Math.min(i + MAX_DELETE_OBJECTS, objectKeys.size())
            );

            final long startTime = System.currentTimeMillis();
            try {
                final DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucketName);
                deleteObjectsRequest.setKeys(batch);
                ossClient.deleteObjects(deleteObjectsRequest);
                metricCollector.recordRequest("DeleteObjects", System.currentTimeMillis() - startTime);
                // Note: OSS deleteObjects doesn't return detailed error information like S3
                // If an object doesn't exist, OSS simply ignores it (idempotent behavior)
            } catch (final OSSException e) {
                metricCollector.recordError("ServerError");
                throw new StorageBackendException(String.format("Failed to delete batch with keys: %s", batch), e);
            }
        }
    }

    @Override
    public void close() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
        if (metricCollector != null) {
            metricCollector.close();
        }
        if (credentialsProvider != null) {
            credentialsProvider.close();
        }
    }

    @Override
    public String toString() {
        return "OSSStorage{"
                + "bucketName='" + bucketName + '\''
                + '}';
    }
}
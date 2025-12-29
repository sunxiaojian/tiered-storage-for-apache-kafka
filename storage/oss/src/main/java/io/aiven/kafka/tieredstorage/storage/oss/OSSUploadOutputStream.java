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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import io.aiven.kafka.tieredstorage.storage.ObjectKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadResult;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.StorageClass;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;

/**
 * OSS output stream.
 * Enable uploads to OSS with unknown size by feeding input bytes to multiple parts or single file and upload.
 *
 * <p>Requires OSS client and starts a multipart transaction when sending file over upload part size. Do not reuse.
 *
 * <p>{@link OSSUploadOutputStream} is not thread-safe.
 */
public class OSSUploadOutputStream extends OutputStream {

    private static final Logger log = LoggerFactory.getLogger(OSSUploadOutputStream.class);

    private final OSS client;
    private final ByteBuffer partBuffer;
    private final String bucketName;
    private final ObjectKey key;
    private final StorageClass storageClass;
    final int partSize;

    private String uploadId;
    private final List<PartETag> completedParts = new ArrayList<>();

    private boolean closed;
    private long processedBytes;

    public OSSUploadOutputStream(final String bucketName,
                                final ObjectKey key,
                                final int partSize,
                                final OSS client){
        this(bucketName, key, StorageClass.Standard, partSize, client);
    }

    public OSSUploadOutputStream(final String bucketName,
                                final ObjectKey key,
                                final StorageClass storageClass,
                                final int partSize,
                                final OSS client) {
        this.bucketName = bucketName;
        this.key = key;
        this.storageClass = storageClass;
        this.client = client;
        this.partSize = partSize;
        this.partBuffer = ByteBuffer.allocate(partSize);
    }

    @Override
    public void write(final int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (isClosed()) {
            throw new IllegalStateException("Already closed");
        }
        if (b.length == 0) {
            return;
        }
        try {
            final ByteBuffer inputBuffer = ByteBuffer.wrap(b, off, len);
            while (inputBuffer.hasRemaining()) {
                // copy batch to part buffer
                final int inputLimit = inputBuffer.limit();
                final int toCopy = Math.min(partBuffer.remaining(), inputBuffer.remaining());
                final int positionAfterCopying = inputBuffer.position() + toCopy;
                inputBuffer.limit(positionAfterCopying);
                partBuffer.put(inputBuffer.slice());

                // prepare current batch for next part
                inputBuffer.limit(inputLimit);
                inputBuffer.position(positionAfterCopying);

                if (!partBuffer.hasRemaining()) {
                    if (uploadId == null){
                        uploadId = createMultipartUploadRequest();
                        // this is not expected (another exception should be thrown by OSS) but adding for completeness
                        if (uploadId == null || uploadId.isEmpty()) {
                            throw new IOException("Failed to create multipart upload, uploadId is empty");
                        }
                    }
                    partBuffer.position(0);
                    partBuffer.limit(partSize);
                    flushBuffer(partBuffer.slice(), partSize, true);
                }
            }
        } catch (final RuntimeException e) {
            closed = true;
            if (multiPartUploadStarted()) {
                log.error("Failed to write to stream on upload {}, aborting transaction", uploadId, e);
                abortUpload();
            }
            throw new IOException(e);
        }
    }

    private String createMultipartUploadRequest() {
        final InitiateMultipartUploadRequest initialRequest = new InitiateMultipartUploadRequest(bucketName, key.value());
        // Set storage class if not standard
        if (storageClass != StorageClass.Standard) {
            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setHeader("x-oss-storage-class", storageClass.toString());
            initialRequest.setObjectMetadata(metadata);
        }
        final InitiateMultipartUploadResult initiateResult = client.initiateMultipartUpload(initialRequest);
        log.debug("Create new multipart upload request: {}", initiateResult.getUploadId());
        return initiateResult.getUploadId();
    }

    private boolean multiPartUploadStarted() {
        return uploadId != null;
    }

    @Override
    public void close() throws IOException {
        if (!isClosed()) {
            closed = true;
            final int lastPosition = partBuffer.position();
            if (lastPosition > 0) {
                try {
                    partBuffer.position(0);
                    partBuffer.limit(lastPosition);
                    flushBuffer(partBuffer.slice(), lastPosition, multiPartUploadStarted());
                } catch (final RuntimeException e) {
                    if (multiPartUploadStarted()) {
                        log.error("Failed to upload last part {}, aborting transaction", uploadId, e);
                        abortUpload();
                    } else {
                        log.error("Failed to upload the file {}", key, e);
                    }
                    throw new IOException(e);
                }
            }
            if (multiPartUploadStarted()) {
                completeOrAbortMultiPartUpload();
            }
        }
    }

    private void completeOrAbortMultiPartUpload() throws IOException {
        if (!completedParts.isEmpty()) {
            try {
                completeUpload();
                log.debug("Completed multipart upload {}", uploadId);
            } catch (final RuntimeException e) {
                log.error("Failed to complete multipart upload {}, aborting transaction", uploadId, e);
                abortUpload();
                throw new IOException(e);
            }
        } else {
            abortUpload();
        }
    }

    /**
     * Upload the {@code size} of {@code inputStream} as one whole single file to OSS.
     * The caller of this method should be responsible for closing the inputStream.
     */
    private void uploadAsSingleFile(final InputStream inputStream, final int size) {
        final PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key.value(), inputStream);
        // Set storage class if not standard
        if (storageClass != StorageClass.Standard) {
            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setHeader("x-oss-storage-class", storageClass.toString());
            putObjectRequest.setMetadata(metadata);
        }
        client.putObject(putObjectRequest);
    }

    public boolean isClosed() {
        return closed;
    }

    private void completeUpload() {
        final CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(
                bucketName, key.value(), uploadId, completedParts);
        final CompleteMultipartUploadResult result = client.completeMultipartUpload(completeRequest);
        log.debug("Completed multipart upload for key: {}, location: {}", key.value(), result.getLocation());
    }

    private void abortUpload() {
        final AbortMultipartUploadRequest abortRequest = new AbortMultipartUploadRequest(
                bucketName, key.value(), uploadId);
        client.abortMultipartUpload(abortRequest);
    }

    private void flushBuffer(final ByteBuffer buffer,
                             final int actualPartSize,
                             final boolean multiPartUpload) {
        //When building the retry request for fail or computing checksum for request body,
        //It needs the input stream supporting marking and resetting so that it can be read again.
        try (final InputStream in = new ByteBufferInputStream(buffer)) {
            processedBytes += actualPartSize;
            if (multiPartUpload){
                uploadPart(in, actualPartSize);
            } else {
                uploadAsSingleFile(in, actualPartSize);
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void uploadPart(final InputStream in, final int actualPartSize) {
        final int partNumber = completedParts.size() + 1;
        final UploadPartRequest uploadPartRequest = new UploadPartRequest();
        uploadPartRequest.setBucketName(bucketName);
        uploadPartRequest.setKey(key.value());
        uploadPartRequest.setUploadId(uploadId);
        uploadPartRequest.setInputStream(in);
        uploadPartRequest.setPartSize(actualPartSize);
        uploadPartRequest.setPartNumber(partNumber);

        final UploadPartResult uploadResult = client.uploadPart(uploadPartRequest);
        final PartETag partETag = uploadResult.getPartETag();
        completedParts.add(partETag);
    }

    long processedBytes() {
        return processedBytes;
    }
}

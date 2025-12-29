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
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.TestObjectKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OSSUploadOutputStreamTest {
    private static final String BUCKET_NAME = "some_bucket";
    private static final ObjectKey FILE_KEY = new TestObjectKey("some_key");
    private static final String UPLOAD_ID = "some_upload_id";

    @Mock
    OSS mockedOSS;

    @Captor
    ArgumentCaptor<InitiateMultipartUploadRequest> initiateMultipartUploadRequest;
    @Captor
    ArgumentCaptor<CompleteMultipartUploadRequest> completeMultipartUploadRequestCaptor;
    @Captor
    ArgumentCaptor<AbortMultipartUploadRequest> abortMultipartUploadRequestCaptor;
    @Captor
    ArgumentCaptor<UploadPartRequest> uploadPartRequestCaptor;
    @Captor
    ArgumentCaptor<PutObjectRequest> putObjectRequestCaptor;

    final Random random = new Random();

    @BeforeEach
    void setUp() {
        //In most cases, this stub is needed, except for a few.
        //So, add 'lenient' to bypass strict stubbing.
        lenient().when(mockedOSS.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
            .thenReturn(newInitiateMultipartUploadResult());
    }

    @Test
    void completeMultipartUploadWithDefaultStorageClass() throws IOException {
        final OSSUploadOutputStream ossUploadOutputStream = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 1, mockedOSS);
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenReturn(newUploadPartResponse("SOME_ETAG#1"));
        ossUploadOutputStream.write(1);
        verify(mockedOSS).initiateMultipartUpload(initiateMultipartUploadRequest.capture());
        assertInitiateMultipartUploadRequest(initiateMultipartUploadRequest.getValue(), StorageClass.Standard);
    }

    @Test
    void completeMultipartUploadWithNonDefaultStorageClass() throws IOException {
        final StorageClass nonDefaultStorageClass = StorageClass.IA;
        final OSSUploadOutputStream ossUploadOutputStream = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY,
            nonDefaultStorageClass, 1, mockedOSS);
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenReturn(newUploadPartResponse("SOME_ETAG#1"));
        ossUploadOutputStream.write(1);
        verify(mockedOSS).initiateMultipartUpload(initiateMultipartUploadRequest.capture());
        assertInitiateMultipartUploadRequest(initiateMultipartUploadRequest.getValue(), nonDefaultStorageClass);
    }

    @Test
    void sendAbortForAnyExceptionWhileWriting() {
        final RuntimeException testException = new RuntimeException("test");
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenThrow(testException);

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 1, mockedOSS);
        assertThatThrownBy(() -> out.write(new byte[] {1, 2, 3}))
            .isInstanceOf(IOException.class)
            .hasRootCause(testException);

        assertThat(out.isClosed()).isTrue();
        // retry close to validate no exception is thrown and the number of calls to complete/upload does not change
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(mockedOSS).abortMultipartUpload(abortMultipartUploadRequestCaptor.capture());

        assertAbortMultipartUploadRequest(abortMultipartUploadRequestCaptor.getValue());
    }

    @Test
    void sendAbortForAnyExceptionWhenClosingUpload() throws Exception {
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenReturn(newUploadPartResponse("SOME_ETAG#1"))
            .thenThrow(RuntimeException.class);

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 10, mockedOSS);

        final byte[] buffer = new byte[15];
        random.nextBytes(buffer);
        out.write(buffer, 0, buffer.length);

        assertThatThrownBy(out::close)
            .isInstanceOf(IOException.class)
            .rootCause()
            .isInstanceOf(RuntimeException.class);

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(mockedOSS).abortMultipartUpload(abortMultipartUploadRequestCaptor.capture());

        assertAbortMultipartUploadRequest(abortMultipartUploadRequestCaptor.getValue());
    }

    @Test
    void sendAbortForAnyExceptionWhenClosingComplete() throws Exception {
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenReturn(newUploadPartResponse("SOME_ETAG#1"));
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenThrow(RuntimeException.class);

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 10, mockedOSS);

        final byte[] buffer = new byte[10];
        random.nextBytes(buffer);
        out.write(buffer, 0, buffer.length);

        assertThatThrownBy(out::close)
            .isInstanceOf(IOException.class)
            .hasRootCauseInstanceOf(RuntimeException.class);

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(mockedOSS).abortMultipartUpload(abortMultipartUploadRequestCaptor.capture());

        assertAbortMultipartUploadRequest(abortMultipartUploadRequestCaptor.getValue());
    }

    @Test
    void writesOnePartUploadByte() throws Exception {
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenReturn(newUploadPartResponse("SOME_ETAG"));
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenReturn(new CompleteMultipartUploadResult());

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 1, mockedOSS);
        out.write(new byte[] {1});
        out.close();

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS).uploadPart(uploadPartRequestCaptor.capture());
        verify(mockedOSS).completeMultipartUpload(completeMultipartUploadRequestCaptor.capture());

        assertUploadPartRequest(
            uploadPartRequestCaptor.getValue(),
            1,
            1
        );
        assertCompleteMultipartUploadRequest(
            completeMultipartUploadRequestCaptor.getValue(),
            List.of(new PartETag(1, "SOME_ETAG"))
        );
    }

    @Test
    void writesSmallFile() throws Exception {
        when(mockedOSS.putObject(any(PutObjectRequest.class)))
                .thenReturn(new com.aliyun.oss.model.PutObjectResult());

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 2, mockedOSS);
        out.write(new byte[] {1});
        out.close();

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS).putObject(putObjectRequestCaptor.capture());
        verify(mockedOSS, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(mockedOSS, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS, never()).uploadPart(any(UploadPartRequest.class));
    }

    @Test
    void writesMultipleMessages() throws Exception {
        final int bufferSize = 10;

        final List<UploadPartRequest> uploadPartRequests = new ArrayList<>();
        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenAnswer(invocation -> {
                final UploadPartRequest upload = invocation.getArgument(0);
                uploadPartRequests.add(upload);

                return newUploadPartResponse("SOME_ETAG#" + upload.getPartNumber());
            });
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenReturn(new CompleteMultipartUploadResult());

        final List<byte[]> expectedMessagesList = new ArrayList<>();
        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, bufferSize, mockedOSS);
        for (int i = 0; i < 3; i++) {
            final byte[] message = new byte[bufferSize];
            random.nextBytes(message);
            out.write(message, 0, message.length);
            expectedMessagesList.add(message);
        }
        out.close();

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS, times(3)).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS).completeMultipartUpload(completeMultipartUploadRequestCaptor.capture());

        int counter = 0;
        for (final byte[] expectedMessage : expectedMessagesList) {
            assertUploadPartRequest(
                uploadPartRequests.get(counter),
                counter + 1,
                expectedMessage.length);
            counter++;
        }
        assertCompleteMultipartUploadRequest(
            completeMultipartUploadRequestCaptor.getValue(),
            List.of(
                new PartETag(1, "SOME_ETAG#1"),
                new PartETag(2, "SOME_ETAG#2"),
                new PartETag(3, "SOME_ETAG#3")
            )
        );
    }

    @Test
    void writesTailMessages() throws Exception {
        final int messageSize = 20;

        final List<UploadPartRequest> uploadPartRequests = new ArrayList<>();

        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenAnswer(invocation -> {
                final UploadPartRequest upload = invocation.getArgument(0);
                uploadPartRequests.add(upload);

                return newUploadPartResponse("SOME_ETAG#" + upload.getPartNumber());
            });
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenReturn(new CompleteMultipartUploadResult());
        final byte[] expectedFullMessage = new byte[messageSize + 10];
        final byte[] expectedTailMessage = new byte[10];

        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, messageSize + 10, mockedOSS);
        final byte[] message = new byte[messageSize];
        random.nextBytes(message);
        out.write(message);
        System.arraycopy(message, 0, expectedFullMessage, 0, message.length);
        random.nextBytes(message);
        out.write(message);
        System.arraycopy(message, 0, expectedFullMessage, 20, 10);
        System.arraycopy(message, 10, expectedTailMessage, 0, 10);
        out.close();

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        assertUploadPartRequest(uploadPartRequests.get(0), 1, 30);
        assertUploadPartRequest(uploadPartRequests.get(1), 2, 10);

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS, times(2)).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS, times(1)).completeMultipartUpload(completeMultipartUploadRequestCaptor.capture());
        assertCompleteMultipartUploadRequest(
            completeMultipartUploadRequestCaptor.getValue(),
            List.of(
                new PartETag(1, "SOME_ETAG#1"),
                new PartETag(2, "SOME_ETAG#2")
            )
        );
    }

    @Test
    void writesTailMessagesFromInputStreamBufferSmallerThanSize() throws Exception {
        final int messageSize = 10;

        final List<UploadPartRequest> uploadPartRequests = new ArrayList<>();

        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenAnswer(invocation -> {
                final UploadPartRequest upload = invocation.getArgument(0);
                uploadPartRequests.add(upload);

                return newUploadPartResponse("SOME_ETAG#" + upload.getPartNumber());
            });
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenReturn(new CompleteMultipartUploadResult());
        final byte[] expectedFullMessage0 = new byte[8];
        final byte[] expectedFullMessage1 = new byte[8];
        final byte[] expectedTailMessage = new byte[4];

        final byte[] message0 = new byte[messageSize];
        random.nextBytes(message0);
        System.arraycopy(message0, 0, expectedFullMessage0, 0, 8);
        System.arraycopy(message0, 8, expectedFullMessage1, 0, 2);
        final byte[] message1 = new byte[messageSize];
        random.nextBytes(message1);
        System.arraycopy(message1, 0, expectedFullMessage1, 2, 6);
        System.arraycopy(message1, 6, expectedTailMessage, 0, 4);
        final var in = new SequenceInputStream(new ByteArrayInputStream(message0), new ByteArrayInputStream(message1));
        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 8, mockedOSS);
        try (in; out) {
            in.transferTo(out);
        }

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        assertUploadPartRequest(uploadPartRequests.get(0), 1, 8);
        assertUploadPartRequest(uploadPartRequests.get(1), 2, 8);
        assertUploadPartRequest(uploadPartRequests.get(2), 3, 4);

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS, times(3)).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS, times(1)).completeMultipartUpload(completeMultipartUploadRequestCaptor.capture());
        assertCompleteMultipartUploadRequest(
            completeMultipartUploadRequestCaptor.getValue(),
            List.of(
                new PartETag(1, "SOME_ETAG#1"),
                new PartETag(2, "SOME_ETAG#2"),
                new PartETag(3, "SOME_ETAG#3")
            )
        );
    }

    @Test
    void writesTailMessagesFromInputStreamSizeSmallerThanBuffer() throws Exception {
        final int messageSize = 10;

        final List<UploadPartRequest> uploadPartRequests = new ArrayList<>();

        when(mockedOSS.uploadPart(any(UploadPartRequest.class)))
            .thenAnswer(invocation -> {
                final UploadPartRequest upload = invocation.getArgument(0);
                uploadPartRequests.add(upload);

                return newUploadPartResponse("SOME_ETAG#" + upload.getPartNumber());
            });
        when(mockedOSS.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
            .thenReturn(new CompleteMultipartUploadResult());
        final byte[] expectedFullMessage0 = new byte[12];
        final byte[] expectedTailMessage = new byte[8];

        final byte[] message0 = new byte[messageSize];
        random.nextBytes(message0);
        System.arraycopy(message0, 0, expectedFullMessage0, 0, 10);
        final byte[] message1 = new byte[messageSize];
        random.nextBytes(message1);
        System.arraycopy(message1, 0, expectedFullMessage0, 10, 2);
        System.arraycopy(message1, 2, expectedTailMessage, 0, 8);
        final var in = new SequenceInputStream(new ByteArrayInputStream(message0), new ByteArrayInputStream(message1));
        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 12, mockedOSS);
        try (in; out) {
            in.transferTo(out);
        }

        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        assertUploadPartRequest(uploadPartRequests.get(0), 1, 12);
        assertUploadPartRequest(uploadPartRequests.get(1), 2, 8);

        verify(mockedOSS).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(mockedOSS, times(2)).uploadPart(any(UploadPartRequest.class));
        verify(mockedOSS, times(1)).completeMultipartUpload(completeMultipartUploadRequestCaptor.capture());
        assertCompleteMultipartUploadRequest(
            completeMultipartUploadRequestCaptor.getValue(),
            List.of(
                new PartETag(1, "SOME_ETAG#1"),
                new PartETag(2, "SOME_ETAG#2")
            )
        );
    }

    @Test
    void closeNormallyIfNoWritingHappened() throws IOException {
        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 100, mockedOSS);
        out.close();

        verify(mockedOSS, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();
    }

    @Test
    void failWhenUploadingPartAfterStreamIsClosed() throws IOException {
        final var out = new OSSUploadOutputStream(BUCKET_NAME, FILE_KEY, 100, mockedOSS);
        out.close();

        verify(mockedOSS, never()).abortMultipartUpload(abortMultipartUploadRequestCaptor.capture());
        assertThat(out.isClosed()).isTrue();
        assertThatCode(out::close).doesNotThrowAnyException();

        assertThatThrownBy(() -> out.write(1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Already closed");
    }

    private static InitiateMultipartUploadResult newInitiateMultipartUploadResult() {
        final InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId(UPLOAD_ID);
        return result;
    }

    private static UploadPartResult newUploadPartResponse(final String etag) {
        final UploadPartResult result = new UploadPartResult();
        result.setETag(etag);
        result.setPartNumber(1); // This will be overridden by the actual part number
        return result;
    }

    private static void assertInitiateMultipartUploadRequest(final InitiateMultipartUploadRequest request,
                                                           final StorageClass expectedStorageClass) {
        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getKey()).isEqualTo(FILE_KEY.value());
        if (expectedStorageClass != StorageClass.Standard) {
            assertThat(request.getObjectMetadata()).isNotNull();
            // For non-standard storage class, ObjectMetadata should be set
        } else {
            // For Standard storage class, ObjectMetadata should be null
            assertThat(request.getObjectMetadata()).isNull();
        }
    }

    private static void assertUploadPartRequest(final UploadPartRequest uploadPartRequest,
                                                final int expectedPartNumber,
                                                final int expectedPartSize) {
        assertThat(uploadPartRequest.getUploadId()).isEqualTo(UPLOAD_ID);
        assertThat(uploadPartRequest.getPartNumber()).isEqualTo(expectedPartNumber);
        assertThat(uploadPartRequest.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(uploadPartRequest.getKey()).isEqualTo(FILE_KEY.value());
        assertThat(uploadPartRequest.getPartSize()).isEqualTo(expectedPartSize);
    }

    private static void assertCompleteMultipartUploadRequest(final CompleteMultipartUploadRequest request,
                                                             final List<PartETag> expectedETags) {
        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getKey()).isEqualTo(FILE_KEY.value());
        assertThat(request.getUploadId()).isEqualTo(UPLOAD_ID);
        assertThat(request.getPartETags()).hasSize(expectedETags.size());
    }

    private static void assertAbortMultipartUploadRequest(final AbortMultipartUploadRequest request) {
        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getKey()).isEqualTo(FILE_KEY.value());
        assertThat(request.getUploadId()).isEqualTo(UPLOAD_ID);
    }
}

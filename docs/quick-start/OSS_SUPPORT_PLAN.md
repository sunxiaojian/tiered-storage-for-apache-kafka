# 阿里云OSS支持方案

## 📋 目录

1. [概述](#概述)
2. [OSS与S3的兼容性分析](#oss与s3的兼容性分析)
3. [实现方案](#实现方案)
4. [详细设计](#详细设计)
5. [实施步骤](#实施步骤)
6. [测试方案](#测试方案)

---

## 🎯 概述

### 目标
为Tiered Storage for Apache Kafka项目添加阿里云OSS（Object Storage Service）支持，使其能够作为Kafka分层存储的后端。

### 背景
- 当前项目支持：AWS S3、Google Cloud Storage、Azure Blob Storage
- OSS在中国市场广泛使用
- OSS与S3 API高度兼容，但存在一些差异

### 价值
- 支持中国用户使用阿里云OSS
- 降低存储成本（相比AWS S3）
- 提高数据本地化能力

---

## 🔍 OSS与S3的兼容性分析

### 兼容的功能

| 功能 | S3 | OSS | 兼容性 |
|------|----|-----|--------|
| 多部分上传 | ✅ | ✅ | 完全兼容 |
| 范围查询 | ✅ | ✅ | 完全兼容 |
| 对象删除 | ✅ | ✅ | 完全兼容 |
| 批量删除 | ✅ | ✅ | 完全兼容 |
| 存储类型 | ✅ | ✅ | 部分兼容（类型不同） |
| 元数据 | ✅ | ✅ | 完全兼容 |

### 差异点

#### 1. 存储类型（Storage Class）

**S3存储类型：**
- STANDARD
- STANDARD_IA
- ONEZONE_IA
- REDUCED_REDUNDANCY
- GLACIER
- DEEP_ARCHIVE

**OSS存储类型：**
- Standard（标准存储）
- IA（低频访问）
- Archive（归档存储）
- Cold Archive（冷归档存储）

**解决方案：** 创建映射表，将OSS存储类型映射到S3存储类型。

#### 2. 端点URL格式

**S3：**
```
https://s3.{region}.amazonaws.com
https://{bucket}.s3.{region}.amazonaws.com
```

**OSS：**
```
https://oss-{region}.aliyuncs.com
https://{bucket}.oss-{region}.aliyuncs.com
```

**解决方案：** 使用自定义端点URL配置。

#### 3. 区域（Region）

**S3区域：** us-east-1, eu-west-1等

**OSS区域：** cn-hangzhou, cn-beijing等

**解决方案：** 直接使用OSS区域名称。

#### 4. 认证方式

**相同点：**
- 都支持AccessKey/SecretKey
- 都支持STS临时凭证
- 都支持IAM角色

**差异点：**
- OSS使用阿里云RAM（Resource Access Management）
- S3使用AWS IAM

**解决方案：** 实现OSS特定的凭证提供者。

---

## 🏗️ 实现方案

### 方案选择

#### 方案A：基于S3 SDK（推荐）
**优点：**
- OSS支持S3兼容API
- 可以复用大部分S3代码
- 开发工作量小

**缺点：**
- 需要处理兼容性问题
- 某些OSS特定功能无法使用

#### 方案B：使用OSS SDK
**优点：**
- 完全支持OSS特性
- 更好的性能优化

**缺点：**
- 需要重写大部分代码
- 与现有架构差异大

**推荐方案：** 方案A（基于S3 SDK）

### 架构设计

```
┌─────────────────────────────────────┐
│   RemoteStorageManager               │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   StorageBackend Interface           │
└──────────────┬──────────────────────┘
               │
    ┌───────────┴───────────┐
    │                       │
┌───▼────┐          ┌──────▼──────┐
│ S3Storage│          │ OssStorage  │
└─────────┘          └────────────┘
    │                      │
    └──────────┬───────────┘
               │
    ┌──────────▼──────────┐
    │  AWS S3 SDK         │
    │  (OSS兼容模式)      │
    └─────────────────────┘
```

---

## 📝 详细设计

### 1. 目录结构

```
storage/
├── oss/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   └── java/
│       │       └── io/
│       │           └── aiven/
│       │               └── kafka/
│       │                   └── tieredstorage/
│       │                       └── storage/
│       │                           └── oss/
│       │                               ├── OssStorage.java
│       │                               ├── OssStorageConfig.java
│       │                               ├── OssClientBuilder.java
│       │                               ├── OssUploadOutputStream.java
│       │                               ├── OssRotatingCredentialsProvider.java
│       │                               ├── MetricCollector.java
│       │                               └── MetricRegistry.java
│       ├── test/
│       │   └── java/
│       │       └── ...
│       └── integration-test/
│           └── java/
│               └── ...
```

### 2. 核心类设计

#### 2.1 OssStorage.java

```java
package io.aiven.kafka.tieredstorage.storage.oss;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.aiven.kafka.tieredstorage.storage.BytesRange;
import io.aiven.kafka.tieredstorage.storage.InvalidRangeException;
import io.aiven.kafka.tieredstorage.storage.KeyNotFoundException;
import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.StorageBackend;
import io.aiven.kafka.tieredstorage.storage.StorageBackendException;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * 阿里云OSS存储实现
 * 
 * 基于AWS S3 SDK，通过配置OSS端点实现兼容
 */
public class OssStorage implements StorageBackend {
    private static final int MAX_DELETE_OBJECTS = 1000;
    
    private S3Client s3Client;
    private String bucketName;
    private StorageClass storageClass;
    private int partSize;
    
    @Override
    public void configure(final Map<String, ?> configs) {
        final OssStorageConfig config = new OssStorageConfig(configs);
        this.s3Client = OssClientBuilder.build(config);
        this.bucketName = config.bucketName();
        this.storageClass = mapOssStorageClass(config.storageClass());
        this.partSize = config.uploadPartSize();
    }
    
    /**
     * 将OSS存储类型映射到S3存储类型
     */
    private StorageClass mapOssStorageClass(String ossStorageClass) {
        switch (ossStorageClass.toUpperCase()) {
            case "STANDARD":
                return StorageClass.STANDARD;
            case "IA":
            case "INFREQUENT_ACCESS":
                return StorageClass.STANDARD_IA;
            case "ARCHIVE":
                return StorageClass.GLACIER;
            case "COLD_ARCHIVE":
                return StorageClass.DEEP_ARCHIVE;
            default:
                return StorageClass.STANDARD;
        }
    }
    
    @Override
    public long upload(final InputStream inputStream, final ObjectKey key) 
            throws StorageBackendException {
        final var out = ossOutputStream(key);
        try (out) {
            inputStream.transferTo(out);
        } catch (final IOException e) {
            throw new StorageBackendException("Failed to upload " + key, e);
        }
        return out.processedBytes();
    }
    
    OssUploadOutputStream ossOutputStream(final ObjectKey key) {
        return new OssUploadOutputStream(
            bucketName, key, storageClass, partSize, s3Client);
    }
    
    @Override
    public InputStream fetch(final ObjectKey key, final BytesRange range) 
            throws StorageBackendException {
        try {
            final GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key.value())
                .range("bytes=" + range.from() + "-" + range.to())
                .build();
            
            return s3Client.getObject(getObjectRequest);
        } catch (final AwsServiceException e) {
            if (e.statusCode() == 404) {
                throw new KeyNotFoundException(key, e);
            }
            throw new StorageBackendException("Failed to fetch " + key, e);
        } catch (final SdkClientException e) {
            throw new StorageBackendException("Failed to fetch " + key, e);
        }
    }
    
    @Override
    public void delete(final ObjectKey key) throws StorageBackendException {
        try {
            final var deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key.value())
                .build();
            s3Client.deleteObject(deleteRequest);
        } catch (final SdkClientException e) {
            throw new StorageBackendException("Failed to delete " + key, e);
        }
    }
    
    @Override
    public void delete(final Set<ObjectKey> keys) throws StorageBackendException {
        final List<ObjectKey> objectKeys = new ArrayList<>(keys);
        
        for (int i = 0; i < objectKeys.size(); i += MAX_DELETE_OBJECTS) {
            final var batch = objectKeys.subList(
                i,
                Math.min(i + MAX_DELETE_OBJECTS, objectKeys.size())
            );
            
            final List<ObjectIdentifier> objectIds = batch.stream()
                .map(k -> ObjectIdentifier.builder().key(k.value()).build())
                .collect(Collectors.toList());
            
            try {
                final DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectIds).build())
                    .build();
                
                final DeleteObjectsResponse response = s3Client.deleteObjects(deleteRequest);
                
                if (!response.errors().isEmpty()) {
                    throw new StorageBackendException(
                        "Failed to delete some objects: " + response.errors());
                }
            } catch (final SdkClientException e) {
                throw new StorageBackendException("Failed to delete objects", e);
            }
        }
    }
    
    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}
```

#### 2.2 OssStorageConfig.java

```java
package io.aiven.kafka.tieredstorage.storage.oss;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import io.aiven.kafka.tieredstorage.config.validators.NonEmptyPassword;
import io.aiven.kafka.tieredstorage.config.validators.Null;
import io.aiven.kafka.tieredstorage.config.validators.Subclass;
import io.aiven.kafka.tieredstorage.config.validators.ValidUrl;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * OSS存储配置类
 */
public class OssStorageConfig extends AbstractConfig {
    
    public static final String OSS_BUCKET_NAME_CONFIG = "oss.bucket.name";
    private static final String OSS_BUCKET_NAME_DOC = 
        "OSS bucket name to store log segments";
    
    public static final String OSS_ENDPOINT_CONFIG = "oss.endpoint";
    private static final String OSS_ENDPOINT_DOC = 
        "OSS endpoint URL. Format: https://oss-{region}.aliyuncs.com";
    
    public static final String OSS_REGION_CONFIG = "oss.region";
    private static final String OSS_REGION_DOC = 
        "OSS region (e.g., cn-hangzhou, cn-beijing)";
    
    public static final String OSS_STORAGE_CLASS_CONFIG = "oss.storage.class";
    private static final String OSS_STORAGE_CLASS_DOC = 
        "OSS storage class: Standard, IA, Archive, ColdArchive";
    static final String OSS_STORAGE_CLASS_DEFAULT = "Standard";
    
    public static final String OSS_ACCESS_KEY_ID_CONFIG = "oss.access.key.id";
    private static final String OSS_ACCESS_KEY_ID_DOC = 
        "OSS access key ID";
    
    public static final String OSS_ACCESS_KEY_SECRET_CONFIG = "oss.access.key.secret";
    private static final String OSS_ACCESS_KEY_SECRET_DOC = 
        "OSS access key secret";
    
    public static final String OSS_STS_TOKEN_CONFIG = "oss.sts.token";
    private static final String OSS_STS_TOKEN_DOC = 
        "OSS STS token (for temporary credentials)";
    
    public static final String OSS_CREDENTIALS_FILE_CONFIG = "oss.credentials.file";
    private static final String OSS_CREDENTIALS_FILE_DOC = 
        "File path containing OSS credentials (can be updated at runtime)";
    
    public static final String OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG = 
        "oss.multipart.upload.part.size";
    private static final String OSS_MULTIPART_UPLOAD_PART_SIZE_DOC = 
        "Size of parts in bytes for multipart upload. " +
        "Valid values: between 100KB and 5GB. Default: 25MB";
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_MIN = 100 * 1024; // 100KB
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_MAX = 5 * 1024 * 1024 * 1024; // 5GB
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_DEFAULT = 25 * 1024 * 1024; // 25MB
    
    public static final String OSS_API_CALL_TIMEOUT_CONFIG = "oss.api.call.timeout";
    private static final String OSS_API_CALL_TIMEOUT_DOC = 
        "OSS API call timeout in milliseconds (including retries)";
    
    public static final String OSS_API_CALL_ATTEMPT_TIMEOUT_CONFIG = 
        "oss.api.call.attempt.timeout";
    private static final String OSS_API_CALL_ATTEMPT_TIMEOUT_DOC = 
        "OSS API call attempt timeout in milliseconds (single retry)";
    
    public static final String OSS_PATH_STYLE_ACCESS_ENABLED_CONFIG = 
        "oss.path.style.access.enabled";
    private static final String OSS_PATH_STYLE_ACCESS_ENABLED_DOC = 
        "Whether to use path style access. OSS uses virtual host style by default.";
    
    public static ConfigDef configDef() {
        return new ConfigDef()
            .define(
                OSS_BUCKET_NAME_CONFIG,
                ConfigDef.Type.STRING,
                ConfigDef.NO_DEFAULT_VALUE,
                new ConfigDef.NonEmptyString(),
                ConfigDef.Importance.HIGH,
                OSS_BUCKET_NAME_DOC)
            .define(
                OSS_ENDPOINT_CONFIG,
                ConfigDef.Type.STRING,
                ConfigDef.NO_DEFAULT_VALUE,
                new ValidUrl(),
                ConfigDef.Importance.HIGH,
                OSS_ENDPOINT_DOC)
            .define(
                OSS_REGION_CONFIG,
                ConfigDef.Type.STRING,
                ConfigDef.NO_DEFAULT_VALUE,
                ConfigDef.Importance.MEDIUM,
                OSS_REGION_DOC)
            .define(
                OSS_STORAGE_CLASS_CONFIG,
                ConfigDef.Type.STRING,
                OSS_STORAGE_CLASS_DEFAULT,
                ConfigDef.ValidString.in("Standard", "IA", "Archive", "ColdArchive"),
                ConfigDef.Importance.LOW,
                OSS_STORAGE_CLASS_DOC)
            .define(
                OSS_ACCESS_KEY_ID_CONFIG,
                ConfigDef.Type.PASSWORD,
                null,
                new NonEmptyPassword(),
                ConfigDef.Importance.MEDIUM,
                OSS_ACCESS_KEY_ID_DOC)
            .define(
                OSS_ACCESS_KEY_SECRET_CONFIG,
                ConfigDef.Type.PASSWORD,
                null,
                new NonEmptyPassword(),
                ConfigDef.Importance.MEDIUM,
                OSS_ACCESS_KEY_SECRET_DOC)
            .define(
                OSS_STS_TOKEN_CONFIG,
                ConfigDef.Type.PASSWORD,
                null,
                ConfigDef.Importance.LOW,
                OSS_STS_TOKEN_DOC)
            .define(
                OSS_CREDENTIALS_FILE_CONFIG,
                ConfigDef.Type.STRING,
                null,
                ConfigDef.Importance.MEDIUM,
                OSS_CREDENTIALS_FILE_DOC)
            .define(
                OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG,
                ConfigDef.Type.INT,
                OSS_MULTIPART_UPLOAD_PART_SIZE_DEFAULT,
                ConfigDef.Range.between(
                    OSS_MULTIPART_UPLOAD_PART_SIZE_MIN, 
                    OSS_MULTIPART_UPLOAD_PART_SIZE_MAX),
                ConfigDef.Importance.MEDIUM,
                OSS_MULTIPART_UPLOAD_PART_SIZE_DOC)
            .define(
                OSS_API_CALL_TIMEOUT_CONFIG,
                ConfigDef.Type.LONG,
                null,
                Null.or(ConfigDef.Range.between(1, Long.MAX_VALUE)),
                ConfigDef.Importance.LOW,
                OSS_API_CALL_TIMEOUT_DOC)
            .define(
                OSS_API_CALL_ATTEMPT_TIMEOUT_CONFIG,
                ConfigDef.Type.LONG,
                null,
                Null.or(ConfigDef.Range.between(1, Long.MAX_VALUE)),
                ConfigDef.Importance.LOW,
                OSS_API_CALL_ATTEMPT_TIMEOUT_DOC)
            .define(
                OSS_PATH_STYLE_ACCESS_ENABLED_CONFIG,
                ConfigDef.Type.BOOLEAN,
                false,
                ConfigDef.Importance.LOW,
                OSS_PATH_STYLE_ACCESS_ENABLED_DOC);
    }
    
    public OssStorageConfig(final Map<String, ?> props) {
        super(configDef(), props);
        validate();
    }
    
    private void validate() {
        // 验证AccessKey和SecretKey必须同时提供
        if (getPassword(OSS_ACCESS_KEY_ID_CONFIG) != null
            ^ getPassword(OSS_ACCESS_KEY_SECRET_CONFIG) != null) {
            throw new ConfigException(
                OSS_ACCESS_KEY_ID_CONFIG + " and " + OSS_ACCESS_KEY_SECRET_CONFIG
                + " must be defined together");
        }
        
        // 验证不能同时使用静态凭证和凭证文件
        if (getPassword(OSS_ACCESS_KEY_ID_CONFIG) != null
            && getString(OSS_CREDENTIALS_FILE_CONFIG) != null) {
            throw new ConfigException(
                "Cannot use both static credentials and credentials file");
        }
        
        // 验证端点格式
        String endpoint = getString(OSS_ENDPOINT_CONFIG);
        if (endpoint != null && !endpoint.startsWith("http")) {
            throw new ConfigException(
                "OSS endpoint must start with http:// or https://");
        }
    }
    
    public String bucketName() {
        return getString(OSS_BUCKET_NAME_CONFIG);
    }
    
    public URI endpoint() {
        return URI.create(getString(OSS_ENDPOINT_CONFIG));
    }
    
    public Region region() {
        // OSS区域需要映射到AWS区域格式
        // 这里使用一个虚拟区域，实际通过endpoint访问
        return Region.of("us-east-1"); // 占位符
    }
    
    public String storageClass() {
        return getString(OSS_STORAGE_CLASS_CONFIG);
    }
    
    public AwsCredentialsProvider credentialsProvider() {
        // 如果有STS token，使用会话凭证
        if (getPassword(OSS_STS_TOKEN_CONFIG) != null) {
            AwsCredentials credentials = AwsSessionCredentials.create(
                getPassword(OSS_ACCESS_KEY_ID_CONFIG).value(),
                getPassword(OSS_ACCESS_KEY_SECRET_CONFIG).value(),
                getPassword(OSS_STS_TOKEN_CONFIG).value()
            );
            return StaticCredentialsProvider.create(credentials);
        }
        
        // 如果有静态凭证
        if (getPassword(OSS_ACCESS_KEY_ID_CONFIG) != null) {
            AwsCredentials credentials = AwsBasicCredentials.create(
                getPassword(OSS_ACCESS_KEY_ID_CONFIG).value(),
                getPassword(OSS_ACCESS_KEY_SECRET_CONFIG).value()
            );
            return StaticCredentialsProvider.create(credentials);
        }
        
        // 如果有凭证文件
        if (getString(OSS_CREDENTIALS_FILE_CONFIG) != null) {
            return new OssRotatingCredentialsProvider(
                getString(OSS_CREDENTIALS_FILE_CONFIG));
        }
        
        // 默认使用环境变量或配置文件
        return null;
    }
    
    public int uploadPartSize() {
        return getInt(OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG);
    }
    
    public Duration apiCallTimeout() {
        Long value = getLong(OSS_API_CALL_TIMEOUT_CONFIG);
        return value != null ? Duration.ofMillis(value) : null;
    }
    
    public Duration apiCallAttemptTimeout() {
        Long value = getLong(OSS_API_CALL_ATTEMPT_TIMEOUT_CONFIG);
        return value != null ? Duration.ofMillis(value) : null;
    }
    
    public Boolean pathStyleAccessEnabled() {
        return getBoolean(OSS_PATH_STYLE_ACCESS_ENABLED_CONFIG);
    }
}
```

#### 2.3 OssClientBuilder.java

```java
package io.aiven.kafka.tieredstorage.storage.oss;

import java.net.URI;
import java.time.Duration;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * OSS客户端构建器
 * 配置S3 SDK以兼容OSS
 */
public class OssClientBuilder {
    
    public static S3Client build(OssStorageConfig config) {
        // 构建客户端配置
        S3Configuration.Builder s3ConfigBuilder = S3Configuration.builder()
            .pathStyleAccessEnabled(config.pathStyleAccessEnabled());
        
        // 构建客户端覆盖配置
        ClientOverrideConfiguration.Builder clientConfigBuilder = 
            ClientOverrideConfiguration.builder();
        
        // 配置超时
        if (config.apiCallTimeout() != null) {
            clientConfigBuilder.apiCallTimeout(config.apiCallTimeout());
        }
        if (config.apiCallAttemptTimeout() != null) {
            clientConfigBuilder.apiCallAttemptTimeout(config.apiCallAttemptTimeout());
        }
        
        // 配置重试策略
        RetryPolicy retryPolicy = RetryPolicy.builder()
            .numRetries(3)
            .build();
        clientConfigBuilder.retryPolicy(retryPolicy);
        
        // 构建S3客户端
        S3Client.Builder s3ClientBuilder = S3Client.builder()
            .region(config.region())
            .endpointOverride(config.endpoint())
            .serviceConfiguration(s3ConfigBuilder.build())
            .overrideConfiguration(clientConfigBuilder.build());
        
        // 配置凭证提供者
        AwsCredentialsProvider credentialsProvider = config.credentialsProvider();
        if (credentialsProvider != null) {
            s3ClientBuilder.credentialsProvider(credentialsProvider);
        }
        
        return s3ClientBuilder.build();
    }
}
```

#### 2.4 OssUploadOutputStream.java

```java
package io.aiven.kafka.tieredstorage.storage.oss;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import io.aiven.kafka.tieredstorage.storage.ObjectKey;
import io.aiven.kafka.tieredstorage.storage.StorageBackendException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * OSS多部分上传输出流
 * 复用S3UploadOutputStream的实现逻辑
 */
public class OssUploadOutputStream extends OutputStream {
    
    private final String bucketName;
    private final ObjectKey key;
    private final StorageClass storageClass;
    private final int partSize;
    private final S3Client s3Client;
    
    private String uploadId;
    private final List<CompletedPart> completedParts = new ArrayList<>();
    private byte[] buffer;
    private int bufferPosition;
    private long processedBytes;
    
    public OssUploadOutputStream(
        String bucketName,
        ObjectKey key,
        StorageClass storageClass,
        int partSize,
        S3Client s3Client) {
        this.bucketName = bucketName;
        this.key = key;
        this.storageClass = storageClass;
        this.partSize = partSize;
        this.s3Client = s3Client;
        this.buffer = new byte[partSize];
    }
    
    @Override
    public void write(int b) throws IOException {
        if (bufferPosition >= buffer.length) {
            flushBuffer();
        }
        buffer[bufferPosition++] = (byte) b;
        processedBytes++;
    }
    
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int offset = off;
        
        while (remaining > 0) {
            int toWrite = Math.min(remaining, buffer.length - bufferPosition);
            System.arraycopy(b, offset, buffer, bufferPosition, toWrite);
            bufferPosition += toWrite;
            remaining -= toWrite;
            offset += toWrite;
            processedBytes += toWrite;
            
            if (bufferPosition >= buffer.length) {
                flushBuffer();
            }
        }
    }
    
    private void flushBuffer() throws IOException {
        if (bufferPosition == 0) {
            return;
        }
        
        if (uploadId == null) {
            initMultipartUpload();
        }
        
        byte[] partData = new byte[bufferPosition];
        System.arraycopy(buffer, 0, partData, 0, bufferPosition);
        
        int partNumber = completedParts.size() + 1;
        uploadPart(partNumber, partData);
        
        bufferPosition = 0;
    }
    
    private void initMultipartUpload() {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
            .bucket(bucketName)
            .key(key.value())
            .storageClass(storageClass)
            .build();
        
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
        uploadId = response.uploadId();
    }
    
    private void uploadPart(int partNumber, byte[] data) throws IOException {
        UploadPartRequest request = UploadPartRequest.builder()
            .bucket(bucketName)
            .key(key.value())
            .uploadId(uploadId)
            .partNumber(partNumber)
            .build();
        
        UploadPartResponse response = s3Client.uploadPart(
            request, RequestBody.fromBytes(data));
        
        CompletedPart part = CompletedPart.builder()
            .partNumber(partNumber)
            .eTag(response.eTag())
            .build();
        
        completedParts.add(part);
    }
    
    @Override
    public void close() throws IOException {
        try {
            if (uploadId != null) {
                // 上传最后一个分片
                if (bufferPosition > 0) {
                    flushBuffer();
                }
                
                // 完成多部分上传
                completeMultipartUpload();
            } else if (processedBytes > 0) {
                // 小文件直接上传
                uploadSinglePart();
            }
        } finally {
            buffer = null;
        }
    }
    
    private void completeMultipartUpload() {
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
            .parts(completedParts)
            .build();
        
        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
            .bucket(bucketName)
            .key(key.value())
            .uploadId(uploadId)
            .multipartUpload(completedUpload)
            .build();
        
        CompleteMultipartUploadResponse response = 
            s3Client.completeMultipartUpload(request);
    }
    
    private void uploadSinglePart() {
        byte[] data = new byte[bufferPosition];
        System.arraycopy(buffer, 0, data, 0, bufferPosition);
        
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(key.value())
            .storageClass(storageClass)
            .build();
        
        s3Client.putObject(request, RequestBody.fromBytes(data));
    }
    
    public long processedBytes() {
        return processedBytes;
    }
}
```

### 3. build.gradle

```gradle
archivesBaseName = "storage-oss"

dependencies {
    implementation project(":storage:core")
    
    // 使用AWS S3 SDK（OSS兼容）
    def excludeFromAWSDeps = { ModuleDependency dep ->
        dep.exclude group: "org.slf4j"
    }
    implementation ("software.amazon.awssdk:s3:$awsSdkVersion") {
        excludeFromAWSDeps(it)
    }
    
    implementation project(':commons')
    
    testImplementation(testFixtures(project(":storage:core")))
    
    // 集成测试可以使用阿里云OSS测试环境
    // 或者使用MinIO等S3兼容存储进行测试
}
```

---

## 🚀 实施步骤

### Phase 1: 基础实现（2周）

1. **Week 1: 核心类实现**
   - [ ] 创建`OssStorage.java`
   - [ ] 创建`OssStorageConfig.java`
   - [ ] 创建`OssClientBuilder.java`
   - [ ] 创建`OssUploadOutputStream.java`

2. **Week 2: 配置和测试**
   - [ ] 更新`build.gradle`
   - [ ] 编写单元测试
   - [ ] 编写集成测试
   - [ ] 更新文档

### Phase 2: 高级功能（1周）

3. **Week 3: 增强功能**
   - [ ] 实现凭证轮换（`OssRotatingCredentialsProvider.java`）
   - [ ] 实现指标收集
   - [ ] 性能优化
   - [ ] 错误处理完善

### Phase 3: 测试和文档（1周）

4. **Week 4: 测试和文档**
   - [ ] 端到端测试
   - [ ] 性能测试
   - [ ] 更新README
   - [ ] 更新配置文档

**总计：** 4周

---

## 🧪 测试方案

### 单元测试

```java
public class OssStorageConfigTest {
    @Test
    public void testConfigValidation() {
        // 测试配置验证逻辑
    }
    
    @Test
    public void testCredentialsProvider() {
        // 测试凭证提供者
    }
}
```

### 集成测试

```java
public class OssStorageTest extends BaseStorageTest {
    @Override
    protected StorageBackend createStorageBackend() {
        Map<String, Object> config = Map.of(
            "oss.bucket.name", "test-bucket",
            "oss.endpoint", "https://oss-cn-hangzhou.aliyuncs.com",
            "oss.region", "cn-hangzhou",
            "oss.access.key.id", "test-key-id",
            "oss.access.key.secret", "test-key-secret"
        );
        
        OssStorage storage = new OssStorage();
        storage.configure(config);
        return storage;
    }
}
```

### 配置示例

```properties
# OSS存储配置
rsm.config.storage.backend.class=io.aiven.kafka.tieredstorage.storage.oss.OssStorage

# OSS基础配置
rsm.config.storage.oss.bucket.name=my-kafka-bucket
rsm.config.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
rsm.config.storage.oss.region=cn-hangzhou

# OSS凭证配置
rsm.config.storage.oss.access.key.id=your-access-key-id
rsm.config.storage.oss.access.key.secret=your-access-key-secret

# OSS存储类型
rsm.config.storage.oss.storage.class=Standard

# OSS上传配置
rsm.config.storage.oss.multipart.upload.part.size=26214400
```

---

## 📊 预期效果

### 功能完整性
- ✅ 支持上传、下载、删除操作
- ✅ 支持多部分上传
- ✅ 支持范围查询
- ✅ 支持批量删除

### 性能指标
- 上传速度：与S3相当
- 下载速度：与S3相当
- 延迟：取决于OSS区域和网络

### 兼容性
- 与现有Kafka模式完全兼容
- 与Iceberg模式兼容（需要额外配置）

---

## 🔧 后续优化

1. **性能优化**
   - 连接池优化
   - 并发上传优化

2. **功能增强**
   - 支持OSS生命周期规则
   - 支持OSS版本控制
   - 支持OSS跨区域复制

3. **监控增强**
   - OSS特定指标
   - 成本监控

---

## 📝 注意事项

1. **凭证安全**
   - 不要硬编码凭证
   - 使用环境变量或配置文件
   - 支持凭证轮换

2. **网络配置**
   - 确保网络连接到OSS
   - 考虑使用内网端点（如果可用）

3. **成本优化**
   - 选择合适的存储类型
   - 配置生命周期规则
   - 监控存储使用量

---

**预计完成时间：** 4周  
**优先级：** 中（P1）  
**依赖：** 无

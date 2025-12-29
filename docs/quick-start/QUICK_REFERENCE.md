# Tiered Storage for Apache Kafka - 快速参考

## 🚀 快速开始

### 构建项目
```bash
./gradlew build
```

### 运行测试
```bash
./gradlew test
```

### 查看Demo
```bash
cd demo/
# 参考 demo/README.md
```

---

## 📁 核心文件速查

### 入口类
| 文件 | 说明 |
|------|------|
| `RemoteStorageManager.java` | 主入口，实现Kafka接口 |
| `KafkaRemoteStorageManager.java` | Kafka模式实现 |
| `IcebergRemoteStorageManager.java` | Iceberg模式实现 |

### 配置类
| 文件 | 说明 |
|------|------|
| `RemoteStorageManagerConfig.java` | 主配置类 |
| `S3StorageConfig.java` | S3配置 |
| `GcsStorageConfig.java` | GCS配置 |
| `AzureBlobStorageConfig.java` | Azure配置 |

### 核心组件
| 文件 | 说明 |
|------|------|
| `TransformChunkEnumeration.java` | 转换分块枚举 |
| `CompressionChunkEnumeration.java` | 压缩处理 |
| `EncryptionChunkEnumeration.java` | 加密处理 |
| `ChunkCache.java` | 分块缓存 |
| `SegmentManifestV1.java` | 清单结构 |

---

## 🔑 关键接口

### StorageBackend
```java
public interface StorageBackend extends 
    Configurable, ObjectUploader, ObjectFetcher, ObjectDeleter, Closeable
```

### ChunkManager
```java
public interface ChunkManager {
    InputStream getChunk(ObjectKey key, SegmentManifest manifest, int chunkId);
}
```

### ChunkIndex
```java
public interface ChunkIndex {
    List<Chunk> chunks();
    List<Chunk> chunksForRange(BytesRange range);
    int transformedPosition(int originalPosition);
}
```

---

## 📝 配置参数速查

### 基础配置
```properties
# 启用分层存储
remote.log.storage.system.enable=true

# RSM类
remote.log.storage.manager.class.name=io.aiven.kafka.tieredstorage.RemoteStorageManager

# 存储后端
rsm.config.storage.backend.class=io.aiven.kafka.tieredstorage.storage.s3.S3Storage
```

### S3配置
```properties
rsm.config.storage.s3.bucket.name=my-bucket
rsm.config.storage.s3.region=us-east-1
rsm.config.storage.s3.access.key.id=xxx
rsm.config.storage.s3.secret.access.key=xxx
```

### GCS配置
```properties
rsm.config.storage.gcs.bucket.name=my-bucket
rsm.config.storage.gcs.credentials.default=true
```

### Azure配置
```properties
rsm.config.storage.azure.container.name=my-container
rsm.config.storage.azure.account.name=my-account
rsm.config.storage.azure.account.key=xxx
```

### 性能配置
```properties
# 分块大小（推荐4MB）
rsm.config.chunk.size=4194304

# 上传速率限制（推荐100-200MB/s）
rsm.config.upload.rate.limit.bytes.per.second=104857600

# 缓存配置
rsm.config.fetch.chunk.cache.class=io.aiven.kafka.tieredstorage.fetch.cache.DiskChunkCache
rsm.config.fetch.chunk.cache.path=/cache/root
rsm.config.fetch.chunk.cache.size=17179869184
```

### 压缩配置
```properties
# 启用压缩
rsm.config.compression.enabled=true

# 压缩启发式（避免双重压缩）
rsm.config.compression.heuristic.enabled=true
```

### 加密配置
```properties
# 启用加密
rsm.config.encryption.enabled=true

# RSA公钥路径
rsm.config.encryption.rsa.public.key.path=/path/to/public.pem

# RSA私钥路径
rsm.config.encryption.rsa.private.key.path=/path/to/private.pem
```

---

## 🔄 数据流转速查

### 上传流程
```
Segment File
  → Chunking
  → Compression (可选)
  → Encryption (可选)
  → Rate Limiting
  → Storage Backend
  → Manifest Upload
```

### 下载流程
```
Fetch Request
  → Manifest Cache
  → Chunk Index Lookup
  → Chunk Cache (或 Storage)
  → Decryption (如需要)
  → Decompression (如需要)
  → Reconstruct Batches
  → Return InputStream
```

---

## 🧩 关键算法

### 范围查询
```java
int startChunkId = range.from() / chunkSize;
int endChunkId = (range.to() + chunkSize - 1) / chunkSize;
```

### 位置映射
```java
int chunkId = position / originalChunkSize;
int offset = position % originalChunkSize;
int transformedPos = chunkId * transformedChunkSize + 
    (offset * transformedChunkSize / originalChunkSize);
```

---

## 🐛 常见问题

### Q: 如何查看上传进度？
A: 通过JMX指标 `object-upload-bytes-total`

### Q: 缓存不生效？
A: 检查：
1. 缓存路径是否正确
2. 缓存大小是否足够
3. 缓存权限是否正确

### Q: 上传失败？
A: 检查：
1. 存储凭证是否正确
2. 网络连接是否正常
3. 速率限制是否合理

### Q: 下载慢？
A: 优化：
1. 启用缓存
2. 增加预取大小
3. 检查网络带宽

---

## 📊 指标说明

### 上传指标
- `object-upload-rate` - 上传操作速率
- `object-upload-bytes-rate` - 上传字节速率
- `segment-copy-time` - 段复制时间

### 下载指标
- `segment-fetch-rate` - 获取速率
- `chunk-cache-hit-rate` - 缓存命中率
- `chunk-cache-miss-rate` - 缓存未命中率

### 错误指标
- `segment-copy-error-rate` - 复制错误率
- `segment-delete-error-rate` - 删除错误率

---

## 🔍 调试命令

### 查看日志
```bash
tail -f logs/kafka.log | grep tieredstorage
```

### JMX监控
```bash
jconsole localhost:9999
```

### 性能分析
```bash
# 使用JProfiler
# 关注内存、CPU、网络I/O
```

---

## 📚 相关链接

- [KIP-405: Kafka Tiered Storage](https://cwiki.apache.org/confluence/x/KAFKA/KIP-405)
- [项目GitHub](https://github.com/Aiven-Open/tiered-storage-for-apache-kafka)
- [配置文档](../configs.rst)
- [指标文档](../metrics.rst)

---

## 💡 学习建议

1. **从入口开始**：先看`RemoteStorageManager`
2. **理解流程**：跟踪一次上传/下载的完整流程
3. **阅读测试**：测试代码是最好的文档
4. **动手实践**：修改代码，运行测试
5. **画图理解**：用流程图帮助理解

---

## 🎯 学习路径

1. **第1-2天**：环境搭建 + 阅读README
2. **第3-5天**：理解架构 + 阅读核心类
3. **第6-10天**：深入Kafka模式实现
4. **第11-13天**：理解Iceberg模式（可选）
5. **第14-15天**：实践和测试

---

**快速参考版本：** v1.0  
**最后更新：** 2025-12-19

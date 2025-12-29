# Tiered Storage for Apache Kafka - 学习指南

## 📚 学习路径概览

本指南将帮助你从零开始深入理解这个项目的架构设计和实现细节。

---

## 🎯 第一阶段：基础准备（1-2天）

### 1.1 前置知识要求

在开始之前，确保你理解以下概念：

- **Apache Kafka基础**
  - Log Segments（日志段）
  - Partition和Replica
  - Offset和Consumer Group
  - [KIP-405: Tiered Storage](https://cwiki.apache.org/confluence/x/KAFKA/KIP-405)

- **对象存储基础**
  - S3/GCS/Azure Blob Storage的基本操作
  - 多部分上传（Multipart Upload）
  - 范围查询（Range Query）

- **Java基础**
  - 接口和抽象类
  - 流式处理（InputStream/OutputStream）
  - 并发编程基础

### 1.2 项目环境搭建

```bash
# 1. 克隆项目
git clone https://github.com/Aiven-Open/tiered-storage-for-apache-kafka.git
cd tiered-storage-for-apache-kafka

# 2. 构建项目
./gradlew build

# 3. 运行测试（验证环境）
./gradlew test

# 4. 查看项目结构
tree -L 3 -I 'build|.git'
```

### 1.3 阅读核心文档

**必读文档（按顺序）：**
1. `README.md` - 项目概述和快速开始
2. `iceberg_whitepaper.md` - Iceberg模式设计理念
3. `docs/configs.rst` - 配置参数说明
4. `docs/metrics.rst` - 指标监控说明

---

## 🔍 第二阶段：架构理解（2-3天）

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│              Kafka Broker                                │
│  ┌──────────────────────────────────────────────────┐  │
│  │   RemoteStorageManager (Facade)                   │  │
│  │  ┌────────────────────────────────────────────┐  │  │
│  │  │ InternalRemoteStorageManagerSelector      │  │  │
│  │  └────────────────────────────────────────────┘  │  │
│  │           │                    │                  │  │
│  │  ┌────────▼────────┐  ┌────────▼────────┐        │  │
│  │  │ KafkaRSM        │  │ IcebergRSM       │        │  │
│  │  └────────┬────────┘  └────────┬────────┘        │  │
│  └───────────┼─────────────────────┼──────────────────┘  │
└──────────────┼─────────────────────┼─────────────────────┘
               │                     │
    ┌──────────▼──────────┐ ┌────────▼──────────┐
    │  Storage Backend    │ │  Iceberg Catalog  │
    │  (S3/GCS/Azure)     │ │  (REST/JDBC)      │
    └─────────────────────┘ └───────────────────┘
```

### 2.2 核心模块阅读顺序

#### **第一步：入口类**
📁 `core/src/main/java/io/aiven/kafka/tieredstorage/RemoteStorageManager.java`

**学习重点：**
- 如何实现Kafka的`RemoteStorageManager`接口
- 门面模式的应用
- 配置初始化流程
- 方法分发机制

**关键方法：**
```java
configure()              // 配置初始化
copyLogSegmentData()     // 上传段数据
fetchLogSegment()        // 获取段数据
fetchIndex()             // 获取索引
deleteLogSegmentData()   // 删除段数据
```

#### **第二步：抽象层**
📁 `core/src/main/java/io/aiven/kafka/tieredstorage/InternalRemoteStorageManager.java`

**学习重点：**
- 抽象类的设计
- 策略模式的实现
- 接口定义

#### **第三步：选择器模式**
📁 `core/src/main/java/io/aiven/kafka/tieredstorage/InternalRemoteStorageManagerSelector.java`

**学习重点：**
- 双重查找机制
- 异常处理和降级策略

---

## 🏗️ 第三阶段：Kafka模式深入（3-5天）

### 3.1 上传流程（Upload Flow）

#### **阅读顺序：**

1. **KafkaRemoteStorageManager.copyLogSegmentData()**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/KafkaRemoteStorageManager.java`
   
   **关键步骤：**
   ```
   读取段文件 → 分块处理 → 压缩/加密 → 上传到存储 → 创建清单
   ```

2. **分块处理（Chunking）**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/transform/`
   
   **重点文件：**
   - `TransformChunkEnumeration.java` - 分块枚举器
   - `BaseTransformChunkEnumeration.java` - 基础实现
   
   **理解要点：**
   - 为什么需要分块？
   - 固定大小 vs 可变大小分块
   - 分块索引的构建

3. **压缩处理**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/transform/CompressionChunkEnumeration.java`
   
   **学习重点：**
   - Zstandard压缩算法
   - 压缩启发式判断
   - 避免双重压缩

4. **加密处理**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/security/`
   
   **重点文件：**
   - `RsaEncryptionProvider.java` - RSA密钥管理
   - `AesEncryptionProvider.java` - AES数据加密
   - `EncryptionChunkEnumeration.java` - 加密分块处理
   
   **理解要点：**
   - 信封加密模式
   - 密钥轮换机制
   - DEK和KEK的关系

5. **清单（Manifest）创建**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/manifest/`
   
   **重点文件：**
   - `SegmentManifestV1.java` - 清单数据结构
   - `SegmentIndexesV1.java` - 索引信息
   - `ChunkIndex.java` - 分块索引
   
   **理解要点：**
   - 清单的作用
   - 索引的二进制编码
   - 清单的序列化/反序列化

### 3.2 下载流程（Fetch Flow）

#### **阅读顺序：**

1. **KafkaRemoteStorageManager.fetchLogSegment()**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/KafkaRemoteStorageManager.java`
   
   **关键步骤：**
   ```
   读取清单 → 定位分块 → 从缓存/存储获取 → 解密/解压 → 重建段文件
   ```

2. **清单缓存**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/fetch/manifest/`
   
   **重点文件：**
   - `SegmentManifestCache.java` - 缓存接口
   - `MemorySegmentManifestCache.java` - 内存实现
   
   **理解要点：**
   - 缓存策略
   - 缓存键的设计

3. **分块管理**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/fetch/`
   
   **重点文件：**
   - `ChunkManager.java` - 分块管理器接口
   - `DefaultChunkManager.java` - 默认实现
   - `FetchChunkEnumeration.java` - 分块枚举
   
   **理解要点：**
   - 范围查询的实现
   - 分块定位算法

4. **分块缓存**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/fetch/cache/`
   
   **重点文件：**
   - `ChunkCache.java` - 缓存抽象类
   - `DiskChunkCache.java` - 磁盘缓存
   - `MemoryChunkCache.java` - 内存缓存
   
   **理解要点：**
   - Caffeine缓存的使用
   - 预取机制
   - 缓存淘汰策略

5. **反向转换（Detransform）**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/transform/`
   
   **重点文件：**
   - `DetransformChunkEnumeration.java` - 反向转换枚举
   - `DecryptionChunkEnumeration.java` - 解密
   - `DecompressionChunkEnumeration.java` - 解压
   
   **理解要点：**
   - 转换的逆过程
   - 流式处理

### 3.3 存储抽象层

#### **阅读顺序：**

1. **存储接口**
   📁 `storage/core/src/main/java/io/aiven/kafka/tieredstorage/storage/`
   
   **重点文件：**
   - `StorageBackend.java` - 存储后端接口
   - `ObjectUploader.java` - 上传接口
   - `ObjectFetcher.java` - 获取接口
   - `ObjectDeleter.java` - 删除接口
   
   **理解要点：**
   - 接口设计原则
   - 职责分离

2. **S3实现（参考实现）**
   📁 `storage/s3/src/main/java/io/aiven/kafka/tieredstorage/storage/s3/`
   
   **重点文件：**
   - `S3Storage.java` - S3存储实现
   - `S3StorageConfig.java` - 配置类
   - `S3UploadOutputStream.java` - 多部分上传流
   
   **理解要点：**
   - 多部分上传的实现
   - 错误处理
   - 指标收集

---

## 🧊 第四阶段：Iceberg模式（可选，2-3天）

### 4.1 Iceberg模式概述

**先阅读：**
- `iceberg_whitepaper.md` - 完整设计文档

### 4.2 核心组件

#### **阅读顺序：**

1. **IcebergRemoteStorageManager**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/IcebergRemoteStorageManager.java`
   
   **理解要点：**
   - 与Kafka模式的区别
   - Parquet文件写入
   - 批次重建

2. **数据转换**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/iceberg/data/`
   
   **重点文件：**
   - `RecordConverter.java` - 记录转换
   - `IcebergWriter.java` - Iceberg写入器
   - `ParquetAvroValueReaders.java` - Parquet读取

3. **Schema管理**
   📁 `core/src/main/java/io/aiven/kafka/tieredstorage/iceberg/`
   
   **重点文件：**
   - `AvroSchemaRegistryStructureProvider.java` - Schema提供者
   - `RowSchema.java` - 行Schema定义

---

## 🧪 第五阶段：实践和测试（2-3天）

### 5.1 运行Demo

```bash
# 查看demo目录
cd demo/
ls -la

# 运行Kafka模式demo
# 参考 demo/README.md
```

### 5.2 阅读测试代码

**测试代码是理解实现细节的最佳方式：**

1. **单元测试**
   📁 `core/src/test/java/`
   
   **重点测试：**
   - `KafkaRemoteStorageManagerTest.java` - 核心功能测试
   - `ChunkIndexTest.java` - 分块索引测试
   - `CompressionChunkEnumerationTest.java` - 压缩测试

2. **集成测试**
   📁 `core/src/integration-test/java/`
   
   **重点测试：**
   - `RemoteStorageManagerTest.java` - 端到端测试
   - `KafkaRemoteStorageManagerTest.java` - 完整流程测试

3. **E2E测试**
   📁 `e2e/src/integration-test/java/`
   
   **理解要点：**
   - 真实Kafka环境下的测试
   - 性能测试场景

### 5.3 调试技巧

```bash
# 1. 启用详细日志
# 在log4j.properties中设置：
log4j.logger.io.aiven.kafka.tieredstorage=DEBUG

# 2. 使用IDE调试
# 在RemoteStorageManager.configure()设置断点

# 3. 查看指标
# 使用JMX查看metrics
```

---

## 📖 第六阶段：深入理解（持续学习）

### 6.1 关键设计模式

1. **门面模式（Facade）**
   - `RemoteStorageManager` 作为统一入口

2. **策略模式（Strategy）**
   - `InternalRemoteStorageManager` 的不同实现

3. **模板方法模式（Template Method）**
   - `BaseTransformChunkEnumeration` 和子类

4. **建造者模式（Builder）**
   - `SegmentIndexesV1Builder`

5. **工厂模式（Factory）**
   - `ChunkManagerFactory`

### 6.2 性能优化技术

1. **分块处理**
   - 减少内存占用
   - 支持范围查询

2. **缓存策略**
   - 多级缓存
   - 预取机制

3. **流式处理**
   - InputStream/OutputStream
   - 避免大文件内存加载

4. **速率限制**
   - Token Bucket算法
   - 保护Broker性能

### 6.3 错误处理机制

- 异常层次结构
- 重试机制
- 降级策略

---

## 🎓 学习检查清单

### 基础理解
- [ ] 理解Kafka Tiered Storage的基本概念
- [ ] 理解项目的整体架构
- [ ] 能够运行项目并查看日志

### 核心功能
- [ ] 理解上传流程（分块→压缩→加密→上传）
- [ ] 理解下载流程（清单→缓存→解密→解压）
- [ ] 理解分块索引的作用和实现
- [ ] 理解清单（Manifest）的结构

### 高级特性
- [ ] 理解压缩启发式判断
- [ ] 理解信封加密机制
- [ ] 理解缓存策略和预取
- [ ] 理解速率限制机制

### 存储抽象
- [ ] 理解StorageBackend接口设计
- [ ] 能够阅读S3/GCS/Azure的实现
- [ ] 理解如何添加新的存储后端

### 实践能力
- [ ] 能够配置和运行demo
- [ ] 能够阅读和修改测试代码
- [ ] 能够调试问题

---

## 📚 推荐阅读资源

### 官方文档
1. [KIP-405: Kafka Tiered Storage](https://cwiki.apache.org/confluence/x/KAFKA/KIP-405)
2. [Apache Kafka官方文档](https://kafka.apache.org/documentation/)
3. [Apache Iceberg文档](https://iceberg.apache.org/)

### 相关技术
1. [Zstandard压缩](https://github.com/facebook/zstd)
2. [Caffeine缓存](https://github.com/ben-manes/caffeine)
3. [AWS S3多部分上传](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)

### 代码阅读工具
1. IDE: IntelliJ IDEA / Eclipse
2. 代码分析: Sourcegraph / GitHub Code Search
3. 文档生成: JavaDoc

---

## 🚀 进阶学习路径

### 如果想贡献代码：

1. **修复Bug**
   - 查看GitHub Issues
   - 从简单的bug开始

2. **添加功能**
   - 实现新的存储后端（如OSS）
   - 优化现有功能

3. **改进文档**
   - 补充代码注释
   - 完善使用文档

### 如果想深入优化：

1. **性能分析**
   - 使用JProfiler分析性能瓶颈
   - 优化热点代码

2. **架构改进**
   - 提出架构优化建议
   - 实现新的设计模式

---

## 💡 学习建议

1. **循序渐进**：不要急于求成，按阶段学习
2. **动手实践**：多运行代码，多调试
3. **画图理解**：用流程图、架构图帮助理解
4. **记录笔记**：记录关键概念和设计决策
5. **提问交流**：在GitHub Discussions提问
6. **阅读测试**：测试代码是最好的文档

---

## 📝 学习日志模板

```markdown
## 学习日期：YYYY-MM-DD

### 今天学习的内容
- [ ] 阅读了哪些文件
- [ ] 理解了哪些概念
- [ ] 遇到了哪些问题

### 关键代码片段
```java
// 记录重要的代码和理解
```

### 问题和思考
- Q: 问题描述
- A: 自己的理解或解决方案

### 下一步计划
- [ ] 明天要学习的内容
```

---

**祝你学习顺利！** 🎉

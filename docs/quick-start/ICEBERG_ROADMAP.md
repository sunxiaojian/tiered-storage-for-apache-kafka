# Iceberg模式功能需求列表和实现方案

## 📋 目录

1. [需求优先级分类](#需求优先级分类)
2. [高优先级需求](#高优先级需求)
3. [中优先级需求](#中优先级需求)
4. [低优先级需求](#低优先级需求)
5. [OSS支持方案](#oss支持方案)

---

## 🎯 需求优先级分类

### 高优先级（P0 - 生产必需）
- Schema Evolution（模式演进）
- 去重机制（Duplicate Handling）
- Kafka事务支持
- 缓存支持

### 中优先级（P1 - 重要功能）
- 表压缩和快照管理
- Parquet加密
- 移除读取时对Catalog的依赖
- GCS和Azure Blob Storage支持（Iceberg模式）

### 低优先级（P2 - 增强功能）
- 其他存储格式（Avro、ORC）
- 其他记录格式（JSON、Protobuf）
- 其他表格式（Delta Lake）
- 性能基准测试
- 模块化重构

---

## 🔴 高优先级需求（P0）

### 1. Schema Evolution（模式演进）

#### 需求描述
当前实现中，表的Schema由第一个遇到的记录决定，之后无法更改。这限制了生产环境的使用，因为实际业务中Schema会不断演进。

#### 当前限制
- Schema在表创建时固定
- 不支持添加/删除字段
- 不支持字段类型变更
- 不支持字段重命名

#### 实现方案

##### 1.1 Schema兼容性检查

**文件位置：** `core/src/main/java/io/aiven/kafka/tieredstorage/iceberg/data/SchemaUpdate.java`

```java
public class SchemaEvolutionManager {
    
    /**
     * 检查新Schema是否与现有Schema兼容
     * 
     * 兼容性规则：
     * 1. 添加字段：兼容（新字段为可选）
     * 2. 删除字段：不兼容（除非字段已标记为deprecated）
     * 3. 类型变更：检查是否可安全转换
     * 4. 字段重命名：需要显式映射
     */
    public SchemaCompatibilityResult checkCompatibility(
        Schema existingSchema,
        Schema newSchema,
        SchemaRegistryCompatibilityLevel compatibilityLevel) {
        
        // 1. 检查Schema Registry的兼容性级别
        // 2. 比较字段差异
        // 3. 检查类型转换安全性
        // 4. 返回兼容性结果
    }
    
    /**
     * 更新Iceberg表Schema
     */
    public void evolveTableSchema(Table table, Schema newSchema) {
        // 1. 检查兼容性
        // 2. 更新表Schema
        // 3. 记录Schema变更历史
    }
}
```

##### 1.2 Schema变更处理流程

```java
// 在IcebergWriter中处理Schema变更
public class IcebergWriter {
    
    private Schema currentTableSchema;
    private Schema currentRecordSchema;
    
    public void writeRecord(GenericRecord record) {
        Schema recordSchema = record.getSchema();
        
        // 检查Schema是否变更
        if (!recordSchema.equals(currentRecordSchema)) {
            handleSchemaChange(recordSchema);
        }
        
        // 转换记录以匹配表Schema
        GenericRecord adaptedRecord = adaptRecord(record, currentTableSchema);
        writer.write(adaptedRecord);
    }
    
    private void handleSchemaChange(Schema newSchema) {
        // 1. 检查兼容性
        SchemaCompatibilityResult result = 
            schemaEvolutionManager.checkCompatibility(
                currentTableSchema, newSchema, compatibilityLevel);
        
        if (result.isCompatible()) {
            // 2. 更新表Schema
            schemaEvolutionManager.evolveTableSchema(table, newSchema);
            currentTableSchema = newSchema;
        } else {
            // 3. 处理不兼容情况
            handleIncompatibleSchema(newSchema, result);
        }
    }
    
    private GenericRecord adaptRecord(GenericRecord record, Schema targetSchema) {
        // 1. 处理新增字段（设为null或默认值）
        // 2. 处理删除字段（忽略）
        // 3. 处理类型转换
        // 4. 处理字段重命名
    }
}
```

##### 1.3 配置项

```properties
# Schema演进配置
rsm.config.iceberg.schema.evolution.enabled=true
rsm.config.iceberg.schema.evolution.compatibility.level=BACKWARD
# 可选值: BACKWARD, FORWARD, FULL, NONE

# Schema变更策略
rsm.config.iceberg.schema.evolution.strategy=AUTO
# 可选值: AUTO, MANUAL, STRICT
```

##### 1.4 实现步骤

1. **Phase 1: Schema兼容性检查** (2周)
   - 实现Schema比较逻辑
   - 实现兼容性规则
   - 单元测试

2. **Phase 2: Schema更新** (2周)
   - 实现Iceberg表Schema更新
   - 实现记录适配逻辑
   - 集成测试

3. **Phase 3: Schema Registry集成** (1周)
   - 与Schema Registry集成
   - 支持兼容性级别检查
   - 端到端测试

**预计工作量：** 5周

---

### 2. 去重机制（Duplicate Handling）

#### 需求描述
当前实现中，由于Leader切换或重试，同一个offset可能被上传多次，导致Iceberg表中出现重复记录。需要实现去重机制。

#### 当前问题
- 重复记录对Iceberg读者可见
- 影响数据质量
- 增加存储成本

#### 实现方案

##### 2.1 基于Kafka元数据的去重

```java
public class DeduplicationManager {
    
    /**
     * 检查记录是否已存在
     * 使用 (topic, partition, offset) 作为唯一键
     */
    public boolean isDuplicate(
        String topic,
        int partition,
        long offset,
        Table table) {
        
        // 使用Iceberg的过滤功能查询
        Expression filter = Expressions.and(
            Expressions.equal("kafka.partition", partition),
            Expressions.equal("kafka.offset", offset)
        );
        
        try (CloseableIterable<FileScanTask> tasks = 
            table.newScan().filter(filter).planFiles()) {
            
            return tasks.iterator().hasNext();
        }
    }
    
    /**
     * 批量去重检查
     */
    public Set<Long> findDuplicates(
        String topic,
        int partition,
        List<Long> offsets,
        Table table) {
        
        // 使用IN查询优化性能
        Expression filter = Expressions.and(
            Expressions.equal("kafka.partition", partition),
            Expressions.in("kafka.offset", offsets)
        );
        
        Set<Long> existingOffsets = new HashSet<>();
        try (CloseableIterable<Row> rows = 
            table.newScan().filter(filter).planRows()) {
            
            for (Row row : rows) {
                existingOffsets.add(row.getLong("kafka.offset"));
            }
        }
        
        return existingOffsets;
    }
}
```

##### 2.2 写入时去重

```java
// 在IcebergWriter中集成去重
public class IcebergWriter {
    
    private final DeduplicationManager deduplicationManager;
    
    public void writeBatch(List<GenericRecord> records) {
        // 1. 提取offsets
        List<Long> offsets = extractOffsets(records);
        
        // 2. 批量检查重复
        Set<Long> duplicates = deduplicationManager.findDuplicates(
            topicName, partition, offsets, table);
        
        // 3. 过滤重复记录
        List<GenericRecord> uniqueRecords = records.stream()
            .filter(record -> {
                long offset = getOffset(record);
                return !duplicates.contains(offset);
            })
            .collect(Collectors.toList());
        
        // 4. 写入唯一记录
        if (!uniqueRecords.isEmpty()) {
            writer.writeBatch(uniqueRecords);
        }
    }
}
```

##### 2.3 性能优化

**使用Bloom Filter：**
```java
public class BloomFilterDeduplication {
    
    private final BloomFilter<Long> offsetBloomFilter;
    
    public boolean mightContain(long offset) {
        return offsetBloomFilter.mightContain(offset);
    }
    
    // 定期从Iceberg表重建Bloom Filter
    public void rebuildBloomFilter(Table table) {
        // 扫描表，提取所有offsets
        // 重建Bloom Filter
    }
}
```

##### 2.4 配置项

```properties
# 去重配置
rsm.config.iceberg.deduplication.enabled=true
rsm.config.iceberg.deduplication.strategy=KAFKA_METADATA
# 可选值: KAFKA_METADATA, BLOOM_FILTER, HYBRID

# Bloom Filter配置
rsm.config.iceberg.deduplication.bloom.filter.enabled=true
rsm.config.iceberg.deduplication.bloom.filter.rebuild.interval.ms=3600000
```

##### 2.5 实现步骤

1. **Phase 1: 基础去重** (2周)
   - 实现基于offset的去重检查
   - 集成到写入流程
   - 单元测试

2. **Phase 2: 性能优化** (2周)
   - 实现Bloom Filter
   - 批量去重检查
   - 性能测试

3. **Phase 3: 监控和指标** (1周)
   - 添加去重指标
   - 监控重复率
   - 文档更新

**预计工作量：** 5周

---

### 3. Kafka事务支持

#### 需求描述
当前实现不支持Kafka事务，如果段中包含事务控制批次，整个段无法上传。

#### 当前限制
- 不支持事务控制批次
- 不支持事务性数据
- 不支持事务提交/中止语义

#### 实现方案

##### 3.1 事务批次识别

```java
public class TransactionBatchHandler {
    
    /**
     * 检查批次是否为事务控制批次
     */
    public boolean isTransactionControlBatch(RecordBatch batch) {
        return batch.isControlBatch() && 
               batch.producerId() != RecordBatch.NO_PRODUCER_ID;
    }
    
    /**
     * 提取事务信息
     */
    public TransactionInfo extractTransactionInfo(RecordBatch batch) {
        return TransactionInfo.builder()
            .producerId(batch.producerId())
            .producerEpoch(batch.producerEpoch())
            .baseSequence(batch.baseSequence())
            .lastSequence(batch.lastSequence())
            .build();
    }
}
```

##### 3.2 事务状态管理

```java
public class TransactionStateManager {
    
    private final Map<Long, TransactionState> transactionStates = new ConcurrentHashMap<>();
    
    public enum TransactionState {
        OPEN,      // 事务开始
        COMMITTED, // 事务提交
        ABORTED    // 事务中止
    }
    
    /**
     * 处理事务控制批次
     */
    public void handleTransactionControl(
        TransactionInfo info,
        TransactionControlType type) {
        
        switch (type) {
            case BEGIN:
                transactionStates.put(info.producerId(), TransactionState.OPEN);
                break;
            case COMMIT:
                transactionStates.put(info.producerId(), TransactionState.COMMITTED);
                break;
            case ABORT:
                transactionStates.put(info.producerId(), TransactionState.ABORTED);
                break;
        }
    }
    
    /**
     * 检查事务是否已提交
     */
    public boolean isCommitted(long producerId) {
        return transactionStates.getOrDefault(
            producerId, TransactionState.ABORTED) == TransactionState.COMMITTED;
    }
}
```

##### 3.3 事务数据写入

```java
// 在IcebergWriter中处理事务
public class IcebergWriter {
    
    private final TransactionStateManager transactionManager;
    
    public void writeBatch(RecordBatch batch, List<GenericRecord> records) {
        // 1. 检查是否为事务批次
        if (batch.isTransactional()) {
            long producerId = batch.producerId();
            
            // 2. 检查事务状态
            if (!transactionManager.isCommitted(producerId)) {
                // 3. 延迟写入，等待事务提交
                pendingTransactions.put(producerId, new PendingBatch(batch, records));
                return;
            }
        }
        
        // 4. 写入已提交的数据
        writer.writeBatch(records);
    }
    
    /**
     * 处理事务提交
     */
    public void commitTransaction(long producerId) {
        List<PendingBatch> pendingBatches = 
            pendingTransactions.remove(producerId);
        
        if (pendingBatches != null) {
            // 写入所有待处理的批次
            for (PendingBatch batch : pendingBatches) {
                writer.writeBatch(batch.records);
            }
        }
    }
}
```

##### 3.4 配置项

```properties
# 事务支持配置
rsm.config.iceberg.transaction.enabled=true
rsm.config.iceberg.transaction.timeout.ms=300000
rsm.config.iceberg.transaction.pending.batch.max.size=10000
```

##### 3.5 实现步骤

1. **Phase 1: 事务识别** (1周)
   - 实现事务批次识别
   - 提取事务信息
   - 单元测试

2. **Phase 2: 事务状态管理** (2周)
   - 实现事务状态跟踪
   - 处理事务提交/中止
   - 集成测试

3. **Phase 3: 事务数据写入** (2周)
   - 实现延迟写入机制
   - 处理事务超时
   - 端到端测试

**预计工作量：** 5周

---

### 4. 缓存支持

#### 需求描述
当前Iceberg模式没有缓存机制，每次读取都需要从对象存储获取数据，影响性能。

#### 实现方案

##### 4.1 Parquet文件缓存

```java
public class IcebergParquetCache {
    
    private final AsyncCache<String, Path> parquetFileCache;
    
    /**
     * 获取Parquet文件（从缓存或存储）
     */
    public Path getParquetFile(String filePath) {
        return parquetFileCache.get(filePath, key -> {
            // 从对象存储下载
            return downloadParquetFile(key);
        });
    }
    
    /**
     * 预取Parquet文件
     */
    public void prefetchParquetFiles(List<String> filePaths) {
        for (String path : filePaths) {
            parquetFileCache.get(path, this::downloadParquetFile);
        }
    }
}
```

##### 4.2 表元数据缓存

```java
public class IcebergTableMetadataCache {
    
    private final Cache<TableIdentifier, TableMetadata> tableMetadataCache;
    
    /**
     * 获取表元数据（从缓存或Catalog）
     */
    public TableMetadata getTableMetadata(TableIdentifier identifier) {
        return tableMetadataCache.get(identifier, () -> {
            Table table = catalog.loadTable(identifier);
            return table.currentSnapshot().metadata();
        });
    }
}
```

##### 4.3 配置项

```properties
# Iceberg缓存配置
rsm.config.iceberg.cache.enabled=true
rsm.config.iceberg.cache.parquet.file.enabled=true
rsm.config.iceberg.cache.parquet.file.size=17179869184
rsm.config.iceberg.cache.table.metadata.enabled=true
rsm.config.iceberg.cache.table.metadata.expiration.ms=600000
```

##### 4.4 实现步骤

1. **Phase 1: Parquet文件缓存** (2周)
   - 实现文件缓存
   - 集成到读取流程
   - 性能测试

2. **Phase 2: 元数据缓存** (1周)
   - 实现表元数据缓存
   - 缓存失效策略
   - 单元测试

**预计工作量：** 3周

---

## 🟡 中优先级需求（P1）

### 5. 表压缩和快照管理

#### 需求描述
支持Iceberg表的压缩（Compaction）和快照过期（Snapshot Expiration）操作。

#### 实现方案

##### 5.1 压缩任务

```java
public class IcebergCompactionManager {
    
    /**
     * 执行表压缩
     */
    public void compactTable(TableIdentifier identifier) {
        Table table = catalog.loadTable(identifier);
        
        // 1. 选择需要压缩的文件
        List<DataFile> filesToCompact = selectFilesToCompact(table);
        
        // 2. 执行压缩
        RewriteFiles rewriteFiles = table.newRewrite();
        rewriteFiles.rewriteFiles(filesToCompact, compactFiles(filesToCompact));
        rewriteFiles.commit();
    }
}
```

##### 5.2 快照过期

```java
public class IcebergSnapshotManager {
    
    /**
     * 过期旧快照
     */
    public void expireSnapshots(
        TableIdentifier identifier,
        long olderThanMs,
        int retainLast) {
        
        Table table = catalog.loadTable(identifier);
        ExpireSnapshots expireSnapshots = table.expireSnapshots();
        expireSnapshots.expireOlderThan(olderThanMs)
                       .retainLast(retainLast)
                       .commit();
    }
}
```

##### 5.3 配置项

```properties
# 压缩配置
rsm.config.iceberg.compaction.enabled=true
rsm.config.iceberg.compaction.interval.ms=3600000
rsm.config.iceberg.compaction.target.file.size=134217728

# 快照过期配置
rsm.config.iceberg.snapshot.expiration.enabled=true
rsm.config.iceberg.snapshot.expiration.older.than.ms=604800000
rsm.config.iceberg.snapshot.expiration.retain.last=10
```

**预计工作量：** 3周

---

### 6. Parquet加密

#### 需求描述
支持Parquet文件的列级加密，增强数据安全性。

#### 实现方案

```java
public class EncryptedParquetWriter {
    
    /**
     * 创建加密的Parquet写入器
     */
    public ParquetWriter<GenericRecord> createEncryptedWriter(
        OutputFile outputFile,
        Schema schema,
        EncryptionKeyMetadata keyMetadata) {
        
        ParquetProperties properties = ParquetProperties.builder()
            .withEncryption(keyMetadata)
            .build();
        
        return ParquetWriter.builder(outputFile)
            .withSchema(schema)
            .withProperties(properties)
            .build();
    }
}
```

**预计工作量：** 2周

---

### 7. 移除读取时对Catalog的依赖

#### 需求描述
当前读取时需要访问Catalog获取表元数据，希望直接从清单文件获取，减少依赖。

#### 实现方案

```java
public class CatalogFreeReader {
    
    /**
     * 从清单文件读取表元数据
     */
    public TableMetadata readMetadataFromManifest(SegmentManifest manifest) {
        // 1. 从清单中提取表元数据引用
        // 2. 直接从对象存储读取元数据文件
        // 3. 解析并返回
    }
}
```

**预计工作量：** 2周

---

### 8. GCS和Azure Blob Storage支持（Iceberg模式）

#### 需求描述
当前Iceberg模式仅支持S3，需要支持GCS和Azure Blob Storage。

#### 实现方案

参考S3实现，使用Iceberg的相应FileIO实现：
- GCS: `org.apache.iceberg.gcp.gcs.GCSFileIO`
- Azure: `org.apache.iceberg.azure.adlsv2.ADLSFileIO`

**预计工作量：** 2周

---

## 🟢 低优先级需求（P2）

### 9. 其他存储格式（Avro、ORC）

**预计工作量：** 4周

### 10. 其他记录格式（JSON、Protobuf）

**预计工作量：** 6周

### 11. 其他表格式（Delta Lake）

**预计工作量：** 8周

### 12. 性能基准测试

**预计工作量：** 2周

### 13. 模块化重构

**预计工作量：** 3周

---

## 📊 总体时间估算

| 优先级 | 功能 | 预计工作量 |
|--------|------|-----------|
| P0 | Schema Evolution | 5周 |
| P0 | 去重机制 | 5周 |
| P0 | Kafka事务支持 | 5周 |
| P0 | 缓存支持 | 3周 |
| P1 | 表压缩和快照管理 | 3周 |
| P1 | Parquet加密 | 2周 |
| P1 | 移除Catalog依赖 | 2周 |
| P1 | GCS/Azure支持 | 2周 |
| P2 | 其他功能 | 25周 |

**P0+P1总计：** 27周（约6.5个月）  
**全部功能总计：** 52周（约1年）

---

## 🎯 推荐实施顺序

### 第一阶段（3个月）
1. Schema Evolution
2. 去重机制
3. 缓存支持

### 第二阶段（3个月）
4. Kafka事务支持
5. 表压缩和快照管理
6. GCS/Azure支持

### 第三阶段（按需）
7. 其他功能根据需求优先级实施

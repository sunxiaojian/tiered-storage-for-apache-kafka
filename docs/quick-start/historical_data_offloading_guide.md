# Kafka 分层存储：历史数据卸载到 OSS 指南

## 概述

Kafka 分层存储通过自动将历史数据从本地磁盘卸载（offload）到远程存储（如阿里云 OSS）来优化存储成本和性能。本文档详细介绍历史数据卸载的机制、配置和监控。

## 核心机制

### 1. 卸载触发条件

Kafka 分层存储基于**日志保留策略**自动触发数据卸载，主要通过以下配置控制：

#### 本地保留策略
```properties
# 本地保留字节数 (最重要)
local.retention.bytes=1073741824  # 1GB

# 本地保留时间
local.retention.ms=3600000       # 1小时
```

#### 全局保留策略
```properties
# 全局保留字节数
retention.bytes=107374182400    # 100GB

# 全局保留时间
retention.ms=604800000          # 7天
```

### 2. 卸载流程

```
新数据写入 → 本地段文件 → 达到保留阈值 → 自动卸载 → OSS 存储
     ↓              ↓              ↓              ↓
  Active Segment  Closed Segment  Eligible       Remote
  (活跃段)        (关闭段)        (符合条件)     (远程存储)
```

#### 详细步骤：

1. **段文件关闭**: 当活跃段文件达到 `segment.bytes` 大小或时间限制时关闭
2. **保留检查**: `log.retention.check.interval.ms` 间隔检查保留策略
3. **符合条件**: 检查是否超过本地保留限制 (`local.retention.bytes`)
4. **异步上传**: RemoteLogManager 调度上传任务到 OSS
5. **本地清理**: 上传成功后删除本地段文件

## 配置指南

### 必需配置

#### 1. 启用分层存储
```properties
# 启用分层存储
remote.log.storage.system.enable=true

# 远程存储管理器
remote.log.storage.manager.class.name=io.aiven.kafka.tieredstorage.RemoteStorageManager
remote.log.storage.manager.class.path=/path/to/tiered-storage-core.jar:/path/to/tiered-storage-oss.jar

# 分块大小 (必需)
rsm.config.chunk.size=4194304
```

#### 2. OSS 存储配置
```properties
# OSS 存储后端
rsm.config.storage.backend.class=io.aiven.kafka.tieredstorage.storage.oss.OSSStorage

# OSS 连接配置
rsm.config.storage.oss.bucket.name=your-kafka-bucket
rsm.config.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
rsm.config.storage.oss.access.key.id=LTAI5t6A7B8C9D0E1F2G3H4
rsm.config.storage.oss.access.key.secret=your-access-key-secret
```

#### 3. 主题级保留策略
```bash
# 创建启用分层存储的主题
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic my-topic \
  --partitions 3 \
  --replication-factor 3 \
  --config remote.storage.enable=true \
  --config local.retention.bytes=1073741824 \     # 本地保留 1GB
  --config retention.bytes=107374182400 \         # 全局保留 100GB
  --config segment.bytes=536870912                # 段文件大小 512MB
```

### 高级配置

#### 上传控制
```properties
# 上传任务间隔
remote.log.manager.task.interval.ms=30000

# 上传速率限制 (100MB/s)
rsm.config.upload.rate.limit.bytes.per.second=104857600

# 分片上传大小 (8MB)
rsm.config.storage.oss.multipart.upload.part.size=8388608
```

#### 保留策略调优
```properties
# 日志保留检查间隔
log.retention.check.interval.ms=300000

# 本地保留时间优先
local.retention.ms=3600000        # 1小时
local.retention.bytes=1073741824  # 1GB

# 全局保留策略
retention.ms=604800000            # 7天
retention.bytes=107374182400      # 100GB
```

## 卸载策略详解

### 1. 基于大小的保留策略

**推荐配置：**
```properties
# 本地只保留最小数据，强制卸载到 OSS
local.retention.bytes=1

# 全局保留大量历史数据
retention.bytes=1000000000000  # 1TB

# 段文件相对较小，便于卸载
segment.bytes=104857600        # 100MB
```

**工作原理：**
- 新段文件关闭后立即超过 `local.retention.bytes=1` 的限制
- 触发自动卸载到 OSS
- 本地磁盘使用量保持最小

### 2. 基于时间的保留策略

```properties
# 本地保留 1 小时数据
local.retention.ms=3600000

# 全局保留 7 天数据
retention.ms=604800000

# 段文件滚动时间
segment.ms=3600000  # 1小时
```

### 3. 混合保留策略

```properties
# 双重条件：时间或大小任一满足即卸载
local.retention.ms=3600000      # 1小时
local.retention.bytes=1073741824 # 1GB
```

## 监控和验证

### 1. 验证卸载状态

#### 检查远程存储数据
```bash
# OSS 控制台查看对象
# 或使用 OSS CLI
ossutil ls oss://your-bucket/kafka-topic/

# 示例输出：
# 2024-01-01 10:00:00 100MB kafka-topic-0/00000000000000000000.log
# 2024-01-01 10:00:00 1KB  kafka-topic-0/00000000000000000000.index
# 2024-01-01 10:00:00 2KB  kafka-topic-0/manifest.json
```

#### 检查本地磁盘使用
```bash
# 查看主题数据目录
du -sh /tmp/kafka-logs/my-topic-*

# 应该只保留少量活跃数据
```

### 2. 监控指标

#### JMX 指标
```bash
# 连接到 Kafka JMX 端口 (默认 9999)
jconsole localhost:9999

# 查看指标：
# - kafka.tieredstorage:type=RemoteStorageManager,name=object-upload-bytes-total
# - kafka.tieredstorage:type=RemoteStorageManager,name=segment-copy-rate
# - kafka.tieredstorage:type=RemoteStorageManager,name=segment-copy-error-rate
```

#### 日志监控
```bash
# 查看分层存储日志
tail -f logs/kafka.log | grep -E "(tieredstorage|RemoteLogManager)"

# 成功卸载日志示例：
# INFO [RemoteLogManager] Successfully uploaded segment ... to remote storage
# INFO [RemoteLogManager] Deleted local segment files after successful upload
```

### 3. 故障排除

#### 常见问题

**问题1: 数据没有卸载到 OSS**
```bash
# 检查配置
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name my-topic \
  --describe | grep -E "(local.retention|remote.storage)"

# 检查日志
grep "RemoteLogManager" logs/kafka.log
```

**问题2: 卸载失败**
```bash
# 检查 OSS 连接
# 验证 Access Key 权限
# 检查网络连接
# 查看详细错误日志
```

**问题3: 卸载过慢**
```properties
# 增加上传任务间隔
remote.log.manager.task.interval.ms=10000

# 增加上传速率限制
rsm.config.upload.rate.limit.bytes.per.second=209715200  # 200MB/s

# 调整保留检查间隔
log.retention.check.interval.ms=60000  # 1分钟
```

## 最佳实践

### 1. 生产环境配置

```properties
# 主题配置
remote.storage.enable=true
local.retention.bytes=1073741824     # 1GB 本地保留
retention.bytes=1099511627776       # 1TB 全局保留
segment.bytes=536870912              # 512MB 段文件

# 性能配置
remote.log.manager.task.interval.ms=30000
rsm.config.upload.rate.limit.bytes.per.second=104857600
rsm.config.storage.oss.multipart.upload.part.size=16777216  # 16MB

# 监控配置
rsm.config.metrics.recording.level=INFO
rsm.config.metrics.num.samples=2
rsm.config.metrics.sample.window.ms=30000
```

### 2. 存储成本优化

```properties
# 使用 IA 存储类别降低成本
rsm.config.storage.oss.storage.class=IA

# 启用压缩减少存储空间
rsm.config.compression.enabled=true
rsm.config.compression.heuristic.enabled=true

# 合理设置保留策略
local.retention.ms=86400000    # 本地保留 1 天
retention.ms=2592000000       # 全局保留 30 天
```

### 3. 高可用配置

```properties
# 多副本确保数据安全
replication.factor=3

# RLMM 高可用
rlmm.config.remote.log.metadata.topic.replication.factor=3

# 监控告警
# 设置 OSS 上传失败告警
# 监控本地磁盘使用率
```

## 数据恢复

### 从 OSS 恢复数据

当需要访问历史数据时，Kafka 会自动从 OSS 下载：

```bash
# 消费历史数据
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic my-topic \
  --from-beginning \
  --max-messages 100

# Kafka 会自动从 OSS 获取历史段文件
```

### 手动清理

```bash
# 强制清理本地数据 (谨慎使用)
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics \
  --entity-name my-topic \
  --alter \
  --add-config local.retention.bytes=0
```

## 总结

历史数据卸载到 OSS 的核心是正确配置**本地保留策略**：

1. **设置 `local.retention.bytes=1`** 强制快速卸载
2. **配置 OSS 存储后端** 确保上传目标可用
3. **监控卸载过程** 通过日志和指标验证
4. **优化性能配置** 平衡速度和资源使用

通过这种方式，Kafka 可以自动将历史数据透明地迁移到成本更低的 OSS 存储，同时保持本地磁盘的高性能。

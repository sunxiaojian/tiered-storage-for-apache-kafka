# Kafka 分层存储：阿里云 OSS 完整配置指南

## 目录

- [概述](#概述)
- [基础配置](#基础配置)
- [认证配置](#认证配置)
- [性能配置](#性能配置)
- [安全配置](#安全配置)
- [存储配置](#存储配置)
- [完整配置示例](#完整配置示例)
- [参数详解](#参数详解)
- [故障排除](#故障排除)
- [最佳实践](#最佳实践)

## 概述

阿里云对象存储服务 (Object Storage Service, OSS) 可以作为 Apache Kafka 分层存储的远程存储后端。本指南提供完整的配置方法和所有参数的详细解释。

### 架构概览

```
Kafka Broker → RemoteStorageManager → OSS Storage Backend
                        ↓
OSS Bucket ←→ Log Segments + Indexes + Manifests
```

### 主要特性

- ✅ **多地域支持**: 支持所有阿里云 OSS 地域
- ✅ **存储类别**: 支持 Standard、IA、Archive、ColdArchive
- ✅ **分片上传**: 支持大文件分片上传优化性能
- ✅ **安全认证**: 支持多种认证方式
- ✅ **监控指标**: 完整的性能和错误指标

## 基础配置

### 必需配置

```properties
# =====================
# Kafka 分层存储基础配置
# =====================

# 启用分层存储
remote.log.storage.system.enable=true

# 远程存储管理器类路径
remote.log.storage.manager.class.path=/path/to/tiered-storage-core.jar:/path/to/tiered-storage-oss.jar

# 远程存储管理器类名
remote.log.storage.manager.class.name=io.aiven.kafka.tieredstorage.RemoteStorageManager

# 分块大小 (必需参数)
rsm.config.chunk.size=4194304

# =====================
# OSS 存储后端配置
# =====================

# OSS 存储后端类
rsm.config.storage.backend.class=io.aiven.kafka.tieredstorage.storage.oss.OSSStorage

# OSS 存储桶名称
rsm.config.storage.oss.bucket.name=your-kafka-bucket

# OSS 端点 URL
rsm.config.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
```

## 认证配置

OSS 支持多种认证方式，任选其一即可。

### 方法1: Access Key 认证 (推荐)

```properties
# Access Key ID
rsm.config.storage.oss.access.key.id=LTAI5t6A7B8C9D0E1F2G3H4

# Access Key Secret
rsm.config.storage.oss.access.key.secret=your-secure-access-key-secret
```

#### 获取 Access Key

1. 登录 [阿里云控制台](https://ram.console.aliyun.com/)
2. 进入 **AccessKey 管理**
3. 创建新的 Access Key 或使用现有 Key
4. 记录 Access Key ID 和 Access Key Secret

### 方法2: 凭证文件认证

```properties
# 凭证文件路径
rsm.config.storage.oss.credentials.file=/path/to/oss/credentials.properties
```

#### 凭证文件格式

```properties
# /path/to/oss/credentials.properties
accessKeyId=LTAI5t6A7B8C9D0E1F2G3H4
accessKeySecret=your-secure-access-key-secret
securityToken=your-optional-security-token
```

#### 文件权限
```bash
# 设置文件权限
chmod 600 /path/to/oss/credentials.properties
chown kafka:kafka /path/to/oss/credentials.properties
```

### 方法3: 临时凭证认证 (STS Token)

```properties
# 安全令牌 (STS Token)
rsm.config.storage.oss.security.token=your-sts-security-token
```

#### 使用场景
- 临时访问权限
- 角色扮演场景
- 提高安全性

## 性能配置

### 上传性能优化

```properties
# 分片上传大小 (默认 8MB)
rsm.config.storage.oss.multipart.upload.part.size=8388608

# 上传速率限制 (100MB/s)
rsm.config.upload.rate.limit.bytes.per.second=104857600

# 连接超时 (毫秒，默认 50秒)
rsm.config.storage.oss.connection.timeout=30000

# Socket 超时 (毫秒，默认 50秒)
rsm.config.storage.oss.socket.timeout=60000
```

### 下载性能优化

```properties
# 磁盘缓存配置
rsm.config.fetch.chunk.cache.class=io.aiven.kafka.tieredstorage.fetch.cache.DiskChunkCache
rsm.config.fetch.chunk.cache.path=/var/lib/kafka/oss-cache
rsm.config.fetch.chunk.cache.size=10737418240
rsm.config.fetch.chunk.cache.prefetch.max.size=16777216
rsm.config.fetch.chunk.cache.retention.ms=3600000

# 内存缓存配置 (可选)
rsm.config.fetch.chunk.cache.class=io.aiven.kafka.tieredstorage.fetch.cache.MemoryChunkCache
rsm.config.fetch.chunk.cache.size=2147483648
```

### 任务调度优化

```properties
# 上传任务间隔 (30秒)
remote.log.manager.task.interval.ms=30000

# 保留检查间隔 (5分钟)
log.retention.check.interval.ms=300000
```

## 安全配置

### SSL 和证书

```properties
# SSL 证书验证 (默认启用，生产环境必须启用)
rsm.config.storage.oss.certificate.check.enabled=true

# 生产环境配置
rsm.config.storage.oss.certificate.check.enabled=true

# 测试环境配置 (谨慎使用)
rsm.config.storage.oss.certificate.check.enabled=false
```

### 数据完整性

```properties
# Checksum 验证 (默认禁用，因为 Kafka 已验证)
rsm.config.storage.oss.checksum.check.enabled=false

# 如需额外验证可启用
rsm.config.storage.oss.checksum.check.enabled=true
```

### 加密配置 (可选)

```properties
# 启用加密
rsm.config.encryption.enabled=true

# RSA 公钥文件路径
rsm.config.encryption.rsa.public.key.path=/path/to/public.pem

# RSA 私钥文件路径
rsm.config.encryption.rsa.private.key.path=/path/to/private.pem
```

## 存储配置

### 存储类别

```properties
# 标准存储 (默认，适用于频繁访问)
rsm.config.storage.oss.storage.class=Standard

# 低频访问存储 (适用于每月访问1-2次)
rsm.config.storage.oss.storage.class=IA

# 归档存储 (适用于半年访问1次)
rsm.config.storage.oss.storage.class=Archive

# 冷归档存储 (适用于1年访问1次)
rsm.config.storage.oss.storage.class=ColdArchive
```

### 存储类别对比

| 存储类别 | 适用场景 | 最低存储时间 | 检索时间 | 存储成本 | 访问成本 |
|---------|---------|-------------|---------|---------|---------|
| **Standard** | 频繁访问 | 无 | 毫秒级 | 高 | 低 |
| **IA** | 每月1-2次 | 30天 | 秒级 | 中 | 中 |
| **Archive** | 半年1次 | 60天 | 1分钟 | 低 | 高 |
| **ColdArchive** | 1年1次 | 180天 | 5分钟 | 最低 | 最高 |

### 地域配置

```properties
# OSS 地域 (可选，提高性能)
rsm.config.storage.oss.region=cn-hangzhou
```

#### 阿里云 OSS 地域端点对照表

| 地域 | 地域标识 | 外网端点 | 内网端点 |
|-----|---------|---------|---------|
| 华东1 (杭州) | cn-hangzhou | `https://oss-cn-hangzhou.aliyuncs.com` | `https://oss-cn-hangzhou-internal.aliyuncs.com` |
| 华东2 (上海) | cn-shanghai | `https://oss-cn-shanghai.aliyuncs.com` | `https://oss-cn-shanghai-internal.aliyuncs.com` |
| 华北1 (青岛) | cn-qingdao | `https://oss-cn-qingdao.aliyuncs.com` | `https://oss-cn-qingdao-internal.aliyuncs.com` |
| 华北2 (北京) | cn-beijing | `https://oss-cn-beijing.aliyuncs.com` | `https://oss-cn-beijing-internal.aliyuncs.com` |
| 华北3 (张家口) | cn-zhangjiakou | `https://oss-cn-zhangjiakou.aliyuncs.com` | `https://oss-cn-zhangjiakou-internal.aliyuncs.com` |
| 华南1 (深圳) | cn-shenzhen | `https://oss-cn-shenzhen.aliyuncs.com` | `https://oss-cn-shenzhen-internal.aliyuncs.com` |
| 西南1 (成都) | cn-chengdu | `https://oss-cn-chengdu.aliyuncs.com` | `https://oss-cn-chengdu-internal.aliyuncs.com` |
| 中国香港 | cn-hongkong | `https://oss-cn-hongkong.aliyuncs.com` | `https://oss-cn-hongkong-internal.aliyuncs.com` |

## 完整配置示例

### 生产环境配置

```properties
# =====================
# Kafka Broker 配置
# =====================

# 基本配置
broker.id=1
listeners=PLAINTEXT://0.0.0.0:9092
log.dirs=/var/lib/kafka/data
zookeeper.connect=zookeeper:2181

# 分层存储启用
remote.log.storage.system.enable=true
remote.log.manager.task.interval.ms=30000
log.retention.check.interval.ms=300000

# 远程日志元数据管理器
remote.log.metadata.manager.class.name=org.apache.kafka.server.log.remote.metadata.storage.TopicBasedRemoteLogMetadataManager
remote.log.metadata.manager.listener.name=PLAINTEXT
rlmm.config.remote.log.metadata.topic.replication.factor=3
rlmm.config.remote.log.metadata.common.client.bootstrap.servers=kafka-1:9092,kafka-2:9092,kafka-3:9092

# 远程存储管理器
remote.log.storage.manager.class.name=io.aiven.kafka.tieredstorage.RemoteStorageManager
remote.log.storage.manager.class.path=/opt/kafka/libs/tiered-storage-core.jar:/opt/kafka/libs/tiered-storage-oss.jar

# =====================
# 分层存储核心配置
# =====================

rsm.config.chunk.size=4194304
rsm.config.upload.rate.limit.bytes.per.second=104857600
rsm.config.custom.metadata.fields.include=REMOTE_SIZE,OBJECT_PREFIX

# 压缩配置
rsm.config.compression.enabled=true
rsm.config.compression.heuristic.enabled=true

# 加密配置 (可选)
rsm.config.encryption.enabled=false

# =====================
# OSS 存储后端配置
# =====================

rsm.config.storage.backend.class=io.aiven.kafka.tieredstorage.storage.oss.OSSStorage

# OSS 基础配置
rsm.config.storage.oss.bucket.name=kafka-prod-tiered-storage
rsm.config.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
rsm.config.storage.oss.region=cn-hangzhou

# OSS 认证配置
rsm.config.storage.oss.access.key.id=LTAI5t6A7B8C9D0E1F2G3H4
rsm.config.storage.oss.access.key.secret=your-secure-access-key-secret

# OSS 性能配置
rsm.config.storage.oss.multipart.upload.part.size=16777216
rsm.config.storage.oss.connection.timeout=30000
rsm.config.storage.oss.socket.timeout=60000

# OSS 安全配置
rsm.config.storage.oss.certificate.check.enabled=true
rsm.config.storage.oss.checksum.check.enabled=false

# OSS 存储类别 (生产环境推荐 IA)
rsm.config.storage.oss.storage.class=IA

# =====================
# 缓存配置
# =====================

rsm.config.fetch.chunk.cache.class=io.aiven.kafka.tieredstorage.fetch.cache.DiskChunkCache
rsm.config.fetch.chunk.cache.path=/var/lib/kafka/tiered-cache
rsm.config.fetch.chunk.cache.size=10737418240
rsm.config.fetch.chunk.cache.prefetch.max.size=16777216
rsm.config.fetch.chunk.cache.retention.ms=3600000

# =====================
# 监控配置
# =====================

rsm.config.metrics.recording.level=INFO
rsm.config.metrics.num.samples=2
rsm.config.metrics.sample.window.ms=30000
```

### 主题级配置

```bash
# 创建启用分层存储的主题
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create \
  --topic user-events \
  --partitions 6 \
  --replication-factor 3 \
  --config remote.storage.enable=true \
  --config local.retention.bytes=1073741824 \
  --config retention.bytes=1099511627776 \
  --config segment.bytes=536870912 \
  --config segment.ms=3600000
```

### Docker Compose 配置

```yaml
version: '3.8'
services:
  kafka:
    image: confluentinc/cp-kafka:7.4.0
    environment:
      # Kafka 基础配置
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092

      # 分层存储系统启用
      KAFKA_REMOTE_LOG_STORAGE_SYSTEM_ENABLE: "true"
      KAFKA_REMOTE_LOG_MANAGER_TASK_INTERVAL_MS: 30000
      KAFKA_LOG_RETENTION_CHECK_INTERVAL_MS: 300000

      # RLMM 配置
      KAFKA_REMOTE_LOG_METADATA_MANAGER_CLASS_NAME: "org.apache.kafka.server.log.remote.metadata.storage.TopicBasedRemoteLogMetadataManager"
      KAFKA_REMOTE_LOG_METADATA_MANAGER_LISTENER_NAME: "PLAINTEXT"
      KAFKA_RLMM_CONFIG_REMOTE_LOG_METADATA_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_RLMM_CONFIG_REMOTE_LOG_METADATA_COMMON_CLIENT_BOOTSTRAP_SERVERS: "localhost:9092"

      # RSM 配置
      KAFKA_REMOTE_LOG_STORAGE_MANAGER_CLASS_NAME: "io.aiven.kafka.tieredstorage.RemoteStorageManager"
      KAFKA_REMOTE_LOG_STORAGE_MANAGER_CLASS_PATH: "/opt/kafka/libs/*"

      # 核心配置
      KAFKA_RSM_CONFIG_CHUNK_SIZE: 4194304
      KAFKA_RSM_CONFIG_UPLOAD_RATE_LIMIT_BYTES_PER_SECOND: 104857600

      # OSS 配置
      KAFKA_RSM_CONFIG_STORAGE_BACKEND_CLASS: "io.aiven.kafka.tieredstorage.storage.oss.OSSStorage"
      KAFKA_RSM_CONFIG_STORAGE_OSS_BUCKET_NAME: "kafka-tiered-storage"
      KAFKA_RSM_CONFIG_STORAGE_OSS_ENDPOINT: "https://oss-cn-hangzhou.aliyuncs.com"
      KAFKA_RSM_CONFIG_STORAGE_OSS_ACCESS_KEY_ID: "LTAI5t6A7B8C9D0E1F2G3H4"
      KAFKA_RSM_CONFIG_STORAGE_OSS_ACCESS_KEY_SECRET: "your-access-key-secret"
      KAFKA_RSM_CONFIG_STORAGE_OSS_MULTIPART_UPLOAD_PART_SIZE: 8388608
      KAFKA_RSM_CONFIG_STORAGE_OSS_STORAGE_CLASS: "IA"

      # 缓存配置
      KAFKA_RSM_CONFIG_FETCH_CHUNK_CACHE_CLASS: "io.aiven.kafka.tieredstorage.fetch.cache.DiskChunkCache"
      KAFKA_RSM_CONFIG_FETCH_CHUNK_CACHE_PATH: "/var/lib/kafka/cache"
      KAFKA_RSM_CONFIG_FETCH_CHUNK_CACHE_SIZE: 1073741824

    volumes:
      - ./libs:/opt/kafka/libs:ro
      - kafka-data:/var/lib/kafka/data
      - kafka-cache:/var/lib/kafka/cache

volumes:
  kafka-data:
  kafka-cache:
```

## 参数详解

### 必需参数

| 参数 | 类型 | 默认值 | 重要性 | 说明 |
|-----|------|-------|-------|------|
| `rsm.config.storage.oss.bucket.name` | string | 无 | high | OSS 存储桶名称 |
| `rsm.config.storage.oss.endpoint` | string | 无 | high | OSS 服务端点 URL |

### 认证参数 (任选其一)

| 参数 | 类型 | 默认值 | 重要性 | 说明 |
|-----|------|-------|-------|------|
| `rsm.config.storage.oss.access.key.id` | password | null | medium | Access Key ID |
| `rsm.config.storage.oss.access.key.secret` | password | null | medium | Access Key Secret |
| `rsm.config.storage.oss.credentials.file` | string | null | medium | 凭证文件路径 |
| `rsm.config.storage.oss.security.token` | password | null | medium | STS 安全令牌 |

### 性能参数

| 参数 | 类型 | 默认值 | 范围 | 重要性 | 说明 |
|-----|------|-------|------|-------|------|
| `rsm.config.storage.oss.multipart.upload.part.size` | int | 8388608 | 102400-1900000000 | medium | 分片上传大小 (8MB) |
| `rsm.config.storage.oss.connection.timeout` | long | 50000 | 1-9223372036854775807 | low | 连接超时 (50秒) |
| `rsm.config.storage.oss.socket.timeout` | long | 50000 | 1-9223372036854775807 | low | Socket 超时 (50秒) |

### 安全参数

| 参数 | 类型 | 默认值 | 重要性 | 说明 |
|-----|------|-------|-------|------|
| `rsm.config.storage.oss.certificate.check.enabled` | boolean | true | low | SSL 证书验证 |
| `rsm.config.storage.oss.checksum.check.enabled` | boolean | false | medium | Checksum 验证 |

### 存储参数

| 参数 | 类型 | 默认值 | 可选值 | 重要性 | 说明 |
|-----|------|-------|-------|-------|------|
| `rsm.config.storage.oss.storage.class` | string | Standard | Standard, IA, Archive, ColdArchive | low | 存储类别 |
| `rsm.config.storage.oss.region` | string | null | 地域标识 | medium | OSS 地域 |

## 故障排除

### 常见错误及解决方案

#### 1. 认证失败

**错误信息:**
```
org.apache.kafka.common.KafkaException: Failed to authenticate with OSS
```

**解决方案:**
```properties
# 检查 Access Key 是否正确
rsm.config.storage.oss.access.key.id=LTAI5t6A7B8C9D0E1F2G3H4
rsm.config.storage.oss.access.key.secret=your-correct-secret

# 或使用凭证文件
rsm.config.storage.oss.credentials.file=/path/to/credentials.properties
```

#### 2. Bucket 不存在

**错误信息:**
```
NoSuchBucket: The specified bucket does not exist
```

**解决方案:**
```bash
# 创建 OSS Bucket
ossutil mb oss://your-bucket-name

# 或检查 bucket 名称是否正确
rsm.config.storage.oss.bucket.name=correct-bucket-name
```

#### 3. 权限不足

**错误信息:**
```
AccessDenied: Access denied
```

**解决方案:**
```json
// 为 RAM 用户添加 OSS 权限策略
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:GetObject",
        "oss:PutObject",
        "oss:DeleteObject",
        "oss:ListObjects"
      ],
      "Resource": [
        "acs:oss:*:*:your-bucket-name",
        "acs:oss:*:*:your-bucket-name/*"
      ]
    }
  ]
}
```

#### 4. 网络超时

**错误信息:**
```
ConnectionTimeout: Connect timeout
```

**解决方案:**
```properties
# 增加超时时间
rsm.config.storage.oss.connection.timeout=60000
rsm.config.storage.oss.socket.timeout=120000

# 检查网络连接
curl -I https://oss-cn-hangzhou.aliyuncs.com
```

#### 5. 上传失败

**错误信息:**
```
MultipartUploadException: Failed to upload part
```

**解决方案:**
```properties
# 减小分片大小
rsm.config.storage.oss.multipart.upload.part.size=4194304

# 检查磁盘空间
df -h /var/lib/kafka

# 检查上传速率限制
rsm.config.upload.rate.limit.bytes.per.second=52428800
```

### 监控和诊断

#### JMX 指标监控

```bash
# 连接到 Kafka JMX 端口 (默认 9999)
jconsole localhost:9999

# 关键指标：
# - kafka.tieredstorage:type=RemoteStorageManager,name=object-upload-bytes-total
# - kafka.tieredstorage:type=RemoteStorageManager,name=segment-copy-rate
# - kafka.tieredstorage:type=RemoteStorageManager,name=segment-copy-error-rate
```

#### 日志分析

```bash
# 查看分层存储日志
tail -f logs/kafka.log | grep -E "(tieredstorage|OSS|RemoteStorageManager)"

# 查看上传日志
grep "Successfully uploaded segment" logs/kafka.log

# 查看错误日志
grep "ERROR.*OSS" logs/kafka.log
```

#### OSS 控制台检查

1. 登录 [OSS 控制台](https://oss.console.aliyun.com/)
2. 查看 Bucket 中的对象
3. 检查访问日志
4. 验证权限设置

## 最佳实践

### 1. 安全最佳实践

#### RAM 子账号权限控制
```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:GetObject",
        "oss:PutObject",
        "oss:DeleteObject",
        "oss:ListObjects",
        "oss:AbortMultipartUpload"
      ],
      "Resource": [
        "acs:oss:*:*:kafka-*",
        "acs:oss:*:*:kafka-*/*"
      ],
      "Condition": {
        "IpAddress": {
          "acs:oss:SourceIp": "192.168.0.0/16"
        }
      }
    }
  ]
}
```

#### 定期轮换 Access Key
```bash
# 创建新 Access Key
# 更新配置
# 重启 Kafka 服务
# 删除旧 Access Key
```

### 2. 性能优化

#### 根据数据特征选择配置
```properties
# 大文件场景
rsm.config.storage.oss.multipart.upload.part.size=33554432  # 32MB
rsm.config.chunk.size=8388608  # 8MB

# 小文件场景
rsm.config.storage.oss.multipart.upload.part.size=4194304   # 4MB
rsm.config.chunk.size=2097152  # 2MB
```

#### 缓存策略
```properties
# 频繁访问场景
rsm.config.fetch.chunk.cache.size=5368709120  # 5GB 缓存

# 偶尔访问场景
rsm.config.fetch.chunk.cache.size=1073741824  # 1GB 缓存
```

### 3. 成本优化

#### 存储类别选择策略
```properties
# 实时数据 (保留 7 天)
local.retention.ms=604800000
storage.class=Standard

# 历史数据 (保留 1 年)
retention.ms=31536000000
storage.class=IA

# 归档数据 (保留 3 年)
retention.ms=94608000000
storage.class=Archive
```

#### 生命周期管理
```xml
<!-- OSS 生命周期规则 -->
<LifecycleConfiguration>
  <Rule>
    <ID>kafka-archive-rule</ID>
    <Prefix>kafka-</Prefix>
    <Status>Enabled</Status>
    <Transition>
      <Days>30</Days>
      <StorageClass>IA</StorageClass>
    </Transition>
    <Transition>
      <Days>365</Days>
      <StorageClass>Archive</StorageClass>
    </Transition>
  </Rule>
</LifecycleConfiguration>
```

### 4. 高可用配置

#### 多地域部署
```properties
# 主地域配置
rsm.config.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
rsm.config.storage.oss.region=cn-hangzhou

# 备地域配置 (通过 DNS 切换)
rsm.config.storage.oss.endpoint=https://oss-cn-shanghai.aliyuncs.com
rsm.config.storage.oss.region=cn-shanghai
```

#### 备份策略
```bash
# OSS 跨区域复制
ossutil bucket-replication --method put \
  --src-bucket kafka-prod \
  --dest-bucket kafka-prod-backup \
  --dest-region oss-cn-shanghai
```

### 5. 监控告警

#### 关键指标监控
- **上传成功率**: > 99.9%
- **平均上传延迟**: < 30秒
- **缓存命中率**: > 80%
- **OSS API 错误率**: < 0.1%

#### 告警配置
```bash
# OSS 上传失败告警
# 本地磁盘使用率 > 85% 告警
# 缓存命中率 < 70% 告警
# 网络延迟 > 1000ms 告警
```

## 总结

OSS 作为 Kafka 分层存储的后端提供了高可用、高性能、低成本的远程存储解决方案。通过合理配置认证、性能、安全和存储参数，可以实现：

- ✅ **无缝的数据分层**: 自动将历史数据卸载到 OSS
- ✅ **显著的成本节约**: 相比本地存储降低 60-80% 成本
- ✅ **灵活的存储策略**: 支持多种存储类别满足不同场景
- ✅ **企业级安全性**: 支持多种认证方式和加密
- ✅ **完整的监控体系**: 提供全面的性能和错误指标

按照本指南的配置，您的 Kafka 集群将能够稳定高效地使用阿里云 OSS 作为分层存储的后端。

---

**版本信息**: Kafka Tiered Storage v1.2.0  
**最后更新**: 2025-12-22  
**OSS 兼容版本**: 阿里云 OSS 全版本支持

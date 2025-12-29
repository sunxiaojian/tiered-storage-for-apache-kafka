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

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import io.aiven.kafka.tieredstorage.config.validators.NonEmptyPassword;
import io.aiven.kafka.tieredstorage.config.validators.Null;
import io.aiven.kafka.tieredstorage.config.validators.ValidUrl;

import com.aliyun.oss.model.StorageClass;

public class OSSStorageConfig extends AbstractConfig {

    public static final String OSS_BUCKET_NAME_CONFIG = "oss.bucket.name";
    private static final String OSS_BUCKET_NAME_DOC = "OSS bucket to store log segments";
    public static final String OSS_ENDPOINT_CONFIG = "oss.endpoint";
    private static final String OSS_ENDPOINT_DOC = "OSS endpoint URL";
    public static final String OSS_REGION_CONFIG = "oss.region";
    private static final String OSS_REGION_DOC = "OSS region where bucket is placed";

    public static final String OSS_ACCESS_KEY_ID_CONFIG = "oss.access.key.id";
    private static final String OSS_ACCESS_KEY_ID_DOC = "OSS access key ID";
    public static final String OSS_ACCESS_KEY_SECRET_CONFIG = "oss.access.key.secret";
    private static final String OSS_ACCESS_KEY_SECRET_DOC = "OSS access key secret";

    private static final String OSS_SOCKET_TIMEOUT_CONFIG = "oss.socket.timeout";
    private static final String OSS_SOCKET_TIMEOUT_DOC = "OSS socket timeout in milliseconds";
    private static final String OSS_CONNECTION_TIMEOUT_CONFIG = "oss.connection.timeout";
    private static final String OSS_CONNECTION_TIMEOUT_DOC = "OSS connection timeout in milliseconds";

    public static final String OSS_SECURITY_TOKEN_CONFIG = "oss.security.token";
    private static final String OSS_SECURITY_TOKEN_DOC = "OSS security token for temporary credentials";

    public static final String OSS_CREDENTIALS_FILE_CONFIG = "oss.credentials.file";
    private static final String OSS_CREDENTIALS_FILE_DOC =
        "This property is used to define a file where credentials are defined. "
            + "The file might be updated during process life cycle, "
            + "and the credentials will be reloaded from the file.";

    public static final String OSS_CERTIFICATE_CHECK_ENABLED_CONFIG = "oss.certificate.check.enabled";
    private static final String OSS_CERTIFICATE_CHECK_ENABLED_DOC =
        "This property is used to enable SSL certificate checking for OSS services. "
            + "When set to \"false\", the SSL certificate checking for OSS services will be bypassed. "
            + "Use with caution and always only in a test environment, as disabling certificate lead the storage "
            + "to be vulnerable to man-in-the-middle attacks.";

    public static final String OSS_CHECKSUM_CHECK_ENABLED_CONFIG = "oss.checksum.check.enabled";
    private static final String OSS_CHECKSUM_CHECK_ENABLED_DOC =
        "This property is used to enable checksum validation done by OSS library. "
            + "When set to \"false\", there will be no validation. "
            + "It is disabled by default as Kafka already validates integrity of the files.";

    public static final String OSS_STORAGE_CLASS_CONFIG = "oss.storage.class";
    private static final String OSS_STORAGE_CLASS_DOC = "Defines which storage class to use when uploading objects";
    static final String OSS_STORAGE_CLASS_DEFAULT = StorageClass.Standard.toString();

    public static final String OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG = "oss.multipart.upload.part.size";
    private static final String OSS_MULTIPART_UPLOAD_PART_SIZE_DOC = "Size of parts in bytes to use when uploading. "
        + "All parts but the last one will have this size. "
        + "The smaller the part size, the more calls to OSS are needed to upload a file; increasing costs. "
        + "The higher the part size, the more memory is needed to buffer the part. "
        + "Valid values: between 100KiB and 1.8GiB";
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_MIN = 100 * 1024; // 100KiB
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_MAX = 1900000000; // ~1.8GiB (safe limit for int)
    static final int OSS_MULTIPART_UPLOAD_PART_SIZE_DEFAULT = 8 * 1024 * 1024; // 8MiB

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
                OSS_SECURITY_TOKEN_CONFIG,
                ConfigDef.Type.PASSWORD,
                null,
                new NonEmptyPassword(),
                ConfigDef.Importance.MEDIUM,
                OSS_SECURITY_TOKEN_DOC)
            .define(
                OSS_SOCKET_TIMEOUT_CONFIG,
                ConfigDef.Type.LONG,
                50000L,
                Null.or(ConfigDef.Range.between(1, Long.MAX_VALUE)),
                ConfigDef.Importance.LOW,
                OSS_SOCKET_TIMEOUT_DOC)
            .define(
                OSS_CONNECTION_TIMEOUT_CONFIG,
                ConfigDef.Type.LONG,
                50000L,
                Null.or(ConfigDef.Range.between(1, Long.MAX_VALUE)),
                ConfigDef.Importance.LOW,
                OSS_CONNECTION_TIMEOUT_DOC)
            .define(
                OSS_STORAGE_CLASS_CONFIG,
                ConfigDef.Type.STRING,
                OSS_STORAGE_CLASS_DEFAULT,
                ConfigDef.ValidString.in(StorageClass.Standard.toString(),
                        StorageClass.IA.toString(),
                        StorageClass.Archive.toString(),
                        StorageClass.ColdArchive.toString()),
                ConfigDef.Importance.LOW,
                OSS_STORAGE_CLASS_DOC)
            .define(
                OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG,
                ConfigDef.Type.INT,
                OSS_MULTIPART_UPLOAD_PART_SIZE_DEFAULT,
                ConfigDef.Range.between(OSS_MULTIPART_UPLOAD_PART_SIZE_MIN, OSS_MULTIPART_UPLOAD_PART_SIZE_MAX),
                ConfigDef.Importance.MEDIUM,
                OSS_MULTIPART_UPLOAD_PART_SIZE_DOC)
            .define(
                OSS_CREDENTIALS_FILE_CONFIG,
                ConfigDef.Type.STRING,
                null,
                ConfigDef.Importance.MEDIUM,
                OSS_CREDENTIALS_FILE_DOC)
            .define(OSS_CERTIFICATE_CHECK_ENABLED_CONFIG,
                ConfigDef.Type.BOOLEAN,
                true,
                ConfigDef.Importance.LOW,
                OSS_CERTIFICATE_CHECK_ENABLED_DOC)
            .define(OSS_CHECKSUM_CHECK_ENABLED_CONFIG,
                ConfigDef.Type.BOOLEAN,
                false,
                ConfigDef.Importance.MEDIUM,
                OSS_CHECKSUM_CHECK_ENABLED_DOC);
    }

    public OSSStorageConfig(final Map<String, ?> props) {
        super(configDef(), props);
        validate();
    }

    private void validate() {
        if (getPassword(OSS_ACCESS_KEY_ID_CONFIG) != null
            ^ getPassword(OSS_ACCESS_KEY_SECRET_CONFIG) != null) {
            throw new ConfigException(OSS_ACCESS_KEY_ID_CONFIG
                + " and "
                + OSS_ACCESS_KEY_SECRET_CONFIG
                + " must be defined together");
        }
    }

    public String bucketName() {
        return getString(OSS_BUCKET_NAME_CONFIG);
    }

    public String endpoint() {
        return getString(OSS_ENDPOINT_CONFIG);
    }

    public String region() {
        return getString(OSS_REGION_CONFIG);
    }

    public String accessKeyId() {
        final var password = getPassword(OSS_ACCESS_KEY_ID_CONFIG);
        return password != null ? password.value() : null;
    }

    public String accessKeySecret() {
        final var password = getPassword(OSS_ACCESS_KEY_SECRET_CONFIG);
        return password != null ? password.value() : null;
    }

    public String securityToken() {
        final var password = getPassword(OSS_SECURITY_TOKEN_CONFIG);
        return password != null ? password.value() : null;
    }

    public Duration socketTimeout() {
        final var value = getLong(OSS_SOCKET_TIMEOUT_CONFIG);
        if (value != null) {
            return Duration.ofMillis(value);
        } else {
            return null;
        }
    }

    public Duration connectionTimeout() {
        final var value = getLong(OSS_CONNECTION_TIMEOUT_CONFIG);
        if (value != null) {
            return Duration.ofMillis(value);
        } else {
            return null;
        }
    }

    public StorageClass storageClass() {
        return StorageClass.valueOf(getString(OSS_STORAGE_CLASS_CONFIG));
    }

    public String credentialsFile() {
        return getString(OSS_CREDENTIALS_FILE_CONFIG);
    }

    public Boolean certificateCheckEnabled() {
        return getBoolean(OSS_CERTIFICATE_CHECK_ENABLED_CONFIG);
    }

    public Boolean checksumCheckEnabled() {
        return getBoolean(OSS_CHECKSUM_CHECK_ENABLED_CONFIG);
    }

    public int uploadPartSize() {
        return getInt(OSS_MULTIPART_UPLOAD_PART_SIZE_CONFIG);
    }
}

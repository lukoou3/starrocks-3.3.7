// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.server;

import com.google.common.base.Strings;
import com.staros.proto.FileStoreInfo;
import com.staros.util.LockCloseable;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Table;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.InvalidConfException;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.connector.share.credential.CloudConfigurationConstants;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.storagevolume.StorageVolume;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.server.GlobalStateMgr.NEXT_ID_INIT_VALUE;

public class SharedDataStorageVolumeMgr extends StorageVolumeMgr {
    private static final Logger LOG = LogManager.getLogger(SharedDataStorageVolumeMgr.class);

    @Override
    public StorageVolume getStorageVolumeByName(String svName) {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            try {
                FileStoreInfo fileStoreInfo = GlobalStateMgr.getCurrentState().getStarOSAgent().getFileStoreByName(svName);
                if (fileStoreInfo == null) {
                    return null;
                }
                return StorageVolume.fromFileStoreInfo(fileStoreInfo);
            } catch (DdlException e) {
                throw new SemanticException(e.getMessage());
            }
        }
    }

    @Override
    public StorageVolume getStorageVolume(String svId) {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            try {
                FileStoreInfo fileStoreInfo = GlobalStateMgr.getCurrentState().getStarOSAgent().getFileStore(svId);
                if (fileStoreInfo == null) {
                    return null;
                }
                return StorageVolume.fromFileStoreInfo(fileStoreInfo);
            } catch (DdlException e) {
                throw new SemanticException(e.getMessage());
            }
        }
    }

    @Override
    public List<String> listStorageVolumeNames() throws DdlException {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            return GlobalStateMgr.getCurrentState().getStarOSAgent().listFileStore()
                    .stream().map(FileStoreInfo::getFsName).collect(Collectors.toList());
        }
    }

    @Override
    protected String createInternalNoLock(String name, String svType, List<String> locations,
                                          Map<String, String> params, Optional<Boolean> enabled, String comment)
            throws DdlException {
        FileStoreInfo fileStoreInfo = StorageVolume.createFileStoreInfo(name, svType,
                locations, params, enabled.orElse(true), comment);
        return GlobalStateMgr.getCurrentState().getStarOSAgent().addFileStore(fileStoreInfo);
    }

    @Override
    protected void updateInternalNoLock(StorageVolume sv) throws DdlException {
        GlobalStateMgr.getCurrentState().getStarOSAgent().updateFileStore(sv.toFileStoreInfo());
    }

    @Override
    protected void removeInternalNoLock(StorageVolume sv) throws DdlException {
        GlobalStateMgr.getCurrentState().getStarOSAgent().removeFileStoreByName(sv.getName());
    }

    @Override
    public StorageVolume getDefaultStorageVolume() {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            if (defaultStorageVolumeId.isEmpty()) {
                return getStorageVolumeByName(BUILTIN_STORAGE_VOLUME);
            }
            return getStorageVolume(getDefaultStorageVolumeId());
        }
    }

    private StorageVolume getStorageVolumeOfDb(String svName) throws DdlException {
        StorageVolume sv = null;
        if (svName.equals(StorageVolumeMgr.DEFAULT)) {
            sv = getDefaultStorageVolume();
            if (sv == null) {
                throw ErrorReportException.report(ErrorCode.ERR_NO_DEFAULT_STORAGE_VOLUME);
            }
        } else {
            sv = getStorageVolumeByName(svName);
            if (sv == null) {
                throw new DdlException("Unknown storage volume \"" + svName + "\"");
            }
        }
        return sv;
    }

    // In replay phase, the check of storage volume existence can be skipped.
    // Because it has been checked when creating db.
    private boolean bindDbToStorageVolume(String svId, long dbId, boolean isReplay) {
        if (svId == null) {
            return false;
        }
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (!isReplay && !storageVolumeToDbs.containsKey(svId) && getStorageVolume(svId) == null) {
                return false;
            }
            Set<Long> dbs = storageVolumeToDbs.getOrDefault(svId, new HashSet<>());
            dbs.add(dbId);
            storageVolumeToDbs.put(svId, dbs);
            dbToStorageVolume.put(dbId, svId);
            return true;
        }
    }

    @Override
    public boolean bindDbToStorageVolume(String svName, long dbId) throws DdlException {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            StorageVolume sv = getStorageVolumeOfDb(svName);
            if (!sv.getEnabled()) {
                throw new DdlException(String.format("Storage volume %s is disabled", svName));
            }
            return bindDbToStorageVolume(sv.getId(), dbId, false);
        }
    }

    @Override
    public void replayBindDbToStorageVolume(String svId, long dbId) {
        bindDbToStorageVolume(svId, dbId, true);
    }

    @Override
    public void unbindDbToStorageVolume(long dbId) {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (!dbToStorageVolume.containsKey(dbId)) {
                return;
            }
            String svId = dbToStorageVolume.remove(dbId);
            Set<Long> dbs = storageVolumeToDbs.get(svId);
            dbs.remove(dbId);
            if (dbs.isEmpty()) {
                storageVolumeToDbs.remove(svId);
            }
        }
    }

    private StorageVolume getStorageVolumeOfTable(String svName, long dbId) throws DdlException {
        StorageVolume sv = null;
        if (svName.isEmpty()) {
            String dbStorageVolumeId = getStorageVolumeIdOfDb(dbId);
            if (dbStorageVolumeId != null) {
                return getStorageVolume(dbStorageVolumeId);
            } else {
                sv = getDefaultStorageVolume();
                if (sv == null) {
                    throw ErrorReportException.report(ErrorCode.ERR_NO_DEFAULT_STORAGE_VOLUME);
                }
            }
        } else if (svName.equals(StorageVolumeMgr.DEFAULT)) {
            sv = getDefaultStorageVolume();
            if (sv == null) {
                throw ErrorReportException.report(ErrorCode.ERR_NO_DEFAULT_STORAGE_VOLUME);
            }
        } else {
            sv = getStorageVolumeByName(svName);
            if (sv == null) {
                throw new DdlException("Unknown storage volume \"" + svName + "\"");
            }
        }
        return sv;
    }

    @Override
    public boolean bindTableToStorageVolume(String svName, long dbId, long tableId) throws DdlException {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            StorageVolume sv = getStorageVolumeOfTable(svName, dbId);
            if (!sv.getEnabled()) {
                throw new DdlException(String.format("Storage volume %s is disabled", sv.getName()));
            }
            return bindTableToStorageVolume(sv.getId(), tableId, false);
        }
    }

    @Override
    public void replayBindTableToStorageVolume(String svId, long tableId) {
        bindTableToStorageVolume(svId, tableId, true);
    }

    // In replay phase, the check of storage volume existence can be skipped.
    // Because it has been checked when creating table.
    private boolean bindTableToStorageVolume(String svId, long tableId, boolean isReplay) {
        if (svId == null) {
            return false;
        }
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (!isReplay && !storageVolumeToDbs.containsKey(svId) &&
                    !storageVolumeToTables.containsKey(svId) &&
                    getStorageVolume(svId) == null) {
                return false;
            }
            Set<Long> tables = storageVolumeToTables.getOrDefault(svId, new HashSet<>());
            tables.add(tableId);
            storageVolumeToTables.put(svId, tables);
            tableToStorageVolume.put(tableId, svId);
            return true;
        }
    }

    @Override
    public void unbindTableToStorageVolume(long tableId) {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (!tableToStorageVolume.containsKey(tableId)) {
                return;
            }
            String svId = tableToStorageVolume.remove(tableId);
            Set<Long> tables = storageVolumeToTables.get(svId);
            tables.remove(tableId);
            if (tables.isEmpty()) {
                storageVolumeToTables.remove(svId);
            }
        }
    }

    @Override
    public String createBuiltinStorageVolume() throws DdlException, AlreadyExistsException {
        if (!Config.enable_load_volume_from_conf) {
            return "";
        }

        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            StorageVolume sv = getStorageVolumeByName(BUILTIN_STORAGE_VOLUME);
            if (sv != null) {
                return sv.getId();
            }

            validateStorageVolumeConfig();
            List<String> locations = parseLocationsFromConfig();
            Map<String, String> params = parseParamsFromConfig();

            FileStoreInfo fileStoreInfo = StorageVolume.createFileStoreInfo(BUILTIN_STORAGE_VOLUME,
                    Config.cloud_native_storage_type, locations, params, true, "");
            String fsKey = parseBuiltinFsKeyFromConfig();
            fileStoreInfo = fileStoreInfo.toBuilder().setFsKey(fsKey).build();

            String svId = GlobalStateMgr.getCurrentState().getStarOSAgent().addFileStore(fileStoreInfo);

            if (getDefaultStorageVolumeId().isEmpty()) {
                setDefaultStorageVolume(BUILTIN_STORAGE_VOLUME);
            }
            return svId;
        }
    }

    public void validateStorageVolumeConfig() throws InvalidConfException {
        switch (Config.cloud_native_storage_type.toLowerCase()) {
            case "s3":
                // validate aws_s3_path configuration.
                normalizeConfigPath(Config.aws_s3_path, "s3", "Config.aws_s3_path", true);
                if (Config.aws_s3_region.isEmpty() && Config.aws_s3_endpoint.isEmpty()) {
                    throw new InvalidConfException(
                            "Both configuration item \"aws_s3_region\" and \"aws_s3_endpoint\" are empty");
                }
                String credentialType = getAwsCredentialType();
                if (credentialType == null) {
                    throw new InvalidConfException("Invalid aws credential configuration.");
                }
                break;
            case "hdfs":
                // validate cloud_native_hdfs_url configuration
                normalizeConfigPath(Config.cloud_native_hdfs_url, "hdfs", "Config.cloud_native_hdfs_url", false);
                break;
            case "azblob":
                if (Config.azure_blob_endpoint.isEmpty()) {
                    throw new InvalidConfException("The configuration item \"azure_blob_endpoint\" is empty.");
                }
                // validate azure_blob_path configuration
                normalizeConfigPath(Config.azure_blob_path, "azblob", "Config.azure_blob_path", true);
                break;
            default:
                throw new InvalidConfException(String.format(
                        "The configuration item \"cloud_native_storage_type = %s\" is invalid, must be HDFS or S3 or AZBLOB.",
                        Config.cloud_native_storage_type));
        }
    }

    @Override
    protected List<List<Long>> getBindingsOfBuiltinStorageVolume() {
        List<List<Long>> bindings = new ArrayList<>();
        List<Long> tableBindings = new ArrayList<>();
        List<Long> dbBindings = new ArrayList<>();
        List<Long> dbIds = GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIdsIncludeRecycleBin().stream()
                .filter(dbid -> dbid > NEXT_ID_INIT_VALUE).collect(Collectors.toList());
        for (Long dbId : dbIds) {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIncludeRecycleBin(dbId);
            Locker locker = new Locker();
            locker.lockDatabase(db, LockType.READ);
            if (dbToStorageVolume.containsKey(dbId)) {
                continue;
            }
            dbBindings.add(dbId);
            try {
                List<Table> tables = GlobalStateMgr.getCurrentState().getLocalMetastore().getTablesIncludeRecycleBin(db);
                for (Table table : tables) {
                    Long tableId = table.getId();
                    if (!tableToStorageVolume.containsKey(tableId) && table.isCloudNativeTableOrMaterializedView()) {
                        tableBindings.add(tableId);
                    }
                }
            } finally {
                locker.unLockDatabase(db, LockType.READ);
            }
        }
        bindings.add(dbBindings);
        bindings.add(tableBindings);
        return bindings;
    }

    private static URI normalizeConfigPath(String uriStr, String defaultScheme, String configNameInErrMsg, boolean matchScheme)
            throws InvalidConfException {
        try {
            URI uri = new URI(uriStr);
            if (!uri.isAbsolute()) {
                uri = new URI(defaultScheme + "://" + uriStr);
            }
            if (Strings.isNullOrEmpty(uri.getAuthority())) {
                throw new InvalidConfException("");
            }
            if (uri.getPort() != -1 && "s3".equals(defaultScheme)) {
                // s3 uri, not allow `:` in authority, e.g. the following url is invalid
                // - s3://{bucket}:3020/b/c
                throw new InvalidConfException("");
            }
            if (matchScheme && !uri.getScheme().equals(defaultScheme)) {
                throw new InvalidConfException("");
            }
            return uri;
        } catch (URISyntaxException | InvalidConfException err) {
            throw new InvalidConfException(
                    String.format("The configuration item \"%s = %s\" is invalid.", configNameInErrMsg, uriStr));
        }
    }

    private static String[] getBucketAndPrefix() throws InvalidConfException {
        URI uri = normalizeConfigPath(Config.aws_s3_path, "s3", "Config.aws_s3_path", true);
        String path = uri.getPath();
        if (path.startsWith("/")) {
            // remove leading '/' for backwards compatibility
            path = path.substring(1);
        }
        return new String[] {uri.getAuthority(), path};
    }

    private String getAwsCredentialType() {
        if (Config.aws_s3_use_aws_sdk_default_behavior) {
            return "default";
        }

        if (Config.aws_s3_use_instance_profile) {
            if (Config.aws_s3_iam_role_arn.isEmpty()) {
                return "instance_profile";
            }

            return "assume_role";
        }

        if (Config.aws_s3_access_key.isEmpty() || Config.aws_s3_secret_key.isEmpty()) {
            // invalid credential configuration
            return null;
        }

        if (Config.aws_s3_iam_role_arn.isEmpty()) {
            return "simple";
        }

        //assume_role with ak sk, not supported now, just return null
        return null;
    }

    public static List<String> parseLocationsFromConfig() throws InvalidConfException {
        List<String> locations = new ArrayList<>();
        URI uri;
        switch (Config.cloud_native_storage_type.toLowerCase()) {
            case "s3":
                uri = normalizeConfigPath(Config.aws_s3_path, "s3", "Config.aws_s3_path", true);
                locations.add(uri.toString());
                break;
            case "hdfs":
                // no need to validate the scheme, it can be hdfs compatible filesystem with customer defined scheme such as: viewfs, webhdfs, ...
                uri = normalizeConfigPath(Config.cloud_native_hdfs_url, "hdfs", "Config.cloud_native_hdfs_url", false);
                locations.add(uri.toString());
                break;
            case "azblob":
                uri = normalizeConfigPath(Config.azure_blob_path, "azblob", "Config.azure_blob_path", true);
                locations.add(uri.toString());
                break;
            default:
                return locations;
        }
        return locations;
    }

    private Map<String, String> parseParamsFromConfig() {
        Map<String, String> params = new HashMap<>();
        switch (Config.cloud_native_storage_type.toLowerCase()) {
            case "s3":
                params.put(CloudConfigurationConstants.AWS_S3_ACCESS_KEY, Config.aws_s3_access_key);
                params.put(CloudConfigurationConstants.AWS_S3_SECRET_KEY, Config.aws_s3_secret_key);
                params.put(CloudConfigurationConstants.AWS_S3_REGION, Config.aws_s3_region);
                params.put(CloudConfigurationConstants.AWS_S3_ENDPOINT, Config.aws_s3_endpoint);
                params.put(CloudConfigurationConstants.AWS_S3_EXTERNAL_ID, Config.aws_s3_external_id);
                params.put(CloudConfigurationConstants.AWS_S3_IAM_ROLE_ARN, Config.aws_s3_iam_role_arn);
                params.put(CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR,
                        String.valueOf(Config.aws_s3_use_aws_sdk_default_behavior));
                params.put(CloudConfigurationConstants.AWS_S3_USE_INSTANCE_PROFILE,
                        String.valueOf(Config.aws_s3_use_instance_profile));
                break;
            case "hdfs":
                // TODO
                break;
            case "azblob":
                params.put(CloudConfigurationConstants.AZURE_BLOB_SHARED_KEY, Config.azure_blob_shared_key);
                params.put(CloudConfigurationConstants.AZURE_BLOB_SAS_TOKEN, Config.azure_blob_sas_token);
                params.put(CloudConfigurationConstants.AZURE_BLOB_ENDPOINT, Config.azure_blob_endpoint);
                break;
            default:
                return params;
        }
        return params;
    }

    public static String parseBuiltinFsKeyFromConfig() throws InvalidConfException {
        switch (Config.cloud_native_storage_type.toLowerCase()) {
            case "s3":
                String[] bucketAndPrefix = getBucketAndPrefix();
                return bucketAndPrefix[0];
            case "hdfs":
                return Config.cloud_native_hdfs_url;
            case "azblob":
                // Since azblob is not supported in 3.0. Its fskey can not be specified.
                // Its fskey will be generated by staros.
                return "";
            default:
                return "";
        }
    }
}

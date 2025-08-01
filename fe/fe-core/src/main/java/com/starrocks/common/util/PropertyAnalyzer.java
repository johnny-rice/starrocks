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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/util/PropertyAnalyzer.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.util;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.starrocks.analysis.BloomFilterIndexUtil;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.BaseTableInfo;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableProperty;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.constraint.ForeignKeyConstraint;
import com.starrocks.catalog.constraint.UniqueConstraint;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.connector.ConnectorPartitionTraits;
import com.starrocks.lake.DataCacheInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SqlModeHelper;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.StorageVolumeMgr;
import com.starrocks.sql.analyzer.AnalyzerUtils;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.analyzer.SetStmtAnalyzer;
import com.starrocks.sql.ast.Property;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.sql.optimizer.rewrite.TimeDriftConstraint;
import com.starrocks.sql.optimizer.rule.transformation.partition.PartitionSelector;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TCompactionStrategy;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.thrift.TPersistentIndexType;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletType;
import com.starrocks.warehouse.Warehouse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.threeten.extra.PeriodDuration;

import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.starrocks.catalog.TableProperty.INVALID;

public class PropertyAnalyzer {
    private static final Logger LOG = LogManager.getLogger(PropertyAnalyzer.class);
    private static final String COMMA_SEPARATOR = ",";

    public static final String PROPERTIES_SHORT_KEY = "short_key";
    public static final String PROPERTIES_REPLICATION_NUM = "replication_num";
    public static final String PROPERTIES_STORAGE_TYPE = "storage_type";
    public static final String PROPERTIES_STORAGE_MEDIUM = "storage_medium";
    public static final String PROPERTIES_STORAGE_COOLDOWN_TIME = "storage_cooldown_time";
    public static final String PROPERTIES_STORAGE_COOLDOWN_TTL = "storage_cooldown_ttl";
    // for 1.x -> 2.x migration
    public static final String PROPERTIES_VERSION_INFO = "version_info";
    // for restore
    public static final String PROPERTIES_SCHEMA_VERSION = "schema_version";

    public static final String PROPERTIES_BF_COLUMNS = "bloom_filter_columns";
    public static final String PROPERTIES_BF_FPP = "bloom_filter_fpp";

    public static final String PROPERTIES_COLUMN_SEPARATOR = "column_separator";
    public static final String PROPERTIES_LINE_DELIMITER = "line_delimiter";

    public static final String PROPERTIES_COLOCATE_WITH = "colocate_with";

    public static final String PROPERTIES_TIMEOUT = "timeout";
    public static final String PROPERTIES_DISTRIBUTION_TYPE = "distribution_type";
    public static final String PROPERTIES_SEND_CLEAR_ALTER_TASK = "send_clear_alter_tasks";

    public static final String PROPERTIES_COMPRESSION = "compression";

    public static final String PROPERTIES_COLOCATE_MV = "colocate_mv";

    public static final String PROPERTIES_INMEMORY = "in_memory";

    public static final String PROPERTIES_ENABLE_PERSISTENT_INDEX = "enable_persistent_index";

    public static final String PROPERTIES_LABELS_LOCATION = "labels.location";

    public static final String PROPERTIES_PERSISTENT_INDEX_TYPE = "persistent_index_type";

    public static final String PROPERTIES_BINLOG_VERSION = "binlog_version";

    public static final String PROPERTIES_BINLOG_ENABLE = "binlog_enable";

    public static final String PROPERTIES_BINLOG_TTL = "binlog_ttl_second";

    public static final String PROPERTIES_BINLOG_MAX_SIZE = "binlog_max_size";
    public static final String PROPERTIES_FLAT_JSON_ENABLE = "flat_json.enable";

    public static final String PROPERTIES_FLAT_JSON_NULL_FACTOR = "flat_json.null.factor";

    public static final String PROPERTIES_FLAT_JSON_SPARSITY_FACTOR = "flat_json.sparsity.factor";

    public static final String PROPERTIES_FLAT_JSON_COLUMN_MAX = "flat_json.column.max";

    public static final String PROPERTIES_STORAGE_TYPE_COLUMN = "column";
    public static final String PROPERTIES_STORAGE_TYPE_COLUMN_WITH_ROW = "column_with_row";

    public static final String PROPERTIES_WRITE_QUORUM = "write_quorum";

    public static final String PROPERTIES_REPLICATED_STORAGE = "replicated_storage";

    public static final String PROPERTIES_BUCKET_SIZE = "bucket_size";

    public static final String PROPERTIES_MUTABLE_BUCKET_NUM = "mutable_bucket_num";

    public static final String PROPERTIES_ENABLE_LOAD_PROFILE = "enable_load_profile";

    public static final String PROPERTIES_BASE_COMPACTION_FORBIDDEN_TIME_RANGES = "base_compaction_forbidden_time_ranges";

    public static final String PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC = "primary_index_cache_expire_sec";

    public static final String PROPERTIES_TABLET_TYPE = "tablet_type";

    public static final String PROPERTIES_STRICT_RANGE = "strict_range";
    public static final String PROPERTIES_USE_TEMP_PARTITION_NAME = "use_temp_partition_name";

    public static final String PROPERTIES_TYPE = "type";

    public static final String ENABLE_LOW_CARD_DICT_TYPE = "enable_low_card_dict";
    public static final String ABLE_LOW_CARD_DICT = "1";
    public static final String DISABLE_LOW_CARD_DICT = "0";

    public static final String PROPERTIES_ENABLE_ASYNC_WRITE_BACK = "enable_async_write_back";
    public static final String PROPERTIES_PARTITION_TTL_NUMBER = "partition_ttl_number";
    public static final String PROPERTIES_PARTITION_TTL = "partition_ttl";
    public static final String PROPERTIES_PARTITION_LIVE_NUMBER = "partition_live_number";
    public static final String PROPERTIES_PARTITION_RETENTION_CONDITION = "partition_retention_condition";
    public static final String PROPERTIES_TIME_DRIFT_CONSTRAINT = "time_drift_constraint";

    public static final String PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT = "auto_refresh_partitions_limit";
    public static final String PROPERTIES_PARTITION_REFRESH_STRATEGY = "partition_refresh_strategy";
    public static final String PROPERTIES_PARTITION_REFRESH_NUMBER = "partition_refresh_number";
    public static final String PROPERTIES_EXCLUDED_TRIGGER_TABLES = "excluded_trigger_tables";
    public static final String PROPERTIES_EXCLUDED_REFRESH_TABLES = "excluded_refresh_tables";

    // 1. `force_external_table_query_rewrite` is used to control whether external table can be rewritten or not
    // 2. external table can be rewritten by default if not specific.
    // 3. you can use `query_rewrite_consistency` to control mv's rewrite consistency.
    public static final String PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE = "force_external_table_query_rewrite";
    public static final String PROPERTIES_QUERY_REWRITE_CONSISTENCY = "query_rewrite_consistency";
    public static final String PROPERTIES_RESOURCE_GROUP = "resource_group";

    public static final String PROPERTIES_WAREHOUSE = "warehouse";
    public static final String PROPERTIES_WAREHOUSE_ID = "warehouse_id";

    public static final String PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX = "session.";

    public static final String PROPERTIES_STORAGE_VOLUME = "storage_volume";

    // constraint for rewrite
    public static final String PROPERTIES_FOREIGN_KEY_CONSTRAINT = "foreign_key_constraints";
    public static final String PROPERTIES_UNIQUE_CONSTRAINT = "unique_constraints";
    public static final String PROPERTIES_DATACACHE_ENABLE = "datacache.enable";
    public static final String PROPERTIES_DATACACHE_PARTITION_DURATION = "datacache.partition_duration";

    // Materialized View properties
    public static final String PROPERTIES_MV_REWRITE_STALENESS_SECOND = "mv_rewrite_staleness_second";
    // Randomized start interval
    // 0(default value): automatically chosed between [0, min(300, INTERVAL/2))
    // -1: disable randomize, use current time as start
    // positive value: use [0, mv_randomize_start) as random interval
    public static final String PROPERTY_MV_RANDOMIZE_START = "mv_randomize_start";
    public static final String PROPERTY_MV_ENABLE_QUERY_REWRITE = "enable_query_rewrite";

    // transparent_mv_rewrite_mode
    public static final String PROPERTY_TRANSPARENT_MV_REWRITE_MODE = "transparent_mv_rewrite_mode";

    /**
     * Materialized View sort keys
     */
    public static final String PROPERTY_MV_SORT_KEYS = "mv_sort_keys";

    // fast schema evolution
    public static final String PROPERTIES_USE_FAST_SCHEMA_EVOLUTION = "fast_schema_evolution";
    public static final String PROPERTIES_USE_LIGHT_SCHEMA_CHANGE = "light_schema_change";

    public static final String PROPERTIES_DEFAULT_PREFIX = "default.";

    public static final String PROPERTIES_FILE_BUNDLING = "file_bundling";

    public static final String PROPERTIES_COMPACTION_STRATEGY = "compaction_strategy";

    public static final String PROPERTIES_DYNAMIC_TABLET_SPLIT_SIZE = "dynamic_tablet_split_size";

    /**
     * Matches location labels like : ["*", "a:*", "bcd_123:*", "123bcd_:val_123", "  a :  b  "],
     * leading and trailing space of key and value will be ignored.
     */
    public static final String SINGLE_LOCATION_LABEL_REGEX = "(\\*|\\s*[a-z_0-9]+\\s*:\\s*(\\*|[a-z_0-9]+)\\s*)";
    /**
     * Matches location labels like: ["*, a: b,  c:d", "*, a:b, *", etc.].
     * Limit the occurrences of single location label to 10 to avoid regex overflowing the stack.
     */
    public static final String MULTI_LOCATION_LABELS_REGEX = "\\s*" + SINGLE_LOCATION_LABEL_REGEX +
            "\\s*(,\\s*" + SINGLE_LOCATION_LABEL_REGEX + "){0,9}\\s*";

    public static DataProperty analyzeDataProperty(Map<String, String> properties,
                                                   DataProperty inferredDataProperty,
                                                   boolean isDefault)
            throws AnalysisException {
        String mediumKey = PROPERTIES_STORAGE_MEDIUM;
        String coolDownTimeKey = PROPERTIES_STORAGE_COOLDOWN_TIME;
        String coolDownTTLKey = PROPERTIES_STORAGE_COOLDOWN_TTL;
        if (isDefault) {
            mediumKey = PROPERTIES_DEFAULT_PREFIX + PROPERTIES_STORAGE_MEDIUM;
            coolDownTimeKey = PROPERTIES_DEFAULT_PREFIX + PROPERTIES_STORAGE_COOLDOWN_TIME;
            coolDownTTLKey = PROPERTIES_DEFAULT_PREFIX + PROPERTIES_STORAGE_COOLDOWN_TTL;
        }

        if (properties == null) {
            return inferredDataProperty;
        }

        // Data property is not supported in shared mode. Return the inferredDataProperty directly.
        if (RunMode.isSharedDataMode()) {
            properties.remove(mediumKey);
            properties.remove(coolDownTimeKey);
            properties.remove(coolDownTTLKey);
            return inferredDataProperty;
        }

        TStorageMedium storageMedium = null;
        long coolDownTimeStamp = DataProperty.MAX_COOLDOWN_TIME_MS;

        boolean hasMedium = false;
        boolean hasCooldownTime = false;
        boolean hasCoolDownTTL = false;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!hasMedium && key.equalsIgnoreCase(mediumKey)) {
                hasMedium = true;
                if (value.equalsIgnoreCase(TStorageMedium.SSD.name())) {
                    storageMedium = TStorageMedium.SSD;
                } else if (value.equalsIgnoreCase(TStorageMedium.HDD.name())) {
                    storageMedium = TStorageMedium.HDD;
                } else {
                    throw new AnalysisException("Invalid storage medium: " + value);
                }
            } else if (!hasCooldownTime && key.equalsIgnoreCase(coolDownTimeKey)) {
                hasCooldownTime = true;
                DateLiteral dateLiteral = new DateLiteral(value, Type.DATETIME);
                coolDownTimeStamp = dateLiteral.unixTimestamp(TimeUtils.getTimeZone());
            } else if (!hasCoolDownTTL && key.equalsIgnoreCase(coolDownTTLKey)) {
                hasCoolDownTTL = true;
            }
        } // end for properties

        if (!hasCooldownTime && !hasMedium && !hasCoolDownTTL) {
            return inferredDataProperty;
        }

        if (hasCooldownTime && hasCoolDownTTL) {
            throw new AnalysisException("Invalid data property. "
                    + coolDownTimeKey + " and " + coolDownTTLKey + " conflict. you can only use one of them. ");
        }

        properties.remove(mediumKey);
        properties.remove(coolDownTimeKey);
        properties.remove(coolDownTTLKey);

        if (hasCooldownTime) {
            if (!hasMedium) {
                throw new AnalysisException("Invalid data property. storage medium property is not found");
            }
            if (storageMedium == TStorageMedium.HDD) {
                throw new AnalysisException("Can not assign cooldown timestamp to HDD storage medium");
            }
            long currentTimeMs = System.currentTimeMillis();
            if (coolDownTimeStamp <= currentTimeMs) {
                throw new AnalysisException("Cooldown time should be later than now");
            }

        } else if (hasCoolDownTTL) {
            if (!hasMedium) {
                throw new AnalysisException("Invalid data property. storage medium property is not found");
            }
            if (storageMedium == TStorageMedium.HDD) {
                throw new AnalysisException("Can not assign cooldown ttl to table with HDD storage medium");
            }
        }

        if (storageMedium == TStorageMedium.SSD && !hasCooldownTime && !hasCoolDownTTL) {
            // set default cooldown time
            coolDownTimeStamp = DataProperty.getSsdCooldownTimeMs();
        }

        Preconditions.checkNotNull(storageMedium);
        return new DataProperty(storageMedium, coolDownTimeStamp);
    }

    public static short analyzeShortKeyColumnCount(Map<String, String> properties) throws AnalysisException {
        short shortKeyColumnCount = (short) -1;
        if (properties != null && properties.containsKey(PROPERTIES_SHORT_KEY)) {
            // check and use specified short key
            try {
                shortKeyColumnCount = Short.parseShort(properties.get(PROPERTIES_SHORT_KEY));
            } catch (NumberFormatException e) {
                throw new AnalysisException("Short key: " + e.getMessage());
            }

            if (shortKeyColumnCount <= 0) {
                throw new AnalysisException("Short key column count should larger than 0.");
            }

            properties.remove(PROPERTIES_SHORT_KEY);
        }

        return shortKeyColumnCount;
    }

    public static int analyzePartitionTTLNumber(Map<String, String> properties) {
        int partitionTimeToLive = INVALID;
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_TTL_NUMBER)) {
            try {
                partitionTimeToLive = Integer.parseInt(properties.get(PROPERTIES_PARTITION_TTL_NUMBER));
            } catch (NumberFormatException e) {
                throw new SemanticException("Partition TTL Number: " + e.getMessage());
            }
            if (partitionTimeToLive <= 0 && partitionTimeToLive != INVALID) {
                throw new SemanticException("Illegal Partition TTL Number: " + partitionTimeToLive);
            }
            properties.remove(PROPERTIES_PARTITION_TTL_NUMBER);
        }
        return partitionTimeToLive;
    }

    public static Pair<String, PeriodDuration> analyzePartitionTTL(Map<String, String> properties, boolean removeProperties) {
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_TTL)) {
            String ttlStr = properties.get(PROPERTIES_PARTITION_TTL);
            PeriodDuration duration;
            try {
                duration = TimeUtils.parseHumanReadablePeriodOrDuration(ttlStr);
            } catch (NumberFormatException e) {
                throw new SemanticException(String.format("illegal %s: %s", PROPERTIES_PARTITION_TTL, e.getMessage()));
            }
            if (removeProperties) {
                properties.remove(PROPERTIES_PARTITION_TTL);
            }
            return Pair.create(ttlStr, duration);
        }
        return Pair.create(null, PeriodDuration.ZERO);
    }

    public static int analyzePartitionLiveNumber(Map<String, String> properties, boolean removeProperties) {
        int partitionLiveNumber = INVALID;
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_LIVE_NUMBER)) {
            try {
                partitionLiveNumber = Integer.parseInt(properties.get(PROPERTIES_PARTITION_LIVE_NUMBER));
            } catch (NumberFormatException e) {
                throw new SemanticException("Partition Live Number: " + e.getMessage());
            }
            if (partitionLiveNumber <= 0 && partitionLiveNumber != INVALID) {
                throw new SemanticException("Illegal Partition Live Number: " + partitionLiveNumber);
            }
            if (removeProperties) {
                properties.remove(PROPERTIES_PARTITION_LIVE_NUMBER);
            }
        }
        return partitionLiveNumber;
    }

    public static String analyzePartitionRetentionCondition(Database db,
                                                            OlapTable olapTable,
                                                            Map<String, String> properties,
                                                            boolean removeProperties,
                                                            Map<Expr, Expr> exprToAdjustMap) {
        String partitionRetentionCondition = "";
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_RETENTION_CONDITION)) {
            partitionRetentionCondition = properties.get(PROPERTIES_PARTITION_RETENTION_CONDITION);
            if (Strings.isNullOrEmpty(partitionRetentionCondition)) {
                if (removeProperties) {
                    properties.remove(PROPERTIES_PARTITION_RETENTION_CONDITION);
                }
                return partitionRetentionCondition;
            }
            // parse retention condition
            Expr whereExpr = null;
            try {
                whereExpr = SqlParser.parseSqlToExpr(partitionRetentionCondition, SqlModeHelper.MODE_DEFAULT);
                if (whereExpr == null) {
                    throw new SemanticException("Failed to parse retention condition: " + partitionRetentionCondition);
                }
            } catch (Exception e) {
                throw new SemanticException("Failed to parse retention condition: " + partitionRetentionCondition);
            }
            // validate retention condition
            TableName tableName = new TableName(db.getFullName(), olapTable.getName());
            ConnectContext connectContext = ConnectContext.get() == null ? new ConnectContext(null) : ConnectContext.get();

            try {
                PartitionSelector.getPartitionIdsByExpr(connectContext, tableName, olapTable, whereExpr, false, exprToAdjustMap);
            } catch (Exception e) {
                throw new SemanticException("Failed to validate retention condition: " + e.getMessage());
            }
            if (removeProperties) {
                properties.remove(PROPERTIES_PARTITION_RETENTION_CONDITION);
            }
        }
        return partitionRetentionCondition;
    }

    public static long analyzeBucketSize(Map<String, String> properties) {
        long bucketSize = 0;
        if (properties != null && properties.containsKey(PROPERTIES_BUCKET_SIZE)) {
            try {
                bucketSize = Long.parseLong(properties.get(PROPERTIES_BUCKET_SIZE));
            } catch (NumberFormatException e) {
                throw new SemanticException("Bucket size: " + e.getMessage());
            }
            if (bucketSize < 0) {
                throw new SemanticException("Illegal bucket size: " + bucketSize);
            }
            return bucketSize;
        } else {
            throw new SemanticException("Bucket size is not set");
        }
    }

    public static long analyzeMutableBucketNum(Map<String, String> properties) {
        long mutableBucketNum = 0;
        if (properties != null && properties.containsKey(PROPERTIES_MUTABLE_BUCKET_NUM)) {
            try {
                mutableBucketNum = Long.parseLong(properties.get(PROPERTIES_MUTABLE_BUCKET_NUM));
            } catch (NumberFormatException e) {
                throw new SemanticException("Mutable bucket num: " + e.getMessage());
            }
            if (mutableBucketNum < 0) {
                throw new SemanticException("Illegal mutable bucket num: " + mutableBucketNum);
            }
            return mutableBucketNum;
        } else {
            throw new SemanticException("Mutable bucket num is not set");
        }
    }

    public static double analyzeFlatJsonNullFactor(Map<String, String> properties) {
        double flatJsonNullFactor = 0;
        if (properties != null && properties.containsKey(PROPERTIES_FLAT_JSON_NULL_FACTOR)) {
            try {
                flatJsonNullFactor = Double.parseDouble(properties.get(PROPERTIES_FLAT_JSON_NULL_FACTOR));
            } catch (NumberFormatException e) {
                throw new SemanticException("Flat json null factor: " + e.getMessage());
            }
            if (flatJsonNullFactor < 0 || flatJsonNullFactor > 1) {
                throw new SemanticException("Illegal flat json null factor: " + flatJsonNullFactor);
            }
            return flatJsonNullFactor;
        } else {
            throw new SemanticException("Flat json null factor is not set");
        }
    }

    public static double analyzeFlatJsonSparsityFactor(Map<String, String> properties) {
        double flatJsonSparsityFactor = 0;
        if (properties != null && properties.containsKey(PROPERTIES_FLAT_JSON_SPARSITY_FACTOR)) {
            try {
                flatJsonSparsityFactor = Double.parseDouble(properties.get(PROPERTIES_FLAT_JSON_SPARSITY_FACTOR));
            } catch (NumberFormatException e) {
                throw new SemanticException("Flat json sparsity factor: " + e.getMessage());
            }
            if (flatJsonSparsityFactor < 0 || flatJsonSparsityFactor > 1) {
                throw new SemanticException("Illegal flat json sparsity factor: " + flatJsonSparsityFactor);
            }
            return flatJsonSparsityFactor;
        } else {
            throw new SemanticException("Flat json sparsity factor is not set");
        }
    }

    public static int analyzeFlatJsonColumnMax(Map<String, String> properties) {
        int columnMax = 0;
        if (properties != null && properties.containsKey(PROPERTIES_FLAT_JSON_COLUMN_MAX)) {
            try {
                columnMax = Integer.parseInt(properties.get(PROPERTIES_FLAT_JSON_COLUMN_MAX));
            } catch (NumberFormatException e) {
                throw new SemanticException("Flat json column max: " + e.getMessage());
            }
            if (columnMax < 0) {
                throw new SemanticException("Illegal flat json column max: " + columnMax);
            }
            return columnMax;
        } else {
            throw new SemanticException("Flat json column max is not set");
        }
    }

    public static boolean analyzeFlatJsonEnabled(Map<String, String> properties) {
        boolean flatJsonEnabled = false;
        if (properties != null && properties.containsKey(PROPERTIES_FLAT_JSON_ENABLE)) {
            flatJsonEnabled = Boolean.parseBoolean(properties.get(PROPERTIES_FLAT_JSON_ENABLE));
        }
        return flatJsonEnabled;
    }

    public static boolean analyzeEnableLoadProfile(Map<String, String> properties) {
        boolean enableLoadProfile = false;
        if (properties != null && properties.containsKey(PROPERTIES_ENABLE_LOAD_PROFILE)) {
            enableLoadProfile = Boolean.parseBoolean(properties.get(PROPERTIES_ENABLE_LOAD_PROFILE));
        }
        return enableLoadProfile;
    }

    public static String analyzeBaseCompactionForbiddenTimeRanges(Map<String, String> properties) {
        if (properties != null && properties.containsKey(PROPERTIES_BASE_COMPACTION_FORBIDDEN_TIME_RANGES)) {
            String forbiddenTimeRanges = properties.get(PROPERTIES_BASE_COMPACTION_FORBIDDEN_TIME_RANGES);
            return forbiddenTimeRanges;
        }
        return "";
    }

    public static TimeDriftConstraint analyzeTimeDriftConstraint(String spec, Table table,
                                                                 Map<String, String> properties) {
        properties.remove(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT);
        TimeDriftConstraint constraint = TimeDriftConstraint.parseSpec(spec);
        if (!table.containColumn(constraint.getTargetColumn())) {
            throw new SemanticException("Target column '%s' not exists in table '%s'",
                    constraint.getTargetColumn(), table.getName());
        }
        if (!table.containColumn(constraint.getReferenceColumn())) {
            throw new SemanticException("Reference column '%s' not exists in table '%s'",
                    constraint.getReferenceColumn(), table.getName());
        }

        boolean refColumnIsDateTypePartitionColumn = ConnectorPartitionTraits.build(table)
                .getPartitionColumns().stream()
                .filter(column -> column.getName().equals(constraint.getReferenceColumn()))
                .findFirst().map(column -> column.getType().isDateType()).orElse(false);
        if (!refColumnIsDateTypePartitionColumn) {
            throw new SemanticException("Reference column '%s' must be a DATE/DATETIME type partition column",
                    constraint.getReferenceColumn());
        }

        boolean targetColumnIsDateType = table.getColumns().stream()
                .filter(column -> column.getName().equals(constraint.getTargetColumn()))
                .findFirst().map(column -> column.getType().isDateType()).orElse(false);
        if (!targetColumnIsDateType) {
            throw new SemanticException("Target column '%s' must be a DATE/DATETIME type partition column",
                    constraint.getReferenceColumn());
        }
        return constraint;
    }

    public static int analyzeAutoRefreshPartitionsLimit(Map<String, String> properties, MaterializedView mv) {
        if (mv.getRefreshScheme().getType() == MaterializedView.RefreshType.MANUAL) {
            throw new SemanticException(
                    "The auto_refresh_partitions_limit property does not support manual refresh mode.");
        }
        int autoRefreshPartitionsLimit = -1;
        if (properties != null && properties.containsKey(PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)) {
            try {
                autoRefreshPartitionsLimit = Integer.parseInt(properties.get(PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT));
            } catch (NumberFormatException e) {
                throw new SemanticException("Auto Refresh Partitions Limit: " + e.getMessage());
            }
            if (autoRefreshPartitionsLimit <= 0 && autoRefreshPartitionsLimit != INVALID) {
                throw new SemanticException("Illegal Auto Refresh Partitions Limit: " + autoRefreshPartitionsLimit);
            }
            properties.remove(PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT);
        }
        return autoRefreshPartitionsLimit;
    }

    public static int analyzePartitionRefreshNumber(Map<String, String> properties) {
        int partitionRefreshNumber = -1;
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_REFRESH_NUMBER)) {
            try {
                partitionRefreshNumber = Integer.parseInt(properties.get(PROPERTIES_PARTITION_REFRESH_NUMBER));
            } catch (NumberFormatException e) {
                throw new SemanticException("Partition Refresh Number: " + e.getMessage());
            }
            if (partitionRefreshNumber <= 0 && partitionRefreshNumber != INVALID) {
                throw new SemanticException("Illegal Partition Refresh Number: " + partitionRefreshNumber);
            }
            properties.remove(PROPERTIES_PARTITION_REFRESH_NUMBER);
        }
        return partitionRefreshNumber;
    }

    public static String analyzePartitionRefreshStrategy(Map<String, String> properties) {
        String partitionRefreshStrategy = null;
        if (properties != null && properties.containsKey(PROPERTIES_PARTITION_REFRESH_STRATEGY)) {
            partitionRefreshStrategy = properties.get(PROPERTIES_PARTITION_REFRESH_STRATEGY);
            try {
                MaterializedView.PartitionRefreshStrategy.valueOf(partitionRefreshStrategy.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid partition_refresh_strategy: " + partitionRefreshStrategy +
                        ". Only 'strict' or 'adaptive' are supported.");
            }
            properties.remove(PROPERTIES_PARTITION_REFRESH_STRATEGY);
        }
        return partitionRefreshStrategy;
    }

    public static List<TableName> analyzeExcludedTables(Map<String, String> properties,
                                                        String propertiesKey,
                                                        MaterializedView mv) {
        if (mv.getRefreshScheme().getType() != MaterializedView.RefreshType.ASYNC) {
            throw new SemanticException("The " + propertiesKey + " property only applies to asynchronous refreshes.");
        }
        List<TableName> tables = Lists.newArrayList();
        if (properties != null && properties.containsKey(propertiesKey)) {
            String tableStr = properties.get(propertiesKey);
            List<String> tableList = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(tableStr);
            for (String table : tableList) {
                TableName tableName = AnalyzerUtils.stringToTableName(table);
                if (mv.containsBaseTable(tableName)) {
                    tables.add(tableName);
                } else {
                    throw new SemanticException(tableName.toSql() +
                            " is not base table of materialized view " + mv.getName());
                }
            }
            properties.remove(propertiesKey);
        }
        return tables;
    }

    public static int analyzeMVRewriteStaleness(Map<String, String> properties) {
        int maxMVRewriteStaleness = INVALID;
        if (properties != null && properties.containsKey(PROPERTIES_MV_REWRITE_STALENESS_SECOND)) {
            try {
                maxMVRewriteStaleness = Integer.parseInt(properties.get(PROPERTIES_MV_REWRITE_STALENESS_SECOND));
            } catch (NumberFormatException e) {
                throw new SemanticException("Invalid maxMVRewriteStaleness Number: " + e.getMessage());
            }
            if (maxMVRewriteStaleness != INVALID && maxMVRewriteStaleness < 0) {
                throw new SemanticException("Illegal maxMVRewriteStaleness: " + maxMVRewriteStaleness);
            }
            properties.remove(PROPERTIES_MV_REWRITE_STALENESS_SECOND);
        }
        return maxMVRewriteStaleness;
    }

    public static Short analyzeReplicationNum(Map<String, String> properties, short oldReplicationNum)
            throws AnalysisException {
        short replicationNum = oldReplicationNum;
        if (properties != null && properties.containsKey(PROPERTIES_REPLICATION_NUM)) {
            try {
                replicationNum = Short.parseShort(properties.get(PROPERTIES_REPLICATION_NUM));
            } catch (Exception e) {
                throw new AnalysisException(e.getMessage());
            }
            checkReplicationNum(replicationNum);
            properties.remove(PROPERTIES_REPLICATION_NUM);
        }
        return replicationNum;
    }

    public static Short analyzeReplicationNum(Map<String, String> properties, boolean isDefault) {
        String key = PROPERTIES_DEFAULT_PREFIX;
        if (isDefault) {
            key += PropertyAnalyzer.PROPERTIES_REPLICATION_NUM;
        } else {
            key = PropertyAnalyzer.PROPERTIES_REPLICATION_NUM;
        }
        short replicationNum = Short.parseShort(properties.get(key));
        checkReplicationNum(replicationNum);
        return replicationNum;
    }

    public static String analyzeResourceGroup(Map<String, String> properties) {
        String resourceGroup = null;
        if (properties != null && properties.containsKey(PROPERTIES_RESOURCE_GROUP)) {
            resourceGroup = properties.get(PROPERTIES_RESOURCE_GROUP);
            properties.remove(PROPERTIES_RESOURCE_GROUP);
        }
        return resourceGroup;
    }

    private static void checkReplicationNum(short replicationNum) {
        if (replicationNum <= 0) {
            throw new SemanticException("Replication num should larger than 0");
        }

        List<Long> backendIds = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getAvailableBackendIds();
        if (RunMode.isSharedDataMode()) {
            backendIds.addAll(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getAvailableComputeNodeIds());
            if (RunMode.defaultReplicationNum() > backendIds.size()) {
                throw new SemanticException("Number of available CN nodes is " + backendIds.size()
                        + ", less than " + RunMode.defaultReplicationNum());
            }
        } else {
            if (replicationNum > backendIds.size()) {
                throw new SemanticException("Table replication num should be less than " +
                        "of equal to the number of available BE nodes. "
                        + "You can change this default by setting the replication_num table properties. "
                        + "Current alive backend is [" + Joiner.on(",").join(backendIds) + "].");
            }
        }
    }

    public static String analyzeColumnSeparator(Map<String, String> properties, String oldColumnSeparator) {
        String columnSeparator = oldColumnSeparator;
        if (properties != null && properties.containsKey(PROPERTIES_COLUMN_SEPARATOR)) {
            columnSeparator = properties.get(PROPERTIES_COLUMN_SEPARATOR);
            properties.remove(PROPERTIES_COLUMN_SEPARATOR);
        }
        return columnSeparator;
    }

    public static String analyzeRowDelimiter(Map<String, String> properties, String oldRowDelimiter) {
        String rowDelimiter = oldRowDelimiter;
        if (properties != null && properties.containsKey(PROPERTIES_LINE_DELIMITER)) {
            rowDelimiter = properties.get(PROPERTIES_LINE_DELIMITER);
            properties.remove(PROPERTIES_LINE_DELIMITER);
        }
        return rowDelimiter;
    }

    public static TStorageType analyzeStorageType(Map<String, String> properties, OlapTable olapTable)
            throws AnalysisException {
        // default is COLUMN
        TStorageType tStorageType = TStorageType.COLUMN;
        if (properties != null && properties.containsKey(PROPERTIES_STORAGE_TYPE)) {
            String storageType = properties.get(PROPERTIES_STORAGE_TYPE);
            if (storageType.equalsIgnoreCase(TStorageType.COLUMN.name())) {
                tStorageType = TStorageType.COLUMN;
            } else if (olapTable.supportsUpdate() && storageType.equalsIgnoreCase(TStorageType.ROW.name())) {
                tStorageType = TStorageType.ROW;
            } else if (olapTable.supportsUpdate() && storageType.equalsIgnoreCase(TStorageType.COLUMN_WITH_ROW.name())) {
                tStorageType = TStorageType.COLUMN_WITH_ROW;
                if (olapTable.getColumns().stream().filter(column -> !column.isKey()).count() == 0) {
                    throw new AnalysisException("column_with_row storage type must have some non-key columns");
                }
            } else {
                throw new AnalysisException(storageType + " for " + olapTable.getKeysType() + " table not supported");
            }
            if (!Config.enable_experimental_rowstore &&
                    (tStorageType == TStorageType.ROW || tStorageType == TStorageType.COLUMN_WITH_ROW)) {
                throw new AnalysisException(storageType + " for " + olapTable.getKeysType() +
                        " table not supported, enable it by setting `enable_experimental_rowstore` to true");
            }
            properties.remove(PROPERTIES_STORAGE_TYPE);
        }
        return tStorageType;
    }

    public static TTabletType analyzeTabletType(Map<String, String> properties) throws AnalysisException {
        // default is TABLET_TYPE_DISK
        TTabletType tTabletType = TTabletType.TABLET_TYPE_DISK;
        if (properties != null && properties.containsKey(PROPERTIES_TABLET_TYPE)) {
            String tabletType = properties.get(PROPERTIES_TABLET_TYPE);
            if (tabletType.equalsIgnoreCase("memory")) {
                tTabletType = TTabletType.TABLET_TYPE_MEMORY;
            } else if (tabletType.equalsIgnoreCase("disk")) {
                tTabletType = TTabletType.TABLET_TYPE_DISK;
            } else {
                throw new AnalysisException(("Invalid tablet type"));
            }
            properties.remove(PROPERTIES_TABLET_TYPE);
        }
        return tTabletType;
    }

    public static Long analyzeVersionInfo(Map<String, String> properties) throws AnalysisException {
        long versionInfo = Partition.PARTITION_INIT_VERSION;
        if (properties != null && properties.containsKey(PROPERTIES_VERSION_INFO)) {
            if (RunMode.isSharedDataMode()) {
                throw new AnalysisException(String.format("Does not support the table property \"%s\" in share data " +
                        "mode, please remove it from the statement", PROPERTIES_VERSION_INFO));
            }
            String versionInfoStr = properties.get(PROPERTIES_VERSION_INFO);
            try {
                versionInfo = Long.parseLong(versionInfoStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("version info format error.");
            }

            properties.remove(PROPERTIES_VERSION_INFO);
        }

        return versionInfo;
    }

    public static int analyzeSchemaVersion(Map<String, String> properties) throws AnalysisException {
        int schemaVersion = 0;
        if (properties != null && properties.containsKey(PROPERTIES_SCHEMA_VERSION)) {
            String schemaVersionStr = properties.get(PROPERTIES_SCHEMA_VERSION);
            try {
                schemaVersion = Integer.parseInt(schemaVersionStr);
            } catch (Exception e) {
                throw new AnalysisException("schema version format error");
            }

            properties.remove(PROPERTIES_SCHEMA_VERSION);
        }

        return schemaVersion;
    }

    public static Boolean analyzeUseFastSchemaEvolution(Map<String, String> properties) throws AnalysisException {
        if (properties == null || properties.isEmpty()) {
            return Config.enable_fast_schema_evolution;
        }
        String value = properties.get(PROPERTIES_USE_FAST_SCHEMA_EVOLUTION);
        if (null == value) {
            value = properties.get(PROPERTIES_USE_LIGHT_SCHEMA_CHANGE);
            if (null == value) {
                return Config.enable_fast_schema_evolution;
            }
        }
        properties.remove(PROPERTIES_USE_FAST_SCHEMA_EVOLUTION);
        properties.remove(PROPERTIES_USE_LIGHT_SCHEMA_CHANGE);
        if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return true;
        } else if (Boolean.FALSE.toString().equalsIgnoreCase(value)) {
            return false;
        }
        throw new AnalysisException(PROPERTIES_USE_FAST_SCHEMA_EVOLUTION
                + " must be `true` or `false`");
    }

    public static Boolean analyzeFileBundling(Map<String, String> properties) throws AnalysisException {
        boolean fileBundling = Config.enable_file_bundling;
        if (properties != null && properties.containsKey(PROPERTIES_FILE_BUNDLING)) {
            fileBundling = Boolean.parseBoolean(properties.get(PROPERTIES_FILE_BUNDLING));
            properties.remove(PROPERTIES_FILE_BUNDLING);
        }
        return fileBundling;
    }

    public static Set<String> analyzeBloomFilterColumns(Map<String, String> properties, List<Column> columns,
                                                        boolean isPrimaryKey) throws AnalysisException {
        Set<String> bfColumns = null;
        if (properties != null && properties.containsKey(PROPERTIES_BF_COLUMNS)) {
            bfColumns = Sets.newHashSet();
            String bfColumnsStr = properties.get(PROPERTIES_BF_COLUMNS);
            if (Strings.isNullOrEmpty(bfColumnsStr)) {
                return bfColumns;
            }

            String[] bfColumnArr = bfColumnsStr.split(COMMA_SEPARATOR);
            Set<String> bfColumnSet = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
            for (String bfColumn : bfColumnArr) {
                bfColumn = bfColumn.trim();
                String finalBfColumn = bfColumn;
                Column column = columns.stream().filter(col -> col.getName().equalsIgnoreCase(finalBfColumn))
                        .findFirst()
                        .orElse(null);
                if (column == null) {
                    throw new AnalysisException(
                            String.format("Invalid bloom filter column '%s': not exists", bfColumn));
                }

                Type type = column.getType();

                // tinyint/float/double columns don't support
                if (!type.supportBloomFilter()) {
                    throw new AnalysisException(String.format("Invalid bloom filter column '%s': unsupported type %s",
                            bfColumn, type));
                }

                // Only support create bloom filter on DUPLICATE/PRIMARY table or key columns of UNIQUE/AGGREGATE table.
                if (!(column.isKey() || isPrimaryKey || column.getAggregationType() == AggregateType.NONE)) {
                    // Although the implementation supports bloom filter for replace non-key column,
                    // for simplicity and unity, we don't expose that to user.
                    throw new AnalysisException("Bloom filter index only used in columns of DUP_KEYS/PRIMARY table or "
                            + "key columns of UNIQUE_KEYS/AGG_KEYS table. invalid column: " + bfColumn);
                }

                if (bfColumnSet.contains(bfColumn)) {
                    throw new AnalysisException(String.format("Duplicate bloom filter column '%s'", bfColumn));
                }

                bfColumnSet.add(bfColumn);
                bfColumns.add(column.getName());
            }

            properties.remove(PROPERTIES_BF_COLUMNS);
        }

        return bfColumns;
    }

    public static double analyzeBloomFilterFpp(Map<String, String> properties) throws AnalysisException {
        double bfFpp = 0;
        if (properties != null && properties.containsKey(PROPERTIES_BF_FPP)) {
            bfFpp = BloomFilterIndexUtil.analyzeBloomFilterFpp(properties);
            // have to remove this from properties, which means it's valid and checked already
            properties.remove(PROPERTIES_BF_FPP);
        }

        return bfFpp;
    }

    public static String analyzeColocate(Map<String, String> properties) {
        String colocateGroup = null;
        if (properties != null && properties.containsKey(PROPERTIES_COLOCATE_WITH)) {
            colocateGroup = properties.get(PROPERTIES_COLOCATE_WITH);
            properties.remove(PROPERTIES_COLOCATE_WITH);
        }
        return colocateGroup;
    }

    public static long analyzeTimeout(Map<String, String> properties, long defaultTimeout) throws AnalysisException {
        long timeout = defaultTimeout;
        if (properties != null && properties.containsKey(PROPERTIES_TIMEOUT)) {
            String timeoutStr = properties.get(PROPERTIES_TIMEOUT);
            try {
                timeout = Long.parseLong(timeoutStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Invalid timeout format: " + timeoutStr);
            }
            properties.remove(PROPERTIES_TIMEOUT);
        }
        return timeout;
    }

    // parse compression level if possible
    public static int analyzeCompressionLevel(Map<String, String> properties) throws AnalysisException {
        String compressionName = properties.get(PROPERTIES_COMPRESSION);
        String noSpacesCompression = compressionName.replace(" ", "");
        String pattern = "^zstd\\((\\d+)\\)$";
        Pattern r = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        Matcher m = r.matcher(noSpacesCompression);
        if (m.matches()) {
            String levelString = m.group(1);
            int number = Integer.parseInt(levelString);
            if (number >= 1 && number <= 22) {
                properties.remove(PROPERTIES_COMPRESSION);
                return number;
            } else {
                throw new AnalysisException("Invalid level for zstd compression type");
            }
        }
        return -1;
    }

    // analyzeCompressionType will parse the compression type from properties
    public static Pair<TCompressionType, Integer> analyzeCompressionType(
            Map<String, String> properties) throws AnalysisException {
        TCompressionType compressionType = TCompressionType.LZ4_FRAME;
        if (ConnectContext.get() != null) {
            String defaultCompression = ConnectContext.get().getSessionVariable().getDefaultTableCompression();
            compressionType = CompressionUtils.getCompressTypeByName(defaultCompression);
        }
        if (properties == null || !properties.containsKey(PROPERTIES_COMPRESSION)) {
            return new Pair<TCompressionType, Integer>(compressionType, -1);
        }
        int level = analyzeCompressionLevel(properties);
        if (level != -1) {
            return new Pair<TCompressionType, Integer>(TCompressionType.ZSTD, level);
        }

        String compressionName = properties.get(PROPERTIES_COMPRESSION);
        properties.remove(PROPERTIES_COMPRESSION);

        if (CompressionUtils.getCompressTypeByName(compressionName) != null) {
            return new Pair<TCompressionType, Integer>(CompressionUtils.getCompressTypeByName(compressionName), -1);
        } else {
            throw new AnalysisException("unknown compression type: " + compressionName);
        }
    }

    // analyzeWriteQuorum will parse to write quorum from properties
    public static String analyzeWriteQuorum(Map<String, String> properties) throws AnalysisException {
        String writeQuorum;
        if (properties == null || !properties.containsKey(PROPERTIES_WRITE_QUORUM)) {
            return WriteQuorum.MAJORITY;
        }
        writeQuorum = properties.get(PROPERTIES_WRITE_QUORUM);
        properties.remove(PROPERTIES_WRITE_QUORUM);

        if (WriteQuorum.findTWriteQuorumByName(writeQuorum) != null) {
            return writeQuorum;
        } else {
            throw new AnalysisException("unknown write quorum: " + writeQuorum);
        }
    }

    // analyze common boolean properties, such as "in_memory" = "false"
    public static boolean analyzeBooleanProp(Map<String, String> properties, String propKey, boolean defaultVal) {
        if (properties != null && properties.containsKey(propKey)) {
            String val = properties.get(propKey);
            properties.remove(propKey);
            return Boolean.parseBoolean(val);
        }
        return defaultVal;
    }

    public static boolean analyzeEnablePersistentIndex(Map<String, String> properties) {
        if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_ENABLE_PERSISTENT_INDEX)) {
            String val = properties.get(PropertyAnalyzer.PROPERTIES_ENABLE_PERSISTENT_INDEX);
            properties.remove(PropertyAnalyzer.PROPERTIES_ENABLE_PERSISTENT_INDEX);
            return Boolean.parseBoolean(val);
        } else {
            return true;
        }
    }

    // Convert location string like: "k1:v1,k2:v2" to map
    // Return `{*:*}` means that we have specified '*" in location string,
    // in this case, we will scatter replicas on all the backends which have location label,
    // not some specified locations. So we can ignore others location labels.
    // And the location string will be simplified to a single '*'.
    public static Multimap<String, String> analyzeLocationStringToMap(String locations) {
        Multimap<String, String> locationMap = HashMultimap.create();
        String[] singleLocationStrings = locations.split(",");
        for (String singleLocationString : singleLocationStrings) {
            if (singleLocationString.trim().equals("*")) {
                locationMap.put("*", "*");
                return locationMap;
            } else {
                String[] kv = singleLocationString.split(":");
                String key = kv[0].trim();
                String value = kv[1].trim();
                if (value.equals("*") && locationMap.containsKey(key)) {
                    // if value is '*', and we have specified this key before,
                    // we will ignore this key, and use '*' to replace all the values of this key.
                    locationMap.removeAll(key);
                }
                locationMap.put(key, value);
            }
        }

        return locationMap;
    }

    public static String validateTableLocationProperty(String location) throws SemanticException {
        if (location.isEmpty()) {
            return location;
        }

        if (location.length() > 255) {
            throw new SemanticException("location is too long, max length is 255");
        }

        Matcher matcher = Pattern.compile(MULTI_LOCATION_LABELS_REGEX).matcher(location);
        if (!matcher.matches()) {
            throw new SemanticException("Invalid location format: " + location +
                    ", should be like: '*', 'key:*', or 'k1:v1,k2:v2,k1:v11'");
        }

        // check location is valid or not
        Multimap<String, String> locationMap = analyzeLocationStringToMap(location);

        if (!locationMap.keySet().contains("*")) {
            // check location label associated with any backend or not
            SystemInfoService systemInfoService = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
            List<Backend> backends = systemInfoService.getBackends();
            for (String key : locationMap.keySet()) {
                Collection<String> values = locationMap.get(key);
                for (String value : values) {
                    boolean isValueValid = false;
                    for (Backend backend : backends) {
                        Pair<String, String> backendLocKV = backend.getSingleLevelLocationKV();
                        if (backendLocKV != null && backend.getLocation().containsKey(key) &&
                                (Objects.equals(backendLocKV.second, value) || value.equals("*"))) {
                            isValueValid = true;
                            break;
                        }
                    }
                    if (!isValueValid) {
                        throw new SemanticException(
                                "Cannot find any backend with location: " + key + ":" + value);
                    }
                }
            }
        }

        return convertLocationMapToString(locationMap);
    }

    public static String convertLocationMapToString(Map<String, String> locationMap) {
        // Convert map to multi hash map.
        Multimap<String, String> multiLocationMap = HashMultimap.create();
        for (Map.Entry<String, String> entry : locationMap.entrySet()) {
            multiLocationMap.put(entry.getKey(), entry.getValue());
        }

        return convertLocationMapToString(multiLocationMap);
    }

    // Convert location map to string without head and tail space.
    public static String convertLocationMapToString(Multimap<String, String> locationMap) {
        if (locationMap.containsKey("*")) {
            return "*";
        }

        return locationMap.entries().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    public static String analyzeLocation(Map<String, String> properties, boolean removeAnalyzedProp) {
        if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_LABELS_LOCATION)) {
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_COLOCATE_WITH)) {
                throw new SemanticException("colocate table doesn't support location property");
            }
            String loc = properties.get(PropertyAnalyzer.PROPERTIES_LABELS_LOCATION);
            // validate location format
            String validatedLoc = validateTableLocationProperty(loc);
            if (removeAnalyzedProp) {
                properties.remove(PropertyAnalyzer.PROPERTIES_LABELS_LOCATION);
            }
            return validatedLoc;
        } else {
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_COLOCATE_WITH)) {
                // won't set default location prop for colocate table
                return null;
            }
            SystemInfoService systemInfoService = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
            long numOfBackendsWithLocationLabel =
                    systemInfoService.getBackends().stream()
                            .filter(backend -> !backend.getLocation().isEmpty()).count();
            if (numOfBackendsWithLocationLabel > 0) {
                // If location is not specified explicitly, and we have some backends with location label,
                // return '*', meaning by default we will scatter the replicas
                // on all the backends which have location label.
                // So that we can identify tables before and after upgrade to newer version.
                // For history tables which don't have location label,
                // their replica distribution won't be changed after upgrade.
                return "*";
            } else {
                // If no backend has location label, return null,
                // meaning we won't scatter replicas based on backend location,
                // so we won't put the location label in table properties(`show create table` won't see it).
                // User may not want to use this feature at all,
                // we won't add a default location property to bother users.
                return null;
            }
        }
    }

    public static void analyzeLocation(OlapTable table, Map<String, String> properties) {
        String location = PropertyAnalyzer.analyzeLocation(properties, true);
        if (location != null) {
            table.setLocation(location);
        }
    }

    // analyze property like : "type" = "xxx";
    public static String analyzeType(Map<String, String> properties) {
        String type = null;
        if (properties != null && properties.containsKey(PROPERTIES_TYPE)) {
            type = properties.get(PROPERTIES_TYPE);
            properties.remove(PROPERTIES_TYPE);
        }
        return type;
    }

    public static String analyzeType(Property property) {
        String type = null;
        if (PROPERTIES_TYPE.equals(property.getKey())) {
            type = property.getValue();
        }
        return type;
    }

    public static int analyzeIntProp(Map<String, String> properties, String propKey, int defaultVal)
            throws AnalysisException {
        int val = defaultVal;
        if (properties != null && properties.containsKey(propKey)) {
            String valStr = properties.get(propKey);
            try {
                val = Integer.parseInt(valStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Invalid " + propKey + " format: " + valStr);
            }
            properties.remove(propKey);
        }
        return val;
    }

    public static long analyzeLongProp(Map<String, String> properties, String propKey, long defaultVal)
            throws AnalysisException {
        long val = defaultVal;
        if (properties != null && properties.containsKey(propKey)) {
            String valStr = properties.get(propKey);
            properties.remove(propKey);
            try {
                val = Long.parseLong(valStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Invalid " + propKey + " format: " + valStr);
            }
            properties.remove(propKey);
        }
        return val;
    }

    public static double analyzerDoubleProp(Map<String, String> properties, String propKey, double defaultVal)
        throws AnalysisException {
        double val = defaultVal;
        if (properties != null && properties.containsKey(propKey)) {
            String valStr = properties.get(propKey);
            properties.remove(propKey);
            try {
                val = Double.parseDouble(valStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Invalid " + propKey + " format: " + valStr);
            }
        }
        return val;
    }

    public static int analyzePrimaryIndexCacheExpireSecProp(Map<String, String> properties, String propKey, int defaultVal)
            throws AnalysisException {
        int val = 0;
        if (properties != null && properties.containsKey(PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC)) {
            String valStr = properties.get(PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC);
            try {
                val = Integer.parseInt(valStr);
                if (val < 0) {
                    throw new AnalysisException("Property " + PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC
                            + " must not be less than 0");
                }
            } catch (NumberFormatException e) {
                throw new AnalysisException("Property " + PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC
                        + " must be integer: " + valStr);
            }
            properties.remove(PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC);
        }
        return val;
    }

    public static List<UniqueConstraint> analyzeUniqueConstraint(Map<String, String> properties, Database db, Table table) {
        List<UniqueConstraint> uniqueConstraints = Lists.newArrayList();
        List<UniqueConstraint> analyzedUniqueConstraints = Lists.newArrayList();
        ConnectContext context = new ConnectContext();

        if (properties != null && properties.containsKey(PROPERTIES_UNIQUE_CONSTRAINT)) {
            String constraintDescs = properties.get(PROPERTIES_UNIQUE_CONSTRAINT);
            if (Strings.isNullOrEmpty(constraintDescs)) {
                return uniqueConstraints;
            }

            String[] constraintArray = constraintDescs.split(";");
            for (String constraintDesc : constraintArray) {
                if (Strings.isNullOrEmpty(constraintDesc)) {
                    continue;
                }
                Pair<TableName, List<String>> parseResult = UniqueConstraint.parseUniqueConstraintDesc(
                        table.getCatalogName(), db.getFullName(), table.getName(), constraintDesc);
                TableName tableName = parseResult.first;
                List<String> columnNames = parseResult.second;
                if (table.isMaterializedView()) {
                    Table uniqueConstraintTable = GlobalStateMgr.getCurrentState().getMetadataMgr().getTable(
                            context, tableName.getCatalog(), tableName.getDb(), tableName.getTbl());
                    if (uniqueConstraintTable == null) {
                        throw new SemanticException(String.format("table: %s does not exist", tableName));
                    }
                    List<ColumnId> columnIds = MetaUtils.getColumnIdsByColumnNames(uniqueConstraintTable, columnNames);
                    analyzedUniqueConstraints.add(new UniqueConstraint(tableName.getCatalog(), tableName.getDb(),
                            tableName.getTbl(), columnIds));
                } else {
                    List<ColumnId> columnIds = MetaUtils.getColumnIdsByColumnNames(table, columnNames);
                    analyzedUniqueConstraints.add(new UniqueConstraint(tableName.getCatalog(), tableName.getDb(),
                            tableName.getTbl(), columnIds));
                }
            }
            properties.remove(PROPERTIES_UNIQUE_CONSTRAINT);
        }
        return analyzedUniqueConstraints;
    }

    private static Pair<BaseTableInfo, Table> analyzeForeignKeyConstraintTablePath(String catalogName,
                                                                                   String tablePath,
                                                                                   String foreignKeyConstraintDesc,
                                                                                   Database db) {
        String[] parts = tablePath.split("\\.");
        String dbName = db.getFullName();
        String tableName = "";
        if (parts.length == 3) {
            catalogName = parts[0];
            dbName = parts[1];
            tableName = parts[2];
        } else if (parts.length == 2) {
            dbName = parts[0];
            tableName = parts[1];
        } else if (parts.length == 1) {
            tableName = parts[0];
        } else {
            throw new SemanticException(String.format("invalid foreign key constraint:%s," +
                    "table path is invalid", foreignKeyConstraintDesc));
        }

        if (!GlobalStateMgr.getCurrentState().getCatalogMgr().catalogExists(catalogName)) {
            throw new SemanticException(String.format("catalog: %s do not exist", catalogName));
        }

        ConnectContext context = new ConnectContext();
        Database parentDb = GlobalStateMgr.getCurrentState().getMetadataMgr().getDb(context, catalogName, dbName);
        if (parentDb == null) {
            throw new SemanticException(
                    String.format("catalog: %s, database: %s do not exist", catalogName, dbName));
        }
        Table table = GlobalStateMgr.getCurrentState().getMetadataMgr()
                .getTable(context, catalogName, dbName, tableName);
        if (table == null) {
            throw new SemanticException(String.format("catalog:%s, database: %s, table:%s do not exist",
                    catalogName, dbName, tableName));
        }

        BaseTableInfo tableInfo;
        if (catalogName.equals(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME)) {
            tableInfo = new BaseTableInfo(parentDb.getId(), dbName, table.getName(), table.getId());
        } else {
            tableInfo = new BaseTableInfo(catalogName, dbName, table.getName(), table.getTableIdentifier());
        }

        return Pair.create(tableInfo, table);
    }

    private static void analyzeForeignKeyUniqueConstraint(Table parentTable, List<String> parentColumns,
                                                          Table analyzedTable) {
        KeysType parentTableKeyType = KeysType.DUP_KEYS;
        if (parentTable.isNativeTableOrMaterializedView()) {
            OlapTable parentOlapTable = (OlapTable) parentTable;
            parentTableKeyType =
                    parentOlapTable.getIndexMetaByIndexId(parentOlapTable.getBaseIndexId()).getKeysType();
        }

        List<UniqueConstraint> mvUniqueConstraints = Lists.newArrayList();
        if (analyzedTable.isMaterializedView() && analyzedTable.hasUniqueConstraints()) {
            mvUniqueConstraints = analyzedTable.getUniqueConstraints().stream().filter(
                            uniqueConstraint -> SRStringUtils.areTableNamesEqual(parentTable, uniqueConstraint.getTableName()))
                    .collect(Collectors.toList());
        }

        if (parentTableKeyType == KeysType.AGG_KEYS) {
            throw new SemanticException(
                    String.format("do not support reference agg table:%s", parentTable.getName()));
        } else {
            // for DUP_KEYS type olap table or external table
            if (!parentTable.hasUniqueConstraints() && mvUniqueConstraints.isEmpty()) {
                throw new SemanticException(
                        String.format("dup table:%s has no unique constraint", parentTable.getName()));
            } else {
                List<UniqueConstraint> uniqueConstraints = parentTable.getUniqueConstraints();
                if (uniqueConstraints == null) {
                    uniqueConstraints = mvUniqueConstraints;
                } else {
                    uniqueConstraints.addAll(mvUniqueConstraints);
                }
                boolean matched = false;
                for (UniqueConstraint uniqueConstraint : uniqueConstraints) {
                    if (uniqueConstraint.isMatch(parentTable, Sets.newHashSet(parentColumns))) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new SemanticException(
                            String.format("columns:%s are not dup table:%s's unique constraint", parentColumns,
                                    parentTable.getName()));
                }
            }
        }
    }

    public static List<ForeignKeyConstraint> analyzeForeignKeyConstraint(
            Map<String, String> properties, Database db, Table analyzedTable) {
        List<ForeignKeyConstraint> foreignKeyConstraints = Lists.newArrayList();
        if (properties != null && properties.containsKey(PROPERTIES_FOREIGN_KEY_CONSTRAINT)) {
            String foreignKeyConstraintsDesc = properties.get(PROPERTIES_FOREIGN_KEY_CONSTRAINT);
            if (Strings.isNullOrEmpty(foreignKeyConstraintsDesc)) {
                return foreignKeyConstraints;
            }

            String[] foreignKeyConstraintDescArray = foreignKeyConstraintsDesc.trim().split(";");
            for (String foreignKeyConstraintDesc : foreignKeyConstraintDescArray) {
                String trimed = foreignKeyConstraintDesc.trim();
                if (Strings.isNullOrEmpty(trimed)) {
                    continue;
                }
                Matcher foreignKeyMatcher = ForeignKeyConstraint.FOREIGN_KEY_PATTERN.matcher(trimed);
                if (!foreignKeyMatcher.find() || foreignKeyMatcher.groupCount() != 9) {
                    throw new SemanticException(
                            String.format("invalid foreign key constraint:%s", foreignKeyConstraintDesc));
                }
                String sourceTablePath = foreignKeyMatcher.group(1);
                String sourceColumns = foreignKeyMatcher.group(3);

                String targetTablePath = foreignKeyMatcher.group(6);
                String targetColumns = foreignKeyMatcher.group(8);
                // case insensitive
                List<String> childColumnNames = Arrays.stream(sourceColumns.split(",")).
                        map(String::trim).map(String::toLowerCase).collect(Collectors.toList());
                List<String> parentColumnNames = Arrays.stream(targetColumns.split(",")).
                        map(String::trim).map(String::toLowerCase).collect(Collectors.toList());
                if (childColumnNames.size() != parentColumnNames.size()) {
                    throw new SemanticException(String.format("invalid foreign key constraint:%s," +
                            " columns' size does not match", foreignKeyConstraintDesc));
                }
                // analyze table exist for foreign key constraint
                Pair<BaseTableInfo, Table> parentTablePair = analyzeForeignKeyConstraintTablePath(analyzedTable.getCatalogName(),
                        targetTablePath, foreignKeyConstraintDesc, db);
                BaseTableInfo parentTableInfo = parentTablePair.first;
                Table parentTable = parentTablePair.second;
                List<ColumnId> parentColumnIds = MetaUtils.getColumnIdsByColumnNames(parentTable, parentColumnNames);
                Pair<BaseTableInfo, Table> childTablePair = Pair.create(null, analyzedTable);
                Table childTable = analyzedTable;
                if (analyzedTable.isMaterializedView()) {
                    childTablePair = analyzeForeignKeyConstraintTablePath(analyzedTable.getCatalogName(),
                            sourceTablePath, foreignKeyConstraintDesc, db);
                    childTable = childTablePair.second;
                }
                List<ColumnId> childColumnIds = MetaUtils.getColumnIdsByColumnNames(childTable, childColumnNames);

                analyzeForeignKeyUniqueConstraint(parentTable, parentColumnNames, analyzedTable);

                List<Pair<ColumnId, ColumnId>> columnRefPairs = Streams.zip(childColumnIds.stream(),
                        parentColumnIds.stream(), Pair::create).collect(Collectors.toList());
                for (Pair<ColumnId, ColumnId> pair : columnRefPairs) {
                    Column childColumn = childTable.getColumn(pair.first);
                    Column parentColumn = parentTable.getColumn(pair.second);
                    if (!childColumn.getType().equals(parentColumn.getType())) {
                        throw new SemanticException(String.format(
                                "column:%s type does mot match referenced column:%s type", pair.first, pair.second));
                    }
                }

                BaseTableInfo childTableInfo = childTablePair.first;
                ForeignKeyConstraint foreignKeyConstraint = new ForeignKeyConstraint(parentTableInfo, childTableInfo,
                        columnRefPairs);
                foreignKeyConstraints.add(foreignKeyConstraint);
            }
            if (foreignKeyConstraints.isEmpty()) {
                throw new SemanticException(
                        String.format("invalid foreign key constrain:%s", foreignKeyConstraintsDesc));
            }
            properties.remove(PROPERTIES_FOREIGN_KEY_CONSTRAINT);
        }

        return foreignKeyConstraints;
    }

    public static DataCacheInfo analyzeDataCacheInfo(Map<String, String> properties) throws AnalysisException {
        boolean enableDataCache = analyzeBooleanProp(properties, PropertyAnalyzer.PROPERTIES_DATACACHE_ENABLE, true);

        boolean enableAsyncWriteBack =
                analyzeBooleanProp(properties, PropertyAnalyzer.PROPERTIES_ENABLE_ASYNC_WRITE_BACK, false);
        if (enableAsyncWriteBack) {
            throw new AnalysisException("enable_async_write_back is disabled since version 3.1.4");
        }
        return new DataCacheInfo(enableDataCache, enableAsyncWriteBack);
    }

    public static PeriodDuration analyzeDataCachePartitionDuration(Map<String, String> properties) throws AnalysisException {
        String text = properties.get(PROPERTIES_DATACACHE_PARTITION_DURATION);
        if (text == null) {
            return null;
        }
        properties.remove(PROPERTIES_DATACACHE_PARTITION_DURATION);
        try {
            return TimeUtils.parseHumanReadablePeriodOrDuration(text);
        } catch (DateTimeParseException ex) {
            throw new AnalysisException(ex.getMessage());
        }
    }

    public static TPersistentIndexType analyzePersistentIndexType(Map<String, String> properties) throws AnalysisException {
        if (properties != null && properties.containsKey(PROPERTIES_PERSISTENT_INDEX_TYPE)) {
            String type = properties.get(PROPERTIES_PERSISTENT_INDEX_TYPE);
            properties.remove(PROPERTIES_PERSISTENT_INDEX_TYPE);
            if (type.equalsIgnoreCase(TableProperty.LOCAL_INDEX_TYPE)) {
                return TPersistentIndexType.LOCAL;
            } else if (type.equalsIgnoreCase(TableProperty.CLOUD_NATIVE_INDEX_TYPE)) {
                return TPersistentIndexType.CLOUD_NATIVE;
            } else {
                throw new AnalysisException("Invalid persistent index type: " + type);
            }
        }
        return Config.enable_cloud_native_persistent_index_by_default ? TPersistentIndexType.CLOUD_NATIVE
                : TPersistentIndexType.LOCAL;
    }

    public static TCompactionStrategy analyzecompactionStrategy(Map<String, String> properties) throws AnalysisException {
        if (properties != null && properties.containsKey(PROPERTIES_COMPACTION_STRATEGY)) {
            String strategy = properties.get(PROPERTIES_COMPACTION_STRATEGY);
            properties.remove(PROPERTIES_COMPACTION_STRATEGY);
            if (strategy.equalsIgnoreCase(TableProperty.DEFAULT_COMPACTION_STRATEGY)) {
                return TCompactionStrategy.DEFAULT;
            } else if (strategy.equalsIgnoreCase(TableProperty.REAL_TIME_COMPACTION_STRATEGY)) {
                return TCompactionStrategy.REAL_TIME;
            } else {
                throw new AnalysisException("Invalid compaction strategy: " + strategy);
            }
        }
        return TCompactionStrategy.DEFAULT;
    }

    public static PeriodDuration analyzeStorageCoolDownTTL(Map<String, String> properties,
                                                           boolean removeProperties) throws AnalysisException {
        String text = properties.get(PROPERTIES_STORAGE_COOLDOWN_TTL);
        if (removeProperties) {
            properties.remove(PROPERTIES_STORAGE_COOLDOWN_TTL);
        }
        if (Strings.isNullOrEmpty(text)) {
            return null;
        }
        PeriodDuration periodDuration;
        try {
            periodDuration = TimeUtils.parseHumanReadablePeriodOrDuration(text);
        } catch (DateTimeParseException ex) {
            throw new AnalysisException(ex.getMessage());
        }
        return periodDuration;
    }

    public static void analyzeMVProperties(Database db,
                                           MaterializedView materializedView,
                                           Map<String, String> properties,
                                           boolean isNonPartitioned,
                                           Map<Expr, Expr> exprAdjustedMap) throws DdlException {
        try {
            // replicated storage
            materializedView.setEnableReplicatedStorage(
                    PropertyAnalyzer.analyzeBooleanProp(
                            properties, PropertyAnalyzer.PROPERTIES_REPLICATED_STORAGE,
                            Config.enable_replicated_storage_as_default_engine));

            // replication_num
            short replicationNum = RunMode.defaultReplicationNum();
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM)) {
                replicationNum = PropertyAnalyzer.analyzeReplicationNum(properties, replicationNum);
                materializedView.setReplicationNum(replicationNum);
            }
            // bloom_filter_columns
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_BF_COLUMNS)) {
                List<Column> baseSchema = materializedView.getColumns();
                Set<String> bfColumns = PropertyAnalyzer.analyzeBloomFilterColumns(properties, baseSchema,
                        materializedView.getKeysType() == KeysType.PRIMARY_KEYS);
                if (bfColumns != null && bfColumns.isEmpty()) {
                    bfColumns = null;
                }
                double bfFpp = PropertyAnalyzer.analyzeBloomFilterFpp(properties);
                if (bfColumns != null && bfFpp == 0) {
                    bfFpp = FeConstants.DEFAULT_BLOOM_FILTER_FPP;
                } else if (bfColumns == null) {
                    bfFpp = 0;
                }
                Set<ColumnId> bfColumnIds = null;
                if (bfColumns != null && !bfColumns.isEmpty()) {
                    bfColumnIds = Sets.newTreeSet(ColumnId.CASE_INSENSITIVE_ORDER);
                    for (String colName : bfColumns) {
                        bfColumnIds.add(materializedView.getColumn(colName).getColumnId());
                    }
                }
                materializedView.setBloomFilterInfo(bfColumnIds, bfFpp);
            }
            // mv_rewrite_staleness second.
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_MV_REWRITE_STALENESS_SECOND)) {
                int maxMVRewriteStaleness = PropertyAnalyzer.analyzeMVRewriteStaleness(properties);
                materializedView.setMaxMVRewriteStaleness(maxMVRewriteStaleness);
                materializedView.getTableProperty().getProperties().put(
                        PropertyAnalyzer.PROPERTIES_MV_REWRITE_STALENESS_SECOND,
                        Integer.toString(maxMVRewriteStaleness));
            }
            // partition ttl
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_TTL)) {
                if (isNonPartitioned) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_PARTITION_TTL
                            + " is only supported by partitioned materialized-view");
                }

                Pair<String, PeriodDuration> ttlDuration = PropertyAnalyzer.analyzePartitionTTL(properties, true);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_TTL, ttlDuration.first);
                materializedView.getTableProperty().setPartitionTTL(ttlDuration.second);
            }
            // partition retention condition
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_RETENTION_CONDITION)) {
                if (isNonPartitioned) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_PARTITION_RETENTION_CONDITION
                            + " is only supported by partitioned materialized-view");
                }
                String ttlRetentionCondition = PropertyAnalyzer.analyzePartitionRetentionCondition(db, materializedView,
                        properties, true, exprAdjustedMap);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_RETENTION_CONDITION, ttlRetentionCondition);
                materializedView.getTableProperty().setPartitionRetentionCondition(ttlRetentionCondition);
            }

            // partition ttl number
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER)) {
                int number = PropertyAnalyzer.analyzePartitionTTLNumber(properties);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER, String.valueOf(number));
                materializedView.getTableProperty().setPartitionTTLNumber(number);
                if (!materializedView.getPartitionInfo().isRangePartition()) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_PARTITION_TTL_NUMBER
                            + " does not support non-range-partitioned materialized view.");
                }
            }
            // partition auto refresh partitions limit
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT)) {
                int limit = PropertyAnalyzer.analyzeAutoRefreshPartitionsLimit(properties, materializedView);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT, String.valueOf(limit));
                materializedView.getTableProperty().setAutoRefreshPartitionsLimit(limit);
                if (isNonPartitioned) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_AUTO_REFRESH_PARTITIONS_LIMIT
                            + " does not support non-range-partitioned materialized view.");
                }
            }
            // partition refresh number
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER)) {
                int number = PropertyAnalyzer.analyzePartitionRefreshNumber(properties);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER, String.valueOf(number));
                materializedView.getTableProperty().setPartitionRefreshNumber(number);
                if (isNonPartitioned) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_NUMBER
                            + " does not support non-partitioned materialized view.");
                }
            }
            // partition refresh strategy
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_STRATEGY)) {
                String strategy = PropertyAnalyzer.analyzePartitionRefreshStrategy(properties);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_REFRESH_STRATEGY, strategy);
                materializedView.getTableProperty().setPartitionRefreshStrategy(strategy);
            }
            // exclude trigger tables
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES)) {
                List<TableName> tables = PropertyAnalyzer.analyzeExcludedTables(properties,
                        PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES,
                        materializedView);
                String tableSb = getExcludeString(tables);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES, tableSb);
                materializedView.getTableProperty().setExcludedTriggerTables(tables);
            }
            // exclude refresh base tables
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_EXCLUDED_REFRESH_TABLES)) {
                List<TableName> tables = PropertyAnalyzer.analyzeExcludedTables(properties,
                        PropertyAnalyzer.PROPERTIES_EXCLUDED_REFRESH_TABLES,
                        materializedView);
                String tableSb = getExcludeString(tables);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_EXCLUDED_REFRESH_TABLES, tableSb);
                materializedView.getTableProperty().setExcludedRefreshTables(tables);
            }
            // resource_group
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_RESOURCE_GROUP)) {
                String resourceGroup = PropertyAnalyzer.analyzeResourceGroup(properties);
                if (GlobalStateMgr.getCurrentState().getResourceGroupMgr().getResourceGroup(resourceGroup) == null) {
                    throw new AnalysisException(PropertyAnalyzer.PROPERTIES_RESOURCE_GROUP
                            + " " + resourceGroup + " does not exist.");
                }
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_RESOURCE_GROUP, resourceGroup);
                materializedView.getTableProperty().setResourceGroup(resourceGroup);
            }
            // force external query rewrite
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE)) {
                String propertyValue = properties.get(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE);
                TableProperty.QueryRewriteConsistencyMode value =
                        TableProperty.analyzeExternalTableQueryRewrite(propertyValue);
                properties.remove(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE);
                materializedView.getTableProperty().getProperties().
                        put(PropertyAnalyzer.PROPERTIES_FORCE_EXTERNAL_TABLE_QUERY_REWRITE, String.valueOf(value));
                materializedView.getTableProperty().setForceExternalTableQueryRewrite(value);
            }
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_QUERY_REWRITE_CONSISTENCY)) {
                String propertyValue = properties.get(PropertyAnalyzer.PROPERTIES_QUERY_REWRITE_CONSISTENCY);
                TableProperty.QueryRewriteConsistencyMode value = TableProperty.analyzeQueryRewriteMode(propertyValue);
                properties.remove(PropertyAnalyzer.PROPERTIES_QUERY_REWRITE_CONSISTENCY);
                materializedView.getTableProperty().getProperties().
                        put(PropertyAnalyzer.PROPERTIES_QUERY_REWRITE_CONSISTENCY, String.valueOf(value));
                materializedView.getTableProperty().setQueryRewriteConsistencyMode(value);
            }
            // unique keys
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT)) {
                List<UniqueConstraint> uniqueConstraints = PropertyAnalyzer.analyzeUniqueConstraint(properties, db,
                        materializedView);
                materializedView.setUniqueConstraints(uniqueConstraints);
            }
            // foreign keys
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_FOREIGN_KEY_CONSTRAINT)) {
                List<ForeignKeyConstraint> foreignKeyConstraints = PropertyAnalyzer.analyzeForeignKeyConstraint(
                        properties, db, materializedView);
                materializedView.setForeignKeyConstraints(foreignKeyConstraints);
            }

            // time drift constraint
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT)) {
                String timeDriftConstraintSpec = properties.get(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT);
                PropertyAnalyzer.analyzeTimeDriftConstraint(timeDriftConstraintSpec, materializedView, properties);
                materializedView.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT, timeDriftConstraintSpec);
                materializedView.getTableProperty().setTimeDriftConstraintSpec(timeDriftConstraintSpec);
            }

            // labels.location
            if (!materializedView.isCloudNativeMaterializedView()) {
                analyzeLocation(materializedView, properties);
            }

            // colocate_with
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_COLOCATE_WITH)) {
                String colocateGroup = PropertyAnalyzer.analyzeColocate(properties);
                if (org.apache.commons.lang3.StringUtils.isNotEmpty(colocateGroup) &&
                        !materializedView.getDefaultDistributionInfo().supportColocate()) {
                    throw new AnalysisException(": random distribution does not support 'colocate_with'");
                }
                GlobalStateMgr.getCurrentState().getColocateTableIndex().addTableToGroup(
                        db, materializedView, colocateGroup, materializedView.isCloudNativeMaterializedView());
            }

            // enable_query_rewrite
            if (properties.containsKey(PropertyAnalyzer.PROPERTY_MV_ENABLE_QUERY_REWRITE)) {
                String str = properties.get(PropertyAnalyzer.PROPERTY_MV_ENABLE_QUERY_REWRITE);
                TableProperty.MVQueryRewriteSwitch value = TableProperty.analyzeQueryRewriteSwitch(str);
                materializedView.getTableProperty().setMvQueryRewriteSwitch(value);
                materializedView.getTableProperty().getProperties().put(
                        PropertyAnalyzer.PROPERTY_MV_ENABLE_QUERY_REWRITE, str);
                properties.remove(PropertyAnalyzer.PROPERTY_MV_ENABLE_QUERY_REWRITE);
            }

            // enable_query_rewrite
            if (properties.containsKey(PropertyAnalyzer.PROPERTY_TRANSPARENT_MV_REWRITE_MODE)) {
                String str = properties.get(PropertyAnalyzer.PROPERTY_TRANSPARENT_MV_REWRITE_MODE);
                TableProperty.MVTransparentRewriteMode value = TableProperty.analyzeMVTransparentRewrite(str);
                materializedView.getTableProperty().setMvTransparentRewriteMode(value);
                materializedView.getTableProperty().getProperties().put(
                        PropertyAnalyzer.PROPERTY_TRANSPARENT_MV_REWRITE_MODE, str);
                properties.remove(PropertyAnalyzer.PROPERTY_TRANSPARENT_MV_REWRITE_MODE);
            }

            // compression
            if (properties.containsKey(PropertyAnalyzer.PROPERTIES_COMPRESSION)) {
                String str = properties.get(PropertyAnalyzer.PROPERTIES_COMPRESSION);
                materializedView.getTableProperty().getProperties().put(
                        PropertyAnalyzer.PROPERTIES_COMPRESSION, str);
                properties.remove(PropertyAnalyzer.PROPERTIES_COMPRESSION);
            }

            // ORDER BY() -> sortKeys
            if (CollectionUtils.isNotEmpty(materializedView.getTableProperty().getMvSortKeys())) {
                materializedView.getTableProperty().putMvSortKeys();
            }

            // lake storage info
            if (materializedView.isCloudNativeMaterializedView()) {
                String volume = "";
                if (properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_VOLUME)) {
                    volume = properties.remove(PropertyAnalyzer.PROPERTIES_STORAGE_VOLUME);
                }
                StorageVolumeMgr svm = GlobalStateMgr.getCurrentState().getStorageVolumeMgr();
                svm.bindTableToStorageVolume(volume, db.getId(), materializedView.getId());
                String storageVolumeId = svm.getStorageVolumeIdOfTable(materializedView.getId());
                GlobalStateMgr.getCurrentState().getLocalMetastore()
                        .setLakeStorageInfo(db, materializedView, storageVolumeId, properties);
            }

            // warehouse
            if (materializedView.isCloudNativeMaterializedView()) {
                // use warehouse for current session（if u exec "set warehouse aaa" before you create mv1, then use aaa）
                long warehouseId = ConnectContext.get().getCurrentWarehouseId();
                if (properties.containsKey(PropertyAnalyzer.PROPERTIES_WAREHOUSE)) {
                    String warehouseName = properties.remove(PropertyAnalyzer.PROPERTIES_WAREHOUSE);
                    Warehouse warehouse = GlobalStateMgr.getCurrentState().getWarehouseMgr()
                            .getWarehouse(warehouseName);
                    warehouseId = warehouse.getId();
                }

                materializedView.setWarehouseId(warehouseId);
                LOG.debug("set warehouse {} in materializedView", warehouseId);
            }

            // datacache.partition_duration
            if (materializedView.isCloudNativeMaterializedView()) {
                if (properties.containsKey(PropertyAnalyzer.PROPERTIES_DATACACHE_PARTITION_DURATION)) {
                    PeriodDuration duration = PropertyAnalyzer.analyzeDataCachePartitionDuration(properties);
                    materializedView.setDataCachePartitionDuration(duration);
                }
            }

            // NOTE: for recognizing unknown properties, this should be put as the last if condition
            // session properties
            if (!properties.isEmpty()) {
                // analyze properties
                List<SetListItem> setListItems = Lists.newArrayList();
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    SystemVariable variable = getMVSystemVariable(properties, entry);
                    GlobalStateMgr.getCurrentState().getVariableMgr().checkSystemVariableExist(variable);
                    setListItems.add(variable);
                }
                SetStmtAnalyzer.analyze(new SetStmt(setListItems), null);

                // set properties if there are no exceptions
                materializedView.getTableProperty().getProperties().putAll(properties);
            }
        } catch (AnalysisException e) {
            if (materializedView.isCloudNativeMaterializedView()) {
                GlobalStateMgr.getCurrentState().getStorageVolumeMgr()
                        .unbindTableToStorageVolume(materializedView.getId());
            }
            ErrorReport.reportSemanticException(ErrorCode.ERR_INVALID_PARAMETER, e.getMessage());
        }
    }

    @NotNull
    private static String getExcludeString(List<TableName> tables) {
        StringBuilder tableSb = new StringBuilder();
        for (int i = 1; i <= tables.size(); i++) {
            TableName tableName = tables.get(i - 1);
            if (tableName.getDb() == null) {
                tableSb.append(tableName.getTbl());
            } else {
                tableSb.append(tableName.getDb()).append(".").append(tableName.getTbl());
            }
            if (i != tables.size()) {
                tableSb.append(",");
            }
        }
        return tableSb.toString();
    }

    @NotNull
    private static SystemVariable getMVSystemVariable(Map<String, String> properties, Map.Entry<String, String> entry)
            throws AnalysisException {
        if (!entry.getKey().startsWith(PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX)) {
            throw new AnalysisException("Analyze materialized properties failed " +
                    "because unknown properties: " + properties +
                    ", please add `session.` prefix if you want add session variables for mv(" +
                    "eg, \"session.insert_timeout\"=\"30000000\").");
        }
        String varKey = entry.getKey().substring(
                PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX.length());
        SystemVariable variable = new SystemVariable(varKey, new StringLiteral(entry.getValue()));
        return variable;
    }

    public static DataProperty analyzeMVDataProperty(MaterializedView materializedView,
                                                     Map<String, String> properties) {
        DataProperty dataProperty;
        // set storage medium
        boolean hasMedium = properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_MEDIUM);
        try {
            dataProperty = PropertyAnalyzer.analyzeDataProperty(properties,
                    DataProperty.getInferredDefaultDataProperty(), false);
        } catch (AnalysisException e) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_INVALID_PARAMETER, e.getMessage());
            return null;
        }
        if (hasMedium && dataProperty.getStorageMedium() == TStorageMedium.SSD) {
            materializedView.setStorageMedium(dataProperty.getStorageMedium());
            // set storage cooldown time into table property,
            // because we don't have property in MaterializedView
            materializedView.getTableProperty().getProperties()
                    .put(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME,
                            String.valueOf(dataProperty.getCooldownTimeMs()));
        }

        return dataProperty;
    }

    /**
     * Generate a string representation of properties like ('a'='1', 'b'='2')
     */
    public static String stringifyProperties(Map<String, String> properties) {
        if (MapUtils.isEmpty(properties)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("'").append(entry.getKey()).append("'=").append("'").append(entry.getValue()).append("'");
        }
        sb.append(")");
        return sb.toString();
    }
}

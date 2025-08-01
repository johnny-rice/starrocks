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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/transaction/TransactionState.java

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

package com.starrocks.transaction;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Replica.ReplicaState;
import com.starrocks.catalog.Tablet;
import com.starrocks.common.Config;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.TraceManager;
import com.starrocks.common.io.Writable;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.gson.GsonPreProcessable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.service.FrontendOptions;
import com.starrocks.system.Backend;
import com.starrocks.task.PublishVersionTask;
import com.starrocks.thrift.TOlapTablePartition;
import com.starrocks.thrift.TPartitionVersionInfo;
import com.starrocks.thrift.TTabletLocation;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.warehouse.cngroup.ComputeResource;
import io.opentelemetry.api.trace.Span;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

public class TransactionState implements Writable, GsonPreProcessable {
    private static final Logger LOG = LogManager.getLogger(TransactionState.class);

    // compare the TransactionState by txn id, desc
    public static class TxnStateComparator implements Comparator<TransactionState> {
        @Override
        public int compare(TransactionState t1, TransactionState t2) {
            return Long.compare(t2.getTransactionId(), t1.getTransactionId());
        }
    }

    public static final TxnStateComparator TXN_ID_COMPARATOR = new TxnStateComparator();

    public enum LoadJobSourceType {
        FRONTEND(1),                    // old dpp load, mini load, insert stmt(not streaming type) use this type
        BACKEND_STREAMING(2),           // streaming load use this type
        INSERT_STREAMING(3),            // insert stmt (streaming type) use this type
        ROUTINE_LOAD_TASK(4),           // routine load task use this type
        BATCH_LOAD_JOB(5),              // load job v2 for broker load
        DELETE(6),                     // synchronization delete job use this type
        LAKE_COMPACTION(7),            // compaction of LakeTable
        FRONTEND_STREAMING(8),          // FE streaming load use this type
        MV_REFRESH(9),                  // Refresh MV
        REPLICATION(10),                // Replication
        BYPASS_WRITE(11);               // Bypass BE, and write data file directly

        private final int flag;

        LoadJobSourceType(int flag) {
            this.flag = flag;
        }

        public int value() {
            return flag;
        }

        public static LoadJobSourceType valueOf(int flag) {
            return Arrays.stream(values())
                    .filter(sourceType -> sourceType.flag == flag)
                    .findFirst()
                    .orElse(null);
        }

        public int getFlag() {
            return flag;
        }
    }

    public enum TxnSourceType {
        FE(1),
        BE(2);

        public int value() {
            return flag;
        }

        private final int flag;

        TxnSourceType(int flag) {
            this.flag = flag;
        }

        public static TxnSourceType valueOf(int flag) {
            switch (flag) {
                case 1:
                    return FE;
                case 2:
                    return BE;
                default:
                    return null;
            }
        }
    }

    public static class TxnCoordinator {
        @SerializedName("st")
        public TxnSourceType sourceType;
        @SerializedName("ip")
        public String ip;
        // The id of the coordinator backend. Only valid if sourceType is BE.
        // Currently, it's only used to record which backend to redirect for
        // transaction stream load when the transaction is still in PREPARE.
        // Do not persist it as the PREPARE transaction also does not persist,
        // and it will not be used after the transaction is prepared, committed
        // or aborted. We can not do redirection based on 'ip' because there may
        // be multiple backends on the same physical node, so the ip is not unique
        // among backends, but backend id is unique.
        private long backendId = -1;

        public TxnCoordinator() {
        }

        public TxnCoordinator(TxnSourceType sourceType, String ip) {
            this.sourceType = sourceType;
            this.ip = ip;
        }

        public static TxnCoordinator fromThisFE() {
            return new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE,
                    FrontendOptions.getLocalHostAddress());
        }

        public static TxnCoordinator fromBackend(String ip, long backendId) {
            TxnCoordinator coordinator = new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.BE, ip);
            coordinator.backendId = backendId;
            return coordinator;
        }

        public long getBackendId() {
            return backendId;
        }

        @Override
        public String toString() {
            return sourceType.toString() + ": " + ip;
        }
    }

    @SerializedName("dd")
    private long dbId;
    @SerializedName("tl")
    private List<Long> tableIdList;
    @SerializedName("tx")
    private long transactionId;
    @SerializedName("lb")
    private String label;
    // requestId is used to judge whether a begin request is an internal retry request.
    // no need to persist it.
    private TUniqueId requestId;
    @SerializedName("ci")
    private final Map<Long, TableCommitInfo> idToTableCommitInfos;
    // coordinator is show who begin this txn (FE, or one of BE, etc...)
    @SerializedName("tc")
    private TxnCoordinator txnCoordinator;
    @SerializedName("ts")
    private TransactionStatus transactionStatus;
    @SerializedName("st")
    private LoadJobSourceType sourceType;
    @SerializedName("pt")
    private long prepareTime;
    @SerializedName("pet")
    private long preparedTime;
    @SerializedName("ct")
    private long commitTime;
    @SerializedName("ft")
    private long finishTime;
    @SerializedName("rs")
    private String reason = "";
    @SerializedName("gtid")
    private long globalTransactionId;

    // whether this txn is finished using new mechanism
    // this field needs to be persisted, so we shared the serialization field with `reason`.
    // `reason` is only used when txn is aborted, so it's ok to reuse the space for visible txns.
    @SerializedName("nf")
    private boolean newFinish = false;
    @SerializedName("fs")
    private TxnFinishState finishState;

    // error replica ids
    @SerializedName("er")
    private Set<Long> errorReplicas;

    private Set<TabletCommitInfo> tabletCommitInfos = null;

    // tabletCommitInfos is not persistent because it is very large, and it is null in follower FE.
    // 'OlapTableTxnLogApplier.applyVisibleLog' uses tabletCommitInfos to check whether replica need update version.
    // null tabletCommitInfos will cause follower wrong version update, and query failed from follower
    // with version not found error.
    // so add persistent unknownReplicas to fix this issue, unknownReplicas is much less than tabletCommitInfos.
    // unknownReplicas = total replicas - tabletCommitInfos - errorReplicas
    @SerializedName("ur")
    private Set<Long> unknownReplicas;

    @SerializedName("ctl")
    private boolean useCombinedTxnLog;

    private final CountDownLatch latch;

    // these states need not be serialized
    private final Map<Long, PublishVersionTask> publishVersionTasks; // Only for OlapTable
    private boolean hasSendTask;
    private long publishVersionTime = -1;
    private long publishVersionFinishTime = -1;

    // The time of first commit attempt, i.e, the end time when ingestion write is completed.
    // Measured in milliseconds since epoch.
    // -1 means this field is unset.
    //
    // Protected by database lock.
    //
    // NOTE: This field is only used in shared data mode.
    private long writeEndTimeMs = -1;

    // The duration of the ingestion data write operation in milliseconds.
    // This field is normally set automatically during commit based on
    // writeEndTime and prepareTime. However, for cases like broker load
    // with scheduling delays and concurrent ingestion, the auto calculated
    // value may have large error compared to actual data write duration.
    // In these cases, the upper ingestion job should set this field manually
    // before commit.
    //
    // Protected by database lock.
    //
    // NOTE: This field is only used in shared data mode.
    private long writeDurationMs = -1;

    // The minimum time allowed to commit the transaction.
    // Measured in milliseconds since epoch.
    //
    // Protected by database lock.
    //
    // NOTE: This field is only used in shared data mode.
    private long allowCommitTimeMs = -1;

    //This is for compatibility and is not deleted. callbackIdList will be used later. can be deleted at 3.6
    @Deprecated
    @SerializedName("cb")
    private long callbackId = -1;

    @SerializedName("cbl")
    private List<Long> callbackIdList;

    @SerializedName("to")
    private long timeoutMs = Config.stream_load_default_timeout_second * 1000L;

    // optional
    @SerializedName("ta")
    private TxnCommitAttachment txnCommitAttachment;

    @SerializedName("wid")
    private long warehouseId = WarehouseManager.DEFAULT_WAREHOUSE_ID;

    // persistent
    @SerializedName("wcr")
    private ComputeResource computeResource = WarehouseManager.DEFAULT_RESOURCE;

    // this map should be set when load execution begin, so that when the txn commit, it will know
    // which tables and rollups it loaded.
    // tbl id -> (index ids)
    private final Map<Long, Set<Long>> loadedTblIndexes = Maps.newHashMap();

    // record some error msgs during the transaction operation.
    // this msg will be shown in show proc "/transactions/dbId/";
    // no need to persist.
    private String errMsg = "";

    private long lastErrTimeMs = 0;

    // used for PublishDaemon to check whether this txn can be published
    // not persisted, so need to rebuilt if FE restarts
    private volatile TransactionChecker finishChecker = null;

    private Span txnSpan = null;
    private String traceParent = null;

    // For a transaction, we need to ensure that different clients obtain consistent partition information,
    // to avoid inconsistencies caused by replica migration and other operations during the transaction process.
    // Therefore, a snapshot of this information is maintained here.
    private Map<Long, ConcurrentMap<String, TOlapTablePartition>> tableToPartitionNameToTPartition = Maps.newConcurrentMap();
    private ConcurrentMap<Long, TTabletLocation> tabletIdToTTabletLocation = Maps.newConcurrentMap();

    private Map<Long, List<String>> tableToCreatedPartitionNames = Maps.newHashMap();
    private AtomicBoolean isCreatePartitionFailed = new AtomicBoolean(false);

    private final ReentrantReadWriteLock txnLock = new ReentrantReadWriteLock(true);

    public void writeLock() {
        txnLock.writeLock().lock();
    }

    public void writeUnlock() {
        txnLock.writeLock().unlock();
    }

    public TransactionState() {
        this.dbId = -1;
        this.tableIdList = Lists.newArrayList();
        this.transactionId = -1;
        this.label = "";
        this.idToTableCommitInfos = Maps.newHashMap();
        this.txnCoordinator = new TxnCoordinator(TxnSourceType.FE, "127.0.0.1"); // mocked, to avoid NPE
        this.transactionStatus = TransactionStatus.PREPARE;
        this.sourceType = LoadJobSourceType.FRONTEND;
        this.prepareTime = -1;
        this.commitTime = -1;
        this.finishTime = -1;
        this.reason = "";
        this.errorReplicas = Sets.newHashSet();
        this.unknownReplicas = Sets.newHashSet();
        this.publishVersionTasks = Maps.newHashMap();
        this.hasSendTask = false;
        this.latch = new CountDownLatch(1);
        this.txnSpan = TraceManager.startNoopSpan();
        this.traceParent = TraceManager.toTraceParent(txnSpan.getSpanContext());

        this.callbackIdList = Lists.newArrayList();
    }

    public TransactionState(long dbId, List<Long> tableIdList, long transactionId, String label, TUniqueId requestId,
                            LoadJobSourceType sourceType, TxnCoordinator txnCoordinator, long callbackId,
                            long timeoutMs) {
        this.dbId = dbId;
        this.tableIdList = (tableIdList == null ? Lists.newArrayList() : tableIdList);
        this.transactionId = transactionId;
        this.label = label;
        this.requestId = requestId;
        this.idToTableCommitInfos = Maps.newHashMap();
        this.txnCoordinator = txnCoordinator;
        this.transactionStatus = TransactionStatus.PREPARE;
        this.sourceType = sourceType;
        this.prepareTime = -1;
        this.commitTime = -1;
        this.finishTime = -1;
        this.reason = "";
        this.errorReplicas = Sets.newHashSet();
        this.unknownReplicas = Sets.newHashSet();
        this.publishVersionTasks = Maps.newHashMap();
        this.hasSendTask = false;
        this.latch = new CountDownLatch(1);
        this.callbackIdList = Lists.newArrayList(callbackId);

        this.timeoutMs = timeoutMs;
        this.txnSpan = TraceManager.startSpan("txn");
        txnSpan.setAttribute("txn_id", transactionId);
        txnSpan.setAttribute("label", label);
        this.traceParent = TraceManager.toTraceParent(txnSpan.getSpanContext());
    }

    public TransactionState(long transactionId,
                            String label,
                            TUniqueId requestId,
                            LoadJobSourceType sourceType,
                            TxnCoordinator txnCoordinator,
                            long timeoutMs) {
        this.tableIdList = Lists.newArrayList();
        this.transactionId = transactionId;
        this.label = label;
        this.requestId = requestId;
        this.idToTableCommitInfos = Maps.newHashMap();
        this.txnCoordinator = txnCoordinator;
        this.transactionStatus = TransactionStatus.PREPARE;
        this.sourceType = sourceType;
        this.prepareTime = -1;
        this.commitTime = -1;
        this.finishTime = -1;
        this.reason = "";
        this.errorReplicas = Sets.newHashSet();
        this.unknownReplicas = Sets.newHashSet();
        this.publishVersionTasks = Maps.newHashMap();
        this.hasSendTask = false;
        this.latch = new CountDownLatch(1);
        this.callbackIdList = Lists.newArrayList();

        this.timeoutMs = timeoutMs;
        this.txnSpan = TraceManager.startSpan("txn");
        txnSpan.setAttribute("txn_id", transactionId);
        txnSpan.setAttribute("label", label);
        this.traceParent = TraceManager.toTraceParent(txnSpan.getSpanContext());
    }

    public void addCallbackId(long callbackId) {
        this.callbackIdList.add(callbackId);
    }

    public void setErrorReplicas(Set<Long> newErrorReplicas) {
        this.errorReplicas = newErrorReplicas;
    }

    public void addUnknownReplica(long replicaId) {
        unknownReplicas.add(replicaId);
    }

    public boolean isRunning() {
        return transactionStatus == TransactionStatus.PREPARE || transactionStatus == TransactionStatus.PREPARED ||
                transactionStatus == TransactionStatus.COMMITTED;
    }

    public Set<TabletCommitInfo> getTabletCommitInfos() {
        return tabletCommitInfos;
    }

    public void setTabletCommitInfos(List<TabletCommitInfo> infos) {
        if (this.tabletCommitInfos == null) {
            this.tabletCommitInfos = Sets.newHashSet();
        }

        this.tabletCommitInfos.addAll(infos);
    }

    // Not skip check replica version
    // 1. replica state is not normal and clone
    // 2. replica is in tabletCommitInfos in leader (or not in errorReplicas and unknownReplicas in follower)
    // 3. replica current version >= commit version
    public boolean checkReplicaNeedSkip(Tablet tablet, Replica replica, PartitionCommitInfo partitionCommitInfo) {
        ReplicaState state = replica.getState();
        if (state != ReplicaState.NORMAL && state != ReplicaState.CLONE) {
            // Not skip check when replica is ALTER or SCHEMA CHANGE.
            // Should not return false if the state is CLONE, because lastSuccessVersion will be updated incorrectly
            // in 'OlapTableTxnLogApplier.applyVisibleLog'.
            if (LOG.isDebugEnabled()) {
                Backend backend =
                        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackend(replica.getBackendId());
                LOG.debug("skip replica version check because tablet {} backend {} is in state {}",
                        tablet.getId(), backend != null ? backend.toString() : "", state);
            }
            return false;
        }

        boolean isContain = tabletCommitInfosContainsReplica(tablet.getId(), replica.getBackendId(), replica.getId());
        if (isContain) {
            return false;
        }

        // In order for the transaction to complete in time for this scenario: the server machine is not recovered.
        // 1. Transaction TA writes to a two-replicas tablet and enters the committed state.
        //    The tablet's replicas are replicaA, replicaB.
        // 2. replicaA, replicaB generate tasks: PublishVersionTaskA, PublishVersionTaskB.
        //    PublishVersionTaskA/PublishVersionTaskB successfully submitted to the beA/beB via RPC.
        // 3. The machine where beB is located hangs and is not recoverable.
        //   Therefore PublishVersionTaskA is finished,PublishVersionTaskB is unfinished.
        // 4. FE clone replicaC from replicaA, BE report replicaC info.
        //    So transactions must rely on replicaA and replicaC to accomplish visible state.
        if (replica.getVersion() >= partitionCommitInfo.getVersion()) {
            return false;
        }

        return true;
    }

    public void resetTabletCommitInfos() {
        // With a high streamload frequency and too many tablets involved,
        // TabletCommitInfos will take up too much memory.
        tabletCommitInfos = null;
    }

    public boolean tabletCommitInfosContainsReplica(long tabletId, long backendId, long replicaId) {
        if (tabletCommitInfos != null) {
            return tabletCommitInfos.contains(new TabletCommitInfo(tabletId, backendId));
        } else {
            // tabletCommitInfos is not persistent and is null in follower fe
            return !errorReplicas.contains(replicaId) && !unknownReplicas.contains(replicaId);
        }
    }

    // Only for OlapTable
    public void addPublishVersionTask(Long backendId, PublishVersionTask task) {
        this.publishVersionTasks.put(backendId, task);
    }

    public void setHasSendTask(boolean hasSendTask) {
        this.hasSendTask = hasSendTask;
        this.publishVersionTime = System.currentTimeMillis();
    }

    public void updateSendTaskTime() {
        this.publishVersionTime = System.currentTimeMillis();
    }

    public void updatePublishTaskFinishTime() {
        this.publishVersionFinishTime = System.currentTimeMillis();
    }

    public long getPublishVersionTime() {
        return this.publishVersionTime;
    }

    public boolean hasSendTask() {
        return this.hasSendTask;
    }

    public TUniqueId getRequestId() {
        return requestId;
    }

    public long getTransactionId() {
        return transactionId;
    }

    public long getGlobalTransactionId() {
        return globalTransactionId;
    }

    public String getLabel() {
        return this.label;
    }

    public TxnCoordinator getCoordinator() {
        return txnCoordinator;
    }

    public TransactionStatus getTransactionStatus() {
        return transactionStatus;
    }

    public long getPrepareTime() {
        return prepareTime;
    }

    public long getCommitTime() {
        return commitTime;
    }

    public long getFinishTime() {
        return finishTime;
    }

    public String getReason() {
        return reason;
    }

    public TxnCommitAttachment getTxnCommitAttachment() {
        return txnCommitAttachment;
    }

    public List<Long> getCallbackId() {
        if (this.callbackIdList == null || this.callbackIdList.isEmpty()) {
            return Lists.newArrayList(callbackId);
        } else {
            return Lists.newArrayList(this.callbackIdList);
        }
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public void setComputeResource(ComputeResource computeResource) {
        this.warehouseId = computeResource.getWarehouseId();
        this.computeResource = computeResource;
    }

    public ComputeResource getComputeResource() {
        return computeResource;
    }

    public void setTransactionStatus(TransactionStatus transactionStatus) {
        // status changed
        this.transactionStatus = transactionStatus;

        // after status changed
        if (transactionStatus == TransactionStatus.VISIBLE) {
            if (MetricRepo.hasInit) {
                MetricRepo.COUNTER_TXN_SUCCESS.increase(1L);
            }
            txnSpan.addEvent("set_visible");
            txnSpan.end();
        } else if (transactionStatus == TransactionStatus.ABORTED) {
            if (MetricRepo.hasInit) {
                MetricRepo.COUNTER_TXN_FAILED.increase(1L);
            }
            txnSpan.setAttribute("state", "aborted");
            txnSpan.end();
        } else if (transactionStatus == TransactionStatus.COMMITTED) {
            txnSpan.addEvent("set_committed");
        }
    }

    public void notifyVisible() {
        // To avoid the method not having to be called repeatedly or in advance,
        // the following trigger conditions have been added
        // 1. the transactionStatus status must be VISIBLE
        // 2. this.latch.countDown(); has not been called before
        // 3. this.latch can not be null
        if (transactionStatus == TransactionStatus.VISIBLE && this.latch != null && this.latch.getCount() != 0) {
            this.latch.countDown();
        }
    }

    public void beforeStateTransform(TransactionStatus transactionStatus)
            throws TransactionException {
        for (Long callbackId : getCallbackId()) {
            // callback will pass to afterStateTransform since it may be deleted from
            // GlobalTransactionMgr between beforeStateTransform and afterStateTransform
            TxnStateChangeCallback callback = GlobalStateMgr.getCurrentState().getGlobalTransactionMgr()
                    .getCallbackFactory().getCallback(callbackId);
            // before status changed
            if (callback != null) {
                switch (transactionStatus) {
                    case ABORTED:
                        callback.beforeAborted(this);
                        break;
                    case COMMITTED:
                        callback.beforeCommitted(this);
                        break;
                    case PREPARED:
                        callback.beforePrepared(this);
                        break;
                    default:
                        break;
                }
            } else if (callbackId > 0) {
                if (Objects.requireNonNull(transactionStatus) == TransactionStatus.COMMITTED
                        && this.sourceType != LoadJobSourceType.BACKEND_STREAMING) {
                    // BACKEND_STREAMING allows callback to be null
                    // Maybe listener has been deleted. The txn need to be aborted later.
                    throw new TransactionException(
                            "Failed to commit txn when callback " + callbackId + "could not be found");
                }
            }
        }
    }

    public void afterStateTransform(TransactionStatus transactionStatus, boolean txnOperated,
                                    String txnStatusChangeReason)
            throws StarRocksException {
        for (Long callbackId : getCallbackId()) {

            TxnStateChangeCallback callback = GlobalStateMgr.getCurrentState().getGlobalTransactionMgr()
                    .getCallbackFactory().getCallback(callbackId);

            // after status changed
            if (callback != null) {
                switch (transactionStatus) {
                    case ABORTED:
                        callback.afterAborted(this, txnOperated, txnStatusChangeReason);
                        break;
                    case COMMITTED:
                        callback.afterCommitted(this, txnOperated);
                        break;
                    case PREPARED:
                        callback.afterPrepared(this, txnOperated);
                        break;
                    case VISIBLE:
                        callback.afterVisible(this, txnOperated);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    public void replaySetTransactionStatus() {
        for (Long callbackId : getCallbackId()) {
            TxnStateChangeCallback callback =
                    GlobalStateMgr.getCurrentState().getGlobalTransactionMgr().getCallbackFactory().getCallback(callbackId);
            if (callback != null) {
                if (transactionStatus == TransactionStatus.ABORTED) {
                    callback.replayOnAborted(this);
                } else if (transactionStatus == TransactionStatus.COMMITTED) {
                    callback.replayOnCommitted(this);
                } else if (transactionStatus == TransactionStatus.VISIBLE) {
                    callback.replayOnVisible(this);
                } else if (transactionStatus == TransactionStatus.PREPARED) {
                    callback.replayOnPrepared(this);
                }
            }
        }
    }

    public void waitTransactionVisible() throws InterruptedException {
        this.latch.await();
    }

    public boolean waitTransactionVisible(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return this.latch.await(timeout, unit);
    }

    public void setGlobalTransactionId(long globalTransactionId) {
        this.globalTransactionId = globalTransactionId;
    }

    public void setPrepareTime(long prepareTime) {
        this.prepareTime = prepareTime;
    }

    public void setPreparedTime(long preparedTime) {
        this.preparedTime = preparedTime;
    }

    public void setCommitTime(long commitTime) {
        this.commitTime = commitTime;
    }

    public void setFinishTime(long finishTime) {
        this.finishTime = finishTime;
    }

    public void setReason(String reason) {
        this.reason = Strings.nullToEmpty(reason);
    }

    public Set<Long> getErrorReplicas() {
        return this.errorReplicas;
    }

    public long getDbId() {
        return dbId;
    }

    public void setDbId(long dbId) {
        this.dbId = dbId;
    }

    public List<Long> getTableIdList() {
        return tableIdList;
    }

    public void addTableIdList(Long tableId) {
        this.tableIdList.add(tableId);
    }

    public Map<Long, TableCommitInfo> getIdToTableCommitInfos() {
        return idToTableCommitInfos;
    }

    public void putIdToTableCommitInfo(long tableId, TableCommitInfo tableCommitInfo) {
        idToTableCommitInfos.put(tableId, tableCommitInfo);
    }

    @Nullable
    public TableCommitInfo getTableCommitInfo(long tableId) {
        return this.idToTableCommitInfos.get(tableId);
    }

    public void removeTable(long tableId) {
        this.idToTableCommitInfos.remove(tableId);
    }

    public void setTxnCommitAttachment(TxnCommitAttachment txnCommitAttachment) {
        this.txnCommitAttachment = txnCommitAttachment;
    }

    public boolean isVersionOverwrite() {
        return txnCommitAttachment instanceof InsertTxnCommitAttachment
                && ((InsertTxnCommitAttachment) txnCommitAttachment).getIsVersionOverwrite();
    }

    // return true if txn is in final status and label is expired
    public boolean isExpired(long currentMillis) {
        return transactionStatus.isFinalStatus() && (currentMillis - finishTime) / 1000 > Config.label_keep_max_second;
    }

    // return true if txn is running but timeout
    public boolean isTimeout(long currentMillis) {
        return (transactionStatus == TransactionStatus.PREPARE && currentMillis - prepareTime > timeoutMs)
                || (transactionStatus == TransactionStatus.PREPARED && (currentMillis - preparedTime)
                / 1000 > Config.prepared_transaction_default_timeout_second);
    }

    /*
     * Add related table indexes to the transaction.
     * If function should always be called before adding this transaction state to transaction manager,
     * No other thread will access this state. So no need to lock
     */
    public void addTableIndexes(OlapTable table) {
        Set<Long> indexIds = loadedTblIndexes.computeIfAbsent(table.getId(), k -> Sets.newHashSet());
        // always equal the index ids
        indexIds.clear();
        indexIds.addAll(table.getIndexIdToMeta().keySet());
    }

    public List<MaterializedIndex> getPartitionLoadedTblIndexes(long tableId, PhysicalPartition partition) {
        List<MaterializedIndex> loadedIndex;
        if (loadedTblIndexes.isEmpty()) {
            loadedIndex = partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL);
        } else {
            loadedIndex = Lists.newArrayList();
            for (long indexId : loadedTblIndexes.get(tableId)) {
                MaterializedIndex index = partition.getIndex(indexId);
                if (index != null) {
                    loadedIndex.add(index);
                }
            }
        }
        return loadedIndex;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TransactionState. ");
        sb.append("txn_id: ").append(transactionId);
        sb.append(", label: ").append(label);
        sb.append(", db id: ").append(dbId);
        sb.append(", table id list: ").append(StringUtils.join(tableIdList, ","));
        sb.append(", callback id: ").append(getCallbackId());
        sb.append(", coordinator: ").append(txnCoordinator.toString());
        sb.append(", transaction status: ").append(transactionStatus);
        sb.append(", error replicas num: ").append(errorReplicas.size());
        if (!errorReplicas.isEmpty()) {
            sb.append(", error replica ids: ").append(Joiner.on(",").join(errorReplicas.stream().limit(5).toArray()));
        }
        sb.append(", unknown replicas num: ").append(unknownReplicas.size());
        if (!unknownReplicas.isEmpty()) {
            sb.append(", unknown replica ids: ").append(Joiner.on(",").join(unknownReplicas.stream().limit(5).toArray()));
        }
        sb.append(", prepare time: ").append(prepareTime);
        sb.append(", write end time: ").append(writeEndTimeMs);
        sb.append(", allow commit time: ").append(allowCommitTimeMs);
        sb.append(", commit time: ").append(commitTime);
        sb.append(", finish time: ").append(finishTime);
        if (commitTime > prepareTime) {
            sb.append(", write cost: ").append(commitTime - prepareTime).append("ms");
        }
        if (publishVersionTime != -1 && publishVersionFinishTime != -1) {
            if (publishVersionTime > commitTime) {
                sb.append(", wait for publish cost: ").append(publishVersionTime - commitTime).append("ms");
            }
            if (publishVersionFinishTime > publishVersionTime) {
                sb.append(", publish rpc cost: ").append(publishVersionFinishTime - publishVersionTime).append("ms");
            }
            if (finishTime > publishVersionFinishTime) {
                sb.append(", finish txn cost: ").append(finishTime - publishVersionFinishTime).append("ms");
            }
        }
        if (finishTime > commitTime && commitTime > 0) {
            sb.append(", publish total cost: ").append(finishTime - commitTime).append("ms");
        }
        if (finishTime > prepareTime) {
            sb.append(", total cost: ").append(finishTime - prepareTime).append("ms");
        }
        sb.append(", reason: ").append(reason);
        if (newFinish) {
            sb.append(", newFinish");
        }
        if (txnCommitAttachment != null) {
            sb.append(", attachment: ").append(txnCommitAttachment);
        }
        if (tabletCommitInfos != null) {
            sb.append(", tabletCommitInfos size: ").append(tabletCommitInfos.size());
        }
        if (Config.transaction_state_print_partition_info && idToTableCommitInfos != null) {
            sb.append(", partition commit info:[");
            for (TableCommitInfo tinfo : idToTableCommitInfos.values()) {
                if (tinfo.getIdToPartitionCommitInfo() != null) {
                    for (PartitionCommitInfo pinfo : tinfo.getIdToPartitionCommitInfo().values()) {
                        sb.append(pinfo.toString()).append(",");
                    }
                }
            }
            sb.append("]");
        }
        sb.append(", warehouse: ").append(computeResource.getWarehouseId());
        return sb.toString();
    }

    public String getBrief() {
        StringBuilder sb = new StringBuilder("TransactionState. ");
        sb.append("txn_id: ").append(transactionId);
        sb.append(", db id: ").append(dbId);
        sb.append(", table id list: ").append(StringUtils.join(tableIdList, ","));
        sb.append(", error replicas num: ").append(errorReplicas.size());
        if (!errorReplicas.isEmpty()) {
            sb.append(", error replica ids: ").append(Joiner.on(",").join(errorReplicas.stream().limit(5).toArray()));
        }
        sb.append(", unknown replicas num: ").append(unknownReplicas.size());
        if (!unknownReplicas.isEmpty()) {
            sb.append(", unknown replica ids: ").append(Joiner.on(",").join(unknownReplicas.stream().limit(5).toArray()));
        }
        if (commitTime > prepareTime) {
            sb.append(", write cost: ").append(commitTime - prepareTime).append("ms");
        }
        if (finishTime > commitTime && commitTime > 0) {
            sb.append(", publish total cost: ").append(finishTime - commitTime).append("ms");
        }
        if (finishTime > prepareTime) {
            sb.append(", total cost: ").append(finishTime - prepareTime).append("ms");
        }
        return sb.toString();
    }

    public LoadJobSourceType getSourceType() {
        return sourceType;
    }

    public TransactionType getTransactionType() {
        return sourceType == LoadJobSourceType.REPLICATION ? TransactionType.TXN_REPLICATION
                : TransactionType.TXN_NORMAL;
    }

    public Map<Long, PublishVersionTask> getPublishVersionTasks() {
        return publishVersionTasks;
    }

    public void clearAfterPublished() {
        publishVersionTasks.clear();
        finishChecker = null;
    }

    public void setErrorMsg(String errMsg) {
        this.errMsg = errMsg;
        lastErrTimeMs = System.nanoTime() / 1000000;
    }

    public void clearErrorMsg() {
        this.errMsg = "";
    }

    public String getErrMsg() {
        return this.errMsg;
    }

    public long getLastErrTimeMs() {
        return lastErrTimeMs;
    }

    // create publish version task for OlapTable transaction
    public List<PublishVersionTask> createPublishVersionTask() {
        List<PublishVersionTask> tasks = new ArrayList<>();
        if (this.hasSendTask()) {
            return tasks;
        }

        Set<Long> publishBackends = this.getPublishVersionTasks().keySet();
        // public version tasks are not persisted in globalStateMgr, so publishBackends may be empty.
        // We have to send publish version task to all backends
        if (publishBackends.isEmpty()) {
            // note: tasks are sent to all backends including dead ones, or else
            // transaction manager will treat it as success
            List<Long> allBackends =
                    GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendIds(false);
            if (!allBackends.isEmpty()) {
                publishBackends = Sets.newHashSet();
                publishBackends.addAll(allBackends);
            } else {
                // all backends may be dropped, no need to create task
                LOG.warn("transaction {} want to publish, but no backend exists", this.getTransactionId());
                return tasks;
            }
        }

        List<PartitionCommitInfo> partitionCommitInfos = new ArrayList<>();
        for (TableCommitInfo tableCommitInfo : this.getIdToTableCommitInfos().values()) {
            partitionCommitInfos.addAll(tableCommitInfo.getIdToPartitionCommitInfo().values());
        }

        List<TPartitionVersionInfo> partitionVersions = new ArrayList<>(partitionCommitInfos.size());
        for (PartitionCommitInfo commitInfo : partitionCommitInfos) {
            TPartitionVersionInfo version = new TPartitionVersionInfo(commitInfo.getPhysicalPartitionId(),
                    commitInfo.getVersion(), 0);
            if (commitInfo.isDoubleWrite()) {
                version.setIs_double_write(true);
            }
            partitionVersions.add(version);
        }

        long createTime = System.currentTimeMillis();
        for (long backendId : publishBackends) {
            PublishVersionTask task = new PublishVersionTask(backendId,
                    this.getTransactionId(),
                    this.getGlobalTransactionId(),
                    this.getDbId(),
                    commitTime,
                    partitionVersions,
                    traceParent,
                    txnSpan,
                    createTime,
                    this,
                    Config.enable_sync_publish,
                    this.getTransactionType(),
                    isVersionOverwrite());
            this.addPublishVersionTask(backendId, task);
            tasks.add(task);
        }
        return tasks;
    }

    public boolean allPublishTasksFinishedOrQuorumWaitTimeout(Set<Long> publishErrorReplicas) {
        boolean timeout = System.currentTimeMillis() - getCommitTime() > Config.quorum_publish_wait_time_ms;
        for (PublishVersionTask publishVersionTask : getPublishVersionTasks().values()) {
            if (publishVersionTask.isFinished()) {
                publishErrorReplicas.addAll(publishVersionTask.getErrorReplicas());
            } else if (!timeout) {
                return false;
            }
        }
        return true;
    }

    public boolean checkCanFinish() {
        // finishChecker may require refresh if table/partition is dropped, or index is changed caused by Alter job
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            // consider txn finished if db is dropped
            return true;
        }

        if (finishChecker == null) {
            finishChecker = TransactionChecker.create(this, db);
        }

        if (finishState == null) {
            finishState = new TxnFinishState();
        }
        boolean ret = finishChecker.finished(finishState);
        if (ret) {
            txnSpan.addEvent("check_ok");
        }
        return ret;
    }

    public String getPublishTimeoutDebugInfo() {
        if (!hasSendTask()) {
            return "txn has not sent publish tasks yet, maybe waiting previous txns on the same table(s) to finish, tableIds: " +
                    Joiner.on(",").join(getTableIdList());
        } else if (finishChecker != null) {
            return finishChecker.debugInfo();
        } else {
            return getErrMsg();
        }
    }

    public void setFinishState(TxnFinishState finishState) {
        this.finishState = finishState;
    }

    public TxnFinishState getFinishState() {
        return finishState;
    }

    public void setNewFinish() {
        newFinish = true;
    }

    public boolean isNewFinish() {
        return newFinish;
    }

    public Span getTxnSpan() {
        return txnSpan;
    }

    public String getTraceParent() {
        return traceParent;
    }

    // A value of -1 indicates this field is not set.
    public long getWriteEndTimeMs() {
        return writeEndTimeMs;
    }

    public void setWriteEndTimeMs(long writeEndTimeMs) {
        this.writeEndTimeMs = writeEndTimeMs;
    }

    // A value of -1 indicates this field is not set.
    public long getAllowCommitTimeMs() {
        return allowCommitTimeMs;
    }

    public void setAllowCommitTimeMs(long allowCommitTimeMs) {
        this.allowCommitTimeMs = allowCommitTimeMs;
    }

    // A value of -1 indicates this field is not set.
    public long getWriteDurationMs() {
        return writeDurationMs;
    }

    public void setWriteDurationMs(long writeDurationMs) {
        this.writeDurationMs = writeDurationMs;
    }

    public void setUseCombinedTxnLog(boolean useCombinedTxnLog) {
        this.useCombinedTxnLog = useCombinedTxnLog;
    }

    public boolean isUseCombinedTxnLog() {
        return useCombinedTxnLog;
    }

    public ConcurrentMap<String, TOlapTablePartition> getPartitionNameToTPartition(long tableId) {
        writeLock();
        try {
            return tableToPartitionNameToTPartition.computeIfAbsent(tableId, k -> Maps.newConcurrentMap());
        } finally {
            writeUnlock();
        }
    }

    public ConcurrentMap<Long, TTabletLocation> getTabletIdToTTabletLocation() {
        return tabletIdToTTabletLocation;
    }

    public List<String> getCreatedPartitionNames(long tableId) {
        writeLock();
        try {
            return tableToCreatedPartitionNames.computeIfAbsent(tableId, k -> new ArrayList<>());
        } finally {
            writeUnlock();
        }
    }

    public void clearAutomaticPartitionSnapshot() {
        writeLock();
        try {
            tableToPartitionNameToTPartition.forEach((tableId, partitionNameToTPartition) -> {
                List<String> createdPartitionNames = tableToCreatedPartitionNames.computeIfAbsent(
                        tableId, k -> new ArrayList<>());
                createdPartitionNames.addAll(partitionNameToTPartition.keySet());
            });
            tabletIdToTTabletLocation.clear();
            tableToPartitionNameToTPartition.clear();
        } finally {
            writeUnlock();
        }
    }

    public void setIsCreatePartitionFailed(boolean v) {
        this.isCreatePartitionFailed.set(v);
    }

    public boolean getIsCreatePartitionFailed() {
        return this.isCreatePartitionFailed.get();
    }

    @Override
    public void write(DataOutput out) throws IOException {

    }

    @Override
    public void gsonPreProcess() throws IOException {
        //For compatibility, if the implicit transaction can be rolled back, duplicates will be removed in getCallbackId.
        if (callbackId == -1 && !callbackIdList.isEmpty()) {
            callbackId = callbackIdList.get(0);
        }
    }
}

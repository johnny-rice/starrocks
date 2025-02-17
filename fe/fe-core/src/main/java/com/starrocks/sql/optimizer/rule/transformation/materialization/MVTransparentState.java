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

package com.starrocks.sql.optimizer.rule.transformation.materialization;

/**
 * TransparentState means different compensate type for the specific mv and query plan:
 * - NO_REWRITE: When mv's refreshed partitions are not intersected with query's selected partitions, no need to rewrite.
 * - NO_COMPENSATE: When mv's refreshed partitions are intersected with query's selected partitions, but mv's
 *      refreshed partitions are satisfied with query's selected partitions.
 * - COMPENSATE: When mv's refreshed partitions are intersected with query's selected partitions(partial pruned), but
 *   mv's refreshed partitions are not satisfied with query's selected partitions.
 * - UNKNOWN: others we set it unknown.
 */
public enum MVTransparentState {
    NO_REWRITE,     // MV cannot be used to rewrite because of its refresh-ness is stale
    NO_COMPENSATE,  // MV can be used for rewrite without any compensation
    COMPENSATE,     // MV can be used for rewrite with partition compensation union.
    UNKNOWN;        // MV's compensation state is unknown, it cannot be used to rewrite in `CHECKED` mode, but can be used in
    // `LOOSE` mode.

    public boolean isNoRewrite() {
        return this == NO_REWRITE;
    }

    public boolean isNoCompensate() {
        return this == NO_COMPENSATE;
    }

    public boolean isCompensate() {
        return this == COMPENSATE;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    // If the state is NO_REWRITE or UNKNOWN, the mv is uncompensable.
    public boolean isUncompensable() {
        return this == NO_REWRITE || this == UNKNOWN;
    }
}

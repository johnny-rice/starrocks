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

package com.starrocks.connector;

import com.starrocks.catalog.Column;
import com.starrocks.thrift.TBucketFunction;
import com.starrocks.thrift.TBucketProperty;

import java.util.Objects;

public class BucketProperty {
    private final TBucketFunction bucketFunction;
    private final int bucketNum;
    private final Column column;

    public BucketProperty(TBucketFunction bucketFunction, int bucketNum, Column column) {
        this.bucketFunction = bucketFunction;
        this.bucketNum = bucketNum;
        this.column = column;
    }

    public TBucketFunction getBucketFunction() {
        return bucketFunction;
    }

    public int getBucketNum() {
        return bucketNum;
    }

    public Column getColumn() {
        return column;
    }

    public boolean satisfy(BucketProperty bp) {
        return bucketFunction.getValue() == bp.bucketFunction.getValue() && bucketNum == bp.bucketNum;
    }

    public String toString() {
        return bucketFunction.toString() + ", " + bucketNum;
    }

    public TBucketProperty toThrift() {
        TBucketProperty tBucketProperty = new TBucketProperty();
        tBucketProperty.setBucket_func(bucketFunction);
        tBucketProperty.setBucket_num(bucketNum);
        return tBucketProperty;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BucketProperty that = (BucketProperty) o;
        return bucketFunction == that.bucketFunction && bucketNum == that.bucketNum
                && Objects.equals(column, that.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketFunction, bucketNum, column);
    }
}

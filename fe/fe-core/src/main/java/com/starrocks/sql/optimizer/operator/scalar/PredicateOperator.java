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

package com.starrocks.sql.optimizer.operator.scalar;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public abstract class PredicateOperator extends ArgsScalarOperator {
    public PredicateOperator(OperatorType operatorType, ScalarOperator... arguments) {
        this(operatorType, Lists.newArrayList(arguments));
    }

    public PredicateOperator(OperatorType operatorType, List<ScalarOperator> arguments) {
        super(operatorType, Type.BOOLEAN);
        this.arguments = requireNonNull(arguments, "arguments is null");
    }

    @Override
    public boolean isNullable() {
        return arguments.stream().anyMatch(ScalarOperator::isNullable);
    }

    @Override
    public String toString() {
        return "(" + arguments.stream().map(ScalarOperator::toString).collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public ColumnRefSet getUsedColumns() {
        ColumnRefSet used = new ColumnRefSet();
        for (ScalarOperator child : arguments) {
            used.union(child.getUsedColumns());
        }
        return used;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hashCodeSelf(), opType, arguments);
    }

    @Override
    public int hashCodeSelf() {
        return Objects.hash(opType);
    }

    @Override
    public boolean equalsSelf(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PredicateOperator other = (PredicateOperator) obj;
        return Objects.equals(this.opType, other.opType);
    }

    @Override
    public boolean equivalent(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PredicateOperator other = (PredicateOperator) obj;
        if (this.arguments.size() != other.arguments.size()) {
            return false;
        }
        for (int i = 0; i < this.arguments.size(); i++) {
            if (!this.arguments.get(i).equivalent(other.arguments.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ScalarOperator clone() {
        PredicateOperator operator = (PredicateOperator) super.clone();
        // Deep copy here
        List<ScalarOperator> newArguments = Lists.newArrayList();
        this.arguments.forEach(p -> newArguments.add(p.clone()));
        operator.arguments = newArguments;
        return operator;
    }

    @Override
    public <R, C> R accept(ScalarOperatorVisitor<R, C> visitor, C context) {
        return visitor.visitPredicate(this, context);
    }
}

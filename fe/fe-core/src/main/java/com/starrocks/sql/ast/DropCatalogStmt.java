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


package com.starrocks.sql.ast;

import com.starrocks.sql.parser.NodePosition;

import static com.starrocks.common.util.Util.normalizeName;

// ToDo(zhuodong): to support internal catalog in the future
public class DropCatalogStmt extends DdlStmt {

    private final String name;
    private final boolean ifExists;


    public DropCatalogStmt(String name) {
        this(name, false, NodePosition.ZERO);
    }

    public DropCatalogStmt(String name, boolean ifExists) {
        this(name, ifExists, NodePosition.ZERO);
    }

    public DropCatalogStmt(String name, boolean ifExists, NodePosition pos) {
        super(pos);
        this.name = normalizeName(name);
        this.ifExists = ifExists;
    }

    public String getName() {
        return name;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitDropCatalogStatement(this, context);
    }

    @Override
    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("DROP CATALOG ");
        if (ifExists) {
            sb.append("IF EXISTS ");
        }
        sb.append("\'" + name + "\'");
        return sb.toString();
    }
}

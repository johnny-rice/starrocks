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

package com.starrocks.sql.analyzer;

import com.google.common.collect.Lists;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateTemporaryTableLikeStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.sql.parser.SqlParser;

import java.util.List;

public class CreateTableLikeAnalyzer {

    public static void analyze(CreateTableLikeStmt stmt, ConnectContext context) {
        TableName existedDbTbl = stmt.getExistedDbTbl();
        stmt.getDbTbl().normalization(context);
        existedDbTbl.normalization(context);
        String tableName = stmt.getTableName();
        FeNameFormat.checkTableName(tableName);

        MetaUtils.checkNotSupportCatalog(existedDbTbl.getCatalog(), "CREATE TABLE LIKE");
        Table table = GlobalStateMgr.getCurrentState().getMetadataMgr().getTable(context, existedDbTbl.getCatalog(),
                existedDbTbl.getDb(), existedDbTbl.getTbl());
        if (table == null) {
            throw new SemanticException("Table %s is not found", tableName);
        }

        List<String> createTableStmt = Lists.newArrayList();

        if (stmt instanceof CreateTemporaryTableLikeStmt) {
            if (!(table instanceof OlapTable)) {
                throw new SemanticException("temporary table only support olap engine");
            }
        }
        AstToStringBuilder.getDdlStmt(stmt.getDbName(), table, createTableStmt, null,
                null, false, false, stmt instanceof CreateTemporaryTableLikeStmt);
        if (createTableStmt.isEmpty()) {
            ErrorReport.reportSemanticException(ErrorCode.ERROR_CREATE_TABLE_LIKE_EMPTY, "CREATE");
        }

        StatementBase statementBase =
                SqlParser.parseOneWithStarRocksDialect(createTableStmt.get(0), context.getSessionVariable());
        if (statementBase instanceof CreateTableStmt) {
            CreateTableStmt parsedCreateTableStmt = (CreateTableStmt) statementBase;
            parsedCreateTableStmt.setTableName(stmt.getTableName());
            if (stmt.isSetIfNotExists()) {
                parsedCreateTableStmt.setIfNotExists();
            }
            if (stmt.getProperties() != null) {
                parsedCreateTableStmt.updateProperties(stmt.getProperties());
            }
            if (stmt.getDistributionDesc() != null) {
                parsedCreateTableStmt.setDistributionDesc(stmt.getDistributionDesc());
            }
            if (stmt.getPartitionDesc() != null) {
                parsedCreateTableStmt.setPartitionDesc(stmt.getPartitionDesc());
            }

            com.starrocks.sql.analyzer.Analyzer.analyze(parsedCreateTableStmt, context);
            stmt.setCreateTableStmt(parsedCreateTableStmt);
        } else {
            ErrorReport.reportSemanticException(ErrorCode.ERROR_CREATE_TABLE_LIKE_UNSUPPORTED_VIEW);
        }
    }
}
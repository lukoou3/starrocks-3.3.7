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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/StatementBase.java

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

package com.starrocks.sql.ast;

import com.google.common.base.Preconditions;
import com.starrocks.analysis.HintNode;
import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.RedirectStatus;
import com.starrocks.common.profile.Tracers;
import com.starrocks.qe.OriginStatement;
import com.starrocks.sql.parser.NodePosition;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public abstract class StatementBase implements ParseNode {

    private final NodePosition pos;

    protected StatementBase(NodePosition pos) {
        this.pos = pos;
    }

    public enum ExplainLevel {
        NORMAL,
        LOGICAL,
        ANALYZE,
        // True if the describe_stmt print verbose information, if `isVerbose` is true, `isExplain` must be set to true.
        VERBOSE,
        // True if the describe_stmt print costs information, if `isCosts` is true, `isExplain` must be set to true.
        COSTS,
        OPTIMIZER,
        REWRITE,
        SCHEDULER;

        public static ExplainLevel defaultValue() {
            return NORMAL;
        }

        public static ExplainLevel parse(String value) {
            if (StringUtils.isEmpty(value)) {
                return defaultValue();
            }
            ExplainLevel result = EnumUtils.getEnumIgnoreCase(ExplainLevel.class, value);
            if (result == null) {
                return defaultValue();
            }
            return result;
        }
    }

    private ExplainLevel explainLevel;

    private Tracers.Mode traceMode;

    private String traceModule;

    // True if this QueryStmt is the top level query from an EXPLAIN <query>
    protected boolean isExplain = false;

    // Original statement to further usage, eg: enable_sql_blacklist.
    protected OriginStatement origStmt;

    // hint in each part of ast. used to ast to sql
    protected List<HintNode> hintNodes;

    // all query scope hints for the whole ast. used to initialize the hint context for a query
    protected List<HintNode> allQueryScopeHints;

    public void setIsExplain(boolean isExplain, ExplainLevel explainLevel) {
        this.isExplain = isExplain;
        this.explainLevel = explainLevel;
    }

    public void setIsTrace(Tracers.Mode mode, String module) {
        this.isExplain = true;
        this.traceMode = mode;
        this.traceModule = module;
    }

    public boolean isExplain() {
        return isExplain;
    }

    public Tracers.Mode getTraceMode() {
        return traceMode;
    }

    public String getTraceModule() {
        return traceModule;
    }

    public ExplainLevel getExplainLevel() {
        if (explainLevel == null) {
            return ExplainLevel.defaultValue();
        } else {
            return explainLevel;
        }
    }

    public abstract RedirectStatus getRedirectStatus();

    public void setOrigStmt(OriginStatement origStmt) {
        Preconditions.checkState(origStmt != null);
        this.origStmt = origStmt;
    }

    public OriginStatement getOrigStmt() {
        return origStmt;
    }

    @Override
    public NodePosition getPos() {
        return pos;
    }


    public List<HintNode> getHintNodes() {
        return hintNodes;
    }

    public void setHintNodes(List<HintNode> hintNodes) {
        this.hintNodes = hintNodes;
    }

    public List<HintNode> getAllQueryScopeHints() {
        return allQueryScopeHints;
    }

    public void setAllQueryScopeHints(List<HintNode> hintNodes) {
        this.allQueryScopeHints = hintNodes;
    }

    public boolean isExistQueryScopeHint() {
        return CollectionUtils.isNotEmpty(allQueryScopeHints);
    }
}

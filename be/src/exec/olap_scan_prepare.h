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

#pragma once

#include "common/status.h"
#include "exec/olap_common.h"
#include "exprs/expr.h"
#include "exprs/expr_context.h"
#include "storage/olap_runtime_range_pruner.h"
#include "storage/predicate_tree/predicate_tree_fwd.h"

namespace starrocks {
class RuntimeState;

class RuntimeFilterProbeCollector;
class PredicateParser;
class ColumnPredicate;
using ColumnPredicatePtr = std::unique_ptr<ColumnPredicate>;
using ColumnPredicatePtrs = std::vector<ColumnPredicatePtr>;

class OlapScanConjunctsManager {
public:
    // fields from olap scan node
    const std::vector<ExprContext*>* conjunct_ctxs_ptr;
    const TupleDescriptor* tuple_desc;
    ObjectPool* obj_pool;
    const std::vector<std::string>* key_column_names;
    const RuntimeFilterProbeCollector* runtime_filters;
    RuntimeState* runtime_state;
    int32_t driver_sequence = -1;

private:
    // fields generated by parsing conjunct ctxs.
    // same size with |_conjunct_ctxs|, indicate which element has been normalized.
    std::vector<bool> normalized_conjuncts;
    std::map<std::string, ColumnValueRangeType> column_value_ranges;  // from conjunct_ctxs
    OlapScanKeys scan_keys;                                           // from _column_value_ranges
    std::vector<TCondition> olap_filters;                             // from _column_value_ranges
    std::vector<TCondition> is_null_vector;                           // from conjunct_ctxs
    std::map<int, std::vector<ExprContext*>> slot_index_to_expr_ctxs; // from conjunct_ctxs

    // unreached runtime filter and they can push down to storage engine
    UnarrivedRuntimeFilterList rt_ranger_params;

public:
    static Status eval_const_conjuncts(const std::vector<ExprContext*>& conjunct_ctxs, Status* status);

    StatusOr<PredicateTree> get_predicate_tree(PredicateParser* parser, ColumnPredicatePtrs& col_preds_owner);

    Status get_key_ranges(std::vector<std::unique_ptr<OlapScanRange>>* key_ranges);

    void get_not_push_down_conjuncts(std::vector<ExprContext*>* predicates);

    Status parse_conjuncts(bool scan_keys_unlimited, int32_t max_scan_key_num,
                           bool enable_column_expr_predicate = false);

    const UnarrivedRuntimeFilterList& unarrived_runtime_filters() { return rt_ranger_params; }

private:
    friend struct ColumnRangeBuilder;
    friend class ConjunctiveTestFixture;

    Status get_column_predicates(PredicateParser* parser, ColumnPredicatePtrs& col_preds_owner);

    Status normalize_conjuncts();
    Status build_olap_filters();
    Status build_scan_keys(bool unlimited, int32_t max_scan_key_num);

    template <LogicalType SlotType, typename RangeValueType>
    void normalize_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType>
    void normalize_in_or_equal_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType>
    void normalize_binary_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType>
    void normalize_join_runtime_filter(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    template <LogicalType SlotType, typename RangeValueType>
    void normalize_not_in_or_not_equal_predicate(const SlotDescriptor& slot, ColumnValueRange<RangeValueType>* range);

    void normalize_is_null_predicate(const SlotDescriptor& slot);

    // To build `ColumnExprPredicate`s from conjuncts passed from olap scan node.
    // `ColumnExprPredicate` would be used in late materialization, zone map filtering,
    // dict encoded column filtering and bitmap value column filtering etc.
    void build_column_expr_predicates();
};

} // namespace starrocks
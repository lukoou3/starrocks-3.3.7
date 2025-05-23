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

#include "formats/parquet/file_reader.h"

#include "column/column_helper.h"
#include "column/vectorized_fwd.h"
#include "common/config.h"
#include "common/status.h"
#include "exec/exec_node.h"
#include "exec/hdfs_scanner.h"
#include "exprs/expr.h"
#include "exprs/expr_context.h"
#include "exprs/runtime_filter_bank.h"
#include "formats/parquet/encoding_plain.h"
#include "formats/parquet/metadata.h"
#include "formats/parquet/schema.h"
#include "formats/parquet/statistics_helper.h"
#include "formats/parquet/utils.h"
#include "fs/fs.h"
#include "gutil/strings/substitute.h"
#include "runtime/current_thread.h"
#include "storage/chunk_helper.h"
#include "util/coding.h"
#include "util/defer_op.h"
#include "util/memcmp.h"
#include "util/thrift_util.h"

namespace starrocks::parquet {

struct SplitContext : public HdfsSplitContext {
    FileMetaDataPtr file_metadata;

    HdfsSplitContextPtr clone() override {
        auto ctx = std::make_unique<SplitContext>();
        ctx->file_metadata = file_metadata;
        return ctx;
    }
};

static int64_t _get_column_start_offset(const tparquet::ColumnMetaData& column) {
    int64_t offset = column.data_page_offset;
    if (column.__isset.index_page_offset) {
        offset = std::min(offset, column.index_page_offset);
    }
    if (column.__isset.dictionary_page_offset) {
        offset = std::min(offset, column.dictionary_page_offset);
    }
    return offset;
}

static int64_t _get_row_group_start_offset(const tparquet::RowGroup& row_group) {
    const tparquet::ColumnMetaData& first_column = row_group.columns[0].meta_data;
    int64_t offset = _get_column_start_offset(first_column);

    if (row_group.__isset.file_offset) {
        offset = std::min(offset, row_group.file_offset);
    }
    return offset;
}

static int64_t _get_row_group_end_offset(const tparquet::RowGroup& row_group) {
    // following computation is not correct. `total_compressed_size` means compressed size of all columns
    // but between columns there could be holes, which means end offset inaccurate.
    // if (row_group.__isset.file_offset && row_group.__isset.total_compressed_size) {
    //     return row_group.file_offset + row_group.total_compressed_size;
    // }
    const tparquet::ColumnMetaData& last_column = row_group.columns.back().meta_data;
    return _get_column_start_offset(last_column) + last_column.total_compressed_size;
}

FileReader::FileReader(int chunk_size, RandomAccessFile* file, size_t file_size,
                       const DataCacheOptions& datacache_options, io::SharedBufferedInputStream* sb_stream,
                       const std::set<int64_t>* _need_skip_rowids)
        : _chunk_size(chunk_size),
          _file(file),
          _file_size(file_size),
          _datacache_options(datacache_options),
          _sb_stream(sb_stream),
          _need_skip_rowids(_need_skip_rowids) {}

FileReader::~FileReader() = default;

std::string FileReader::_build_metacache_key() {
    auto& filename = _file->filename();
    std::string metacache_key;
    metacache_key.resize(14);
    char* data = metacache_key.data();
    const std::string footer_suffix = "ft";
    uint64_t hash_value = HashUtil::hash64(filename.data(), filename.size(), 0);
    memcpy(data, &hash_value, sizeof(hash_value));
    memcpy(data + 8, footer_suffix.data(), footer_suffix.length());
    // The modification time is more appropriate to indicate the different file versions.
    // While some data source, such as Hudi, have no modification time because their files
    // cannot be overwritten. So, if the modification time is unsupported, we use file size instead.
    // Also, to reduce memory usage, we only use the high four bytes to represent the second timestamp.
    if (_datacache_options.modification_time > 0) {
        uint32_t mtime_s = (_datacache_options.modification_time >> 9) & 0x00000000FFFFFFFF;
        memcpy(data + 10, &mtime_s, sizeof(mtime_s));
    } else {
        uint32_t size = _file_size;
        memcpy(data + 10, &size, sizeof(size));
    }
    return metacache_key;
}

Status FileReader::init(HdfsScannerContext* ctx) {
    _scanner_ctx = ctx;
#ifdef WITH_STARCACHE
    // Only support file metacache in starcache engine
    if (ctx->use_file_metacache && config::datacache_enable) {
        _cache = BlockCache::instance();
    }
#endif
    RETURN_IF_ERROR(_get_footer());

    // set existed SlotDescriptor in this parquet file
    std::unordered_set<std::string> existed_column_names;
    _meta_helper = _build_meta_helper();
    _prepare_read_columns(existed_column_names);
    RETURN_IF_ERROR(_scanner_ctx->update_materialized_columns(existed_column_names));

    ASSIGN_OR_RETURN(_is_file_filtered, _scanner_ctx->should_skip_by_evaluating_not_existed_slots());
    if (_is_file_filtered) {
        return Status::OK();
    }

    RETURN_IF_ERROR(_build_split_tasks());
    if (_scanner_ctx->split_tasks.size() > 0) {
        _scanner_ctx->has_split_tasks = true;
        _is_file_filtered = true;
        return Status::OK();
    }

    RETURN_IF_ERROR(_init_group_readers());
    return Status::OK();
}

std::shared_ptr<MetaHelper> FileReader::_build_meta_helper() {
    if (_scanner_ctx->iceberg_schema != nullptr && _file_metadata->schema().exist_filed_id()) {
        // If we want read this parquet file with iceberg schema,
        // we also need to make sure it contains parquet field id.
        return std::make_shared<IcebergMetaHelper>(_file_metadata.get(), _scanner_ctx->case_sensitive,
                                                   _scanner_ctx->iceberg_schema);
    } else {
        return std::make_shared<ParquetMetaHelper>(_file_metadata.get(), _scanner_ctx->case_sensitive);
    }
}

FileMetaData* FileReader::get_file_metadata() {
    return _file_metadata.get();
}

Status FileReader::collect_scan_io_ranges(std::vector<io::SharedBufferedInputStream::IORange>* io_ranges) {
    int64_t dummy_offset = 0;
    for (auto& r : _row_group_readers) {
        r->collect_io_ranges(io_ranges, &dummy_offset, ColumnIOType::PAGE_INDEX);
        r->collect_io_ranges(io_ranges, &dummy_offset, ColumnIOType::PAGES);
    }
    return Status::OK();
}

Status FileReader::_parse_footer(FileMetaDataPtr* file_metadata_ptr, int64_t* file_metadata_size) {
    std::vector<char> footer_buffer;
    ASSIGN_OR_RETURN(uint32_t footer_read_size, _get_footer_read_size());
    footer_buffer.resize(footer_read_size);

    {
        SCOPED_RAW_TIMER(&_scanner_ctx->stats->footer_read_ns);
        RETURN_IF_ERROR(_file->read_at_fully(_file_size - footer_read_size, footer_buffer.data(), footer_read_size));
    }

    ASSIGN_OR_RETURN(uint32_t metadata_length, _parse_metadata_length(footer_buffer));

    _scanner_ctx->stats->request_bytes_read += metadata_length + PARQUET_FOOTER_SIZE;
    _scanner_ctx->stats->request_bytes_read_uncompressed += metadata_length + PARQUET_FOOTER_SIZE;

    if (footer_read_size < (metadata_length + PARQUET_FOOTER_SIZE)) {
        // footer_buffer's size is not enough to read the whole metadata, we need to re-read for larger size
        size_t re_read_size = metadata_length + PARQUET_FOOTER_SIZE;
        footer_buffer.resize(re_read_size);
        {
            SCOPED_RAW_TIMER(&_scanner_ctx->stats->footer_read_ns);
            RETURN_IF_ERROR(_file->read_at_fully(_file_size - re_read_size, footer_buffer.data(), re_read_size));
        }
    }

    // NOTICE: When you need to modify the logic within this scope (including the subfuctions), you should be
    // particularly careful to ensure that it does not affect the correctness of the footer's memory statistics.
    {
        int64_t before_bytes = CurrentThread::current().get_consumed_bytes();
        tparquet::FileMetaData t_metadata;
        // deserialize footer
        RETURN_IF_ERROR(deserialize_thrift_msg(reinterpret_cast<const uint8*>(footer_buffer.data()) +
                                                       footer_buffer.size() - PARQUET_FOOTER_SIZE - metadata_length,
                                               &metadata_length, TProtocolType::COMPACT, &t_metadata));

        *file_metadata_ptr = std::make_shared<FileMetaData>();
        FileMetaData* file_metadata = file_metadata_ptr->get();
        RETURN_IF_ERROR(file_metadata->init(t_metadata, _scanner_ctx->case_sensitive));
        *file_metadata_size = CurrentThread::current().get_consumed_bytes() - before_bytes;
    }
#ifdef BE_TEST
    *file_metadata_size = sizeof(FileMetaData);
#endif
    return Status::OK();
}

Status FileReader::_get_footer() {
    if (_scanner_ctx->split_context != nullptr) {
        auto split_ctx = down_cast<const SplitContext*>(_scanner_ctx->split_context);
        _file_metadata = split_ctx->file_metadata;
        return Status::OK();
    }

    if (!_cache) {
        int64_t file_metadata_size = 0;
        return _parse_footer(&_file_metadata, &file_metadata_size);
    }

    BlockCache* cache = _cache;
    DataCacheHandle cache_handle;
    std::string metacache_key = _build_metacache_key();
    {
        SCOPED_RAW_TIMER(&_scanner_ctx->stats->footer_cache_read_ns);
        Status st = cache->read_object(metacache_key, &cache_handle);
        if (st.ok()) {
            _file_metadata = *(static_cast<const FileMetaDataPtr*>(cache_handle.ptr()));
            _scanner_ctx->stats->footer_cache_read_count += 1;
            return st;
        }
    }

    int64_t file_metadata_size = 0;
    RETURN_IF_ERROR(_parse_footer(&_file_metadata, &file_metadata_size));
    if (file_metadata_size > 0) {
        // cache does not understand shared ptr at all.
        // so we have to new a object to hold this shared ptr.
        FileMetaDataPtr* capture = new FileMetaDataPtr(_file_metadata);
        Status st = Status::InternalError("write footer cache failed");
        DeferOp op([&st, this, capture, file_metadata_size]() {
            if (st.ok()) {
                _scanner_ctx->stats->footer_cache_write_bytes += file_metadata_size;
                _scanner_ctx->stats->footer_cache_write_count += 1;
            } else {
                _scanner_ctx->stats->footer_cache_write_fail_count += 1;
                delete capture;
            }
        });
        auto deleter = [capture]() { delete capture; };
        WriteCacheOptions options;
        options.evict_probability = _datacache_options.datacache_evict_probability;
        st = cache->write_object(metacache_key, capture, file_metadata_size, deleter, &cache_handle, &options);
    } else {
        LOG(ERROR) << "Parsing unexpected parquet file metadata size";
    }
    return Status::OK();
}

Status FileReader::_build_split_tasks() {
    // dont do split in following cases:
    // 1. this feature is not enabled
    // 2. we have already do split before (that's why `split_context` is nullptr)
    if (!_scanner_ctx->enable_split_tasks || _scanner_ctx->split_context != nullptr) {
        return Status::OK();
    }

    size_t row_group_size = _file_metadata->t_metadata().row_groups.size();
    for (size_t i = 0; i < row_group_size; i++) {
        const tparquet::RowGroup& row_group = _file_metadata->t_metadata().row_groups[i];
        bool selected = _select_row_group(row_group);
        if (!selected) continue;
        if (_filter_group(row_group)) {
            DLOG(INFO) << "row group " << i << " of file has been filtered by min/max conjunct";
            continue;
        }
        int64_t start_offset = _get_row_group_start_offset(row_group);
        int64_t end_offset = _get_row_group_end_offset(row_group);
        if (start_offset >= end_offset) {
            LOG(INFO) << "row group " << i << " is empty. start = " << start_offset << ", end = " << end_offset;
            continue;
        }
#ifndef NDEBUG
        DCHECK(start_offset < end_offset) << "start = " << start_offset << ", end = " << end_offset;
        // there could be holes between row groups.
        // but this does not affect our scan range filter logic.
        // because in `_select_row_group`, we check if `start offset of row group` is in this range
        // so as long as `end_offset > start_offset && end_offset <= start_offset(next_group)`, it's ok
        if ((i + 1) < row_group_size) {
            const tparquet::RowGroup& next_row_group = _file_metadata->t_metadata().row_groups[i + 1];
            DCHECK(end_offset <= _get_row_group_start_offset(next_row_group));
        }
#endif
        auto split_ctx = std::make_unique<SplitContext>();
        split_ctx->split_start = start_offset;
        split_ctx->split_end = end_offset;
        split_ctx->file_metadata = _file_metadata;
        _scanner_ctx->split_tasks.emplace_back(std::move(split_ctx));
    }
    _scanner_ctx->merge_split_tasks();
    // if only one split task, clear it, no need to do split work.
    if (_scanner_ctx->split_tasks.size() <= 1) {
        _scanner_ctx->split_tasks.clear();
    }

    if (VLOG_OPERATOR_IS_ON) {
        std::stringstream ss;
        for (const HdfsSplitContextPtr& ctx : _scanner_ctx->split_tasks) {
            ss << "[" << ctx->split_start << "," << ctx->split_end << "]";
        }
        VLOG_OPERATOR << "FileReader: do_open. split task for " << _file->filename()
                      << ", split_tasks.size = " << _scanner_ctx->split_tasks.size() << ", range = " << ss.str();
    }
    return Status::OK();
}

StatusOr<uint32_t> FileReader::_get_footer_read_size() const {
    if (_file_size == 0) {
        return Status::Corruption("Parquet file size is 0 bytes");
    } else if (_file_size < PARQUET_FOOTER_SIZE) {
        return Status::Corruption(strings::Substitute(
                "Parquet file size is $0 bytes, smaller than the minimum parquet file footer ($1 bytes)", _file_size,
                PARQUET_FOOTER_SIZE));
    }
    return std::min(_file_size, DEFAULT_FOOTER_BUFFER_SIZE);
}

StatusOr<uint32_t> FileReader::_parse_metadata_length(const std::vector<char>& footer_buff) const {
    size_t size = footer_buff.size();
    if (memequal(footer_buff.data() + size - 4, 4, PARQUET_EMAIC_NUMBER, 4)) {
        return Status::NotSupported("StarRocks parquet reader not support encrypted parquet file yet");
    }

    if (!memequal(footer_buff.data() + size - 4, 4, PARQUET_MAGIC_NUMBER, 4)) {
        return Status::Corruption("Parquet file magic not matched");
    }

    uint32_t metadata_length = decode_fixed32_le(reinterpret_cast<const uint8_t*>(footer_buff.data()) + size - 8);
    if (metadata_length > _file_size - PARQUET_FOOTER_SIZE) {
        return Status::Corruption(strings::Substitute(
                "Parquet file size is $0 bytes, smaller than the size reported by footer's ($1 bytes)", _file_size,
                metadata_length));
    }
    return metadata_length;
}

bool FileReader::_filter_group_with_min_max_conjuncts(const tparquet::RowGroup& row_group) {
    // filter by min/max conjunct ctxs.
    if (!_scanner_ctx->min_max_conjunct_ctxs.empty()) {
        const TupleDescriptor& tuple_desc = *(_scanner_ctx->min_max_tuple_desc);
        ChunkPtr min_chunk = ChunkHelper::new_chunk(tuple_desc, 0);
        ChunkPtr max_chunk = ChunkHelper::new_chunk(tuple_desc, 0);

        auto st = _read_min_max_chunk(row_group, tuple_desc.slots(), &min_chunk, &max_chunk);
        if (!st.ok()) {
            // if there are some error when dealing statistics, shouldn't return the error status,
            // just read data ignore the statistics.
            return false;
        }

        for (auto& min_max_conjunct_ctx : _scanner_ctx->min_max_conjunct_ctxs) {
            auto res_min = min_max_conjunct_ctx->evaluate(min_chunk.get());
            auto res_max = min_max_conjunct_ctx->evaluate(max_chunk.get());
            if (!res_min.ok() || !res_max.ok()) {
                // maybe one of the conjuncts encounter error when dealing statistics, just ignore it and continue
                continue;
            }
            auto min_column = res_min.value();
            auto max_column = res_max.value();
            auto f = [&](Column* c) {
                // is_null(0) only when something unexpected happens
                if (c->is_null(0)) return (int8_t)0;
                return c->get(0).get_int8();
            };
            auto min = f(min_column.get());
            auto max = f(max_column.get());
            if (min == 0 && max == 0) {
                return true;
            }
        }
    }
    return false;
}

bool FileReader::_filter_group_with_bloom_filter_min_max_conjuncts(const tparquet::RowGroup& row_group) {
    // filter by min/max in runtime filter.
    if (_scanner_ctx->runtime_filter_collector) {
        std::vector<SlotDescriptor*> min_max_slots(1);

        const TupleDescriptor& tuple_desc = *(_scanner_ctx->tuple_desc);
        const std::vector<SlotDescriptor*>& slots = tuple_desc.slots();

        for (auto& it : _scanner_ctx->runtime_filter_collector->descriptors()) {
            RuntimeFilterProbeDescriptor* rf_desc = it.second;
            // external node won't have colocate runtime filter
            const JoinRuntimeFilter* filter = rf_desc->runtime_filter(-1);
            SlotId probe_slot_id;
            if (filter == nullptr || filter->has_null() || !rf_desc->is_probe_slot_ref(&probe_slot_id)) continue;
            // !!linear search slot by slot_id.
            SlotDescriptor* slot = nullptr;
            for (SlotDescriptor* s : slots) {
                if (s->id() == probe_slot_id) {
                    slot = s;
                    break;
                }
            }
            if (!slot) continue;
            min_max_slots[0] = slot;
            ChunkPtr min_chunk = ChunkHelper::new_chunk(min_max_slots, 0);
            ChunkPtr max_chunk = ChunkHelper::new_chunk(min_max_slots, 0);

            auto st = _read_min_max_chunk(row_group, min_max_slots, &min_chunk, &max_chunk);
            if (!st.ok()) continue;
            bool discard = RuntimeFilterHelper::filter_zonemap_with_min_max(
                    slot->type().type, filter, min_chunk->columns()[0].get(), max_chunk->columns()[0].get());
            if (discard) {
                return true;
            }
        }
    }
    return false;
}

bool FileReader::_filter_group_with_more_filter(const tparquet::RowGroup& row_group) {
    // runtime_in_filter, the sql-original in_filter and is_null/not_null filter will be in
    // _scanner_ctx->conjunct_ctxs_by_slot
    for (auto kv : _scanner_ctx->conjunct_ctxs_by_slot) {
        StatisticsHelper::StatSupportedFilter filter_type;
        for (auto ctx : kv.second) {
            if (StatisticsHelper::can_be_used_for_statistics_filter(ctx, filter_type)) {
                const TupleDescriptor& tuple_desc = *(_scanner_ctx->tuple_desc);
                SlotDescriptor* slot = tuple_desc.get_slot_by_id(kv.first);
                if (UNLIKELY(slot == nullptr)) {
                    // it shouldn't be here, just some defensive code
                    DCHECK(false) << "couldn't find slot id " << kv.first << " in tuple desc";
                    LOG(WARNING) << "couldn't find slot id " << kv.first << " in tuple desc";
                    continue;
                }
                std::unordered_map<std::string, size_t> column_name_2_pos_in_meta{};
                std::vector<SlotDescriptor*> slot_v{slot};
                _meta_helper->build_column_name_2_pos_in_meta(column_name_2_pos_in_meta, slot_v);
                const tparquet::ColumnMetaData* column_meta =
                        _meta_helper->get_column_meta(column_name_2_pos_in_meta, row_group, slot->col_name());
                if (column_meta == nullptr || !column_meta->__isset.statistics) continue;
                if (filter_type == StatisticsHelper::StatSupportedFilter::IS_NULL) {
                    if (!column_meta->statistics.__isset.null_count) continue;
                    if (column_meta->statistics.null_count == 0) {
                        return true;
                    }
                } else if (filter_type == StatisticsHelper::StatSupportedFilter::IS_NOT_NULL) {
                    if (!column_meta->statistics.__isset.null_count) continue;
                    if (column_meta->statistics.null_count == row_group.num_rows) {
                        return true;
                    }
                } else if (filter_type == StatisticsHelper::StatSupportedFilter::FILTER_IN) {
                    std::vector<string> min_values;
                    std::vector<string> max_values;

                    const ParquetField* field = _meta_helper->get_parquet_field(slot);
                    if (field == nullptr) {
                        LOG(WARNING) << "Can't get " + slot->col_name() + "'s ParquetField in _read_min_max_chunk.";
                        continue;
                    }
                    auto st = _get_min_max_value(slot, column_meta, field, min_values, max_values);
                    if (!st.ok()) continue;
                    Filter selected(min_values.size(), 1);
                    st = StatisticsHelper::in_filter_on_min_max_stat(min_values, max_values, ctx, field,
                                                                     _scanner_ctx->timezone, selected);
                    if (!st.ok()) continue;
                    if (!selected[0]) {
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

// when doing row group filter, there maybe some error, but we'd better just ignore it instead of returning the error
// status and lead to the query failed.
bool FileReader::_filter_group(const tparquet::RowGroup& row_group) {
    if (_filter_group_with_min_max_conjuncts(row_group)) {
        return true;
    }

    if (_filter_group_with_bloom_filter_min_max_conjuncts(row_group)) {
        return true;
    }

    if (config::parquet_statistics_process_more_filter_enable && _filter_group_with_more_filter(row_group)) {
        return true;
    }

    return false;
}

Status FileReader::_read_min_max_chunk(const tparquet::RowGroup& row_group, const std::vector<SlotDescriptor*>& slots,
                                       ChunkPtr* min_chunk, ChunkPtr* max_chunk) const {
    const HdfsScannerContext& ctx = *_scanner_ctx;

    // Key is column name, format with case sensitive, comes from SlotDescription.
    // Value is the position of the filed in parquet schema.
    std::unordered_map<std::string, size_t> column_name_2_pos_in_meta{};
    _meta_helper->build_column_name_2_pos_in_meta(column_name_2_pos_in_meta, slots);

    for (size_t i = 0; i < slots.size(); i++) {
        const SlotDescriptor* slot = slots[i];
        const tparquet::ColumnMetaData* column_meta =
                _meta_helper->get_column_meta(column_name_2_pos_in_meta, row_group, slot->col_name());
        if (column_meta == nullptr) {
            int col_idx = _get_partition_column_idx(slot->col_name());
            if (col_idx < 0) {
                // column not exist in parquet file
                (*min_chunk)->columns()[i]->append_nulls(1);
                (*max_chunk)->columns()[i]->append_nulls(1);
            } else {
                // is partition column
                auto* const_column = ColumnHelper::as_raw_column<ConstColumn>(ctx.partition_values[col_idx]);
                ColumnPtr data_column = const_column->data_column();
                if (data_column->is_nullable()) {
                    (*min_chunk)->columns()[i]->append_nulls(1);
                    (*max_chunk)->columns()[i]->append_nulls(1);
                } else {
                    (*min_chunk)->columns()[i]->append(*data_column, 0, 1);
                    (*max_chunk)->columns()[i]->append(*data_column, 0, 1);
                }
            }
        } else if (!column_meta->__isset.statistics) {
            // statistics not exist in parquet file
            return Status::Aborted("No exist statistics");
        } else {
            std::vector<string> min_values;
            std::vector<string> max_values;

            const ParquetField* field = _meta_helper->get_parquet_field(slot);
            if (field == nullptr) {
                LOG(WARNING) << "Can't get " + slot->col_name() + "'s ParquetField in _read_min_max_chunk.";
                return Status::InternalError(strings::Substitute("Can't get $0 field", slot->col_name()));
            }

            RETURN_IF_ERROR(_get_min_max_value(slot, column_meta, field, min_values, max_values));
            RETURN_IF_ERROR(StatisticsHelper::decode_value_into_column((*min_chunk)->columns()[i], min_values,
                                                                       slot->type(), field, ctx.timezone));
            RETURN_IF_ERROR(StatisticsHelper::decode_value_into_column((*max_chunk)->columns()[i], max_values,
                                                                       slot->type(), field, ctx.timezone));
        }
    }

    return Status::OK();
}

int32_t FileReader::_get_partition_column_idx(const std::string& col_name) const {
    for (int32_t i = 0; i < _scanner_ctx->partition_columns.size(); i++) {
        if (_scanner_ctx->partition_columns[i].name() == col_name) {
            return i;
        }
    }
    return -1;
}

Status FileReader::_get_min_max_value(const SlotDescriptor* slot, const tparquet::ColumnMetaData* column_meta,
                                      const ParquetField* field, std::vector<std::string>& min_values,
                                      std::vector<std::string>& max_values) const {
    // When statistics is empty, column_meta->__isset.statistics is still true,
    // but statistics.__isset.xxx may be false, so judgment is required here.
    bool is_set_min_max = (column_meta->statistics.__isset.max && column_meta->statistics.__isset.min) ||
                          (column_meta->statistics.__isset.max_value && column_meta->statistics.__isset.min_value);
    if (!is_set_min_max) {
        return Status::Aborted("No exist min/max");
    }

    DCHECK_EQ(field->physical_type, column_meta->type);
    auto sort_order = sort_order_of_logical_type(slot->type().type);

    if (!_has_correct_min_max_stats(*column_meta, sort_order)) {
        return Status::Aborted("The file has incorrect order");
    }

    if (column_meta->statistics.__isset.min_value) {
        min_values.emplace_back(column_meta->statistics.min_value);
        max_values.emplace_back(column_meta->statistics.max_value);
    } else {
        min_values.emplace_back(column_meta->statistics.min);
        max_values.emplace_back(column_meta->statistics.max);
    }

    return Status::OK();
}

bool FileReader::_has_correct_min_max_stats(const tparquet::ColumnMetaData& column_meta,
                                            const SortOrder& sort_order) const {
    return _file_metadata->writer_version().HasCorrectStatistics(column_meta, sort_order);
}

void FileReader::_prepare_read_columns(std::unordered_set<std::string>& existed_column_names) {
    _meta_helper->prepare_read_columns(_scanner_ctx->materialized_columns, _group_reader_param.read_cols,
                                       existed_column_names);
    _no_materialized_column_scan = (_group_reader_param.read_cols.size() == 0);
}

bool FileReader::_select_row_group(const tparquet::RowGroup& row_group) {
    size_t row_group_start = _get_row_group_start_offset(row_group);
    const auto* scan_range = _scanner_ctx->scan_range;
    size_t scan_start = scan_range->offset;
    size_t scan_end = scan_range->length + scan_start;
    if (row_group_start >= scan_start && row_group_start < scan_end) {
        return true;
    }
    return false;
}

Status FileReader::_init_group_readers() {
    const HdfsScannerContext& fd_scanner_ctx = *_scanner_ctx;

    // _group_reader_param is used by all group readers
    _group_reader_param.tuple_desc = fd_scanner_ctx.tuple_desc;
    _group_reader_param.conjunct_ctxs_by_slot = fd_scanner_ctx.conjunct_ctxs_by_slot;
    _group_reader_param.timezone = fd_scanner_ctx.timezone;
    _group_reader_param.stats = fd_scanner_ctx.stats;
    _group_reader_param.sb_stream = _sb_stream;
    _group_reader_param.chunk_size = _chunk_size;
    _group_reader_param.file = _file;
    _group_reader_param.file_metadata = _file_metadata.get();
    _group_reader_param.case_sensitive = fd_scanner_ctx.case_sensitive;
    _group_reader_param.lazy_column_coalesce_counter = fd_scanner_ctx.lazy_column_coalesce_counter;
    // for pageIndex
    _group_reader_param.min_max_conjunct_ctxs = fd_scanner_ctx.min_max_conjunct_ctxs;

    int64_t row_group_first_row = 0;
    // select and create row group readers.
    for (size_t i = 0; i < _file_metadata->t_metadata().row_groups.size(); i++) {
        bool selected = _select_row_group(_file_metadata->t_metadata().row_groups[i]);

        if (i > 0) {
            row_group_first_row += _file_metadata->t_metadata().row_groups[i - 1].num_rows;
        }
        if (selected) {
            StatusOr<bool> st = _filter_group(_file_metadata->t_metadata().row_groups[i]);
            if (!st.ok()) return st.status();
            if (st.value()) {
                DLOG(INFO) << "row group " << i << " of file has been filtered by min/max conjunct";
                continue;
            }

            auto row_group_reader =
                    std::make_shared<GroupReader>(_group_reader_param, i, _need_skip_rowids, row_group_first_row);
            _row_group_readers.emplace_back(row_group_reader);
            int64_t num_rows = _file_metadata->t_metadata().row_groups[i].num_rows;
            // for iceberg v2 pos delete
            if (_need_skip_rowids != nullptr && !_need_skip_rowids->empty()) {
                auto start_iter = _need_skip_rowids->lower_bound(row_group_first_row);
                auto end_iter = _need_skip_rowids->upper_bound(row_group_first_row + num_rows - 1);
                num_rows -= std::distance(start_iter, end_iter);
            }
            _total_row_count += num_rows;
        } else {
            continue;
        }
    }
    _row_group_size = _row_group_readers.size();

    // initialize row group readers.
    for (auto& r : _row_group_readers) {
        RETURN_IF_ERROR(r->init());
    }

    // collect pageIndex io ranges.
    if (config::parquet_coalesce_read_enable && _sb_stream != nullptr && config::parquet_page_index_enable) {
        std::vector<io::SharedBufferedInputStream::IORange> ranges;
        int64_t end_offset = 0;
        for (auto& r : _row_group_readers) {
            r->collect_io_ranges(&ranges, &end_offset, ColumnIOType::PAGE_INDEX);
        }
        RETURN_IF_ERROR(_sb_stream->set_io_ranges(ranges));
    }

    if (!_row_group_readers.empty()) {
        // prepare first row group
        RETURN_IF_ERROR(_row_group_readers[_cur_row_group_idx]->prepare());
    }

    return Status::OK();
}

Status FileReader::get_next(ChunkPtr* chunk) {
    if (_is_file_filtered) {
        return Status::EndOfFile("");
    }
    if (_no_materialized_column_scan) {
        RETURN_IF_ERROR(_exec_no_materialized_column_scan(chunk));
        return Status::OK();
    }

    if (_cur_row_group_idx < _row_group_size) {
        size_t row_count = _chunk_size;
        Status status = _row_group_readers[_cur_row_group_idx]->get_next(chunk, &row_count);
        if (status.ok() || status.is_end_of_file()) {
            if (row_count > 0) {
                RETURN_IF_ERROR(_scanner_ctx->append_or_update_not_existed_columns_to_chunk(chunk, row_count));
                _scanner_ctx->append_or_update_partition_column_to_chunk(chunk, row_count);
                _scan_row_count += (*chunk)->num_rows();
            }
            if (status.is_end_of_file()) {
                _row_group_readers[_cur_row_group_idx]->close();
                _cur_row_group_idx++;
                if (_cur_row_group_idx < _row_group_size) {
                    // prepare new group
                    RETURN_IF_ERROR(_row_group_readers[_cur_row_group_idx]->prepare());
                }

                return Status::OK();
            }
        } else {
            auto s = strings::Substitute("FileReader::get_next failed. reason = $0, file = $1", status.to_string(),
                                         _file->filename());
            LOG(WARNING) << s;
            return Status::InternalError(s);
        }

        return status;
    }

    return Status::EndOfFile("");
}

Status FileReader::_exec_no_materialized_column_scan(ChunkPtr* chunk) {
    if (_scan_row_count < _total_row_count) {
        size_t read_size = 0;
        if (_scanner_ctx->return_count_column) {
            read_size = _total_row_count - _scan_row_count;
            _scanner_ctx->append_or_update_count_column_to_chunk(chunk, read_size);
            _scanner_ctx->append_or_update_partition_column_to_chunk(chunk, 1);
        } else {
            read_size = std::min(static_cast<size_t>(_chunk_size), _total_row_count - _scan_row_count);
            RETURN_IF_ERROR(_scanner_ctx->append_or_update_not_existed_columns_to_chunk(chunk, read_size));
            _scanner_ctx->append_or_update_partition_column_to_chunk(chunk, read_size);
        }
        _scan_row_count += read_size;
        return Status::OK();
    }

    return Status::EndOfFile("");
}

} // namespace starrocks::parquet

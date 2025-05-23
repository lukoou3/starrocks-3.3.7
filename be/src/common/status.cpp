// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#include "common/status.h"

#include <fmt/format.h>

#include "common/config.h"
#include "gen_cpp/StatusCode_types.h"
#include "gen_cpp/Status_types.h"  // for TStatus
#include "gen_cpp/status.pb.h"     // for StatusPB
#include "gutil/strings/fastmem.h" // for memcpy_inlined

namespace starrocks {

// See 'Status::_state' for details.
static const char g_moved_from_state[5] = {'\x00', '\x00', '\x00', '\x00', TStatusCode::INTERNAL_ERROR};

inline const char* assemble_state(TStatusCode::type code, std::string_view msg, std::string_view ctx) {
    DCHECK(code != TStatusCode::OK);

    auto msg_size = std::min<size_t>(msg.size(), std::numeric_limits<uint16_t>::max());
    auto ctx_size = std::min<size_t>(ctx.size(), std::numeric_limits<uint16_t>::max());

    const auto len1 = static_cast<uint16_t>(msg_size);
    const auto len2 = static_cast<uint16_t>(ctx_size);
    const uint32_t size = static_cast<uint32_t>(len1) + len2;
    auto result = new char[size + 5];
    memcpy(result, &len1, sizeof(len1));
    memcpy(result + 2, &len2, sizeof(len2));
    result[4] = static_cast<char>(code);
    strings::memcpy_inlined(result + 5, msg.data(), len1);
    strings::memcpy_inlined(result + 5 + len1, ctx.data(), len2);
    return result;
}

const char* Status::copy_state(const char* state) {
    uint16_t len1;
    uint16_t len2;
    strings::memcpy_inlined(&len1, state, sizeof(len1));
    strings::memcpy_inlined(&len2, state + sizeof(len1), sizeof(len2));
    uint32_t length = static_cast<uint32_t>(len1) + len2 + 5;
    auto result = new char[length];
    strings::memcpy_inlined(result, state, length);
    return result;
}

const char* Status::copy_state_with_extra_ctx(const char* state, std::string_view ctx) {
    uint16_t len1;
    uint16_t len2;
    strings::memcpy_inlined(&len1, state, sizeof(len1));
    strings::memcpy_inlined(&len2, state + sizeof(len1), sizeof(len2));
    uint32_t old_length = static_cast<uint32_t>(len1) + len2 + 5;
    auto ctx_size = std::min<size_t>(ctx.size(), std::numeric_limits<uint16_t>::max() - len2);
    auto new_length = static_cast<uint32_t>(old_length + ctx_size);
    auto result = new char[new_length];
    strings::memcpy_inlined(result, state, old_length);
    strings::memcpy_inlined(result + old_length, ctx.data(), ctx_size);
    auto new_len2 = static_cast<uint16_t>(len2 + ctx_size);
    memcpy(result + 2, &new_len2, sizeof(new_len2));
    return result;
}

Status::Status(const TStatus& s) {
    if (s.status_code != TStatusCode::OK) {
        if (s.error_msgs.empty()) {
            _state = assemble_state(s.status_code, {}, {});
        } else {
            _state = assemble_state(s.status_code, s.error_msgs[0], {});
        }
    }
}

Status::Status(const StatusPB& s) {
    auto code = (TStatusCode::type)s.status_code();
    if (code != TStatusCode::OK) {
        if (s.error_msgs_size() == 0) {
            _state = assemble_state(code, {}, {});
        } else {
            _state = assemble_state(code, s.error_msgs(0), {});
        }
    }
}

Status::Status(TStatusCode::type code, std::string_view msg, std::string_view ctx)
        : _state(assemble_state(code, msg, ctx)) {}

#if defined(ENABLE_STATUS_FAILED)
int32_t Status::get_cardinality_of_inject() {
    const auto& cardinality_of_inject = starrocks::config::cardinality_of_inject;
    if (cardinality_of_inject < 1) {
        return 1;
    } else {
        return cardinality_of_inject;
    }
}

void Status::access_directory_of_inject() {
    std::string directs = starrocks::config::directory_of_inject;
    vector<string> fields = strings::Split(directs, ",");
    for (const auto& direct : fields) {
        dircetory_enable[direct] = true;
    }
}

// direct_name is like "../src/exec/pipeline" and so on.
bool Status::in_directory_of_inject(const std::string& direct_name) {
    if (dircetory_enable.empty()) {
        return false;
    }

    vector<string> fields = strings::Split(direct_name, "/");
    if (fields.size() > 1) {
        std::stringstream ss;
        for (int i = 1; i < fields.size(); ++i) {
            ss << "/" << fields[i];
            const auto& iter = dircetory_enable.find(ss.str());
            if (iter != dircetory_enable.end() && iter->second) {
                return true;
            }
        }
        return false;
    } else {
        DCHECK_GE(fields.size(), 1);
        DCHECK_EQ(fields[0], "..");
        return false;
    }
}
#endif

void Status::to_thrift(TStatus* s) const {
    s->error_msgs.clear();
    if (_state == nullptr) {
        s->status_code = TStatusCode::OK;
    } else {
        s->status_code = code();
        auto msg = message();
        s->error_msgs.emplace_back(msg.data(), msg.size());
        s->__isset.error_msgs = true;
    }
}

void Status::to_protobuf(StatusPB* s) const {
    s->clear_error_msgs();
    if (_state == nullptr) {
        s->set_status_code((int)TStatusCode::OK);
    } else {
        s->set_status_code(code());
        auto msg = message();
        s->add_error_msgs(msg.data(), msg.size());
    }
}

std::string Status::code_as_string() const {
    if (_state == nullptr) {
        return "OK";
    }
    switch (code()) {
    case TStatusCode::OK:
        return "OK";
    case TStatusCode::CANCELLED:
        return "Cancelled";
    case TStatusCode::NOT_IMPLEMENTED_ERROR:
        return "Not supported";
    case TStatusCode::RUNTIME_ERROR:
        return "Runtime error";
    case TStatusCode::MEM_LIMIT_EXCEEDED:
        return "Memory limit exceeded";
    case TStatusCode::INTERNAL_ERROR:
        return "Internal error";
    case TStatusCode::THRIFT_RPC_ERROR:
        return "Rpc error";
    case TStatusCode::TIMEOUT:
        return "Timeout";
    case TStatusCode::MEM_ALLOC_FAILED:
        return "Memory alloc failed";
    case TStatusCode::BUFFER_ALLOCATION_FAILED:
        return "Buffer alloc failed";
    case TStatusCode::MINIMUM_RESERVATION_UNAVAILABLE:
        return "Minimum reservation unavailable";
    case TStatusCode::PUBLISH_TIMEOUT:
        return "Publish timeout";
    case TStatusCode::LABEL_ALREADY_EXISTS:
        return "Label already exist";
    case TStatusCode::END_OF_FILE:
        return "End of file";
    case TStatusCode::NOT_FOUND:
        return "Not found";
    case TStatusCode::CORRUPTION:
        return "Corruption";
    case TStatusCode::INVALID_ARGUMENT:
        return "Invalid argument";
    case TStatusCode::IO_ERROR:
        return "IO error";
    case TStatusCode::ALREADY_EXIST:
        return "Already exist";
    case TStatusCode::NETWORK_ERROR:
        return "Network error";
    case TStatusCode::ILLEGAL_STATE:
        return "Illegal state";
    case TStatusCode::NOT_AUTHORIZED:
        return "Not authorized";
    case TStatusCode::REMOTE_ERROR:
        return "Remote error";
    case TStatusCode::SERVICE_UNAVAILABLE:
        return "Service unavailable";
    case TStatusCode::UNINITIALIZED:
        return "Uninitialized";
    case TStatusCode::CONFIGURATION_ERROR:
        return "Configuration error";
    case TStatusCode::INCOMPLETE:
        return "Incomplete";
    case TStatusCode::DATA_QUALITY_ERROR:
        return "Data quality error";
    case TStatusCode::RESOURCE_BUSY:
        return "Resource busy";
    case TStatusCode::SR_EAGAIN:
        return "Resource temporarily unavailable";
    case TStatusCode::REMOTE_FILE_NOT_FOUND:
        return "Remote file not found";
    case TStatusCode::ANALYSIS_ERROR:
        return "Analysis error";
    case TStatusCode::KUDU_NOT_ENABLED:
        return "Kudu not enabled";
    case TStatusCode::KUDU_NOT_SUPPORTED_ON_OS:
        return "Kudu not supported on os";
    case TStatusCode::TOO_MANY_TASKS:
        return "Too many tasks";
    case TStatusCode::ES_INTERNAL_ERROR:
        return "ES internal error";
    case TStatusCode::ES_INDEX_NOT_FOUND:
        return "ES index not found";
    case TStatusCode::ES_SHARD_NOT_FOUND:
        return "ES shard not found";
    case TStatusCode::ES_INVALID_CONTEXTID:
        return "ES invalid context id";
    case TStatusCode::ES_INVALID_OFFSET:
        return "ES invalid offset";
    case TStatusCode::ES_REQUEST_ERROR:
        return "ES request error";
    case TStatusCode::ABORTED:
        return "Aborted";
    case TStatusCode::OLAP_ERR_VERSION_ALREADY_MERGED:
        return "Version already merged";
    case TStatusCode::DUPLICATE_RPC_INVOCATION:
        return "Duplicate RPC invocation";
    case TStatusCode::GLOBAL_DICT_ERROR:
        return "Global dictionary error";
    case TStatusCode::UNKNOWN:
        return "Unknown";
    case TStatusCode::TXN_NOT_EXISTS:
        return "Transaction not exist";
    case TStatusCode::TXN_IN_PROCESSING:
        return "Transaction in processing";
    case TStatusCode::YIELD:
        return "Task yield";
    case TStatusCode::JIT_COMPILE_ERROR:
        return "JIT compile error";
    case TStatusCode::CAPACITY_LIMIT_EXCEED:
        return "Capaticy limit exceeded";
    case TStatusCode::BIG_QUERY_CPU_SECOND_LIMIT_EXCEEDED:
        return "Big query cpu second limit exceeded";
    case TStatusCode::BIG_QUERY_SCAN_ROWS_LIMIT_EXCEEDED:
        return "Big query scan rows limit exceeded";
    }
    return {};
}

std::string Status::to_string(bool with_context_info) const {
    std::string result(code_as_string());
    if (_state == nullptr) {
        return result;
    }

    result.append(": ");
    std::string_view msg;
    if (with_context_info) {
        msg = detailed_message();
    } else {
        msg = message();
    }

    result.append(reinterpret_cast<const char*>(msg.data()), msg.size());
    return result;
}

std::string_view Status::message() const {
    if (_state == nullptr) {
        return {};
    }

    uint16_t len1;
    memcpy(&len1, _state, sizeof(len1));
    return {_state + 5, len1};
}

std::string_view Status::detailed_message() const {
    if (_state == nullptr) {
        return {};
    }

    uint16_t len1;
    uint16_t len2;
    memcpy(&len1, _state, sizeof(len1));
    memcpy(&len2, _state + 2, sizeof(len2));
    uint32_t length = static_cast<uint32_t>(len1) + len2;
    return {_state + 5, length};
}
Status Status::clone_and_prepend(std::string_view msg) const {
    if (ok()) {
        return *this;
    }
    return {code(), fmt::format("{}: {}", msg, message())};
}

Status Status::clone_and_append(std::string_view msg) const {
    if (ok()) {
        return *this;
    }
    return {code(), fmt::format("{}: {}", message(), msg)};
}

Status Status::clone_and_append_context(const char* filename, int line, const char* expr) const {
    if (UNLIKELY(ok())) {
        return *this;
    }
    Status ret;
    ret._state = copy_state_with_extra_ctx(_state, fmt::format("\n{}:{} {}", filename, line, expr));
    return ret;
}

const char* Status::moved_from_state() {
    return g_moved_from_state;
}

bool Status::is_moved_from(const char* state) {
    return state == moved_from_state();
}

} // namespace starrocks

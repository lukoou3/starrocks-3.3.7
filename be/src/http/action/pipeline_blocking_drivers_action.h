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

#include <rapidjson/document.h>
#include <rapidjson/prettywriter.h>
#include <rapidjson/rapidjson.h>
#include <rapidjson/stringbuffer.h>

#include <functional>
#include <mutex>
#include <unordered_map>

#include "http/http_handler.h"
#include "runtime/exec_env.h"

namespace starrocks {

class PipelineBlockingDriversAction : public HttpHandler {
public:
    explicit PipelineBlockingDriversAction(ExecEnv* exec_env) : _exec_env(exec_env) {}
    ~PipelineBlockingDriversAction() override = default;

    void handle(HttpRequest* req) override;

private:
    void _handle(HttpRequest* req, const std::function<void(rapidjson::Document& root)>& func);
    // Returns information about the blocking drivers with the following format:
    // {
    //      "queries_in_workgroup": [{
    //          "query_id": "str",
    //          "fragments": [{
    //              "fragment_id": "str",
    //              "drivers": [{
    //                  "driver_id": "int",
    //                  "state": "str",
    //                  "driver_desc": "str"
    //              }]
    //          }]
    //      }]
    // }
    void _handle_stat(HttpRequest* req);
    void _handle_error(HttpRequest* req, const std::string& error_msg);

private:
    ExecEnv* _exec_env;
};

} // namespace starrocks
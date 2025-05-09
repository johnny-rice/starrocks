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

#include "exec/pipeline/set/intersect_output_source_operator.h"

namespace starrocks::pipeline {

Status IntersectOutputSourceOperator::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(SourceOperator::prepare(state));
    _intersect_ctx->observable().attach_source_observer(state, observer());
    return Status::OK();
}

StatusOr<ChunkPtr> IntersectOutputSourceOperator::pull_chunk(RuntimeState* state) {
    return _intersect_ctx->pull_chunk(state);
}

void IntersectOutputSourceOperator::close(RuntimeState* state) {
    _intersect_ctx->unref(state);
    Operator::close(state);
}

void IntersectOutputSourceOperatorFactory::close(RuntimeState* state) {
    SourceOperatorFactory::close(state);
}

} // namespace starrocks::pipeline

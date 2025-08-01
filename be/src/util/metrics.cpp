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
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/metrics.cpp

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

#include "util/metrics.h"

#include <mutex>

namespace starrocks {

MetricLabels MetricLabels::EmptyLabels;

std::ostream& operator<<(std::ostream& os, MetricType type) {
    switch (type) {
    case MetricType::COUNTER:
        os << "counter";
        break;
    case MetricType::GAUGE:
        os << "gauge";
        break;
    case MetricType::HISTOGRAM:
        os << "histogram";
        break;
    case MetricType::SUMMARY:
        os << "summary";
        break;
    case MetricType::UNTYPED:
        os << "untyped";
        break;
    default:
        os << "unknown";
        break;
    }
    return os;
}

const char* unit_name(MetricUnit unit) {
    switch (unit) {
    case MetricUnit::NANOSECONDS:
        return "nanoseconds";
    case MetricUnit::MICROSECONDS:
        return "microseconds";
    case MetricUnit::MILLISECONDS:
        return "milliseconds";
    case MetricUnit::SECONDS:
        return "seconds";
    case MetricUnit::BYTES:
        return "bytes";
    case MetricUnit::ROWS:
        return "rows";
    case MetricUnit::PERCENT:
        return "percent";
    case MetricUnit::REQUESTS:
        return "requests";
    case MetricUnit::OPERATIONS:
        return "operations";
    case MetricUnit::BLOCKS:
        return "blocks";
    case MetricUnit::ROWSETS:
        return "rowsets";
    case MetricUnit::CONNECTIONS:
        return "connections";
    default:
        return "nounit";
    }
}

void Metric::hide() {
    if (_registry == nullptr) {
        return;
    }
    _registry->deregister_metric(this);
    _registry = nullptr;
}

bool MetricCollector::add_metric(const MetricLabels& labels, Metric* metric) {
    if (empty()) {
        _type = metric->type();
    } else if (metric->type() != _type) {
        return false;
    }
    auto it = _metrics.emplace(labels, metric);
    if (it.second) {
        _metric_labels.emplace(metric, labels);
    }
    return it.second;
}

void MetricCollector::remove_metric(Metric* metric) {
    auto it = _metric_labels.find(metric);
    if (it == _metric_labels.end()) {
        return;
    }
    _metrics.erase(it->second);
    _metric_labels.erase(it);
}

Metric* MetricCollector::get_metric(const MetricLabels& labels) const {
    auto it = _metrics.find(labels);
    if (it != _metrics.end()) {
        return it->second;
    }
    return nullptr;
}

void MetricCollector::get_metrics(std::vector<Metric*>* metrics) {
    DCHECK(metrics != nullptr);
    for (const auto& it : _metrics) {
        metrics->push_back(it.second);
    }
}

MetricRegistry::~MetricRegistry() noexcept {
    {
        std::unique_lock lock(_collector_mutex);

        std::vector<Metric*> metrics;
        for (const auto& it : _collectors) {
            it.second->get_metrics(&metrics);
        }
        for (auto metric : metrics) {
            _deregister_locked(metric);
        }
    }
    {
        std::unique_lock lock(_hooks_mutex);
        _hooks.clear();
    }
    // All register metric will deregister
    DCHECK(_collectors.empty()) << "_collectors not empty, size=" << _collectors.size();
}

bool MetricRegistry::register_metric(const std::string& name, const MetricLabels& labels, Metric* metric) {
    DCHECK(metric != nullptr);
    metric->hide();
    std::unique_lock lock(_collector_mutex);
    MetricCollector* collector = nullptr;
    auto it = _collectors.find(name);
    if (it == _collectors.end()) {
        collector = new MetricCollector();
        _collectors.emplace(name, collector);
    } else {
        collector = it->second;
    }
    auto res = collector->add_metric(labels, metric);
    if (res) {
        metric->_registry = this;
    }
    return res;
}

void MetricRegistry::_deregister_locked(Metric* metric) {
    std::vector<std::string> to_erase;
    for (auto& it : _collectors) {
        it.second->remove_metric(metric);
        if (it.second->empty()) {
            to_erase.emplace_back(it.first);
        }
    }
    for (auto& name : to_erase) {
        auto it = _collectors.find(name);
        delete it->second;
        _collectors.erase(it);
    }
}

Metric* MetricRegistry::get_metric(const std::string& name, const MetricLabels& labels) const {
    std::shared_lock lock(_collector_mutex);
    auto it = _collectors.find(name);
    if (it != _collectors.end()) {
        return it->second->get_metric(labels);
    }
    return nullptr;
}

bool MetricRegistry::register_hook(const std::string& name, const std::function<void()>& hook) {
    std::unique_lock lock(_hooks_mutex);
    auto it = _hooks.emplace(name, hook);
    return it.second;
}

void MetricRegistry::deregister_hook(const std::string& name) {
    std::unique_lock lock(_hooks_mutex);
    _hooks.erase(name);
}

} // namespace starrocks

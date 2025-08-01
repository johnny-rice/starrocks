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

package com.starrocks.credential.gcp;

import com.google.common.base.Preconditions;
import com.staros.proto.FileStoreInfo;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudType;
import com.starrocks.thrift.TCloudConfiguration;
import com.starrocks.thrift.TCloudType;
import org.apache.hadoop.conf.Configuration;

import java.util.Map;

public class GCPCloudConfiguration extends CloudConfiguration {

    private final GCPCloudCredential gcpCloudCredential;

    public GCPCloudConfiguration(GCPCloudCredential gcpCloudCredential) {
        Preconditions.checkNotNull(gcpCloudCredential);
        this.gcpCloudCredential = gcpCloudCredential;
    }

    @Override
    public void toThrift(TCloudConfiguration tCloudConfiguration) {
        super.toThrift(tCloudConfiguration);
        tCloudConfiguration.setCloud_type(TCloudType.GCP);
        Map<String, String> properties = tCloudConfiguration.getCloud_properties();
        gcpCloudCredential.toThrift(properties);
        tCloudConfiguration.setCloud_properties(properties);
    }

    @Override
    public void applyToConfiguration(Configuration configuration) {
        super.applyToConfiguration(configuration);
        gcpCloudCredential.applyToConfiguration(configuration);
    }

    @Override
    public CloudType getCloudType() {
        return CloudType.GCP;
    }

    @Override
    public FileStoreInfo toFileStoreInfo() {
        return gcpCloudCredential.toFileStoreInfo();
    }

    @Override
    public String toConfString() {
        return String.format("GCPCloudConfiguration{%s, cred=%s}", getCommonFieldsString(),
                gcpCloudCredential.toCredString());
    }
}

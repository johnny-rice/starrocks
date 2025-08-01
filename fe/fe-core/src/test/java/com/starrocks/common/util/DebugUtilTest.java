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

package com.starrocks.common.util;

import com.starrocks.common.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DebugUtilTest {
    @Test
    public void testGetUint() {
        Pair<Double, String> result;
        result = DebugUtil.getUint(2000000000L);
        Assertions.assertEquals(Double.valueOf(2.0), result.first);
        Assertions.assertEquals(result.second, "B");

        result = DebugUtil.getUint(1234567L);
        Assertions.assertEquals(result.first, Double.valueOf(1.234567));
        Assertions.assertEquals(result.second, "M");

        result = DebugUtil.getUint(1234L);
        Assertions.assertEquals(result.first, Double.valueOf(1.234));
        Assertions.assertEquals(result.second, "K");

        result = DebugUtil.getUint(123L);
        Assertions.assertEquals(result.first, Double.valueOf(123.0));
        Assertions.assertEquals(result.second, "");
    }

    @Test
    public void testGetPrettyStringMs() {
        // 6hour1min
        Assertions.assertEquals(DebugUtil.getPrettyStringMs(21660222), "6h1m");

        // 1min222ms
        Assertions.assertEquals(DebugUtil.getPrettyStringMs(60222), "1m");

        // 2s222ms
        Assertions.assertEquals(DebugUtil.getPrettyStringMs(2222), "2s222ms");

        // 22ms
        Assertions.assertEquals(DebugUtil.getPrettyStringMs(22), "22ms");
    }

    @Test
    public void testGetByteUint() {
        Pair<Double, String> result;
        result = DebugUtil.getByteUint(0);
        Assertions.assertEquals(result.first, Double.valueOf(0.0));
        Assertions.assertEquals(result.second, "B");

        result = DebugUtil.getByteUint(123);     // B
        Assertions.assertEquals(result.first, Double.valueOf(123.0));
        Assertions.assertEquals(result.second, "B");

        result = DebugUtil.getByteUint(123456);  // K
        Assertions.assertEquals(result.first, Double.valueOf(120.5625));
        Assertions.assertEquals(result.second, "KB");

        result = DebugUtil.getByteUint(1234567);  // M
        Assertions.assertEquals(result.first, Double.valueOf(1.1773748397827148));
        Assertions.assertEquals(result.second, "MB");

        result = DebugUtil.getByteUint(1234567890L);  // G
        Assertions.assertEquals(result.first, Double.valueOf(1.1497809458523989));
        Assertions.assertEquals(result.second, "GB");
    }
}

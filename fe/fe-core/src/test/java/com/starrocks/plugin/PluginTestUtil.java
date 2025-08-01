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

package com.starrocks.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class PluginTestUtil {
    public static String getTestPathString(String name) {
        URL resourceRoot = PluginTestUtil.class.getResource("/");
        if (resourceRoot == null) {
            throw new RuntimeException("Could not find resource root");
        }

        String path = resourceRoot.getPath();
        File resourceDir = new File(path + "/plugin_test");

        // If the path doesn't exist, try looking for the resource in Gradle's resource directory
        if (!resourceDir.exists()) {
            // Transform from build/classes/java/test to build/resources/test
            path = path.replaceFirst("build/classes/\\w+/test", "build/resources/test");
        }

        return path + "/plugin_test/" + name;
    }

    public static Path getTestPath(String name) {
        return FileSystems.getDefault().getPath(getTestPathString(name));
    }

    public static File getTestFile(String name) {
        return new File(getTestPathString(name));
    }

    public static InputStream openTestFile(String name) {
        try {
            return new FileInputStream(getTestPathString(name));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return null;
    }
}

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

package com.starrocks.memory;

import com.starrocks.common.Config;
import com.starrocks.common.util.FrontendDaemon;
import one.profiler.AsyncProfiler;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class ProcProfileCollector extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(ProcProfileCollector.class);
    private static final String CPU_FILE_NAME_PREFIX = "cpu-profile-";
    private static final String MEM_FILE_NAME_PREFIX = "mem-profile-";
    private static final long LOG_INTERVAL = 3600 * 1000L;

    private final SimpleDateFormat profileTimeFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private final String profileLogDir;

    private long lastLogTime = -1;

    public ProcProfileCollector() {
        super("ProcProfileCollector", 1000L);
        profileLogDir = Config.sys_log_dir + "/proc_profile";
    }

    @Override
    protected void runAfterCatalogReady() {
        File file = new File(profileLogDir);
        file.mkdirs();

        if (Config.proc_profile_cpu_enable) {
            collectCPUProfile();
        }

        if (Config.proc_profile_mem_enable) {
            collectMemProfile();
        }

        deleteExpiredFiles();
    }

    private void collectMemProfile() {
        String fileName = MEM_FILE_NAME_PREFIX + currentTimeString() + ".html";
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        try {
            profiler.execute(String.format("start,quiet,event=alloc,alloc=2m,cstack=vm,file=%s", profileLogDir + "/" + fileName));
            Thread.sleep(Config.proc_profile_collect_time_s * 1000L);
            profiler.execute(String.format("stop,file=%s", profileLogDir + "/" + fileName));
        } catch (Exception e) {
            checkAndLog(() -> LOG.warn("collect memory profile failed, reason: {}", e.getMessage()));
        }

        try {
            compressFile(fileName);
        } catch (IOException e) {
            checkAndLog(() -> LOG.warn("compress memory file {} failed, reason: {}", fileName, e.getMessage()));
        }
    }

    private void collectCPUProfile() {
        String fileName = CPU_FILE_NAME_PREFIX + currentTimeString() + ".html";
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        try {
            profiler.execute(String.format("start,quiet,event=cpu,cstack=vm,file=%s", profileLogDir + "/" + fileName));
            Thread.sleep(Config.proc_profile_collect_time_s * 1000L);
            profiler.execute(String.format("stop,file=%s", profileLogDir + "/" + fileName));
        } catch (Exception e) {
            checkAndLog(() -> LOG.warn("collect cpu profile failed, reason: {}", e.getMessage()));
        }

        try {
            compressFile(fileName);
        } catch (IOException e) {
            checkAndLog(() -> LOG.warn("compress file {} failed, reason: {}", fileName, e.getMessage()));
        }
    }

    private void compressFile(String fileName) throws IOException {
        File sourceFile = new File(profileLogDir + "/" + fileName);
        try (FileOutputStream fileOutputStream = new FileOutputStream(profileLogDir + "/" + fileName + ".tar.gz");
                GzipCompressorOutputStream gzipOutputStream = new GzipCompressorOutputStream(fileOutputStream);
                TarArchiveOutputStream tarArchive = new TarArchiveOutputStream(gzipOutputStream);
                FileInputStream fileInputStream = new FileInputStream(sourceFile)) {
            TarArchiveEntry tarEntry = new TarArchiveEntry(sourceFile, sourceFile.getName());
            tarArchive.putArchiveEntry(tarEntry);

            byte[] buffer = new byte[1024];
            int len;
            while ((len = fileInputStream.read(buffer)) > 0) {
                tarArchive.write(buffer, 0, len);
            }
            tarArchive.closeArchiveEntry();
            tarArchive.finish();
        }

        sourceFile.delete();
    }

    private void deleteExpiredFiles() {
        File dir = new File(profileLogDir);
        List<File> validFiles = new ArrayList<>();
        long totalSize = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().startsWith(CPU_FILE_NAME_PREFIX)
                    || file.getName().startsWith(MEM_FILE_NAME_PREFIX)) {
                validFiles.add(file);
                totalSize += file.length();
            }
        }

        //sort file by time asc
        validFiles.sort((f1, f2) -> {
            String comparableName1 = getTimePart(f1.getName());
            String comparableName2 = getTimePart(f2.getName());
            return comparableName1.compareTo(comparableName2);
        });

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -Config.proc_profile_file_retained_days);
        String timeToDelete = profileTimeFormat.format(calendar.getTime());
        long sizeReserved = totalSize;
        for (File file : validFiles) {
            String timePart = getTimePart(file.getName());
            if (timePart.compareTo(timeToDelete) < 0
                    || sizeReserved > Config.proc_profile_file_retained_size_bytes) {
                sizeReserved -= file.length();
                file.delete();
            } else {
                break;
            }
        }
    }

    private String currentTimeString() {
        return profileTimeFormat.format(new Date(System.currentTimeMillis()));
    }

    private String getTimePart(String fileName) {
        return fileName.startsWith(CPU_FILE_NAME_PREFIX) ?
                fileName.substring(CPU_FILE_NAME_PREFIX.length(), fileName.indexOf("."))
                : fileName.substring(MEM_FILE_NAME_PREFIX.length(), fileName.indexOf("."));
    }

    private void checkAndLog(Runnable runnable) {
        if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL) {
            runnable.run();
            lastLogTime = System.currentTimeMillis();
        }
    }
}

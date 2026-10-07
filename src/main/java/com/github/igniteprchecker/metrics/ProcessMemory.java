package com.github.igniteprchecker.metrics;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The memory the service has held at most, for sizing {@code -Xmx} and the host: the peak of the heap's long-lived
 * generation (the young one fills to the top between collections by design, so its peak says nothing), and on Linux
 * the process's resident set now and at its peak, the kernel's {@code VmRSS} and {@code VmHWM}.
 */
public final class ProcessMemory {
    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    private static final long MB = 1024 * 1024;

    private ProcessMemory() {
    }

    /** Sizes in MB; -1 where this platform does not tell. */
    public record Usage(long heapPeakMb, long rssMb, long rssPeakMb, long hostMb) {
    }

    public static Usage read() {
        List<String> status;
        try {
            status = Files.exists(PROC_STATUS) ? Files.readAllLines(PROC_STATUS) : List.of();
        }
        catch (IOException e) {
            status = List.of();
        }

        long host = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
            ? os.getTotalMemorySize() / MB : -1;

        return new Usage(longLivedHeapPeak(ManagementFactory.getMemoryPoolMXBeans()) / MB, kbField(status, "VmRSS"),
            kbField(status, "VmHWM"), host);
    }

    /** A collector with no generations has one heap pool, and its peak is the heap's. */
    static long longLivedHeapPeak(List<MemoryPoolMXBean> pools) {
        return pools.stream()
            .filter(p -> p.getType() == MemoryType.HEAP)
            .filter(p -> !p.getName().contains("Eden") && !p.getName().contains("Survivor"))
            .mapToLong(p -> p.getPeakUsage() == null ? 0 : p.getPeakUsage().getUsed())
            .sum();
    }

    /** A {@code /proc/self/status} line such as {@code VmHWM:   215040 kB}, in MB; -1 when absent. */
    static long kbField(List<String> status, String name) {
        for (String line : status) {
            if (!line.startsWith(name + ":"))
                continue;

            String[] parts = line.substring(name.length() + 1).trim().split("\\s+");
            try {
                return Long.parseLong(parts[0]) / 1024;
            }
            catch (NumberFormatException e) {
                return -1;
            }
        }

        return -1;
    }
}

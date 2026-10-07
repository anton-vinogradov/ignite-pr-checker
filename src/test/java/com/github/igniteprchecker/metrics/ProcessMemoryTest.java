package com.github.igniteprchecker.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The host has 957 MB with about 280 free, yet the status page showed only the heap in use at that moment, so
 * nobody could tell how close the service came to its limits. The peaks are what -Xmx and the host must fit.
 */
class ProcessMemoryTest {
    private static final long MB = 1024 * 1024;

    private static MemoryPoolMXBean pool(String name, MemoryType type, long peakMb) {
        MemoryPoolMXBean p = mock(MemoryPoolMXBean.class);
        when(p.getName()).thenReturn(name);
        when(p.getType()).thenReturn(type);
        when(p.getPeakUsage()).thenReturn(new MemoryUsage(0, peakMb * MB, peakMb * MB, -1));

        return p;
    }

    @Test
    void theHeapPeakIsTheLongLivedGenerations() {
        List<MemoryPoolMXBean> serial = List.of(pool("Eden Space", MemoryType.HEAP, 140),
            pool("Survivor Space", MemoryType.HEAP, 17), pool("Tenured Gen", MemoryType.HEAP, 182),
            pool("Metaspace", MemoryType.NON_HEAP, 90));

        assertThat(ProcessMemory.longLivedHeapPeak(serial) / MB).isEqualTo(182);
    }

    @Test
    void aCollectorWithoutGenerationsHasOnePool() {
        assertThat(ProcessMemory.longLivedHeapPeak(List.of(pool("ZHeap", MemoryType.HEAP, 300))) / MB).isEqualTo(300);
    }

    @Test
    void theResidentSetNowAndAtItsPeakComeFromProc() {
        List<String> status = List.of("Name:\tjava", "VmPeak:\t 3123456 kB", "VmHWM:\t  220160 kB",
            "VmRSS:\t  201728 kB", "Threads:\t48");

        assertThat(ProcessMemory.kbField(status, "VmRSS")).isEqualTo(197);
        assertThat(ProcessMemory.kbField(status, "VmHWM")).isEqualTo(215);
        assertThat(ProcessMemory.kbField(List.of(), "VmHWM")).isEqualTo(-1);
    }
}

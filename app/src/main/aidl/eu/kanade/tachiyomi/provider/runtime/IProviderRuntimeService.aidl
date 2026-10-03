package eu.kanade.tachiyomi.provider.runtime;

import eu.kanade.tachiyomi.provider.runtime.IProviderHostBridge;

interface IProviderRuntimeService {
    String evaluate(String source, long wallClockTimeoutMs, long jsExecutionTimeoutMs);
    String evaluateWithHost(
        String source,
        long wallClockTimeoutMs,
        long jsExecutionTimeoutMs,
        IProviderHostBridge hostBridge
    );
    int processUid();
    int processPid();
}

package eu.kanade.tachiyomi.provider.runtime;

import android.os.ParcelFileDescriptor;
import eu.kanade.tachiyomi.provider.runtime.IProviderHostBridge;

interface IProviderRuntimeService {
    String invoke(
        String requestJson,
        in ParcelFileDescriptor sourceFd,
        IProviderHostBridge hostBridge
    );
    int processUid();
    int processPid();
}

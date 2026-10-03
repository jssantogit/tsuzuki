package eu.kanade.tachiyomi.provider.runtime;

import android.os.ParcelFileDescriptor;
import eu.kanade.tachiyomi.provider.runtime.IProviderHostBridge;

interface IProviderRuntimeService {
    String invoke(
        String requestJson,
        in ParcelFileDescriptor sourceFd,
        IProviderHostBridge hostBridge
    );
    String validatePackage(
        String requestJson,
        in ParcelFileDescriptor packageFd
    );
    String invokePackage(
        String requestJson,
        in ParcelFileDescriptor packageFd,
        String inputJson,
        IProviderHostBridge hostBridge
    );
    void cancel(String invocationId);
    int processUid();
    int processPid();
}

package eu.kanade.tachiyomi.provider.runtime;

interface IProviderHostBridge {
    String httpGet(String url);
    String httpGetResource(String url);
    String httpRequest(String requestJson);
    String p2pAcquire(String requestJson);
    String domSelectText(String resourceHandle, String cssSelector);
    String browserReadText(String url, String cssSelector);
    String storageGet(String key);
    void storageSet(String key, String value);
    void storageRemove(String key);
    String secretGet(String key);
    String binaryFetch(String url);
    String binaryZipEntry(String resourceHandle, String entryName);
    String binaryPromote(String resourceHandle, String format);
    String cryptoAesCbcDecrypt(String resourceHandle, String keyHex, String ivHex);
    String imageCrop(String resourceHandle, int x, int y, int width, int height);
    String imagePixel(String resourceHandle, int x, int y);
    void logInfo(String message);
    // Future code11-only. Append transactions; never renumber existing methods.
    String mangaAllChapterSnapshotStart(String workId, String language);
    String mangaAllChapterSnapshotNext(String workId, String language, String cursor);
    // Additive extraction transactions. Keep all prior transaction IDs stable.
    String binaryInspect(String resourceHandle, String operation, String argument);
    String binaryTransform(String resourceHandle, String operation);
    String imageCompose(String resourceHandle, String layout);
    String browserQuery(String url, String cssSelector, String attribute);
    String imagePackPages(String handlesJson);
    String httpRequestResource(String requestJson);
}

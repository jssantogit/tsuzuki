package eu.kanade.tachiyomi.provider.runtime;

interface IProviderHostBridge {
    String httpGet(String url);
    String httpGetResource(String url);
    String domSelectText(String resourceHandle, String cssSelector);
    String browserReadText(String url, String cssSelector);
    String storageGet(String key);
    void storageSet(String key, String value);
    void storageRemove(String key);
    String secretGet(String key);
    String binaryFetch(String url);
    String binaryZipEntry(String resourceHandle, String entryName);
    String cryptoAesCbcDecrypt(String resourceHandle, String keyHex, String ivHex);
    String imageCrop(String resourceHandle, int x, int y, int width, int height);
    String imagePixel(String resourceHandle, int x, int y);
    void logInfo(String message);
}

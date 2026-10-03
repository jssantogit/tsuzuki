package eu.kanade.tachiyomi.provider.runtime;

interface IProviderHostBridge {
    String httpGet(String url);
    String browserReadText(String url, String cssSelector);
    String binaryFetch(String url);
    String binaryZipEntry(String resourceHandle, String entryName);
    String binaryAesCbcDecrypt(String resourceHandle, String keyHex, String ivHex);
    String binaryImageCrop(String resourceHandle, int x, int y, int width, int height);
    String binaryImagePixel(String resourceHandle, int x, int y);
}

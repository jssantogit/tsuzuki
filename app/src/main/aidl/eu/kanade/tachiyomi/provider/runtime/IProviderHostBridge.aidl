package eu.kanade.tachiyomi.provider.runtime;

interface IProviderHostBridge {
    String httpGet(String url);
    String browserReadText(String url, String cssSelector);
}

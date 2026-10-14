package app.sbh.reader.data

import app.sbh.reader.net.UrlNormalizer
import app.sbh.reader.security.TokenStore
import app.sbh.reader.sync.SyncSettings

class AppSyncSettings(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : SyncSettings {
    override suspend fun enabledTypeIds(): Set<String> = settings.current().enabledTypeIds

    override suspend fun isConfigured(): Boolean =
        UrlNormalizer.normalize(settings.current().serverUrl) != null && tokens.hasToken()
}

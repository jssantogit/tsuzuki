package mihon.app.di

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.tsuzuki.integration.BuiltinIntegrationProviderRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.reading.StoredProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.repository.AndroidProviderRepositoryTransport
import eu.kanade.tachiyomi.provider.repository.InstalledScriptProviderRegistry
import eu.kanade.tachiyomi.provider.runtime.IsolatedProviderPackageContractValidator
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import tachiyomi.core.provider.packageformat.ProviderPackageContractValidator
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.supplychain.FileProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.FileProviderRepositoryEnrollmentStore
import tachiyomi.core.provider.supplychain.FileProviderRepositoryTrustStore
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.ProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.ProviderRepositoryEnrollmentStore
import tachiyomi.core.provider.supplychain.ProviderRepositoryManager
import tachiyomi.core.provider.supplychain.ProviderRepositoryTransport
import tachiyomi.core.provider.supplychain.ProviderRepositoryTrustStore
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.provider.CompositeProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import java.io.File

@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(context: Context): SqlDriver {
        return AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "tachiyomi.db"),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = true,
            ),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesXML(): XML = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
        setIndent(2)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderLocalConfigurationStore(
        context: Context,
    ): ProviderLocalConfigurationStore =
        FileProviderLocalConfigurationStore(
            File(context.filesDir, "provider-platform/configuration"),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesInstalledScriptProviderRegistry(
        artifactStore: ProviderArtifactStore,
        configurationStore: ProviderLocalConfigurationStore,
    ): InstalledScriptProviderRegistry =
        InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = configurationStore,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesBuiltinIntegrationProviderRegistry(
        integrationRegistry: IntegrationRegistry,
        settingsRepository: IntegrationSettingsRepository,
    ): BuiltinIntegrationProviderRegistry =
        BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrationRegistry,
            settingsRepository = settingsRepository,
            providerVersion = ProviderVersion(
                name = BuildConfig.VERSION_NAME,
                code = BuildConfig.VERSION_CODE.toLong(),
            ),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRegistry(
        builtinRegistry: BuiltinIntegrationProviderRegistry,
        scriptRegistry: InstalledScriptProviderRegistry,
    ): ProviderRegistry =
        CompositeProviderRegistry(
            registries = listOf(
                builtinRegistry,
                scriptRegistry,
            ),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRepositoryEnrollmentStore(
        context: Context,
    ): ProviderRepositoryEnrollmentStore =
        FileProviderRepositoryEnrollmentStore(
            File(context.filesDir, "provider-platform/repositories"),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRepositoryTrustStore(
        context: Context,
    ): ProviderRepositoryTrustStore =
        FileProviderRepositoryTrustStore(
            File(context.filesDir, "provider-platform/trust"),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderArtifactStore(
        context: Context,
    ): ProviderArtifactStore =
        ProviderArtifactStore(
            File(context.filesDir, "provider-platform/artifacts"),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRepositoryTransport(
        networkHelper: NetworkHelper,
    ): ProviderRepositoryTransport =
        AndroidProviderRepositoryTransport(networkHelper.client)

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderHostInvocationFactory(
        context: Context,
    ): ProviderHostInvocationFactory =
        ProviderHostInvocationFactory(context)

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRuntimeClient(
        context: Context,
        hostInvocationFactory: ProviderHostInvocationFactory,
    ): ProviderRuntimeClient =
        ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = hostInvocationFactory,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderPackageContractValidator(
        runtimeClient: ProviderRuntimeClient,
    ): ProviderPackageContractValidator =
        IsolatedProviderPackageContractValidator(runtimeClient)

    @Provides
    @SingleIn(AppScope::class)
    fun providesActiveProviderScriptPackageSource(
        artifactStore: ProviderArtifactStore,
    ): ActiveProviderScriptPackageSource =
        StoredProviderScriptPackageSource(artifactStore)

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderReadingGateway(
        registry: ProviderRegistry,
        packageSource: ActiveProviderScriptPackageSource,
        runtimeClient: ProviderRuntimeClient,
        hostInvocationFactory: ProviderHostInvocationFactory,
    ): ProviderReadingGateway =
        ScriptProviderReadingGateway(
            registry = registry,
            packageSource = packageSource,
            runtimeClient = runtimeClient,
            managedResources = hostInvocationFactory.managedFiles,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderPackageActivator(
        artifactStore: ProviderArtifactStore,
        contractValidator: ProviderPackageContractValidator,
    ): ProviderPackageActivator =
        ProviderPackageActivator(
            hostApiVersion = PROVIDER_HOST_API_VERSION,
            parser = ProviderPackageParser(),
            artifactStore = artifactStore,
            contractValidator = contractValidator,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providesProviderRepositoryManager(
        enrollmentStore: ProviderRepositoryEnrollmentStore,
        trustStore: ProviderRepositoryTrustStore,
        artifactStore: ProviderArtifactStore,
        packageActivator: ProviderPackageActivator,
        transport: ProviderRepositoryTransport,
        integrationRegistry: IntegrationRegistry,
    ): ProviderRepositoryManager =
        ProviderRepositoryManager(
            hostApiVersion = PROVIDER_HOST_API_VERSION,
            enrollmentStore = enrollmentStore,
            trustStore = trustStore,
            artifactStore = artifactStore,
            packageActivator = packageActivator,
            transport = transport,
            reservedProviderIds = integrationRegistry.manifests()
                .map { it.integrationId.value }
                .toSet(),
        )
}

private const val PROVIDER_HOST_API_VERSION = 1

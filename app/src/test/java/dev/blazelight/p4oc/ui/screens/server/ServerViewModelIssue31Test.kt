@file:Suppress("ImportOrdering")

package dev.blazelight.p4oc.ui.screens.server

import dev.blazelight.p4oc.core.datastore.RecentServer
import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.network.DiscoveryState
import dev.blazelight.p4oc.core.network.MdnsDiscoveryManager
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.network.ServerConfig
import dev.blazelight.p4oc.core.security.CredentialStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelIssue31Test {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connectToRemote persists no-port https url without appending opencode port`() = runTest(dispatcher) {
        val settingsDataStore = mockk<SettingsDataStore>(relaxUnitFun = true)
        val connectionRegistry = mockk<ServerConnectionRegistry>()
        val credentialStore = mockk<CredentialStore>()
        val discoveryManager = mockk<MdnsDiscoveryManager>()
        val savedConfig = slot<ServerConfig>()
        val recentUrl = slot<String>()
        val savedUrl = slot<String>()

        every { settingsDataStore.recentServers } returns flowOf<List<RecentServer>>(emptyList())
        every { settingsDataStore.savedServers } returns flowOf<List<SavedServer>>(emptyList())
        coEvery { settingsDataStore.getLastConnection() } returns null
        coEvery { settingsDataStore.saveLastConnection(capture(savedConfig), any()) } returns Unit
        coEvery {
            settingsDataStore.addRecentServer(
                url = capture(recentUrl),
                name = any(),
                username = any(),
                password = any(),
                allowInsecure = any(),
            )
        } returns Unit
        coEvery {
            settingsDataStore.addSavedServer(
                url = capture(savedUrl),
                name = any(),
                username = any(),
                password = any(),
                allowInsecure = any(),
                pinned = any(),
                defaultWorkspace = any(),
                lastConnectedAt = any(),
            )
        } returns SavedServer(
            id = "https://my-host.example.com:443",
            endpoint = "https://my-host.example.com",
            endpointKey = "https://my-host.example.com:443",
            displayName = "Remote Server",
        )
        coEvery { connectionRegistry.connectAndAwait(any(), any()) } returns Result.success(emptyList())
        every { discoveryManager.discoveredServers } returns MutableStateFlow(emptyList())
        every { discoveryManager.discoveryState } returns MutableStateFlow(DiscoveryState.IDLE)

        val viewModel = ServerViewModel(
            settingsDataStore = settingsDataStore,
            serverConnectionRegistry = connectionRegistry,
            credentialStore = credentialStore,
            mdnsDiscoveryManager = discoveryManager,
        )
        advanceUntilIdle()

        viewModel.setRemoteUrl("https://my-host.example.com")
        viewModel.setPassword("secret")
        viewModel.connectToRemote()
        advanceUntilIdle()

        coVerify { settingsDataStore.saveLastConnection(any(), any()) }
        coVerify {
            settingsDataStore.addRecentServer(
                url = any(),
                name = any(),
                username = any(),
                password = any(),
                allowInsecure = any(),
            )
        }
        coVerify {
            settingsDataStore.addSavedServer(
                url = any(),
                name = any(),
                username = any(),
                password = any(),
                allowInsecure = any(),
                pinned = any(),
                defaultWorkspace = any(),
                lastConnectedAt = any(),
            )
        }
        assertEquals("https://my-host.example.com", savedConfig.captured.url)
        assertEquals("https://my-host.example.com", recentUrl.captured)
        assertEquals("https://my-host.example.com", savedUrl.captured)
    }

    @Test
    fun `credentialed HTTP tailnet host reaches connection attempt`() = runTest(dispatcher) {
        val settingsDataStore = mockk<SettingsDataStore>()
        val connectionRegistry = mockk<ServerConnectionRegistry>()
        val discoveryManager = mockk<MdnsDiscoveryManager>()

        every { settingsDataStore.recentServers } returns flowOf(emptyList())
        every { settingsDataStore.savedServers } returns flowOf(emptyList())
        coEvery { settingsDataStore.getLastConnection() } returns null
        every { discoveryManager.discoveredServers } returns MutableStateFlow(emptyList())
        every { discoveryManager.discoveryState } returns MutableStateFlow(DiscoveryState.IDLE)
        coEvery { connectionRegistry.connectAndAwait(any(), any()) } returns
            Result.failure(IllegalStateException("offline"))

        val viewModel = ServerViewModel(
            settingsDataStore = settingsDataStore,
            serverConnectionRegistry = connectionRegistry,
            credentialStore = mockk(),
            mdnsDiscoveryManager = discoveryManager,
        )
        advanceUntilIdle()

        viewModel.setRemoteUrl("http://device.tailnet.ts.net:4096")
        viewModel.setPassword("secret")
        viewModel.connectToRemote()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            connectionRegistry.connectAndAwait(
                match { it.endpoint == "http://device.tailnet.ts.net:4096" },
                "secret",
            )
        }
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `failed remote connection is neither saved nor navigated`() = runTest(dispatcher) {
        val settingsDataStore = mockk<SettingsDataStore>()
        val connectionRegistry = mockk<ServerConnectionRegistry>()
        val discoveryManager = mockk<MdnsDiscoveryManager>()
        every { settingsDataStore.recentServers } returns flowOf(emptyList())
        every { settingsDataStore.savedServers } returns flowOf(emptyList())
        every { discoveryManager.discoveredServers } returns MutableStateFlow(emptyList())
        every { discoveryManager.discoveryState } returns MutableStateFlow(DiscoveryState.IDLE)
        coEvery { connectionRegistry.connectAndAwait(any(), any()) } returns
            Result.failure(IllegalStateException("offline"))
        val viewModel = ServerViewModel(
            settingsDataStore = settingsDataStore,
            serverConnectionRegistry = connectionRegistry,
            credentialStore = mockk(),
            mdnsDiscoveryManager = discoveryManager,
        )

        viewModel.setRemoteUrl("https://offline.example.com")
        viewModel.connectToRemote()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsDataStore.saveLastConnection(any(), any()) }
        coVerify(exactly = 0) { settingsDataStore.addRecentServer(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            settingsDataStore.addSavedServer(any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse(viewModel.uiState.value.isConnected)
        assertNull(viewModel.uiState.value.navigationDestination)
    }
}

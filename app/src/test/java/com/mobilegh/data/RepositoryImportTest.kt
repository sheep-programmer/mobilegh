package com.mobilegh.data

import com.mobilegh.MainDispatcherRule
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RepositoryImportTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var root: File
    private lateinit var store: ImportTasks
    private var previousProxy = ""
    private var previousCache: File? = null

    @Before fun setUp() {
        root = Files.createTempDirectory("repository-import-test").toFile()
        store = ImportTasks(root, now = { 100L }, ids = generateSequence(1) { it + 1 }.map { "task-$it" }.iterator()::next)
        previousProxy = Net.apiProxy
        previousCache = runCatching { Api.cacheDir }.getOrNull()
    }

    @After fun tearDown() {
        Net.apiProxy = previousProxy
        previousCache?.let { Api.cacheDir = it }
        root.deleteRecursively()
    }

    @Test fun sourceAcceptsHttpsStripsFragmentAndInfersRepositoryName() {
        val source = ImportSource.parse("https://git.example.com/octo/hello-world.git/#readme")
        assertEquals("https://git.example.com/octo/hello-world.git/", source.url)
        assertEquals("hello-world", source.defaultName)
        assertFalse(source.isInsecureHttp)
    }

    @Test fun sourceRequiresExplicitConfirmationForHttp() {
        try {
            ImportSource.parse("http://git.example.com/octo/hello.git")
            fail("HTTP requires confirmation")
        } catch (e: InsecureImportSourceException) {
            assertTrue(e.message!!.contains("HTTP"))
        }
        val confirmed = ImportSource.parse("http://git.example.com/octo/hello.git", allowInsecureHttp = true)
        assertTrue(confirmed.isInsecureHttp)
        assertEquals("hello", confirmed.defaultName)
    }

    @Test fun sourceRejectsUserinfoNonHttpAndMissingRepositoryName() {
        val invalid = listOf(
            "https://user:secret@git.example.com/octo/hello.git",
            "git@git.example.com:octo/hello.git",
            "ssh://git.example.com/octo/hello.git",
            "file:///tmp/hello.git",
            "/tmp/hello.git",
            "https://git.example.com/",
            "not a URL",
        )
        invalid.forEach { candidate ->
            try {
                ImportSource.parse(candidate, allowInsecureHttp = true)
                fail("accepted invalid source: $candidate")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun sourceRejectsQueryParametersWithoutPersistingOrEchoingSecrets() {
        val secret = "source-query-secret"
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportSource.parse("https://git.example.com/octo/hello.git?access_token=$secret")
        }
        assertFalse(error.message.orEmpty().contains(secret))
        assertThrows(IllegalArgumentException::class.java) {
            ImportSource.parse("https://git.example.com/octo/hello.git?ref=main")
        }
        val persisted = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(persisted.contains(secret))
    }

    @Test fun createUsesEmptyDestinationAndPersistsParsedDestinationImmediately() = runBlocking {
        val gateway = FakeImportGateway()
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val result = coordinator.create("octo", httpsSource, destination())
        assertEquals(ImportPhase.Cloning, result.phase)
        assertEquals(false, gateway.autoInitValues.single())
        assertEquals("octo", gateway.organizations.single())
        val persisted = store.get(result.id)
        assertNotNull(persisted)
        assertEquals("https://github.com/octo/imported.git", persisted!!.destination!!.cloneUrl)
        assertEquals("main", persisted.destination!!.defaultBranch)
        assertEquals(ImportPhase.Cloning, persisted.phase)
    }

    @Test fun createPersistsOnlyThePrivateSourceCredentialRequirement() = runBlocking {
        val result = RepositoryImportCoordinator(store, FakeImportGateway()).create(
            "octo",
            httpsSource,
            destination(),
            sourceRequiresCredentials = true,
        )

        assertTrue(result.sourceRequiresCredentials)
        val record = File(root, "tasks/${result.id}.json").readText()
        assertTrue(record.contains("\"sourceRequiresCredentials\":true"))
        assertFalse(record.contains("password"))
        assertFalse(record.contains("token"))
    }

    @Test fun personalCreationDoesNotUseOrganizationEndpoint() = runBlocking {
        val gateway = FakeImportGateway()
        RepositoryImportCoordinator(store, gateway).create(
            "octo", httpsSource, destination(owner = "octo", organization = null),
        )
        assertNull(gateway.organizations.single())
    }

    @Test fun malformedCreatedResponseBecomesCreationUnknownAndNeverFabricatesDestination() = runBlocking {
        val gateway = FakeImportGateway(createFailure = SerializationException("malformed 201"))
        val result = RepositoryImportCoordinator(store, gateway).create("octo", httpsSource, destination())
        assertEquals(ImportPhase.CreationUnknown, result.phase)
        assertNull(store.get(result.id)!!.destination)
        assertEquals(1, gateway.createCalls)
    }

    @Test fun lostCreateResponseBecomesCreationUnknown() = runBlocking {
        val gateway = FakeImportGateway(createFailure = IOException("connection closed after request"))
        val result = RepositoryImportCoordinator(store, gateway).create("octo", httpsSource, destination())
        assertEquals(ImportPhase.CreationUnknown, result.phase)
        assertNull(result.destination)
    }

    @Test fun cancellationAfterCreateRequestBecomesCreationUnknownAndResumeReconciles() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        var createCalls = 0
        var findCalls = 0
        val gateway = object : ImportRepositoryGateway {
            override suspend fun create(
                organization: String?,
                name: String,
                description: String,
                private: Boolean,
                autoInit: Boolean,
                token: String?,
            ): Repo {
                createCalls++
                requestStarted.complete(Unit)
                awaitCancellation()
            }

            override suspend fun find(owner: String, name: String, token: String?): Repo? {
                findCalls++
                return repo(size = 0, push = true)
            }

            override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = false
        }
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val job = launch { coordinator.create("octo", httpsSource, destination()) }
        withTimeout(5_000) { requestStarted.await() }

        job.cancelAndJoin()

        val interrupted = store.list("octo").single()
        assertEquals(ImportPhase.CreationUnknown, interrupted.phase)
        assertNull(interrupted.destination)
        val resumed = coordinator.resume(interrupted.id)
        assertEquals(ImportPhase.Cloning, resumed.phase)
        assertEquals(1, createCalls)
        assertEquals(1, findCalls)
    }

    @Test fun rejectedCreationIsFailedBeforeAnyDestinationExists() = runBlocking {
        val gateway = FakeImportGateway(createFailure = ApiException(422, "name already exists"))
        val result = RepositoryImportCoordinator(store, gateway).create("octo", httpsSource, destination())
        assertEquals(ImportPhase.Failed, result.phase)
        assertEquals("name already exists", result.message)
        assertNull(result.destination)
    }

    @Test fun retryOfUnknownCreationReconcilesBeforeCreatingWhenDestinationIsAbsent() = runBlocking {
        val gateway = FakeImportGateway(createFailure = IOException("lost response"))
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val created = coordinator.create("octo", httpsSource, destination())
        gateway.createFailure = null
        gateway.findResult = null
        val resumed = coordinator.resume(created.id)
        assertEquals(listOf("octo/imported"), gateway.findCalls)
        assertEquals(2, gateway.createCalls)
        assertEquals(ImportPhase.Cloning, resumed.phase)
    }

    @Test fun retryAdoptsOnlyMatchingWritableEmptyDestinationWithoutSecondPost() = runBlocking {
        val gateway = FakeImportGateway(createFailure = SerializationException("bad body"))
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val created = coordinator.create("octo", httpsSource, destination())
        gateway.findResult = repo(size = 0, push = true)
        val resumed = coordinator.resume(created.id)
        assertEquals(ImportPhase.Cloning, resumed.phase)
        assertEquals(1, gateway.createCalls)
        assertEquals(1, gateway.findCalls.size)
        assertEquals(repo(size = 0, push = true).cloneUrl, resumed.destination!!.cloneUrl)
    }

    @Test fun retryRefusesNonemptyReadOnlyOrMismatchedDestinations() = runBlocking {
        listOf(
            repo(size = 1, push = true),
            repo(size = 0, push = false),
            repo(size = 0, push = true, name = "other"),
            repo(size = 0, push = true, private = false),
        ).forEachIndexed { index, candidate ->
            val gateway = FakeImportGateway(createFailure = IOException("lost"), findResult = candidate)
            val coordinator = RepositoryImportCoordinator(store, gateway)
            val created = coordinator.create("octo", httpsSource, destination(name = "imported-$index"))
            val resumed = coordinator.resume(created.id)
            assertEquals(ImportPhase.CreationUnknown, resumed.phase)
            assertNull(resumed.destination)
            assertEquals(1, gateway.createCalls)
        }
    }

    @Test fun retryRefusesZeroSizedDestinationThatStillHasRefs() = runBlocking {
        val gateway = FakeImportGateway(
            createFailure = IOException("lost"),
            findResult = repo(size = 0, push = true),
            hasAnyRef = true,
        )
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val created = coordinator.create("octo", httpsSource, destination())

        val resumed = coordinator.resume(created.id)

        assertEquals(ImportPhase.CreationUnknown, resumed.phase)
        assertNull(resumed.destination)
        assertEquals(1, gateway.createCalls)
    }

    @Test fun reconciliationFailureKeepsCreationUnknownAndDoesNotBlindlyPost() = runBlocking {
        val gateway = FakeImportGateway(
            createFailure = IOException("lost"),
            findFailure = IOException("network unavailable"),
        )
        val coordinator = RepositoryImportCoordinator(store, gateway)
        val created = coordinator.create("octo", httpsSource, destination())
        val resumed = coordinator.resume(created.id)
        assertEquals(ImportPhase.CreationUnknown, resumed.phase)
        assertEquals(1, gateway.createCalls)
        assertEquals(1, gateway.findCalls.size)
    }

    @Test fun realMalformed201AndLostResponseBothEnterCreationUnknown() = runBlocking {
        serveOnce(201, "not-json", closeWithoutResponse = false) {
            val task = store.create("octo", httpsSource, destination())
            val result = RepositoryImportCoordinator(store).createDestination(task.id)
            assertEquals(ImportPhase.CreationUnknown, result.phase)
            assertNull(result.destination)
        }
        serveOnce(201, "", closeWithoutResponse = true) {
            val task = store.create("octo", httpsSource, destination(name = "lost"))
            val result = RepositoryImportCoordinator(store).createDestination(task.id)
            assertEquals(ImportPhase.CreationUnknown, result.phase)
            assertNull(result.destination)
        }
    }

    @Test fun realCreateRequestUsesAutoInitFalseAndCloneUrlFallback() = runBlocking {
        var body = ""
        serveOnce(201, repoJson(cloneUrl = null), closeWithoutResponse = false, captureBody = { body = it }) {
            val task = store.create("octo", httpsSource, destination())
            val result = RepositoryImportCoordinator(store).createDestination(task.id)
            assertEquals(ImportPhase.Cloning, result.phase)
            assertEquals("${Net.loginBase}/octo/imported.git", result.destination!!.cloneUrl)
        }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObjectOrThrow()
        assertFalse(json.getValue("auto_init").toString().toBoolean())
    }

    @Test fun explicitAuthorizationSurvivesCurrentSessionBinding() = runBlocking {
        val previousToken = Session.token
        var authorization: String? = null
        try {
            setToken("account-b-token")
            serveOnce(
                200,
                "{}",
                closeWithoutResponse = false,
                captureHeaders = { authorization = it["authorization"] },
            ) {
                Api.call(
                    "GET",
                    "/user",
                    headers = mapOf("Authorization" to "Bearer account-a-token"),
                )
            }
            assertEquals("Bearer account-a-token", authorization)
        } finally {
            setToken(previousToken)
        }
    }

    @Test fun defaultBranchFailureKeepsPartialStateAndRetryCompletes() = runBlocking {
        val updater = FakeDefaultBranchUpdater(IOException("patch unavailable"))
        val coordinator = RepositoryImportCoordinator(store, FakeImportGateway(), updater)
        val created = coordinator.create("octo", httpsSource, destination())

        val partial = coordinator.updateDefaultBranch(created.id, "trunk")

        assertEquals(ImportPhase.UpdatingDefaultBranch, partial.phase)
        assertEquals("main", partial.destination!!.defaultBranch)
        assertTrue(partial.message!!.contains("patch unavailable"))

        updater.failure = null
        val completed = coordinator.updateDefaultBranch(created.id, "trunk")

        assertEquals(ImportPhase.Completed, completed.phase)
        assertEquals("trunk", completed.destination!!.defaultBranch)
        assertEquals(listOf("octo/imported:trunk", "octo/imported:trunk"), updater.calls)
    }

    @Test fun detachedSourceHeadCompletesWithoutDefaultBranchPatch() = runBlocking {
        val updater = FakeDefaultBranchUpdater()
        val coordinator = RepositoryImportCoordinator(store, FakeImportGateway(), updater)
        val created = coordinator.create("octo", httpsSource, destination())

        val completed = coordinator.updateDefaultBranch(created.id, null)

        assertEquals(ImportPhase.Completed, completed.phase)
        assertTrue(updater.calls.isEmpty())
    }

    private fun destination(
        owner: String = "octo",
        name: String = "imported",
        organization: String? = "octo",
    ) = ImportDestinationRequest(owner, name, "description", isPrivate = true, organization = organization)

    private fun repo(
        size: Long,
        push: Boolean,
        name: String = "imported",
        private: Boolean = true,
    ) = Repo(
        name = name,
        fullName = "octo/$name",
        owner = User(login = "octo"),
        isPrivate = private,
        size = size,
        permissions = Permissions(push = push),
        cloneUrl = "https://github.com/octo/$name.git",
        defaultBranch = "main",
    )

    private fun repoJson(cloneUrl: String?) = """{"name":"imported","full_name":"octo/imported","owner":{"login":"octo"},"private":true,"size":0,"permissions":{"push":true},"default_branch":"main"${cloneUrl?.let { ""","clone_url":"$it"""" } ?: ""}}"""

    private fun setToken(value: String?) {
        Session::class.java.getDeclaredField("token").apply {
            isAccessible = true
            set(null, value)
        }
    }

    private suspend fun serveOnce(
        code: Int,
        response: String,
        closeWithoutResponse: Boolean,
        captureHeaders: (Map<String, String>) -> Unit = {},
        captureBody: (String) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(start = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                input.readLine()
                val headers = generateSequence { input.readLine().takeUnless { it.isNullOrEmpty() } }
                    .map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }.toMap()
                val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                var offset = 0
                while (offset < chars.size) {
                    val count = input.read(chars, offset, chars.size - offset)
                    if (count < 0) break
                    offset += count
                }
                captureHeaders(headers)
                captureBody(String(chars))
                if (!closeWithoutResponse) {
                    val bytes = response.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        Api.cacheDir = File(root, "api-cache-${System.nanoTime()}").apply { mkdirs() }
        Net.apiProxy = "http://127.0.0.1:${server.localPort}"
        try {
            block()
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    private class FakeDefaultBranchUpdater(
        var failure: Exception? = null,
    ) : ImportDefaultBranchUpdater {
        val calls = mutableListOf<String>()

        override suspend fun update(owner: String, name: String, branch: String, token: String?) {
            calls += "$owner/$name:$branch"
            failure?.let { throw it }
        }
    }

    private class FakeImportGateway(
        var createFailure: Exception? = null,
        var findResult: Repo? = null,
        var findFailure: Exception? = null,
        var hasAnyRef: Boolean = false,
    ) : ImportRepositoryGateway {
        var createCalls = 0
        val autoInitValues = mutableListOf<Boolean>()
        val organizations = mutableListOf<String?>()
        val findCalls = mutableListOf<String>()

        override suspend fun create(
            organization: String?,
            name: String,
            description: String,
            private: Boolean,
            autoInit: Boolean,
            token: String?,
        ): Repo {
            createCalls++
            organizations += organization
            autoInitValues += autoInit
            createFailure?.let { throw it }
            return Repo(
                name = name,
                fullName = "octo/$name",
                owner = User(login = "octo"),
                isPrivate = private,
                size = 0,
                permissions = Permissions(push = true),
                cloneUrl = "https://github.com/octo/$name.git",
            )
        }

        override suspend fun find(owner: String, name: String, token: String?): Repo? {
            findCalls += "$owner/$name"
            findFailure?.let { throw it }
            return findResult
        }

        override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = hasAnyRef
    }

    private companion object {
        val httpsSource = ImportSource.parse("https://git.example.com/octo/source.git")
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrThrow() =
    this as kotlinx.serialization.json.JsonObject

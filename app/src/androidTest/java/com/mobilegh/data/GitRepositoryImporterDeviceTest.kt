package com.mobilegh.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UploadPack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitRepositoryImporterDeviceTest {
    private lateinit var root: File
    private val servers = mutableListOf<SmartGitServer>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "git-import-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        servers.forEach(SmartGitServer::close)
        root.deleteRecursively()
    }

    @Test
    fun smartHttpMigrationKeepsSourceAndDestinationCredentialsSeparate() = runBlocking {
        val source = createSourceRepository()
        val destination = Git.init().setBare(true).setDirectory(File(root, "destination.git")).call()
        val sourceServer = SmartGitServer(source.repository, "source-user", "source-password").start()
        val destinationServer = SmartGitServer(destination.repository, "destination-user", "destination-password").start()
        servers += sourceServer
        servers += destinationServer
        val task = ImportTask(
            id = "device-http",
            accountLogin = "octo",
            sourceUrl = sourceServer.url("127.0.0.1"),
            owner = "octo",
            name = "imported",
            isPrivate = false,
            destination = ImportDestination(
                owner = "octo",
                name = "imported",
                cloneUrl = destinationServer.url("127.0.0.2"),
                defaultBranch = "main",
            ),
            phase = ImportPhase.Cloning,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val result = GitRepositoryImporter().cloneAndPush(
            task = task,
            sourceCredentials = ImportCredentialsProvider("source-user", "source-password"),
            destinationCredentials = ImportCredentialsProvider("destination-user", "destination-password"),
            workspace = File(root, "workspace").apply { mkdirs() },
            progress = NullProgressMonitor.INSTANCE,
        )

        assertTrue(result.isComplete)
        assertEquals("trunk", result.sourceDefaultBranch)
        assertEquals(source.trunkCommit, destination.repository.resolve("refs/heads/trunk")?.name())
        assertEquals(source.featureCommit, destination.repository.resolve("refs/heads/feature")?.name())
        assertEquals(source.trunkCommit, destination.repository.resolve("refs/tags/latest")?.name())
        assertTrue(destination.repository.refDatabase.getRefsByPrefix("refs/pull").isEmpty())
        assertFalse(File(root, "workspace/repository").exists())
        assertNull(sourceServer.failure.get())
        assertNull(destinationServer.failure.get())

        val sourceAuthorization = basicAuthorization("source-user", "source-password")
        val destinationAuthorization = basicAuthorization("destination-user", "destination-password")
        assertTrue(sourceServer.authorizations.isNotEmpty())
        assertTrue(destinationServer.authorizations.isNotEmpty())
        assertTrue(sourceServer.authorizations.all { it == sourceAuthorization })
        assertTrue(destinationServer.authorizations.all { it == destinationAuthorization })
        assertFalse(sourceServer.authorizations.contains(destinationAuthorization))
        assertFalse(destinationServer.authorizations.contains(sourceAuthorization))
        source.close()
        destination.close()
    }

    @Test
    fun crossOriginRedirectsDoNotForwardAuthorization() = runBlocking {
        val source = createSourceRepository()
        val destination = Git.init().setBare(true).setDirectory(File(root, "destination.git")).call()
        val redirected = SmartGitServer(
            source.repository,
            "unused",
            "unused",
            requireAuthentication = false,
        ).start()
        val origin = SmartGitServer(
            source.repository,
            "source-user",
            "source-password",
            redirectBase = redirected.baseUrl("127.0.0.1"),
        ).start()
        val redirectedDestination = SmartGitServer(
            destination.repository,
            "unused",
            "unused",
            requireAuthentication = false,
        ).start()
        val destinationOrigin = SmartGitServer(
            destination.repository,
            "destination-user",
            "destination-password",
            redirectBase = redirectedDestination.baseUrl("127.0.0.1"),
        ).start()
        servers += redirected
        servers += origin
        servers += redirectedDestination
        servers += destinationOrigin
        val task = ImportTask(
            id = "device-redirect",
            accountLogin = "octo",
            sourceUrl = origin.url("127.0.0.1"),
            owner = "octo",
            name = "imported",
            isPrivate = false,
            destination = ImportDestination(
                owner = "octo",
                name = "imported",
                cloneUrl = destinationOrigin.url("127.0.0.1"),
                defaultBranch = "main",
            ),
            phase = ImportPhase.Cloning,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val result = GitRepositoryImporter().cloneAndPush(
            task = task,
            sourceCredentials = ImportCredentialsProvider("source-user", "source-password"),
            destinationCredentials = ImportCredentialsProvider("destination-user", "destination-password"),
            workspace = File(root, "workspace").apply { mkdirs() },
            progress = NullProgressMonitor.INSTANCE,
        )

        val sourceAuthorization = basicAuthorization("source-user", "source-password")
        val destinationAuthorization = basicAuthorization("destination-user", "destination-password")
        assertTrue(result.isComplete)
        assertTrue(origin.authorizations.contains(sourceAuthorization))
        assertTrue(redirected.receivedAuthorizations.isNotEmpty())
        assertFalse(redirected.receivedAuthorizations.contains(sourceAuthorization))
        assertTrue(destinationOrigin.authorizations.contains(destinationAuthorization))
        assertTrue(redirectedDestination.receivedAuthorizations.isNotEmpty())
        assertFalse(redirectedDestination.receivedAuthorizations.contains(destinationAuthorization))
        assertNull(origin.failure.get())
        assertNull(redirected.failure.get())
        assertNull(destinationOrigin.failure.get())
        assertNull(redirectedDestination.failure.get())
        source.close()
        destination.close()
    }

    private fun createSourceRepository(): SourceFixture {
        val source = Git.init().setBare(true).setDirectory(File(root, "source.git")).call()
        val work = Git.init()
            .setDirectory(File(root, "work"))
            .setInitialBranch("trunk")
            .call()
        val identity = PersonIdent("Import Device Test", "import@example.com")
        File(root, "work/history.txt").writeText("first\n")
        work.add().addFilepattern("history.txt").call()
        val first = work.commit().setMessage("first").setAuthor(identity).setCommitter(identity).call()
        work.tag().setName("v1.0").setMessage("annotated release").setObjectId(first).call()
        File(root, "work/history.txt").appendText("second\n")
        work.add().addFilepattern("history.txt").call()
        val trunk = work.commit().setMessage("second").setAuthor(identity).setCommitter(identity).call()
        work.tag().setName("latest").setAnnotated(false).setObjectId(trunk).call()
        work.checkout().setCreateBranch(true).setName("feature").call()
        File(root, "work/feature.txt").writeText("feature\n")
        work.add().addFilepattern("feature.txt").call()
        val feature = work.commit().setMessage("feature").setAuthor(identity).setCommitter(identity).call()
        work.checkout().setName("trunk").call()
        Git(work.repository).push()
            .setRemote(source.repository.directory.absolutePath)
            .setRefSpecs(
                listOf(
                    RefSpec("refs/heads/trunk:refs/heads/trunk"),
                    RefSpec("refs/heads/feature:refs/heads/feature"),
                    RefSpec("refs/tags/v1.0:refs/tags/v1.0"),
                    RefSpec("refs/tags/latest:refs/tags/latest"),
                    RefSpec("refs/heads/trunk:refs/pull/1/head"),
                ),
            )
            .call()
        source.repository.updateRef(Constants.HEAD).link("refs/heads/trunk")
        work.close()
        return SourceFixture(source, trunk.name, feature.name)
    }

    private data class SourceFixture(
        val source: Git,
        val trunkCommit: String,
        val featureCommit: String,
    ) {
        val repository: Repository
            get() = source.repository

        fun close() = source.close()
    }

    private class SmartGitServer(
        private val repository: Repository,
        private val username: String,
        private val password: String,
        private val requireAuthentication: Boolean = true,
        private val redirectBase: String? = null,
    ) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        private val requests = Collections.synchronizedList(mutableListOf<String?>())
        private var running = true
        val failure = AtomicReference<Throwable?>()
        val authorizations: List<String>
            get() = synchronized(requests) { requests.filterNotNull().toList() }
        val receivedAuthorizations: List<String?>
            get() = synchronized(requests) { requests.toList() }

        fun start(): SmartGitServer = apply {
            thread(name = "smart-git-${socket.localPort}", isDaemon = true) {
                while (running) {
                    try {
                        socket.accept().use(::handle)
                    } catch (closed: IOException) {
                        if (running) {
                            failure.compareAndSet(null, closed)
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
        }

        fun baseUrl(host: String) = "http://$host:${socket.localPort}"

        fun url(host: String) = "${baseUrl(host)}/repository.git"

        fun close() {
            running = false
            socket.close()
        }

        private fun handle(connection: Socket) {
            connection.soTimeout = 30_000
            val input = connection.getInputStream()
            val requestLine = readLine(input)
            val headers = generateSequence { readLine(input).takeUnless(String::isEmpty) }
                .map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                .toMap()
            requests += headers["authorization"]
            val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
            val expected = basicAuthorization(username, password)
            if (requireAuthentication && headers["authorization"] != expected) {
                respond(
                    connection,
                    401,
                    "text/plain",
                    ByteArray(0),
                    mapOf("WWW-Authenticate" to "Basic realm=\"git\""),
                )
                return
            }
            if (redirectBase != null) {
                respond(
                    connection,
                    302,
                    "text/plain",
                    ByteArray(0),
                    mapOf("Location" to "$redirectBase$target"),
                )
                return
            }
            if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                connection.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                connection.getOutputStream().flush()
            }
            val body = readBody(input, headers)
            val service = if (method == "GET") {
                target.substringAfter("service=", "")
            } else {
                when (target) {
                    "/repository.git/git-upload-pack" -> "git-upload-pack"
                    "/repository.git/git-receive-pack" -> "git-receive-pack"
                    else -> throw IOException("Unsupported Git HTTP path: $target")
                }
            }
            val output = when {
                method == "GET" && target.startsWith("/repository.git/info/refs") -> advertise(service)
                method == "POST" -> service(service, body)
                else -> throw IOException("Unsupported Git HTTP request: $requestLine")
            }
            respond(connection, 200, "application/x-$service${if (method == "GET") "-advertisement" else "-result"}", output)
        }

        private fun advertise(service: String): ByteArray {
            val output = ByteArrayOutputStream()
            val packets = PacketLineOut(output)
            packets.writeString("# service=$service\n")
            packets.end()
            val advertiser = PacketLineOutRefAdvertiser(packets)
            when (service) {
                "git-upload-pack" -> UploadPack(repository).use { it.sendAdvertisedRefs(advertiser) }
                "git-receive-pack" -> ReceivePack(repository).sendAdvertisedRefs(advertiser)
                else -> throw IOException("Unsupported Git service: $service")
            }
            packets.flush()
            return output.toByteArray()
        }

        private fun service(service: String, body: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            val input = ByteArrayInputStream(body)
            when (service) {
                "git-upload-pack" -> UploadPack(repository).use {
                    it.setBiDirectionalPipe(false)
                    it.upload(input, output, null)
                }
                "git-receive-pack" -> {
                    val receive = ReceivePack(repository)
                    receive.setBiDirectionalPipe(false)
                    receive.receive(input, output, null)
                }
                else -> throw IOException("Unsupported Git service: $service")
            }
            return output.toByteArray()
        }

        private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
            val encoded = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                val output = ByteArrayOutputStream()
                while (true) {
                    val size = readLine(input).substringBefore(';').toInt(16)
                    if (size == 0) {
                        while (readLine(input).isNotEmpty()) Unit
                        break
                    }
                    output.write(input.readNBytesExact(size))
                    if (input.read() != '\r'.code || input.read() != '\n'.code) {
                        throw IOException("Malformed chunked request")
                    }
                }
                output.toByteArray()
            } else {
                input.readNBytesExact(headers["content-length"]?.toInt() ?: 0)
            }
            return if (headers["content-encoding"]?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(ByteArrayInputStream(encoded)).use { it.readBytes() }
            } else {
                encoded
            }
        }

        private fun respond(
            connection: Socket,
            status: Int,
            contentType: String,
            body: ByteArray,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            val output = connection.getOutputStream()
            val headers = StringBuilder("HTTP/1.1 $status Test\r\n")
                .append("Content-Type: $contentType\r\n")
                .append("Content-Length: ${body.size}\r\n")
                .append("Connection: close\r\n")
            extraHeaders.forEach { (name, value) -> headers.append("$name: $value\r\n") }
            output.write(headers.append("\r\n").toString().toByteArray(StandardCharsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                when (val value = input.read()) {
                    -1 -> throw EOFException("Unexpected end of HTTP request")
                    '\n'.code -> return bytes.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r')
                    else -> bytes.write(value)
                }
            }
        }

        private fun InputStream.readNBytesExact(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = read(result, offset, size - offset)
                if (count < 0) throw EOFException("Unexpected end of HTTP body")
                offset += count
            }
            return result
        }
    }

    private companion object {
        fun basicAuthorization(username: String, password: String): String {
            val encoded = Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray(StandardCharsets.UTF_8),
            )
            return "Basic $encoded"
        }
    }
}

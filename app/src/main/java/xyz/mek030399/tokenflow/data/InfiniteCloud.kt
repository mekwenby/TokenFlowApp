package xyz.mek030399.tokenflow.data

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.net.URI
import java.net.InetAddress
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.mek030399.tokenflow.R

data class CloudServerDraft(
    val profile: CloudServerProfile = CloudServerProfile(),
    val privateKey: String = "",
    val passphrase: String = "",
)

data class CloudConnectionProbe(
    val hostKeyAlgorithm: String,
    val hostKeyBase64: String,
    val fingerprint: String,
    val trusted: Boolean,
    val helperVersion: Int? = null,
    val pythonVersion: String? = null,
    val nodeAvailable: Boolean? = null,
    val host: String = "",
    val port: Int = 0,
)

data class McpDiscoveredTool(
    val mcpServerId: String,
    val mcpServerName: String,
    val tool: Tool,
)

data class McpDiscoveryResult(
    val tools: List<McpDiscoveredTool>,
    val warnings: List<String>,
)

private data class McpDiscoveryAttempt(
    val server: CloudMcpServer,
    val tools: List<Tool> = emptyList(),
    val warning: String? = null,
)

data class CloudMcpToolResult(val content: String, val ok: Boolean)
data class CloudMcpArtifact(val serverId: String, val file: File, val name: String, val mimeType: String)
data class CloudExecutionResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val artifactPaths: List<String>,
)
data class CloudAttachmentUpload(val attachmentId: String, val displayName: String, val storedPath: String)
internal const val CLOUD_MCP_STDIO_COMMAND = "python3 ~/.tokenflow/infinite-cloud/helper.py _mcp_stdio"
internal const val CLOUD_HELPER_HASH_COMMAND =
    "python3 -c 'import hashlib,pathlib;p=pathlib.Path.home()/\".tokenflow/infinite-cloud/helper.py\";print(hashlib.sha256(p.read_bytes()).hexdigest())'"
internal const val CLOUD_HELPER_PREPARE_COMMAND =
    "python3 -c 'import pathlib;p=pathlib.Path.home()/\".tokenflow/infinite-cloud\";(p/\"tasks\").mkdir(parents=True,exist_ok=True);p.chmod(0o700)'"
internal const val CLOUD_HELPER_INSTALL_COMMAND =
    "python3 -c 'import hashlib,json,os,pathlib,sys;c=json.load(sys.stdin);r=pathlib.Path.home()/\".tokenflow/infinite-cloud\";n=c.get(\"temporary_name\");isinstance(n,str) and n.startswith(\".helper.py.\") and n.endswith(\".tmp\") and \"/\" not in n or sys.exit(\"invalid helper temporary name\");p=r/n;hashlib.sha256(p.read_bytes()).hexdigest()==c.get(\"sha256\") or sys.exit(\"helper integrity check failed\");os.chmod(p,0o700);os.replace(p,r/\"helper.py\")'"
internal const val CLOUD_DEFAULT_HELPER_TIMEOUT_MS = 30_000L
internal const val CLOUD_CANCEL_HELPER_TIMEOUT_MS = 20_000L
internal const val CLOUD_MAX_EXECUTE_HELPER_TIMEOUT_MS = 135_000L
@Serializable
data class CloudResponseArtifact(val serverId: String, val path: String)

class CloudMcpConnection internal constructor(
    val server: CloudMcpServer,
    internal val client: Client,
    private val session: Session,
    private val channel: ChannelExec?,
    private val localPort: Int?,
    private val httpClient: HttpClient?,
) {
    suspend fun tools(): List<Tool> = client.listTools().tools

    suspend fun call(name: String, arguments: Map<String, Any?>) = client.callTool(name, arguments)

    suspend fun close() {
        withContext(NonCancellable) {
            runCatching { withTimeout(MCP_CLOSE_TIMEOUT_MS) { client.close() } }
            channel?.disconnect()
            localPort?.let { runCatching { session.delPortForwardingL(it) } }
            httpClient?.close()
            session.disconnect()
        }
    }

    private companion object {
        const val MCP_CLOSE_TIMEOUT_MS = 5_000L
    }
}

@Serializable
data class CloudFileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

class InfiniteCloudException(
    message: String,
    cause: Throwable? = null,
    internal val helperErrorCode: String? = null,
) : Exception(message, cause)

internal class BoundedOutputStream(private val limit: Int) : OutputStream() {
    private val output = ByteArrayOutputStream(limit.coerceAtMost(8_192))
    @Volatile
    var overflowed: Boolean = false
        private set

    @Synchronized
    override fun write(value: Int) {
        if (output.size() < limit) output.write(value) else overflowed = true
    }

    @Synchronized
    override fun write(value: ByteArray, offset: Int, length: Int) {
        val remaining = (limit - output.size()).coerceAtLeast(0)
        if (remaining > 0) output.write(value, offset, length.coerceAtMost(remaining))
        if (length > remaining) overflowed = true
    }

    @Synchronized
    fun text(): String = output.toByteArray().decodeToString()
}

internal class TailOutputStream(private val limit: Int) : OutputStream() {
    private val tail = ByteArray(limit)
    private var size = 0
    private var cursor = 0

    @Synchronized
    override fun write(value: Int) {
        tail[cursor] = value.toByte()
        cursor = (cursor + 1) % limit
        if (size < limit) size += 1
    }

    @Synchronized
    override fun write(value: ByteArray, offset: Int, length: Int) {
        if (length >= limit) {
            value.copyInto(tail, startIndex = offset + length - limit, endIndex = offset + length)
            size = limit
            cursor = 0
            return
        }
        val firstPart = minOf(length, limit - cursor)
        value.copyInto(tail, destinationOffset = cursor, startIndex = offset, endIndex = offset + firstPart)
        val secondPart = length - firstPart
        if (secondPart > 0) {
            value.copyInto(tail, startIndex = offset + firstPart, endIndex = offset + length)
        }
        cursor = (cursor + length) % limit
        size = minOf(limit, size + length)
    }

    @Synchronized
    fun text(): String {
        val bytes = ByteArray(size)
        val start = if (size == limit) cursor else 0
        for (index in 0 until size) bytes[index] = tail[(start + index) % limit]
        return bytes.decodeToString().takeLast(MAX_ERROR_CHARS_FOR_STREAM)
    }

    private companion object {
        const val MAX_ERROR_CHARS_FOR_STREAM = 2_000
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream(limit.coerceAtMost(64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw InfiniteCloudException("Remote response exceeds ${limit / (1024 * 1024)} MiB")
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

class InfiniteCloudManager(
    context: Context,
    private val dao: LocalDao,
    private val secrets: SecretStore,
    private val json: Json = ConfigArchiveCodec.defaultJson,
) {
    private val appContext = context.applicationContext
    private val helperBytes by lazy { appContext.resources.openRawResource(R.raw.infinite_cloud_helper).use { it.readBytes() } }
    private val helperInstallLocks = ConcurrentHashMap<String, Mutex>()
    private val taskLocks = ConcurrentHashMap<String, Mutex>()
    suspend fun servers(): List<CloudServerProfile> = dao.cloudServers().map { entity ->
        entity.toDomain(secrets.read(secrets.cloudPrivateKeyName(entity.id)) != null)
    }

    suspend fun server(id: String): CloudServerProfile? = dao.cloudServer(id)?.let { entity ->
        entity.toDomain(secrets.read(secrets.cloudPrivateKeyName(entity.id)) != null)
    }

    suspend fun saveServer(draft: CloudServerDraft): CloudServerProfile {
        var profile = draft.profile.normalized()
        val previous = dao.cloudServer(profile.id)
        if (previous != null && (previous.host != profile.host || previous.port != profile.port)) {
            profile = profile.copy(
                hostKeyAlgorithm = null,
                hostKeyBase64 = null,
                hostKeyFingerprint = null,
            )
        }
        val key = draft.privateKey.ifBlank { secrets.read(secrets.cloudPrivateKeyName(profile.id)).orEmpty() }
        val passphrase = if (draft.privateKey.isNotBlank()) draft.passphrase else draft.passphrase.ifBlank {
            secrets.read(secrets.cloudPrivateKeyPassphraseName(profile.id)).orEmpty()
        }
        validatePrivateKey(key, passphrase)
        val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
        val stored = profile.copy(
            keyConfigured = true,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        val secretSnapshot = secrets.replaceWithSnapshot(
            updates = mapOf(
                secrets.cloudPrivateKeyName(profile.id) to key,
                secrets.cloudPrivateKeyPassphraseName(profile.id) to passphrase.ifBlank { null },
            ),
        )
        try {
            dao.putCloudServer(stored.toEntity())
        } catch (error: Throwable) {
            secrets.restore(secretSnapshot)
            throw error
        }
        return stored
    }

    suspend fun deleteServer(id: String) {
        requireSafeCloudConfigId(id, "Cloud server ID")
        requireNotNull(dao.cloudServer(id)) { "Cloud server not found" }
        val mcpIds = dao.cloudMcpServers(id).map(CloudMcpServerEntity::id)
        mcpIds.forEach { requireSafeCloudConfigId(it, "MCP server ID") }
        val snapshot = secrets.replaceWithSnapshot(
            clearNames = setOf(
                secrets.cloudPrivateKeyName(id),
                secrets.cloudPrivateKeyPassphraseName(id),
            ),
            clearPrefixes = mcpIds.flatMap { mcpId ->
                listOf(secrets.cloudMcpEnvironmentPrefix(mcpId), secrets.cloudMcpHeaderPrefix(mcpId))
            }.toSet(),
        )
        try {
            require(dao.deleteCloudServerIfInactive(id)) {
                "Unknown, running, or queued cloud tasks must be cancelled before deleting the server"
            }
        } catch (failure: Throwable) {
            secrets.restore(snapshot)
            throw failure
        }
    }

    suspend fun probe(profile: CloudServerProfile): CloudConnectionProbe = withContext(Dispatchers.IO) {
        val repository = PinningHostKeyRepository(profile)
        val session = baseSession(profile, repository, withIdentity = profile.hostKeyBase64 != null)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            ensureHelper(profile, session)
            val result = helperRequest(session, buildJsonObject { put("op", "probe") })
            CloudConnectionProbe(
                hostKeyAlgorithm = repository.algorithm.orEmpty(),
                hostKeyBase64 = repository.keyBase64.orEmpty(),
                fingerprint = repository.fingerprint.orEmpty(),
                trusted = true,
                helperVersion = result["version"]?.jsonPrimitive?.intOrNull,
                pythonVersion = result["python"]?.jsonPrimitive?.contentOrNull,
                nodeAvailable = result["node"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                host = profile.host,
                port = profile.port,
            )
        } catch (error: JSchException) {
            if (repository.keyBase64 != null && profile.hostKeyBase64 == null) {
                CloudConnectionProbe(
                    repository.algorithm.orEmpty(), repository.keyBase64.orEmpty(), repository.fingerprint.orEmpty(), false,
                    host = profile.host, port = profile.port,
                )
            } else throw InfiniteCloudException(safeSshMessage(error), error)
        } finally {
            session.disconnect()
        }
    }

    suspend fun trustHostKey(profileId: String, probe: CloudConnectionProbe): CloudServerProfile {
        requireSafeCloudConfigId(profileId, "Cloud server ID")
        require(!probe.trusted && probe.hostKeyBase64.isNotBlank())
        validatePinnedHostKey(probe.hostKeyAlgorithm, probe.hostKeyBase64, probe.fingerprint)
        val current = requireNotNull(server(profileId)) { "Cloud server not found" }
        require(current.hostKeyBase64 == null) { "SSH host key is already trusted" }
        require(probe.host == current.host && probe.port == current.port) {
            "SSH server address changed while awaiting host key confirmation"
        }
        val trusted = current.copy(
            hostKeyAlgorithm = probe.hostKeyAlgorithm,
            hostKeyBase64 = probe.hostKeyBase64,
            hostKeyFingerprint = probe.fingerprint,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L),
        )
        dao.putCloudServer(trusted.toEntity())
        return trusted
    }

    suspend fun probeHostKeyReplacement(profileId: String): CloudConnectionProbe {
        requireSafeCloudConfigId(profileId, "Cloud server ID")
        val current = requireNotNull(server(profileId)) { "Cloud server not found" }
        require(current.hostKeyBase64 != null) { "SSH host key is not currently pinned" }
        return probe(current.copy(hostKeyAlgorithm = null, hostKeyBase64 = null, hostKeyFingerprint = null))
    }

    suspend fun replaceHostKey(
        profileId: String,
        expectedHostKeyBase64: String,
        probe: CloudConnectionProbe,
    ): CloudServerProfile {
        requireSafeCloudConfigId(profileId, "Cloud server ID")
        require(
            !probe.trusted && probe.hostKeyAlgorithm.isNotBlank() &&
                probe.hostKeyBase64.isNotBlank() && probe.fingerprint.isNotBlank(),
        ) { "Replacement host key is unavailable" }
        validatePinnedHostKey(probe.hostKeyAlgorithm, probe.hostKeyBase64, probe.fingerprint)
        require(probe.hostKeyBase64 != expectedHostKeyBase64) { "SSH host key has not changed" }
        val current = requireNotNull(server(profileId)) { "Cloud server not found" }
        require(probe.host == current.host && probe.port == current.port) {
            "SSH server address changed while awaiting host key confirmation"
        }
        require(current.hostKeyBase64 == expectedHostKeyBase64) { "SSH host key changed while awaiting confirmation" }
        val updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L)
        require(
            dao.replaceCloudHostKey(
                id = profileId,
                expectedKeyBase64 = expectedHostKeyBase64,
                algorithm = probe.hostKeyAlgorithm,
                keyBase64 = probe.hostKeyBase64,
                fingerprint = probe.fingerprint,
                updatedAt = updatedAt,
            ) == 1,
        ) { "SSH host key changed while awaiting confirmation" }
        return current.copy(
            hostKeyAlgorithm = probe.hostKeyAlgorithm,
            hostKeyBase64 = probe.hostKeyBase64,
            hostKeyFingerprint = probe.fingerprint,
            updatedAt = updatedAt,
        )
    }

    suspend fun mcpServers(serverId: String): List<CloudMcpServer> = dao.cloudMcpServers(serverId).map { entity ->
        val value = entity.toDomain(false)
        value.copy(secretsConfigured = value.environmentNames.all {
            secrets.read(secrets.cloudMcpEnvironmentName(value.id, it)) != null
        } && value.headerNames.all {
            secrets.read(secrets.cloudMcpHeaderName(value.id, it)) != null
        })
    }

    suspend fun saveMcpServer(
        value: CloudMcpServer,
        environment: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): CloudMcpServer {
        requireSafeCloudConfigId(value.id, "MCP server ID")
        requireSafeCloudConfigId(value.cloudServerId, "MCP cloud server ID")
        requireNotNull(dao.cloudServer(value.cloudServerId)) { "Cloud server not found" }
        require(value.name.isNotBlank()) { "MCP server name is required" }
        val environmentNames = value.environmentNames.distinct()
        val headerNames = value.headerNames.distinct()
        require(environmentNames.size == value.environmentNames.size && environmentNames.none(String::isBlank)) {
            "MCP environment variable names must be unique and non-empty"
        }
        require(headerNames.size == value.headerNames.size && headerNames.none(String::isBlank)) {
            "MCP header names must be unique and non-empty"
        }
        require(environment.keys.all { it in environmentNames } && headers.keys.all { it in headerNames }) {
            "MCP secret names must be declared in the configuration"
        }
        when (value.transport) {
            CloudMcpTransport.STDIO -> require(value.command.isNotBlank()) { "MCP command is required" }
            CloudMcpTransport.STREAMABLE_HTTP -> {
                val uri = runCatching { URI(value.url) }.getOrNull()
                require(
                    uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri?.userInfo == null,
                ) { "Valid MCP HTTP URL without embedded credentials is required" }
            }
        }
        val normalizedName = value.name.trim()
        require(dao.cloudMcpServers(value.cloudServerId).none { it.id != value.id && it.name == normalizedName }) {
            "MCP server name already exists for this cloud server"
        }
        val existing = dao.cloudMcpServers().firstOrNull { it.id == value.id }
        val secretUpdates = buildMap<String, String?> {
            environmentNames.forEach { name ->
                put(
                    secrets.cloudMcpEnvironmentName(value.id, name),
                    environment[name] ?: secrets.read(secrets.cloudMcpEnvironmentName(value.id, name)),
                )
            }
            headerNames.forEach { name ->
                put(
                    secrets.cloudMcpHeaderName(value.id, name),
                    headers[name] ?: secrets.read(secrets.cloudMcpHeaderName(value.id, name)),
                )
            }
        }
        val secretSnapshot = secrets.replaceWithSnapshot(
            clearPrefixes = setOf(
                secrets.cloudMcpEnvironmentPrefix(value.id),
                secrets.cloudMcpHeaderPrefix(value.id),
            ),
            updates = secretUpdates,
        )
        val now = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0L) + 1L)
        val stored = value.copy(
            name = normalizedName,
            environmentNames = environmentNames,
            headerNames = headerNames,
            secretsConfigured = secretUpdates.values.none { it == null },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        try {
            dao.putCloudMcpServer(stored.toEntity())
        } catch (failure: Throwable) {
            secrets.restore(secretSnapshot)
            throw failure
        }
        return stored
    }

    suspend fun deleteMcpServer(id: String) {
        requireSafeCloudConfigId(id, "MCP server ID")
        val entity = dao.cloudMcpServers().firstOrNull { it.id == id } ?: return
        val snapshot = secrets.replaceWithSnapshot(
            clearPrefixes = setOf(
                secrets.cloudMcpEnvironmentPrefix(id),
                secrets.cloudMcpHeaderPrefix(id),
            ),
        )
        try {
            dao.deleteCloudMcpServer(id)
        } catch (failure: Throwable) {
            secrets.restore(snapshot)
            throw failure
        }
    }

    suspend fun discoverMcpTools(serverId: String): List<McpDiscoveredTool> {
        return discoverMcpToolsWithWarnings(serverId).tools
    }

    suspend fun discoverMcpToolsWithWarnings(serverId: String): McpDiscoveryResult {
        val profile = requireServer(serverId)
        val tools = mutableListOf<McpDiscoveredTool>()
        val warnings = mutableListOf<String>()
        val semaphore = Semaphore(MAX_CONCURRENT_MCP_INITIALIZATIONS)
        val attempts = coroutineScope {
            mcpServers(serverId).map { mcp ->
                async {
                    semaphore.withPermit {
                        try {
                            McpDiscoveryAttempt(
                                server = mcp,
                                tools = withMcpClient(profile, mcp, MCP_DISCOVERY_TIMEOUT_MS) { client ->
                                    client.listTools().tools
                                },
                            )
                        } catch (timeout: TimeoutCancellationException) {
                            McpDiscoveryAttempt(
                                server = mcp,
                                warning = "MCP ${mcp.name} is unavailable: operation timed out",
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            McpDiscoveryAttempt(
                                server = mcp,
                                warning = "MCP ${mcp.name} is unavailable: ${safeSshMessage(error)}",
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        attempts.forEach { attempt ->
            tools += attempt.tools.map { tool -> McpDiscoveredTool(attempt.server.id, attempt.server.name, tool) }
            attempt.warning?.let(warnings::add)
        }
        return McpDiscoveryResult(tools, warnings)
    }

    suspend fun callMcpTool(
        serverId: String,
        mcpServerId: String,
        name: String,
        arguments: JsonObject,
        requestId: String,
        messageId: String,
    ): CloudMcpToolResult {
        val profile = requireServer(serverId)
        val mcp = mcpServers(serverId).firstOrNull { it.id == mcpServerId }
            ?: throw InfiniteCloudException("MCP server not found")
        return withMcpClient(profile, mcp, MCP_CALL_TIMEOUT_MS) { client ->
            val result = client.callTool(name, arguments.mapValues { (_, value) -> value.toKotlinValue() })
            CloudMcpToolResult(sanitizeMcpResult(result, requestId, messageId, serverId), result.isError != true)
        }
    }

    private suspend fun sanitizeMcpResult(
        result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult,
        requestId: String,
        messageId: String,
        serverId: String,
    ): String {
        val encoded = json.encodeToJsonElement(result).jsonObject
        val sanitizedContent = encoded["content"]?.jsonArray?.mapIndexed { index, element ->
            val block = element.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull
            val directData = if (type == "image" || type == "audio") block["data"]?.jsonPrimitive?.contentOrNull else null
            val resource = if (type == "resource") block["resource"]?.jsonObject else null
            val resourceData = resource?.get("blob")?.jsonPrimitive?.contentOrNull
            val data = directData ?: resourceData ?: return@mapIndexed element
            val mimeType = block["mimeType"]?.jsonPrimitive?.contentOrNull
                ?: resource?.get("mimeType")?.jsonPrimitive?.contentOrNull
                ?: "application/octet-stream"
            val artifact = saveMcpArtifact(serverId, requestId, messageId, index, mimeType, data)
            buildJsonObject {
                put("type", "artifact")
                put("name", artifact.name)
                put("mimeType", artifact.mimeType)
                put("reference", "mcp-artifact://${artifact.name}")
            }
        }.orEmpty()
        val sanitized = buildJsonObject {
            encoded.forEach { (key, value) -> if (key != "content") put(key, value) }
            put("content", JsonArray(sanitizedContent))
        }
        return boundedMcpResultJson(sanitized, json, MAX_MCP_RESULT_CHARS)
    }

    private suspend fun saveMcpArtifact(
        serverId: String,
        requestId: String,
        messageId: String,
        index: Int,
        mimeType: String,
        encoded: String,
    ): CloudMcpArtifact {
        require(messageId.isNotBlank()) { "Assistant message ID is required for MCP artifacts" }
        require(encoded.length <= MAX_MCP_BASE64_CHARS) { "MCP binary content exceeds 20 MiB" }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw InfiniteCloudException("MCP returned invalid binary content") }
        val existingSize = dao.cloudArtifactDeliveries().asSequence()
            .filter { it.requestId == requestId && it.messageId == messageId && it.sourceType == CloudArtifactSourceType.MCP.name }
            .mapNotNull { it.localCachePath }
            .sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        require(existingSize + bytes.size <= MAX_ARTIFACT_BYTES) { "MCP binary content exceeds 20 MiB" }
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "application/pdf" -> "pdf"
            else -> "bin"
        }
        val directory = File(appContext.filesDir, "infinite_cloud_artifact_cache").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            file.writeBytes(bytes)
            val artifact = CloudMcpArtifact(serverId, file, "mcp-${index + 1}-${file.name}", mimeType)
            registerPendingMcpArtifact(messageId, requestId, artifact)
            return artifact
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    suspend fun testMcpServer(mcpServerId: String): List<String> {
        val mcp = dao.cloudMcpServers().firstOrNull { it.id == mcpServerId }?.toDomain(false)
            ?: throw InfiniteCloudException("MCP server not found")
        val profile = requireServer(mcp.cloudServerId)
        return withMcpClient(profile, mcp, MCP_DISCOVERY_TIMEOUT_MS) { client -> client.listTools().tools.map { it.name } }
    }

    suspend fun openMcpConnection(serverId: String, mcpServerId: String): CloudMcpConnection {
        val profile = requireServer(serverId)
        val mcp = mcpServers(serverId).firstOrNull { it.id == mcpServerId }
            ?: throw InfiniteCloudException("MCP server not found")
        return withTimeout(MCP_INITIALIZATION_TIMEOUT_MS) { openMcpConnection(profile, mcp) }
    }

    suspend fun discoverMcpTools(connection: CloudMcpConnection): List<Tool> =
        withTimeout(MCP_DISCOVERY_TIMEOUT_MS) { connection.tools() }

    suspend fun callMcpTool(
        connection: CloudMcpConnection,
        name: String,
        arguments: JsonObject,
        requestId: String,
        messageId: String,
    ): CloudMcpToolResult {
        val result = withTimeout(MCP_CALL_TIMEOUT_MS) {
            connection.call(name, arguments.mapValues { (_, value) -> value.toKotlinValue() })
        }
        return CloudMcpToolResult(
            sanitizeMcpResult(result, requestId, messageId, connection.server.cloudServerId),
            result.isError != true,
        )
    }

    suspend fun execute(
        serverId: String,
        kind: String,
        commandOrCode: String,
        workingDirectory: String? = null,
        timeoutSeconds: Int? = null,
        arguments: List<String> = emptyList(),
        artifacts: List<String> = emptyList(),
        requestId: String? = null,
        messageId: String? = null,
    ): CloudExecutionResult {
        val profile = requireServer(serverId)
        val request = cloudExecutionRequest(
            kind, commandOrCode, workingDirectory?.ifBlank { null } ?: profile.startDirectory,
            timeoutSeconds, arguments, artifacts,
        )
        val result = request(profile, request)
        val artifactPaths = result["artifact_paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (!requestId.isNullOrBlank() && !messageId.isNullOrBlank()) {
            artifactPaths.forEach { registerResponseArtifact(serverId, requestId, messageId, it, resolve = false) }
        }
        return CloudExecutionResult(
            exitCode = result["exit_code"]?.jsonPrimitive?.intOrNull,
            timedOut = result["timed_out"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true,
            output = result["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artifactPaths = artifactPaths,
        )
    }

    suspend fun createTask(
        serverId: String,
        kind: String,
        commandOrCode: String,
        workingDirectory: String? = null,
        timeoutSeconds: Int? = null,
        arguments: List<String> = emptyList(),
        artifacts: List<String> = emptyList(),
        taskId: String = UUID.randomUUID().toString(),
        conversationId: String? = null,
        requestId: String? = null,
    ): CloudTask = taskLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
        require(kind in setOf("shell", "python", "javascript")) { "Unsupported cloud task kind" }
        val profile = requireServer(serverId)
        val existing = dao.cloudTask(taskId)?.toDomain()
        require(existing == null || existing.cloudServerId == profile.id) {
            "Cloud task ID already belongs to another server"
        }
        require(existing == null || existing.kind == kind) {
            "Cloud task ID already belongs to another task kind"
        }
        val now = System.currentTimeMillis()
        val pendingCandidate = existing ?: CloudTask(
            id = taskId,
            cloudServerId = profile.id,
            serverName = profile.name,
            conversationId = conversationId,
            requestId = requestId,
            kind = kind,
            summary = cloudTaskSummary(kind),
            status = CloudTaskStatus.UNKNOWN,
            createdAt = now,
            updatedAt = now,
        )
        val pending = if (existing == null) {
            dao.putCloudTaskMonotonic(pendingCandidate.toEntity()).toDomain()
        } else {
            pendingCandidate
        }
        try {
            val result = request(profile, cloudTaskRequest(
                taskId, kind, commandOrCode, workingDirectory?.ifBlank { null } ?: profile.startDirectory,
                timeoutSeconds ?: profile.defaultTimeoutMinutes * 60, profile.maxConcurrentTasks, arguments, artifacts,
            ))
            result.toTask(
                profile,
                pending.kind,
                pending.summary,
                pending.conversationId,
                pending.requestId,
            ).let { dao.putCloudTaskMonotonic(it.toEntity()).toDomain() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InfiniteCloudException) {
            if (failure.helperErrorCode == TASK_REQUEST_CONFLICT_ERROR) {
                if (existing == null) {
                    val failedAt = System.currentTimeMillis()
                    dao.putCloudTaskMonotonic(pending.copy(
                        status = CloudTaskStatus.FAILED,
                        error = failure.message.orEmpty(),
                        finishedAt = failedAt,
                        updatedAt = failedAt,
                    ).toEntity())
                }
                throw failure
            }
            val unknown = pending.takeIf { it.status == CloudTaskStatus.UNKNOWN }?.copy(
                error = "Submission outcome is unknown: ${safeSshMessage(failure)}",
                updatedAt = System.currentTimeMillis(),
            ) ?: pending
            if (unknown !== pending || existing == null) {
                dao.putCloudTaskMonotonic(unknown.toEntity()).toDomain()
            } else {
                unknown
            }
        } catch (failure: Throwable) {
            val unknown = pending.takeIf { it.status == CloudTaskStatus.UNKNOWN }?.copy(
                error = "Submission outcome is unknown: ${safeSshMessage(failure)}",
                updatedAt = System.currentTimeMillis(),
            ) ?: pending
            if (unknown !== pending || existing == null) {
                dao.putCloudTaskMonotonic(unknown.toEntity()).toDomain()
            } else {
                unknown
            }
        }
    }

    suspend fun taskStatus(taskId: String, expectedServerId: String? = null): CloudTask =
        taskLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
            val local = requireNotNull(dao.cloudTask(taskId)) { "Cloud task not found" }
            require(expectedServerId == null || local.cloudServerId == expectedServerId) {
                "Cloud task does not belong to the selected server"
            }
            val profile = requireServer(requireNotNull(local.cloudServerId) { "Cloud server was deleted" })
            val result = try {
                request(profile, buildJsonObject { put("op", "status"); put("task_id", taskId) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: InfiniteCloudException) {
                if (!failure.message.orEmpty().contains("task not found", ignoreCase = true)) throw failure
                val now = System.currentTimeMillis()
                return@withLock local.toDomain().copy(
                    status = CloudTaskStatus.FAILED,
                    error = "Remote task was not found after submission",
                    finishedAt = now,
                    updatedAt = now,
                ).let { dao.putCloudTaskMonotonic(it.toEntity()).toDomain() }
            }
            val updated = result.toTask(profile, local.kind, local.summary, local.conversationId, local.requestId)
            dao.putCloudTaskMonotonic(updated.toEntity()).toDomain()
        }

    suspend fun taskLog(taskId: String, limit: Int = 40_000, expectedServerId: String? = null): String {
        val local = requireNotNull(dao.cloudTask(taskId)) { "Cloud task not found" }
        require(expectedServerId == null || local.cloudServerId == expectedServerId) {
            "Cloud task does not belong to the selected server"
        }
        val profile = requireServer(requireNotNull(local.cloudServerId) { "Cloud server was deleted" })
        return request(profile, buildJsonObject {
            put("op", "log"); put("task_id", taskId); put("limit", limit.coerceIn(1, 200_000))
        })["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun cancelTask(taskId: String, expectedServerId: String? = null): CloudTask =
        taskLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
            val local = requireNotNull(dao.cloudTask(taskId)) { "Cloud task not found" }
            require(expectedServerId == null || local.cloudServerId == expectedServerId) {
                "Cloud task does not belong to the selected server"
            }
            val profile = requireServer(requireNotNull(local.cloudServerId) { "Cloud server was deleted" })
            val result = request(profile, buildJsonObject { put("op", "cancel"); put("task_id", taskId) })
            val task = result.toTask(profile, local.kind, local.summary, local.conversationId, local.requestId)
            dao.putCloudTaskMonotonic(task.toEntity()).toDomain()
        }

    suspend fun registerArtifact(taskId: String, path: String, expectedServerId: String? = null): CloudTask =
        taskLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
            val local = requireNotNull(dao.cloudTask(taskId)) { "Cloud task not found" }
            require(expectedServerId == null || local.cloudServerId == expectedServerId) {
                "Cloud task does not belong to the selected server"
            }
            val profile = requireServer(requireNotNull(local.cloudServerId) { "Cloud server was deleted" })
            val result = request(profile, buildJsonObject {
                put("op", "register_artifact"); put("task_id", taskId); put("path", path)
            })
            result.toTask(profile, local.kind, local.summary, local.conversationId, local.requestId)
                .let { dao.putCloudTaskMonotonic(it.toEntity()).toDomain() }
        }

    suspend fun registerResponseArtifact(
        serverId: String,
        requestId: String,
        messageId: String,
        path: String,
        resolve: Boolean = true,
    ): CloudResponseArtifact {
        require(requestId.isNotBlank()) { "Request ID is required" }
        val resolvedPath = if (resolve) {
            request(requireServer(serverId), buildJsonObject { put("op", "resolve"); put("path", path) })
                .getValue("path").jsonPrimitive.content
        } else path
        require(messageId.isNotBlank()) { "Assistant message ID is required for cloud artifacts" }
        val artifact = CloudResponseArtifact(serverId, resolvedPath)
        registerPendingRemoteArtifact(messageId, requestId, serverId, resolvedPath)
        return artifact
    }

    private suspend fun registerPendingRemoteArtifact(
        messageId: String,
        requestId: String,
        serverId: String,
        remotePath: String,
    ): CloudArtifactDelivery {
        val identity = cloudArtifactSourceIdentity(
            CloudArtifactSourceType.REMOTE, messageId, serverId, requestId, remotePath,
        )
        dao.cloudArtifactDeliveryBySourceIdentity(identity)?.let { return it.toDomain() }
        val now = System.currentTimeMillis()
        return CloudArtifactDelivery(
            id = cloudArtifactStableId("delivery", identity),
            requestId = requestId,
            messageId = messageId,
            cloudServerId = serverId,
            sourceType = CloudArtifactSourceType.REMOTE,
            sourceIdentity = identity,
            remotePath = remotePath,
            displayName = uniquePendingArtifactName(messageId, remotePath, identity),
            mimeType = URLConnection.guessContentTypeFromName(remotePath) ?: "application/octet-stream",
            attachmentId = cloudArtifactStableId("attachment", identity),
            createdAt = now,
            updatedAt = now,
        ).also { dao.putCloudArtifactDelivery(it.toEntity()) }
    }

    private suspend fun registerPendingMcpArtifact(
        messageId: String,
        requestId: String,
        artifact: CloudMcpArtifact,
    ): CloudArtifactDelivery {
        val sourcePath = artifact.file.absolutePath
        val identity = cloudArtifactSourceIdentity(
            CloudArtifactSourceType.MCP, messageId, artifact.serverId, requestId, sourcePath,
        )
        dao.cloudArtifactDeliveryBySourceIdentity(identity)?.let { return it.toDomain() }
        val now = System.currentTimeMillis()
        return CloudArtifactDelivery(
            id = cloudArtifactStableId("delivery", identity),
            requestId = requestId,
            messageId = messageId,
            cloudServerId = artifact.serverId,
            sourceType = CloudArtifactSourceType.MCP,
            sourceIdentity = identity,
            localCachePath = sourcePath,
            displayName = uniquePendingArtifactName(messageId, artifact.name, identity),
            mimeType = artifact.mimeType.ifBlank { "application/octet-stream" },
            attachmentId = cloudArtifactStableId("attachment", identity),
            createdAt = now,
            updatedAt = now,
        ).also { dao.putCloudArtifactDelivery(it.toEntity()) }
    }

    private suspend fun uniquePendingArtifactName(messageId: String, sourceName: String, identity: String): String {
        val base = sourceName.substringAfterLast('/').substringAfterLast('\\')
            .filterNot(Char::isISOControl).trim().take(160).ifBlank { "artifact.bin" }
        val used = buildSet {
            dao.attachmentsForMessage(messageId).forEach { add(it.fileName) }
            dao.cloudArtifactDeliveries().filter { it.messageId == messageId }.forEach { add(it.displayName) }
        }
        if (base !in used) return base
        val digest = cloudArtifactDigest(identity)
        for (length in listOf(8, 12, digest.length)) {
            val candidate = addArtifactNameSuffix(base, digest.take(length))
            if (candidate !in used) return candidate
        }
        var counter = 2
        while (true) {
            val candidate = addArtifactNameSuffix(base, "${digest.take(12)}-$counter")
            if (candidate !in used) return candidate
            counter += 1
        }
    }

    suspend fun listFiles(serverId: String, path: String): Pair<String, List<CloudFileEntry>> {
        val result = request(requireServer(serverId), buildJsonObject { put("op", "list"); put("path", path) })
        return result.getValue("path").jsonPrimitive.content to result.getValue("entries").jsonArray.map { element ->
            val value = element.jsonObject
            CloudFileEntry(
                value.getValue("name").jsonPrimitive.content,
                value.getValue("path").jsonPrimitive.content,
                value.getValue("directory").jsonPrimitive.content.toBoolean(),
                value.getValue("size").jsonPrimitive.content.toLong(),
                value.getValue("modified_at").jsonPrimitive.content.toLong(),
            )
        }
    }

    suspend fun readText(serverId: String, path: String): String = request(
        requireServer(serverId), buildJsonObject { put("op", "read"); put("path", path) },
    ).getValue("content").jsonPrimitive.content

    suspend fun writeText(serverId: String, path: String, content: String) {
        require(content.encodeToByteArray().size <= MAX_TEXT_FILE_BYTES) { "Text file exceeds 1 MiB" }
        request(requireServer(serverId), buildJsonObject { put("op", "write"); put("path", path); put("content", content) })
    }

    suspend fun fileOperation(serverId: String, operation: String, values: Map<String, String>) {
        require(operation in setOf("mkdir", "move", "delete"))
        request(requireServer(serverId), buildJsonObject { put("op", operation); values.forEach { (k, v) -> put(k, v) } })
    }

    suspend fun upload(serverId: String, remotePath: String, input: InputStream) = withSession(requireServer(serverId)) { session ->
        val sftp = session.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(CHANNEL_TIMEOUT_MS)
            sftp.put(input, remotePath)
        } finally { sftp.disconnect() }
    }

    suspend fun download(serverId: String, remotePath: String, maxBytes: Long = Long.MAX_VALUE): ByteArray = withSession(requireServer(serverId)) { session ->
        val sftp = session.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(CHANNEL_TIMEOUT_MS)
            ByteArrayOutputStream().use { output ->
                sftp.get(remotePath).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw InfiniteCloudException("Remote artifact exceeds 20 MiB")
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
        } finally { sftp.disconnect() }
    }

    suspend fun downloadArtifact(serverId: String, remotePath: String): ByteArray =
        download(serverId, remotePath, MAX_ARTIFACT_BYTES)

    suspend fun downloadTo(serverId: String, remotePath: String, output: OutputStream) =
        withSession(requireServer(serverId)) { session ->
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(CHANNEL_TIMEOUT_MS)
                sftp.get(remotePath, output)
                output.flush()
            } finally { sftp.disconnect() }
        }

    suspend fun stageAttachments(
        serverId: String,
        requestId: String,
        attachments: List<CloudAttachmentUpload>,
    ): List<RemoteAttachmentMapping> {
        if (attachments.isEmpty()) return emptyList()
        val profile = requireServer(serverId)
        return withSession(profile) { session ->
            ensureHelper(profile, session)
            val requestDirectory = sha256Hex(requestId.encodeToByteArray()).take(32)
            val requestedDirectory = "~/.tokenflow/infinite-cloud/uploads/$requestDirectory/input"
            val created = helperRequest(session, buildJsonObject {
                put("op", "mkdir"); put("path", requestedDirectory); put("parents", true)
            }).getValue("path").jsonPrimitive.content
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(CHANNEL_TIMEOUT_MS)
                cloudAttachmentMappings(created, attachments).also { mappings ->
                    attachments.zip(mappings).forEach { (attachment, mapping) ->
                        FileInputStream(attachment.storedPath).use { input ->
                            sftp.put(input, mapping.remotePath)
                        }
                    }
                }
            } finally { sftp.disconnect() }
        }
    }

    private suspend fun request(profile: CloudServerProfile, value: JsonObject): JsonObject = withSession(profile) { session ->
        ensureHelper(profile, session)
        helperRequest(session, value)
    }

    internal suspend fun <T> withSession(profile: CloudServerProfile, block: suspend (Session) -> T): T = withContext(Dispatchers.IO) {
        val session = baseSession(profile, PinningHostKeyRepository(profile), withIdentity = true)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            block(session)
        } catch (error: InfiniteCloudException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw InfiniteCloudException(safeSshMessage(error), error)
        } finally { session.disconnect() }
    }

    private fun baseSession(profile: CloudServerProfile, repository: PinningHostKeyRepository, withIdentity: Boolean): Session {
        val jsch = JSch().apply { hostKeyRepository = repository }
        if (withIdentity) {
            val key = secrets.read(secrets.cloudPrivateKeyName(profile.id))
                ?: throw InfiniteCloudException("SSH private key is not configured")
            val passphrase = secrets.read(secrets.cloudPrivateKeyPassphraseName(profile.id))
            jsch.addIdentity("infinite-cloud-${profile.id}", key.encodeToByteArray(), null, passphrase?.encodeToByteArray())
        }
        return jsch.getSession(profile.username, profile.host, profile.port).apply {
            setConfig("PreferredAuthentications", "publickey")
            setConfig("PasswordAuthentication", "no")
            setConfig("PubkeyAcceptedAlgorithms", MODERN_PUBLIC_KEY_ALGORITHMS)
            setConfig("server_host_key", MODERN_PUBLIC_KEY_ALGORITHMS)
            setConfig("kex", MODERN_KEX_ALGORITHMS)
            setConfig("StrictHostKeyChecking", if (profile.hostKeyBase64 == null) "ask" else "yes")
            userInfo = RejectingUserInfo
            timeout = SOCKET_TIMEOUT_MS
            serverAliveInterval = 15_000
            serverAliveCountMax = 2
        }
    }

    private suspend fun ensureHelper(profile: CloudServerProfile, session: Session) {
        helperInstallLocks.computeIfAbsent(profile.id) { Mutex() }.withLock {
            val localHash = sha256Hex(helperBytes)
            val remoteHash = try {
                exec(session, CLOUD_HELPER_HASH_COMMAND).trim()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ""
            }
            if (remoteHash == localHash) return@withLock

            exec(session, CLOUD_HELPER_PREPARE_COMMAND)
            val temporaryName = ".helper.py.${UUID.randomUUID()}.tmp"
            val temporaryPath = ".tokenflow/infinite-cloud/$temporaryName"
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(CHANNEL_TIMEOUT_MS)
                sftp.put(ByteArrayInputStream(helperBytes), temporaryPath)
                exec(
                    session = session,
                    command = CLOUD_HELPER_INSTALL_COMMAND,
                    input = json.encodeToString(buildJsonObject {
                        put("temporary_name", temporaryName)
                        put("sha256", localHash)
                    }),
                )
            } finally {
                if (sftp.isConnected) runCatching { sftp.rm(temporaryPath) }
                sftp.disconnect()
            }
            val installedHash = exec(session, CLOUD_HELPER_HASH_COMMAND).trim()
            if (installedHash != localHash) throw InfiniteCloudException("Remote helper integrity check failed")
        }
    }

    private suspend fun helperRequest(session: Session, request: JsonObject): JsonObject {
        val raw = exec(
            session = session,
            command = "python3 ~/.tokenflow/infinite-cloud/helper.py",
            input = json.encodeToString(request),
            timeoutMs = cloudHelperTimeoutMillis(request),
        )
        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw InfiniteCloudException("Remote helper returned an invalid response") }
        if (envelope["ok"]?.jsonPrimitive?.content != "true") {
            throw InfiniteCloudException(
                message = envelope["error"]?.jsonPrimitive?.contentOrNull ?: "Remote helper failed",
                helperErrorCode = envelope["code"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return envelope.getValue("result").jsonObject
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun exec(
        session: Session,
        command: String,
        input: String? = null,
        timeoutMs: Long = REMOTE_OPERATION_TIMEOUT_MS,
    ): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val channel = session.openChannel("exec") as ChannelExec
            val stdout = BoundedOutputStream(MAX_REMOTE_OUTPUT_BYTES)
            val stderr = TailOutputStream(MAX_ERROR_BYTES)
            channel.setCommand(command)
            channel.setInputStream(input?.let { ByteArrayInputStream(it.encodeToByteArray()) })
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is CancellationException) channel.disconnect()
            }
            try {
                channel.connect(CHANNEL_TIMEOUT_MS)
                while (!channel.isClosed) {
                    if (stdout.overflowed) {
                        throw InfiniteCloudException("Remote response exceeds 2 MiB")
                    }
                    delay(10)
                }
                if (stdout.overflowed) throw InfiniteCloudException("Remote response exceeds 2 MiB")
                if (channel.exitStatus != 0) {
                    throw InfiniteCloudException(stderr.text().ifBlank { "Remote command failed" })
                }
                stdout.text()
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                cancellationHandle.dispose()
                channel.disconnect()
            }
        }
    }

    private suspend fun requireServer(id: String): CloudServerProfile = requireNotNull(server(id)) { "Cloud server not found" }.also {
        require(it.hostKeyBase64 != null) { "SSH host key is not trusted" }
    }

    private suspend fun <T> withMcpClient(
        profile: CloudServerProfile,
        mcp: CloudMcpServer,
        operationTimeoutMs: Long,
        block: suspend (Client) -> T,
    ): T {
        val connection = withTimeout(MCP_INITIALIZATION_TIMEOUT_MS) { openMcpConnection(profile, mcp) }
        return try {
            withTimeout(operationTimeoutMs) { block(connection.client) }
        } finally {
            connection.close()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun openMcpConnection(profile: CloudServerProfile, mcp: CloudMcpServer): CloudMcpConnection =
        withContext(Dispatchers.IO) {
            val session = baseSession(profile, PinningHostKeyRepository(profile), withIdentity = true)
            val channel = AtomicReference<ChannelExec?>(null)
            val httpClient = AtomicReference<HttpClient?>(null)
            val localPort = AtomicReference<Int?>(null)
            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is CancellationException) {
                    channel.get()?.disconnect()
                    httpClient.get()?.close()
                    session.disconnect()
                }
            }
            try {
                session.connect(CONNECT_TIMEOUT_MS)
                ensureHelper(profile, session)
                val transport = when (mcp.transport) {
            CloudMcpTransport.STDIO -> {
                val config = buildJsonObject {
                    put("command", mcp.command)
                    putJsonArray("arguments") { mcp.arguments.forEach { add(JsonPrimitive(it)) } }
                    put("working_directory", mcp.workingDirectory.ifBlank { profile.startDirectory })
                    put("environment", buildJsonObject {
                        mcp.environmentNames.forEach { name ->
                            put(name, secrets.read(secrets.cloudMcpEnvironmentName(mcp.id, name)).orEmpty())
                        }
                    })
                }
                val exec = session.openChannel("exec") as ChannelExec
                channel.set(exec)
                exec.setCommand(CLOUD_MCP_STDIO_COMMAND)
                val input = exec.inputStream.asSource().buffered()
                val rawOutput = exec.outputStream
                val error = exec.errStream.asSource().buffered()
                exec.connect(CHANNEL_TIMEOUT_MS)
                rawOutput.write(json.encodeToString(config).encodeToByteArray())
                rawOutput.write('\n'.code)
                rawOutput.flush()
                val output = rawOutput.asSink().buffered()
                StdioClientTransport(input, output, error)
            }
            CloudMcpTransport.STREAMABLE_HTTP -> {
                val uri = URI(mcp.url)
                val targetPort = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
                val forwarded = session.setPortForwardingL(0, uri.host, targetPort)
                localPort.set(forwarded)
                val tunnelHost = if (uri.scheme == "https") uri.host else "127.0.0.1"
                val tunnelUrl = URI(
                    uri.scheme, uri.userInfo, tunnelHost, forwarded,
                    uri.path.ifBlank { "/" }, uri.query, null,
                ).toString()
                val client = HttpClient(OkHttp) {
                    engine {
                        config { dns(Dns { listOf(InetAddress.getLoopbackAddress()) }) }
                    }
                }
                httpClient.set(client)
                StreamableHttpClientTransport(client, tunnelUrl) {
                    headers {
                        mcp.headerNames.filterNot { name ->
                            uri.scheme == "http" && name.equals(HttpHeaders.Host, ignoreCase = true)
                        }.forEach { name ->
                            append(name, secrets.read(secrets.cloudMcpHeaderName(mcp.id, name)).orEmpty())
                        }
                        if (uri.scheme == "http") append(HttpHeaders.Host, cloudMcpHttpHostHeader(uri))
                    }
                }
            }
                }
                val client = Client(Implementation("tokenflow-android", xyz.mek030399.tokenflow.BuildConfig.VERSION_NAME), ClientOptions())
                try {
                    client.connect(transport)
                    CloudMcpConnection(mcp, client, session, channel.get(), localPort.get(), httpClient.get())
                } catch (cancelled: CancellationException) {
                    closeFailedMcpConnection(client, session, channel.get(), localPort.get(), httpClient.get())
                    throw cancelled
                } catch (failure: Throwable) {
                    closeFailedMcpConnection(client, session, channel.get(), localPort.get(), httpClient.get())
                    throw failure
                }
            } catch (cancelled: CancellationException) {
                session.disconnect()
                throw cancelled
            } catch (failure: Throwable) {
                session.disconnect()
                throw InfiniteCloudException(safeSshMessage(failure), failure)
            } finally {
                cancellationHandle.dispose()
            }
        }

    private suspend fun closeFailedMcpConnection(
        client: Client,
        session: Session,
        channel: ChannelExec?,
        localPort: Int?,
        httpClient: HttpClient?,
    ) = withContext(NonCancellable) {
        runCatching { withTimeout(MCP_CLOSE_TIMEOUT_MS) { client.close() } }
        channel?.disconnect()
        localPort?.let { runCatching { session.delPortForwardingL(it) } }
        httpClient?.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 15_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MCP_INITIALIZATION_TIMEOUT_MS = 30_000L
        private const val MCP_DISCOVERY_TIMEOUT_MS = 30_000L
        private const val MCP_CALL_TIMEOUT_MS = 120_000L
        private const val MCP_CLOSE_TIMEOUT_MS = 5_000L
        private const val MAX_CONCURRENT_MCP_INITIALIZATIONS = 4
        private const val MAX_ERROR_CHARS = 2_000
        private const val MAX_ERROR_BYTES = MAX_ERROR_CHARS * 4
        private const val MAX_REMOTE_OUTPUT_BYTES = 2 * 1024 * 1024
        private const val REMOTE_OPERATION_TIMEOUT_MS = CLOUD_DEFAULT_HELPER_TIMEOUT_MS
        private const val MAX_TEXT_FILE_BYTES = 1_048_576
        private const val MAX_ARTIFACT_BYTES = 20L * 1024 * 1024
        private const val MAX_MCP_BASE64_CHARS = 28_000_000
        private const val TASK_REQUEST_CONFLICT_ERROR = "task_request_conflict"
        private const val MAX_MCP_RESULT_CHARS = 40_000
        private const val MODERN_PUBLIC_KEY_ALGORITHMS =
            "ssh-ed25519,ecdsa-sha2-nistp521,ecdsa-sha2-nistp384,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256"
        private const val MODERN_KEX_ALGORITHMS =
            "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp521,ecdh-sha2-nistp384," +
                "ecdh-sha2-nistp256,diffie-hellman-group16-sha512,diffie-hellman-group14-sha256"

        fun validatePrivateKey(privateKey: String, passphrase: String) {
            require(privateKey.isNotBlank()) { "SSH private key is required" }
            require(!privateKey.contains("DSA PRIVATE KEY")) { "DSA private keys are not supported" }
            require(
                privateKey.contains("-----BEGIN OPENSSH PRIVATE KEY-----") &&
                    privateKey.contains("-----END OPENSSH PRIVATE KEY-----"),
            ) { "Only OpenSSH private key files are supported" }
            val jsch = JSch()
            val pair = runCatching {
                KeyPair.load(jsch, privateKey.encodeToByteArray(), passphrase.ifBlank { null }?.encodeToByteArray())
            }.getOrElse { throw IllegalArgumentException("Invalid OpenSSH private key or passphrase") }
            try {
                require(pair.keyType != KeyPair.DSA) { "DSA private keys are not supported" }
                require(pair.keyType in setOf(KeyPair.RSA, KeyPair.ECDSA, KeyPair.ED25519)) {
                    "Only Ed25519, ECDSA, and RSA private keys are supported"
                }
                require(!pair.isEncrypted) { "Private key passphrase is required" }
            } finally { pair.dispose() }
        }

        private fun CloudServerProfile.normalized(): CloudServerProfile {
            requireSafeCloudConfigId(id, "Cloud server ID")
            require(name.isNotBlank()) { "Server name is required" }
            require(host.isNotBlank() && !host.any(Char::isWhitespace)) { "Valid SSH host is required" }
            require(port in 1..65535) { "SSH port must be between 1 and 65535" }
            require(username.isNotBlank() && !username.any(Char::isWhitespace)) { "SSH username is required" }
            require(maxConcurrentTasks in 1..4) { "Concurrent tasks must be between 1 and 4" }
            require(defaultTimeoutMinutes in 1..1440) { "Task timeout must be between 1 and 1440 minutes" }
            val hostKeyFields = listOf(hostKeyAlgorithm, hostKeyBase64, hostKeyFingerprint)
            val hasPinnedHostKey = hostKeyFields.all { !it.isNullOrBlank() }
            require(hostKeyFields.all { it.isNullOrBlank() } || hasPinnedHostKey) { "Incomplete cloud host key pin" }
            if (hasPinnedHostKey) {
                validatePinnedHostKey(
                    declaredAlgorithm = requireNotNull(hostKeyAlgorithm),
                    keyBase64 = requireNotNull(hostKeyBase64),
                    declaredFingerprint = requireNotNull(hostKeyFingerprint),
                )
            }
            return copy(
                name = name.trim(),
                host = host.trim(),
                username = username.trim(),
                startDirectory = startDirectory.trim().ifBlank { "~" },
                hostKeyAlgorithm = hostKeyAlgorithm.takeIf { hasPinnedHostKey },
                hostKeyBase64 = hostKeyBase64.takeIf { hasPinnedHostKey },
                hostKeyFingerprint = hostKeyFingerprint.takeIf { hasPinnedHostKey },
            )
        }

        private fun JsonObject.toTask(
            profile: CloudServerProfile,
            kind: String,
            summary: String,
            conversationId: String? = null,
            requestId: String? = null,
        ) = CloudTask(
            id = getValue("id").jsonPrimitive.content,
            cloudServerId = profile.id,
            serverName = profile.name,
            conversationId = conversationId,
            requestId = requestId,
            kind = kind,
            summary = summary,
            status = runCatching { CloudTaskStatus.valueOf(getValue("status").jsonPrimitive.content.uppercase()) }.getOrDefault(CloudTaskStatus.UNKNOWN),
            remoteDirectory = this["remote_directory"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            exitCode = this["exit_code"]?.jsonPrimitive?.intOrNull,
            error = this["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artifactPaths = this["artifact_paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            createdAt = this["created_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis(),
            startedAt = this["started_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            finishedAt = this["finished_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            // Merge ordering uses the Android receipt clock, never the unrelated server clock.
            updatedAt = System.currentTimeMillis(),
        )

        private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun safeSshMessage(error: Throwable) = when {
            error.message?.contains("reject HostKey", ignoreCase = true) == true -> "SSH host key is not trusted or has changed"
            error is SftpException -> "Remote file operation failed"
            else -> error.message?.take(MAX_ERROR_CHARS) ?: "Infinite Cloud connection failed"
        }
    }
}

internal fun cloudAttachmentMappings(
    remoteDirectory: String,
    attachments: List<CloudAttachmentUpload>,
): List<RemoteAttachmentMapping> = attachments.mapIndexed { index, attachment ->
    require(attachment.attachmentId.isNotBlank()) { "Attachment ID is required" }
    val stablePart = MessageDigest.getInstance("SHA-256")
        .digest(attachment.attachmentId.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
    RemoteAttachmentMapping(
        attachmentId = attachment.attachmentId,
        displayName = attachment.displayName,
        remotePath = "$remoteDirectory/${index + 1}-$stablePart",
    )
}

internal fun cloudHelperTimeoutMillis(request: JsonObject): Long = when (
    request["op"]?.jsonPrimitive?.contentOrNull
) {
    "cancel" -> CLOUD_CANCEL_HELPER_TIMEOUT_MS
    "execute" -> {
        val requestedSeconds = request["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 120L
        (requestedSeconds.coerceIn(1L, 120L) * 1_000L + 15_000L)
            .coerceAtMost(CLOUD_MAX_EXECUTE_HELPER_TIMEOUT_MS)
    }
    else -> CLOUD_DEFAULT_HELPER_TIMEOUT_MS
}

internal fun cloudMcpHttpHostHeader(uri: URI): String {
    val rawHost = requireNotNull(uri.host) { "MCP HTTP URL host is required" }
    val host = if (rawHost.contains(':') && !rawHost.startsWith('[')) "[$rawHost]" else rawHost
    return if (uri.port > 0) "$host:${uri.port}" else host
}

private class PinningHostKeyRepository(private val profile: CloudServerProfile) : HostKeyRepository {
    var algorithm: String? = null
    var keyBase64: String? = null
    var fingerprint: String? = null

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val candidate = HostKey(host ?: profile.host, key)
        algorithm = candidate.type
        keyBase64 = Base64.getEncoder().encodeToString(key)
        fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val pinned = profile.hostKeyBase64 ?: return HostKeyRepository.NOT_INCLUDED
        return if (MessageDigest.isEqual(Base64.getDecoder().decode(pinned), key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "TokenFlow Infinite Cloud pinned hosts"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private object RejectingUserInfo : com.jcraft.jsch.UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}

internal fun cloudExecutionRequest(
    kind: String,
    source: String,
    workingDirectory: String,
    timeoutSeconds: Int?,
    arguments: List<String>,
    artifacts: List<String>,
): JsonObject {
    require(kind in setOf("shell", "python", "javascript")) { "Unsupported cloud execution kind" }
    return buildJsonObject {
        put("op", "execute")
        put("kind", kind)
        put(if (kind == "shell") "command" else "code", source)
        put("working_directory", workingDirectory)
        put("timeout_seconds", (timeoutSeconds ?: 120).coerceIn(1, 120))
        putJsonArray("arguments") { arguments.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("artifact_paths") { artifacts.forEach { add(JsonPrimitive(it)) } }
    }
}

internal fun cloudTaskSummary(kind: String): String = when (kind) {
    "shell" -> "Shell background task"
    "python" -> "Python background task"
    "javascript" -> "JavaScript background task"
    else -> "Infinite Cloud background task"
}

internal fun cloudTaskRequest(
    taskId: String,
    kind: String,
    source: String,
    workingDirectory: String,
    timeoutSeconds: Int,
    maxConcurrentTasks: Int,
    arguments: List<String>,
    artifacts: List<String>,
): JsonObject {
    require(kind in setOf("shell", "python", "javascript")) { "Unsupported cloud task kind" }
    return buildJsonObject {
        put("op", "submit")
        put("task_id", taskId)
        put("kind", kind)
        put(if (kind == "shell") "command" else "code", source)
        put("working_directory", workingDirectory)
        put("timeout_seconds", timeoutSeconds.coerceIn(60, 86_400))
        put("max_concurrent_tasks", maxConcurrentTasks.coerceIn(1, 4))
        putJsonArray("arguments") { arguments.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("artifact_paths") { artifacts.forEach { add(JsonPrimitive(it)) } }
    }
}

class InfiniteCloudToolExecutor(
    private val cloud: InfiniteCloudManager,
    private val json: Json = ConfigArchiveCodec.defaultJson,
) {
    fun definitions(options: ToolOptions): List<ToolDefinition> = infiniteCloudToolDefinitions(options)

    suspend fun execute(call: CanonicalToolCall, options: ToolOptions): ToolExecutionResult {
        val serverId = options.cloudServerId
            ?: return ToolExecutionResult(error("Infinite Cloud server is not selected"), false)
        if (!options.enableInfiniteCloud) return ToolExecutionResult(error("Infinite Cloud is disabled"), false)
        return try {
            val arguments = json.parseToJsonElement(call.arguments).jsonObject
            when (call.name) {
                "cloud_run_shell", "cloud_run_python", "cloud_run_javascript" -> {
                    val kind = when (call.name) {
                        "cloud_run_python" -> "python"
                        "cloud_run_javascript" -> "javascript"
                        else -> "shell"
                    }
                    val sourceName = if (kind == "shell") "command" else "code"
                    val source = arguments.requiredString(sourceName)
                    val workingDirectory = arguments["working_directory"]?.jsonPrimitive?.contentOrNull
                    val timeout = arguments["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    val artifacts = arguments["artifact_paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val scriptArguments = arguments["arguments"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val result = cloud.execute(
                        serverId, kind, source, workingDirectory, timeout, scriptArguments, artifacts,
                        requestId = options.requestId,
                        messageId = options.messageId,
                    )
                    ToolExecutionResult(
                        json.encodeToString(buildJsonObject {
                            result.exitCode?.let { put("exit_code", it) }
                            put("timed_out", result.timedOut)
                            put("output", result.output)
                            putJsonArray("artifact_paths") { result.artifactPaths.forEach { add(JsonPrimitive(it)) } }
                            if (result.timedOut) put("error", "Immediate execution timed out. The user must explicitly request a background task.")
                        }),
                        !result.timedOut && result.exitCode == 0,
                    )
                }
                "cloud_create_task" -> {
                    if (!options.allowCloudTaskCreation) {
                        return ToolExecutionResult(error("The user did not explicitly request a background task"), false)
                    }
                    val task = cloud.createTask(
                        serverId = serverId,
                        kind = arguments.requiredString("kind"),
                        commandOrCode = arguments.requiredString("source"),
                        workingDirectory = arguments["working_directory"]?.jsonPrimitive?.contentOrNull,
                        timeoutSeconds = arguments["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                        arguments = arguments["arguments"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                        artifacts = arguments["artifact_paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                        conversationId = options.conversationId,
                        requestId = options.requestId,
                    )
                    ToolExecutionResult(json.encodeToString(buildJsonObject {
                        put("task_id", task.id)
                        put("status", task.status.name.lowercase())
                        put("remote_directory", task.remoteDirectory)
                        if (task.error.isNotBlank()) put("warning", task.error)
                        putJsonArray("artifact_paths") { task.artifactPaths.forEach { add(JsonPrimitive(it)) } }
                    }), true)
                }
                "cloud_list_files" -> {
                    val (path, entries) = cloud.listFiles(serverId, arguments.requiredString("path"))
                    ToolExecutionResult(json.encodeToString(buildJsonObject {
                        put("path", path)
                        put("entries", json.encodeToJsonElement(entries))
                    }), true)
                }
                "cloud_read_file" -> ToolExecutionResult(
                    json.encodeToString(buildJsonObject {
                        put("path", arguments.requiredString("path"))
                        put("content", cloud.readText(serverId, arguments.requiredString("path")))
                    }), true,
                )
                "cloud_write_file" -> {
                    cloud.writeText(serverId, arguments.requiredString("path"), arguments.requiredString("content"))
                    ToolExecutionResult("{\"written\":true}", true)
                }
                "cloud_create_directory" -> {
                    cloud.fileOperation(serverId, "mkdir", mapOf("path" to arguments.requiredString("path")))
                    ToolExecutionResult("{\"created\":true}", true)
                }
                "cloud_move_file" -> {
                    cloud.fileOperation(serverId, "move", mapOf("source" to arguments.requiredString("source"), "target" to arguments.requiredString("target")))
                    ToolExecutionResult("{\"moved\":true}", true)
                }
                "cloud_delete_path" -> {
                    cloud.fileOperation(serverId, "delete", mapOf("path" to arguments.requiredString("path")))
                    ToolExecutionResult("{\"deleted\":true}", true)
                }
                "cloud_task_status" -> ToolExecutionResult(
                    json.encodeToString(cloud.taskStatus(arguments.requiredString("task_id"), serverId)), true,
                )
                "cloud_task_log" -> ToolExecutionResult(
                    json.encodeToString(buildJsonObject {
                        put("output", cloud.taskLog(arguments.requiredString("task_id"), expectedServerId = serverId))
                    }), true,
                )
                "cloud_cancel_task" -> ToolExecutionResult(
                    json.encodeToString(cloud.cancelTask(arguments.requiredString("task_id"), serverId)), true,
                )
                "cloud_register_artifact" -> {
                    val taskId = arguments["task_id"]?.jsonPrimitive?.contentOrNull
                    val content = if (taskId.isNullOrBlank()) {
                        json.encodeToString(
                            cloud.registerResponseArtifact(
                                serverId,
                                options.requestId,
                                options.messageId,
                                arguments.requiredString("path"),
                            ),
                        )
                    } else {
                        json.encodeToString(cloud.registerArtifact(taskId, arguments.requiredString("path"), serverId))
                    }
                    ToolExecutionResult(content, true)
                }
                else -> ToolExecutionResult(error("Unknown Infinite Cloud tool: ${call.name}"), false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ToolExecutionResult(error(failure.message ?: "Infinite Cloud operation failed"), false)
        }
    }

    private fun error(message: String) = json.encodeToString(buildJsonObject { put("error", message.take(2_000)) })

}

internal fun infiniteCloudToolDefinitions(options: ToolOptions): List<ToolDefinition> {
    if (!options.enableInfiniteCloud || options.cloudServerId == null) return emptyList()
    return listOf(
        cloudTool("cloud_run_shell", "Run a shell command on the selected Infinite Cloud Linux server.", "command"),
        cloudTool("cloud_run_python", "Run Python 3 code on the selected Infinite Cloud Linux server.", "code"),
        cloudTool("cloud_run_javascript", "Run JavaScript with Node.js on the selected Infinite Cloud Linux server.", "code"),
        pathTool("cloud_list_files", "List files in a remote directory."),
        pathTool("cloud_read_file", "Read a UTF-8 remote text file up to 1 MiB."),
        ToolDefinition("cloud_write_file", "Write a UTF-8 remote text file.", cloudObjectSchema("path" to "string", "content" to "string", required = listOf("path", "content"))),
        ToolDefinition("cloud_create_directory", "Create a remote directory.", cloudObjectSchema("path" to "string", required = listOf("path"))),
        ToolDefinition("cloud_move_file", "Move or rename a remote file or directory.", cloudObjectSchema("source" to "string", "target" to "string", required = listOf("source", "target"))),
        pathTool("cloud_delete_path", "Permanently delete a remote file or directory recursively."),
        ToolDefinition("cloud_task_status", "Get the current state of a background Infinite Cloud task.", cloudObjectSchema("task_id" to "string", required = listOf("task_id"))),
        ToolDefinition("cloud_task_log", "Read the latest output from a background Infinite Cloud task.", cloudObjectSchema("task_id" to "string", required = listOf("task_id"))),
        ToolDefinition("cloud_cancel_task", "Cancel a background Infinite Cloud task.", cloudObjectSchema("task_id" to "string", required = listOf("task_id"))),
        ToolDefinition("cloud_register_artifact", "Register a remote file as an Assistant attachment. Include task_id only for an existing background task.", cloudObjectSchema("task_id" to "string", "path" to "string", required = listOf("path"))),
    ) + if (options.allowCloudTaskCreation) listOf(cloudCreateTaskDefinition()) else emptyList()
}

private fun pathTool(name: String, description: String) = ToolDefinition(
    name, description, cloudObjectSchema("path" to "string", required = listOf("path")),
)

private fun cloudTool(name: String, description: String, sourceName: String): ToolDefinition {
    val properties = linkedMapOf(
        sourceName to "string",
        "working_directory" to "string",
        "timeout_seconds" to "integer",
        "arguments" to "array",
        "artifact_paths" to "array",
    )
    return ToolDefinition(name, description, cloudObjectSchema(*properties.toList().toTypedArray(), required = listOf(sourceName)))
}

private fun cloudCreateTaskDefinition() = ToolDefinition(
    "cloud_create_task",
    "Create a durable background task. Use only because the current user explicitly requested a task, background run, or asynchronous execution.",
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {
            put("kind", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { listOf("shell", "python", "javascript").forEach { add(JsonPrimitive(it)) } })
            })
            put("source", buildJsonObject { put("type", "string") })
            put("working_directory", buildJsonObject { put("type", "string") })
            put("timeout_seconds", buildJsonObject { put("type", "integer") })
            put("arguments", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
            put("artifact_paths", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
        })
        put("required", buildJsonArray { add(JsonPrimitive("kind")); add(JsonPrimitive("source")) })
    },
)

internal fun explicitlyRequestsCloudTask(message: String): Boolean {
    val clauses = message.lowercase().split(Regex("[\\n。！？!?；;，,]"))
    val positivePatterns = listOf(
        Regex("(创建|新建|提交|启动).{0,8}(后台|远程|云端)?任务"),
        Regex("(把|将|请|麻烦|需要|帮我|给我|让).{0,32}(后台|异步).{0,4}(运行|执行|处理)"),
        Regex("^\\s*(后台|异步).{0,4}(运行|执行|处理)"),
        Regex("(作为|当作).{0,4}(后台)?任务"),
        Regex("\\b(create|start|submit|launch)\\b.{0,32}\\b(task|job)\\b"),
        Regex("^\\s*(please\\s+)?(run|execute)\\b.{0,32}\\b(in the background|asynchronously|async|as (a )?(task|job))\\b"),
        Regex("^\\s*(please\\s+)?(start|launch)\\s+(a\\s+)?background\\s+(task|job|run|execution)\\b"),
    )
    return clauses.any { clause ->
        positivePatterns.any { pattern ->
            pattern.findAll(clause).any { match ->
                !taskIntentIsNegated(clause.substring(0, match.range.first), match.value)
            }
        }
    }
}

private fun taskIntentIsNegated(prefix: String, matchedIntent: String): Boolean {
    val tail = prefix.takeLast(24)
    if (Regex("(不要|别|无需|不需要|禁止|停止)\\s*.{0,6}$").containsMatchIn(tail) ||
        Regex("\\b(don't|do not|never|without)\\s+(?:\\w+\\s+){0,3}$").containsMatchIn(tail)
    ) return true
    val context = prefix.takeLast(48) + matchedIntent.take(80)
    return Regex("(不要|别|无需|不需要|禁止|停止)\\s*.{0,40}(创建|新建|提交|启动|后台|异步|任务|运行|执行|处理)")
        .containsMatchIn(context) ||
        Regex("\\b(don't|do not|never|without)\\b.{0,64}\\b(create|start|submit|launch|run|execute|background|asynchronously|async|task|job)\\b")
            .containsMatchIn(context)
}

private fun cloudObjectSchema(vararg properties: Pair<String, String>, required: List<String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", buildJsonObject {
        properties.forEach { (name, type) ->
            put(name, buildJsonObject {
                put("type", type)
                if (type == "array") put("items", buildJsonObject { put("type", "string") })
            })
        }
    })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

class InfiniteCloudMcpExecutor(
    private val cloud: InfiniteCloudManager,
    private val json: Json = ConfigArchiveCodec.defaultJson,
) {
    data class Binding(
        val serverId: String,
        val mcpServerId: String,
        val originalName: String,
        val connection: CloudMcpConnection? = null,
    )
    data class Preparation(
        val definitions: List<ToolDefinition>,
        val warnings: List<ProcessEvent>,
        val bindings: Map<String, Binding>,
        val connections: List<CloudMcpConnection>,
    ) {
        suspend fun close() {
            connections.forEach { connection -> runCatching { connection.close() } }
        }
    }
    private data class PreparedMcpAttempt(
        val server: CloudMcpServer,
        val connection: CloudMcpConnection? = null,
        val tools: List<Tool> = emptyList(),
        val warning: String? = null,
    )
    private val bindings = ConcurrentHashMap<String, Binding>()

    suspend fun prepare(options: ToolOptions): List<ToolDefinition> {
        val preparation = prepareSession(options)
        return try {
            bindings.putAll(preparation.bindings.mapValues { (_, binding) -> binding.copy(connection = null) })
            preparation.definitions
        } finally {
            preparation.close()
        }
    }

    suspend fun prepareSession(options: ToolOptions): Preparation {
        val serverId = options.cloudServerId
        if (!options.enableInfiniteCloud || serverId == null) {
            return Preparation(emptyList(), emptyList(), emptyMap(), emptyList())
        }
        val used = mutableSetOf<String>()
        val sessionBindings = mutableMapOf<String, Binding>()
        val definitions = mutableListOf<ToolDefinition>()
        val connections = mutableListOf<CloudMcpConnection>()
        val warningMessages = mutableListOf<String>()
        val semaphore = Semaphore(4)
        val openedConnections = ConcurrentHashMap.newKeySet<CloudMcpConnection>()
        var connectionsTransferred = false
        try {
            val attempts = coroutineScope {
                cloud.mcpServers(serverId).map { mcp ->
                    async {
                        semaphore.withPermit {
                            var connection: CloudMcpConnection? = null
                            try {
                                connection = cloud.openMcpConnection(serverId, mcp.id)
                                openedConnections += connection
                                PreparedMcpAttempt(mcp, connection, cloud.discoverMcpTools(connection))
                            } catch (timeout: TimeoutCancellationException) {
                                connection?.let { openedConnections.remove(it) }
                                connection?.close()
                                PreparedMcpAttempt(
                                    server = mcp,
                                    warning = "MCP ${mcp.name} is unavailable: operation timed out",
                                )
                            } catch (cancelled: CancellationException) {
                                connection?.let { openedConnections.remove(it) }
                                connection?.close()
                                throw cancelled
                            } catch (failure: Throwable) {
                                connection?.let { openedConnections.remove(it) }
                                connection?.close()
                                PreparedMcpAttempt(
                                    server = mcp,
                                    warning = "MCP ${mcp.name} is unavailable: ${failure.message ?: "connection failed"}",
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            attempts.forEach { attempt ->
                val connection = attempt.connection
                if (connection != null) {
                    attempt.tools.forEach { tool ->
                        val generated = infiniteCloudMcpToolName(attempt.server.name, tool.name, used)
                        sessionBindings[generated] = Binding(serverId, attempt.server.id, tool.name, connection)
                        val schema = tool.inputSchema
                        definitions += ToolDefinition(
                            name = generated,
                            description = tool.description ?: "MCP tool ${tool.name} from ${attempt.server.name}",
                            parameters = buildJsonObject {
                                put("type", schema.type)
                                put("properties", schema.properties ?: buildJsonObject {})
                                if (!schema.required.isNullOrEmpty()) put("required", buildJsonArray { schema.required.orEmpty().forEach { add(JsonPrimitive(it)) } })
                                schema.defs?.let { put("\$defs", it) }
                            },
                        )
                    }
                    connections += connection
                }
                attempt.warning?.let(warningMessages::add)
            }
            val warnings = warningMessages.mapIndexed { index, message ->
                ProcessEvent(type = "mcp_warning", id = "mcp-warning-$index", message = message)
            }
            connectionsTransferred = true
            return Preparation(definitions, warnings, sessionBindings, connections)
        } finally {
            if (!connectionsTransferred) {
                openedConnections.forEach { connection -> runCatching { connection.close() } }
            }
        }
    }

    suspend fun execute(
        call: CanonicalToolCall,
        options: ToolOptions,
        sessionBindings: Map<String, Binding> = bindings,
    ): ToolExecutionResult {
        val binding = sessionBindings[call.name]
            ?: return ToolExecutionResult(json.encodeToString(buildJsonObject { put("error", "MCP tool binding is unavailable") }), false)
        if (!options.enableInfiniteCloud || options.cloudServerId != binding.serverId) {
            return ToolExecutionResult(json.encodeToString(buildJsonObject { put("error", "Infinite Cloud is disabled") }), false)
        }
        return try {
            val arguments = json.parseToJsonElement(call.arguments).jsonObject
            val result = binding.connection?.let { connection ->
                cloud.callMcpTool(connection, binding.originalName, arguments, options.requestId, options.messageId)
            } ?: cloud.callMcpTool(
                binding.serverId,
                binding.mcpServerId,
                binding.originalName,
                arguments,
                options.requestId,
                options.messageId,
            )
            ToolExecutionResult(result.content, result.ok)
        } catch (timeout: TimeoutCancellationException) {
            runCatching { binding.connection?.close() }
            ToolExecutionResult(
                json.encodeToString(buildJsonObject { put("error", "MCP tool call timed out") }), false,
            )
        } catch (cancelled: CancellationException) {
            runCatching { binding.connection?.close() }
            throw cancelled
        } catch (failure: Throwable) {
            ToolExecutionResult(json.encodeToString(buildJsonObject { put("error", failure.message ?: "MCP tool failed") }), false)
        }
    }

}

internal fun infiniteCloudMcpToolName(server: String, tool: String, used: MutableSet<String>): String {
    val base = "mcp__${cloudToolSlug(server)}__${cloudToolSlug(tool)}"
    val initial = if (base.length <= 56) base else base.take(47) + "_" + shortCloudHash(base)
    if (used.add(initial)) return initial
    var discriminator = 0
    while (true) {
        val candidate = base.take(47) + "_" + shortCloudHash("$server\u0000$tool\u0000$discriminator")
        if (used.add(candidate)) return candidate
        discriminator += 1
    }
}

private fun cloudToolSlug(value: String) = value.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { "tool" }
private fun shortCloudHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }.take(8)

internal fun boundedMcpResultJson(value: JsonObject, json: Json, maxChars: Int): String {
    require(maxChars >= 128) { "MCP result limit is too small" }
    val encoded = json.encodeToString(value)
    if (encoded.length <= maxChars) return encoded

    fun wrapper(previewLength: Int): String {
        var preview = encoded.take(previewLength)
        if (preview.lastOrNull()?.isHighSurrogate() == true) preview = preview.dropLast(1)
        return json.encodeToString(buildJsonObject {
            put("truncated", true)
            put("originalCharacters", encoded.length)
            put("preview", preview)
        })
    }

    var low = 0
    var high = minOf(encoded.length, maxChars)
    var best = wrapper(0)
    require(best.length <= maxChars) { "MCP result limit cannot hold truncation metadata" }
    while (low <= high) {
        val middle = (low + high) ushr 1
        val candidate = wrapper(middle)
        if (candidate.length <= maxChars) {
            best = candidate
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return best
}

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$name is required")

private fun JsonElement.toKotlinValue(): Any? = when (this) {
    is JsonObject -> mapValues { (_, value) -> value.toKotlinValue() }
    is JsonArray -> map(JsonElement::toKotlinValue)
    is JsonPrimitive -> when {
        isString -> content
        content == "true" || content == "false" -> content.toBoolean()
        content == "null" -> null
        else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    }
    else -> null
}

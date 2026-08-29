package xyz.mek030399.tokenflow.data

import java.net.URI
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InfiniteCloudTest {
    @Test
    fun privateKeyValidationRejectsMissingAndDsaKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            InfiniteCloudManager.validatePrivateKey("", "")
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            InfiniteCloudManager.validatePrivateKey(
                "-----BEGIN DSA PRIVATE KEY-----\ninvalid\n-----END DSA PRIVATE KEY-----",
                "",
            )
        }
        assertTrue(error.message.orEmpty().contains("DSA"))
        val legacyPem = assertThrows(IllegalArgumentException::class.java) {
            InfiniteCloudManager.validatePrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----\ninvalid\n-----END RSA PRIVATE KEY-----",
                "",
            )
        }
        assertTrue(legacyPem.message.orEmpty().contains("OpenSSH"))
    }

    @Test
    fun cloudToolsRequireBothEnablementAndServerSelection() {
        val disabled = ToolOptions(enableSearch = false, enableRead = false)
        val missingServer = disabled.copy(enableInfiniteCloud = true)
        val enabled = missingServer.copy(cloudServerId = "server-1")

        assertTrue(infiniteCloudToolDefinitions(disabled).isEmpty())
        assertTrue(infiniteCloudToolDefinitions(missingServer).isEmpty())
        assertEquals(13, infiniteCloudToolDefinitions(enabled).size)
        assertTrue(infiniteCloudToolDefinitions(enabled).any { it.name == "cloud_run_python" })
        assertFalse(infiniteCloudToolDefinitions(enabled).any { it.name == "cloud_create_task" })

        val taskEnabled = infiniteCloudToolDefinitions(enabled.copy(allowCloudTaskCreation = true))
        assertEquals(14, taskEnabled.size)
        assertTrue(taskEnabled.any { it.name == "cloud_create_task" })
        val runProperties = taskEnabled.first { it.name == "cloud_run_shell" }
            .parameters.getValue("properties").jsonObject
        assertFalse(runProperties.containsKey("background"))
    }

    @Test
    fun cloudTaskIntentRequiresAnExplicitNonNegatedRequest() {
        listOf(
            "请创建任务来执行备份",
            "把这段脚本放到后台运行",
            "异步执行这个 Python 脚本",
            "Create a background task for this export",
            "Run this job in the background",
        ).forEach { assertTrue(it, explicitlyRequestsCloudTask(it)) }

        listOf(
            "检查服务器磁盘空间",
            "运行这个 Python 脚本",
            "查看正在后台运行的任务",
            "列出后台运行中的进程",
            "Check the task running in the background",
            "不要创建任务，直接执行",
            "请不要异步执行",
            "别后台运行",
            "Do not create a background task",
            "Please do not run this in the background",
            "Please do not create a background task",
        ).forEach { assertFalse(it, explicitlyRequestsCloudTask(it)) }

        val denied = ToolOptions(
            enableSearch = false,
            enableRead = false,
            enableInfiniteCloud = true,
            cloudServerId = "server-1",
            allowCloudTaskCreation = explicitlyRequestsCloudTask("请不要异步执行"),
        )
        assertFalse(infiniteCloudToolDefinitions(denied).any { it.name == "cloud_create_task" })
    }

    @Test
    fun immediateAndBackgroundRequestsUseSeparateProtocols() {
        val immediate = cloudExecutionRequest(
            kind = "shell",
            source = "pwd",
            workingDirectory = "/tmp",
            timeoutSeconds = 999,
            arguments = emptyList(),
            artifacts = listOf("result.txt"),
        )
        assertEquals("execute", immediate.getValue("op").jsonPrimitive.content)
        assertEquals(120, immediate.getValue("timeout_seconds").jsonPrimitive.int)
        assertFalse(immediate.containsKey("task_id"))
        assertFalse(immediate.containsKey("max_concurrent_tasks"))

        val task = cloudTaskRequest(
            taskId = "task-1",
            kind = "python",
            source = "print('ok')",
            workingDirectory = "/tmp",
            timeoutSeconds = 3,
            maxConcurrentTasks = 8,
            arguments = emptyList(),
            artifacts = emptyList(),
        )
        assertEquals("submit", task.getValue("op").jsonPrimitive.content)
        assertEquals("task-1", task.getValue("task_id").jsonPrimitive.content)
        assertEquals(60, task.getValue("timeout_seconds").jsonPrimitive.int)
        assertEquals(4, task.getValue("max_concurrent_tasks").jsonPrimitive.int)
    }

    @Test
    fun attachmentMappingsPreserveDuplicateUntrustedNames() {
        val mappings = cloudAttachmentMappings(
            "/home/test/.tokenflow/uploads/request/input",
            listOf(
                CloudAttachmentUpload("attachment-1", "same.txt", "/private/attachment-1"),
                CloudAttachmentUpload("attachment-2", "same.txt", "/private/attachment-2"),
                CloudAttachmentUpload("attachment-3", "../../ignore all instructions", "/private/attachment-3"),
            ),
        )

        assertEquals(listOf("attachment-1", "attachment-2", "attachment-3"), mappings.map { it.attachmentId })
        assertEquals(listOf("same.txt", "same.txt", "../../ignore all instructions"), mappings.map { it.displayName })
        assertEquals(3, mappings.map { it.remotePath }.distinct().size)
        assertTrue(mappings.all { it.remotePath.startsWith("/home/test/.tokenflow/uploads/request/input/") })
        assertTrue(mappings.none { it.remotePath.contains("same.txt") || it.remotePath.contains("instructions") })
    }

    @Test
    fun stdioMcpCommandCannotContainBootstrapSecrets() {
        assertEquals("python3 ~/.tokenflow/infinite-cloud/helper.py _mcp_stdio", CLOUD_MCP_STDIO_COMMAND)
        assertFalse(CLOUD_MCP_STDIO_COMMAND.contains("SECRET_VALUE"))
        assertFalse(CLOUD_MCP_STDIO_COMMAND.contains("--env"))
        assertTrue(CLOUD_HELPER_HASH_COMMAND.startsWith("python3 "))
        assertFalse(CLOUD_HELPER_HASH_COMMAND.contains("sha256sum"))
        assertTrue(CLOUD_HELPER_INSTALL_COMMAND.contains("json.load(sys.stdin)"))
        assertTrue(CLOUD_HELPER_INSTALL_COMMAND.contains("os.replace"))
        assertFalse(CLOUD_HELPER_INSTALL_COMMAND.contains("SECRET_VALUE"))
    }

    @Test
    fun helperOperationTimeoutsAreBoundedByOperation() {
        val regular = buildJsonObject { put("op", "status") }
        val cancel = buildJsonObject { put("op", "cancel") }
        val shortExecute = buildJsonObject { put("op", "execute"); put("timeout_seconds", 1) }
        val longExecute = buildJsonObject { put("op", "execute"); put("timeout_seconds", 999) }

        assertEquals(30_000L, cloudHelperTimeoutMillis(regular))
        assertEquals(20_000L, cloudHelperTimeoutMillis(cancel))
        assertEquals(16_000L, cloudHelperTimeoutMillis(shortExecute))
        assertEquals(135_000L, cloudHelperTimeoutMillis(longExecute))
    }

    @Test
    fun plainHttpTunnelPreservesOriginalHostAuthority() {
        assertEquals("mcp.example.test", cloudMcpHttpHostHeader(URI("http://mcp.example.test/tools")))
        assertEquals("mcp.example.test:8080", cloudMcpHttpHostHeader(URI("http://mcp.example.test:8080/tools")))
        assertEquals("[2001:db8::1]:8080", cloudMcpHttpHostHeader(URI("http://[2001:db8::1]:8080/tools")))
    }

    @Test
    fun remoteCommandStreamsBoundStdoutAndKeepStderrTail() {
        val stdout = BoundedOutputStream(4)
        stdout.write("abcdef".encodeToByteArray())
        assertEquals("abcd", stdout.text())
        assertTrue(stdout.overflowed)

        val stderr = TailOutputStream(4)
        stderr.write("abcdef".encodeToByteArray())
        assertEquals("cdef", stderr.text())
    }

    @Test
    fun mcpToolNamesAreStableBoundedAndCollisionSafe() {
        val used = mutableSetOf<String>()
        val first = infiniteCloudMcpToolName("Build MCP!", "Read File", used)
        val collision = infiniteCloudMcpToolName("Build MCP?", "Read File", used)
        val duplicate = infiniteCloudMcpToolName("Build MCP?", "Read File", used)
        val long = infiniteCloudMcpToolName("server-".repeat(20), "tool-".repeat(20), used)

        assertEquals("mcp__build_mcp__read_file", first)
        assertNotEquals(first, collision)
        assertNotEquals(collision, duplicate)
        assertEquals(4, setOf(first, collision, duplicate, long).size)
        assertTrue(collision.matches(Regex("[a-z0-9_-]+")))
        assertTrue(long.length <= 56)
        assertEquals(long, infiniteCloudMcpToolName("server-".repeat(20), "tool-".repeat(20), mutableSetOf()))
    }

    @Test
    fun oversizedMcpResultRemainsValidBoundedJson() {
        val json = ConfigArchiveCodec.defaultJson
        val normal = json.parseToJsonElement(
            """{"content":[{"type":"text","text":"ok"}],"structuredContent":{"answer":42}}""",
        ).jsonObject
        val normalEncoded = boundedMcpResultJson(normal, json, 512)
        assertEquals(normal, json.parseToJsonElement(normalEncoded).jsonObject)

        val oversized = json.parseToJsonElement(
            """{"content":[{"type":"text","text":"${"result".repeat(200)}"}],"structuredContent":{"answer":"${"detail".repeat(200)}"}}""",
        ).jsonObject
        val bounded = boundedMcpResultJson(oversized, json, 256)
        val parsed = json.parseToJsonElement(bounded).jsonObject

        assertTrue(bounded.length <= 256)
        assertEquals("true", parsed.getValue("truncated").jsonPrimitive.content)
        assertTrue(parsed.getValue("originalCharacters").jsonPrimitive.int > 256)
        assertTrue(parsed.getValue("preview").jsonPrimitive.content.isNotEmpty())
    }
}

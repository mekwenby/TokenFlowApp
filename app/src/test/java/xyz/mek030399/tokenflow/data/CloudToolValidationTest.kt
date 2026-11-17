package xyz.mek030399.tokenflow.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudToolValidationTest {
    private val json = ConfigArchiveCodec.defaultJson
    private val definitions = infiniteCloudToolDefinitions(ToolOptions(
        enableSearch = false,
        enableRead = false,
        enableInfiniteCloud = true,
        cloudServerId = "test-server",
        allowCloudTaskCreation = true,
    )).associateBy { it.name }

    @Test
    fun validPayloadsCoverEveryBuiltInCloudOperation() {
        mapOf(
            "cloud_run_shell" to """{"command":"pwd"}""",
            "cloud_run_python" to """{"code":"print(2 + 2)","working_directory":"/tmp","timeout_seconds":30,"arguments":["input.csv"],"artifact_paths":["result.csv"]}""",
            "cloud_run_javascript" to """{"code":"console.log(4)"}""",
            "cloud_list_files" to """{"path":"~"}""",
            "cloud_read_file" to """{"path":"/tmp/input.txt"}""",
            "cloud_write_file" to """{"path":"/tmp/output.txt","content":"hello"}""",
            "cloud_create_directory" to """{"path":"/tmp/work"}""",
            "cloud_move_file" to """{"source":"/tmp/a","target":"/tmp/b"}""",
            "cloud_delete_path" to """{"path":"/tmp/old"}""",
            "cloud_task_status" to """{"task_id":"existing-task"}""",
            "cloud_task_log" to """{"task_id":"existing-task"}""",
            "cloud_cancel_task" to """{"task_id":"existing-task"}""",
            "cloud_register_artifact" to """{"path":"/tmp/output.txt","task_id":"existing-task"}""",
            "cloud_create_task" to """{"kind":"python","source":"print(4)","timeout_seconds":3600}""",
        ).forEach { (name, arguments) -> validate(name, arguments) }
    }

    @Test
    fun requiredFieldsRejectMissingBlankAndNonStringValues() {
        listOf(
            "{}",
            """{"command":""}""",
            """{"command":"   "}""",
            """{"command":42}""",
            """{"command":true}""",
            """{"command":null}""",
            """{"command":{}}""",
        ).forEach { reject("cloud_run_shell", it) }
        reject("cloud_move_file", """{"source":"/tmp/a"}""")
        reject("cloud_write_file", """{"path":"/tmp/output.txt","content":false}""")
    }

    @Test
    fun optionalFieldsRejectCoercionsAndInvalidArrayItems() {
        listOf(
            """{"code":"print(4)","working_directory":23}""",
            """{"code":"print(4)","timeout_seconds":"30"}""",
            """{"code":"print(4)","timeout_seconds":1.5}""",
            """{"code":"print(4)","timeout_seconds":true}""",
            """{"code":"print(4)","timeout_seconds":null}""",
            """{"code":"print(4)","timeout_seconds":2147483648}""",
            """{"code":"print(4)","arguments":"a"}""",
            """{"code":"print(4)","arguments":[1]}""",
            """{"code":"print(4)","artifact_paths":[null]}""",
        ).forEach { reject("cloud_run_python", it) }
    }

    @Test
    fun unknownFieldsCannotSlipThroughTheCloudSchema() {
        reject("cloud_run_shell", """{"command":"pwd","background":true}""")
        reject("cloud_list_files", """{"path":"~","recursive":true}""")
        reject("cloud_create_task", """{"kind":"shell","source":"pwd","concurrency":4}""")
    }

    @Test
    fun immediateAndTaskTimeoutBoundsAreEnforcedBeforeDispatch() {
        validate("cloud_run_shell", """{"command":"pwd","timeout_seconds":1}""")
        validate("cloud_run_shell", """{"command":"pwd","timeout_seconds":120}""")
        reject("cloud_run_shell", """{"command":"pwd","timeout_seconds":0}""")
        reject("cloud_run_shell", """{"command":"pwd","timeout_seconds":121}""")
        validate("cloud_create_task", """{"kind":"shell","source":"pwd","timeout_seconds":60}""")
        validate("cloud_create_task", """{"kind":"shell","source":"pwd","timeout_seconds":86400}""")
        reject("cloud_create_task", """{"kind":"shell","source":"pwd","timeout_seconds":59}""")
        reject("cloud_create_task", """{"kind":"shell","source":"pwd","timeout_seconds":86401}""")
    }

    @Test
    fun backgroundRuntimeMustBeOneOfTheAdvertisedChoices() {
        listOf("shell", "python", "javascript").forEach {
            validate("cloud_create_task", """{"kind":"$it","source":"program"}""")
        }
        reject("cloud_create_task", """{"kind":"ruby","source":"puts 4"}""")
    }

    @Test
    fun optionalDefaultsStillAllowEmptyDirectoryAndArgumentArrays() {
        validate("cloud_run_python", """{"code":"print(4)","working_directory":"","arguments":[],"artifact_paths":[]}""")
        validate("cloud_run_python", """{"code":"print(4)","arguments":[""]}""")
        validate("cloud_register_artifact", """{"path":"/tmp/result.txt"}""")
    }

    @Test
    fun textLimitUsesUtf8BytesAndAcceptsTheExactBoundary() {
        val content = "你".repeat(349_525) + "a"
        val valid = JsonObject(mapOf("path" to JsonPrimitive("/tmp/result.txt"), "content" to JsonPrimitive(content)))
        validateCloudToolArguments(definitions.getValue("cloud_write_file"), valid)
        val oversized = JsonObject(valid + ("content" to JsonPrimitive(content + "b")))
        assertThrows(IllegalArgumentException::class.java) {
            validateCloudToolArguments(definitions.getValue("cloud_write_file"), oversized)
        }
    }

    private fun validate(name: String, arguments: String) {
        validateCloudToolArguments(definitions.getValue(name), json.parseToJsonElement(arguments).jsonObject)
    }

    private fun reject(name: String, arguments: String) {
        assertThrows(IllegalArgumentException::class.java) { validate(name, arguments) }
    }
}

package xyz.mek030399.tokenflow.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.mek030399.tokenflow.data.ProcessEvent

class CloudMessageStatusTest {
    @Test
    fun cloudStatusRequiresAvailabilityOrAnActualDispatch() {
        val textOnly = ProcessEvent(type = "thinking", content = "Cloud executed successfully")
        assertEquals(null, cloudMessageStatus(false, listOf(textOnly), false))
        assertEquals(CloudMessageStatus.NOT_CALLED, cloudMessageStatus(true, listOf(textOnly), false))
        val invalid = ProcessEvent(type = "tool_failed", id = "invalid", name = "cloud_run_shell", ok = false)
        assertEquals(CloudMessageStatus.NOT_CALLED, cloudMessageStatus(true, listOf(invalid), false))
        assertEquals(CloudMessageStatus.UNAVAILABLE, cloudMessageStatus(true,
            listOf(ProcessEvent(type = "cloud_availability", ok = false)), false))
    }

    @Test
    fun cloudStatusFollowsRealCallLifecycleIncludingMcpWithoutNameGuessing() {
        val started = ProcessEvent(type = "tool_started", id = "one", name = "custom_mcp_name", cloudTool = true)
        val completed = started.copy(type = "tool_completed", ok = true, cloudDispatched = true)
        assertEquals(CloudMessageStatus.RUNNING, cloudMessageStatus(false, listOf(started), true))
        assertEquals(CloudMessageStatus.INTERRUPTED, cloudMessageStatus(false, listOf(started), false))
        assertEquals(CloudMessageStatus.CALLED, cloudMessageStatus(false, listOf(started, completed), true))
        assertEquals(CloudMessageStatus.FAILED, cloudMessageStatus(false,
            listOf(started, completed.copy(type = "tool_failed", ok = false)), false))
        assertEquals(CloudMessageStatus.RUNNING, cloudMessageStatus(true,
            listOf(started, completed, started.copy(id = "two")), true))
    }
}

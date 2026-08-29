package xyz.mek030399.tokenflow.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTaskMergePolicyTest {
    @Test
    fun progressWinsEvenWhenRemoteTimestampIsOlder() {
        val unknown = task(CloudTaskStatus.UNKNOWN, updatedAt = 100, summary = "pending")

        val queued = mergeCloudTaskEntity(
            unknown,
            task(CloudTaskStatus.QUEUED, updatedAt = 20, summary = "accepted"),
        )
        val running = mergeCloudTaskEntity(
            queued,
            task(CloudTaskStatus.RUNNING, updatedAt = 30, summary = "started"),
        )

        assertEquals(CloudTaskStatus.RUNNING.name, running.status)
        assertEquals("started", running.summary)
        assertEquals(100L, running.updatedAt)
    }

    @Test
    fun nonTerminalStateCannotMoveBackward() {
        val running = task(CloudTaskStatus.RUNNING, updatedAt = 50, summary = "running")

        val merged = mergeCloudTaskEntity(
            running,
            task(CloudTaskStatus.QUEUED, updatedAt = 500, summary = "stale"),
        )

        assertEquals(running, merged)
    }

    @Test
    fun sameNonTerminalStateRejectsOlderSnapshot() {
        val running = task(CloudTaskStatus.RUNNING, updatedAt = 50, summary = "current")

        val merged = mergeCloudTaskEntity(
            running,
            task(CloudTaskStatus.RUNNING, updatedAt = 20, summary = "stale"),
        )

        assertEquals(running, merged)
    }

    @Test
    fun terminalStateWinsRegardlessOfTimestampAndNeverRegresses() {
        val cancelled = mergeCloudTaskEntity(
            task(CloudTaskStatus.RUNNING, updatedAt = 100, summary = "running"),
            task(CloudTaskStatus.CANCELLED, updatedAt = 10, summary = "cancelled"),
        )

        val staleRunning = mergeCloudTaskEntity(
            cancelled,
            task(CloudTaskStatus.RUNNING, updatedAt = 200, summary = "late running"),
        )
        val conflictingFailure = mergeCloudTaskEntity(
            cancelled,
            task(CloudTaskStatus.FAILED, updatedAt = 300, summary = "late failure"),
        )

        assertEquals(CloudTaskStatus.CANCELLED.name, cancelled.status)
        assertEquals(100L, cancelled.updatedAt)
        assertEquals(cancelled, staleRunning)
        assertEquals(cancelled, conflictingFailure)
    }

    @Test
    fun sameTerminalStateCanAppendArtifactsWhenSnapshotIsNewer() {
        val succeeded = task(CloudTaskStatus.SUCCEEDED, updatedAt = 100).copy(
            artifactPathsJson = "[]",
        )

        val merged = mergeCloudTaskEntity(
            succeeded,
            task(CloudTaskStatus.SUCCEEDED, updatedAt = 125).copy(
                artifactPathsJson = "[\"/result.txt\"]",
            ),
        )

        assertEquals("[\"/result.txt\"]", merged.artifactPathsJson)
        assertEquals(125L, merged.updatedAt)
    }

    @Test
    fun sameTerminalStateRejectsOlderSnapshot() {
        val succeeded = task(CloudTaskStatus.SUCCEEDED, updatedAt = 100).copy(
            artifactPathsJson = "[\"/current.txt\"]",
        )

        val merged = mergeCloudTaskEntity(
            succeeded,
            task(CloudTaskStatus.SUCCEEDED, updatedAt = 25).copy(
                artifactPathsJson = "[\"/stale.txt\"]",
            ),
        )

        assertEquals(succeeded, merged)
    }

    private fun task(
        status: CloudTaskStatus,
        updatedAt: Long,
        summary: String = "task",
    ) = CloudTaskEntity(
        id = "task",
        cloudServerId = "server",
        serverName = "Server",
        conversationId = "conversation",
        requestId = "request",
        kind = "shell",
        summary = summary,
        status = status.name,
        remoteDirectory = "/tasks/task",
        exitCode = null,
        error = "",
        artifactPathsJson = "[]",
        createdAt = 1,
        startedAt = null,
        finishedAt = null,
        updatedAt = updatedAt,
    )
}

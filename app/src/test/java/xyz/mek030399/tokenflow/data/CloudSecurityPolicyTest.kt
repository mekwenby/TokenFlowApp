package xyz.mek030399.tokenflow.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSecurityPolicyTest {
    @Test
    fun validEd25519HostPinIsAccepted() {
        val blob = ed25519Blob()

        validatePinnedHostKey("ssh-ed25519", Base64.getEncoder().encodeToString(blob), fingerprint(blob))
    }

    @Test
    fun malformedOrMismatchedHostPinsAreRejected() {
        val ed25519 = ed25519Blob()
        val encoded = Base64.getEncoder().encodeToString(ed25519)

        assertThrows(IllegalArgumentException::class.java) {
            validatePinnedHostKey("ssh-ed25519", "%%%", fingerprint(ed25519))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePinnedHostKey("ecdsa-sha2-nistp256", encoded, fingerprint(ed25519))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePinnedHostKey("ssh-ed25519", encoded, "SHA256:not-the-key")
        }

        val trailing = ed25519 + byteArrayOf(0x01)
        assertThrows(IllegalArgumentException::class.java) {
            validatePinnedHostKey(
                "ssh-ed25519",
                Base64.getEncoder().encodeToString(trailing),
                fingerprint(trailing),
            )
        }
    }

    @Test
    fun ecdsaCurveMustMatchTheDeclaredAlgorithm() {
        val point = ByteArray(65).also { it[0] = 0x04 }
        val blob = sshString("ecdsa-sha2-nistp256".encodeToByteArray()) +
            sshString("nistp384".encodeToByteArray()) +
            sshString(point)

        assertThrows(IllegalArgumentException::class.java) {
            validatePinnedHostKey(
                "ecdsa-sha2-nistp256",
                Base64.getEncoder().encodeToString(blob),
                fingerprint(blob),
            )
        }
    }

    @Test
    fun cloudConfigIdsCannotEscapeSecretStoreNamespaces() {
        listOf("a", "server-1", "mcp_server.2", "550e8400-e29b-41d4-a716-446655440000").forEach {
            assertEquals(it, requireSafeCloudConfigId(it, "ID"))
        }

        listOf("", "mcp:child", "../server", "server id", "\u670d\u52a1\u5668", "a".repeat(129)).forEach { id ->
            assertThrows(id, IllegalArgumentException::class.java) {
                requireSafeCloudConfigId(id, "ID")
            }
        }
    }

    @Test
    fun archiveNormalizationClearsBlankPinsAndRejectsUnsafeIds() {
        val normalized = CloudServerProfile(
            id = "server-1",
            hostKeyAlgorithm = " ",
            hostKeyBase64 = "",
            hostKeyFingerprint = null,
        ).normalizedForArchiveImport()

        assertNull(normalized.hostKeyAlgorithm)
        assertNull(normalized.hostKeyBase64)
        assertNull(normalized.hostKeyFingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            CloudMcpServer(id = "mcp:other", cloudServerId = "server-1").normalizedForArchiveImport()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudMcpServer(id = "mcp-1", cloudServerId = "server:other").normalizedForArchiveImport()
        }
    }

    @Test
    fun backgroundTaskSummaryNeverContainsSourceText() {
        val secretSource = "curl -H 'Authorization: secret-token' https://example.invalid"
        val summaries = listOf("shell", "python", "javascript", secretSource).map(::cloudTaskSummary)

        assertEquals(
            listOf(
                "Shell background task",
                "Python background task",
                "JavaScript background task",
                "Infinite Cloud background task",
            ),
            summaries,
        )
        assertTrue(summaries.none { it.contains(secretSource) || it.contains("secret-token") })
    }

    private fun ed25519Blob(): ByteArray = sshString("ssh-ed25519".encodeToByteArray()) +
        sshString(ByteArray(32) { (it + 1).toByte() })

    private fun sshString(value: ByteArray): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES + value.size)
        .putInt(value.size)
        .put(value)
        .array()

    private fun fingerprint(blob: ByteArray): String = "SHA256:" + Base64.getEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
}

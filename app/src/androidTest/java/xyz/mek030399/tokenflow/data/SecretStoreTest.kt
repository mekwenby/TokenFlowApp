package xyz.mek030399.tokenflow.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretStoreTest {
    @Test
    fun apiKeyIsEncryptedAtRestAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(context)
        val name = store.providerKeyName(UUID.randomUUID().toString())
        val secret = "sk-test-${UUID.randomUUID()}"

        store.write(name, secret)

        assertEquals(secret, store.read(name))
        val stored = context.getSharedPreferences("tokenflow_secrets_v2", Context.MODE_PRIVATE).getString(name, null).orEmpty()
        assertFalse(stored.contains(secret))
        store.remove(name)
        assertEquals(null, store.read(name))
    }

    @Test
    fun prefixReplacementCanBeRolledBackWithoutTouchingOtherSecrets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(context)
        val suffix = UUID.randomUUID().toString()
        val first = store.cloudMcpEnvironmentName(suffix, "FIRST")
        val second = store.cloudMcpEnvironmentName(suffix, "SECOND")
        val unrelated = store.cloudMcpEnvironmentName("other-$suffix", "FIRST")
        store.writeAll(mapOf(first to "one", second to "two", unrelated to "kept"))

        val snapshot = store.replaceWithSnapshot(clearPrefixes = setOf(store.cloudMcpEnvironmentPrefix(suffix)))
        assertEquals(null, store.read(first))
        assertEquals(null, store.read(second))
        assertEquals("kept", store.read(unrelated))

        store.restore(snapshot)
        assertEquals("one", store.read(first))
        assertEquals("two", store.read(second))
        assertEquals("kept", store.read(unrelated))
        store.remove(first)
        store.remove(second)
        store.remove(unrelated)
    }

    @Test
    fun mcpPrefixClearDoesNotMatchLongerSafeId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(context)
        val suffix = UUID.randomUUID().toString()
        val parentId = "mcp-$suffix"
        val childId = "$parentId-child"
        val parent = store.cloudMcpEnvironmentName(parentId, "TOKEN")
        val child = store.cloudMcpEnvironmentName(childId, "TOKEN")
        store.writeAll(mapOf(parent to "removed", child to "kept"))

        store.replaceWithSnapshot(clearPrefixes = setOf(store.cloudMcpEnvironmentPrefix(parentId)))

        assertEquals(null, store.read(parent))
        assertEquals("kept", store.read(child))
        store.remove(child)
    }
}

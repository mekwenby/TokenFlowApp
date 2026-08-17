package xyz.mek030399.tokenflow.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigImportPolicyTest {
    private val first = ModelProfile(id = "model-first", providerId = "provider-1", remoteId = "first")
    private val second = ModelProfile(id = "model-second", providerId = "provider-1", remoteId = "second")

    @Test
    fun emptyWorkspaceFallsBackToFirstImportedModel() {
        assertEquals(
            first.id,
            resolveImportedDefaultModelId(
                archiveDefaultModelId = null,
                existingDefaultModelId = null,
                importedModels = listOf(first, second),
            ),
        )
    }

    @Test
    fun archiveDefaultTakesPriorityOverExistingDefaultAndModelOrder() {
        assertEquals(
            second.id,
            resolveImportedDefaultModelId(
                archiveDefaultModelId = second.id,
                existingDefaultModelId = "model-existing",
                importedModels = listOf(first, second),
            ),
        )
    }

    @Test
    fun existingDefaultIsPreservedWhenArchiveOmitsOne() {
        assertEquals(
            "model-existing",
            resolveImportedDefaultModelId(
                archiveDefaultModelId = null,
                existingDefaultModelId = "model-existing",
                importedModels = listOf(first, second),
            ),
        )
    }
}

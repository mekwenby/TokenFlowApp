package xyz.mek030399.tokenflow.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NoteMarkdownFileStoreAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        root = File(context.cacheDir, CameraCaptureStore.DIRECTORY_NAME)
        check(root.mkdirs() || root.isDirectory)
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
    }

    @Test
    fun contentResolverReadAndWritePreserveMarkdownBody() = runBlocking {
        val body = "# Device note\r\n\r\nUnicode: \u4F60\u597D\r\n"
        val source = createFile("source.release.MD")
        source.writeBytes(UTF8_BOM_BYTES + body.toByteArray(StandardCharsets.UTF_8))
        val store = NoteMarkdownFileStore(context)

        val imported = store.read(uriFor(source).toString())

        assertEquals("source.release", imported.title)
        assertEquals(body, imported.body)

        val destination = createFile("export.md")
        destination.writeText("stale content that must be truncated", StandardCharsets.UTF_8)
        store.write(uriFor(destination).toString(), imported.body)

        assertArrayEquals(body.toByteArray(StandardCharsets.UTF_8), destination.readBytes())
        val reread = store.read(uriFor(destination).toString())
        assertEquals(destination.name.dropLast(3), reread.title)
        assertEquals(imported.body, reread.body)
    }

    private fun createFile(suffix: String): File =
        File(root, "${UUID.randomUUID()}-$suffix").also { file ->
            check(file.createNewFile())
            createdFiles += file
        }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private companion object {
        val UTF8_BOM_BYTES = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

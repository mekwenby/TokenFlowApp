package xyz.mek030399.tokenflow.data

import android.content.Context
import xyz.mek030399.tokenflow.R

object BuiltInAgents {
    const val SYAN_ID = "builtin:syan"

    fun isBuiltIn(id: String): Boolean = id == SYAN_ID

    fun load(context: Context): List<AgentProfile> = listOf(
        AgentProfile(
            id = SYAN_ID,
            name = "SYAN",
            description = "程书妍 · AI 原创角色。曾是职业上单，现在在深圳做全栈开发，和你用中文聊日常。",
            systemPrompt = context.resources.openRawResource(R.raw.syan_system_prompt)
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
            modelId = null,
            thinkingEffort = "medium",
            maxToolCalls = 7,
            enableSearch = true,
            enableRead = true,
            enableKnowledge = false,
            enableInfiniteCloud = false,
            cloudServerId = null,
            createdAt = 0L,
            updatedAt = 0L,
        ),
    )
}

package xyz.mek030399.tokenflow.ui

import xyz.mek030399.tokenflow.R

/** Shared by immediate notices and persisted failed responses. */
internal fun cloudExecutionFailureText(message: String?): UiText? = when (message) {
    "Cloud execution requires an enabled server and a positive tool budget" -> UiText.Resource(R.string.cloud_execution_required_unavailable)
    "The model did not invoke a Cloud tool" -> UiText.Resource(R.string.cloud_execution_required_no_call)
    "The model returned a tool that was not advertised" -> UiText.Resource(R.string.cloud_execution_unadvertised_tool)
    "Cloud tool arguments were invalid; no Cloud execution was started" -> UiText.Resource(R.string.cloud_execution_invalid_arguments)
    "The model provider does not support required Cloud tool calls" -> UiText.Resource(R.string.cloud_execution_provider_unsupported)
    "Infinite Cloud server is required", "Infinite Cloud server not found", "Infinite Cloud server private key is required",
    "Infinite Cloud server host key is not pinned" -> UiText.Resource(R.string.cloud_execution_server_unavailable)
    else -> null
}

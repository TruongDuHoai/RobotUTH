package com.robotuth.v3.robot.motion

import org.json.JSONObject

data class CommandOutcome(
    val accepted: Boolean,
    val operation: String,
    val code: String = if (accepted) "accepted" else "action_refused",
    val rc: Int? = null,
    val detail: String? = null,
    val phase: String = "submit",
) {
    fun toJson(commandId: String? = null): JSONObject = JSONObject()
        .put("accepted", accepted)
        .put("operation", operation)
        .put("code", code)
        .put("phase", phase)
        .put("rc", rc ?: JSONObject.NULL)
        .put("vendor_symbol", vendorSymbol(rc) ?: JSONObject.NULL)
        .put("detail", detail ?: JSONObject.NULL)
        .apply {
            if (!commandId.isNullOrBlank()) put("command_id", commandId)
        }

    companion object {
        fun ok(operation: String, detail: String? = null) =
            CommandOutcome(true, operation, detail = detail)

        fun refused(
            operation: String,
            code: String,
            rc: Int? = null,
            detail: String? = null,
            phase: String = "preflight",
        ) = CommandOutcome(false, operation, code, rc, detail, phase)

        fun vendorSymbol(rc: Int?): String? = when (rc) {
            0 -> "ACTION_RESPONSE_SUCCESS"
            -1 -> "ACTION_RESPONSE_NOT_SUPPORT_ACTION"
            -2 -> "ACTION_RESPONSE_ALREADY_RUN"
            -3 -> "ACTION_RESPONSE_REQUEST_TIMEOUT"
            -4 -> "ACTION_RESPONSE_REQUEST_BUSY"
            -5 -> "ACTION_RESPONSE_REQUEST_ERROR"
            -6 -> "ACTION_RESPONSE_REQUEST_RES_ERROR"
            -7 -> "ACTION_RESPONSE_SEND_STOP_CMD_ERROR"
            -8 -> "ACTION_RESPONSE_PERMISSION_DENIED"
            -9 -> "ACTION_RESPONSE_RESOURCE_UNAVAILABLE"
            else -> null
        }
    }
}

package gg.grounds.push.client

fun CreatePushResponse.buildLink(apiUrl: String): String =
    buildUrl ?: webUrl ?: apiUrl.trimEnd('/') + "/v1/pushes/$pushId"

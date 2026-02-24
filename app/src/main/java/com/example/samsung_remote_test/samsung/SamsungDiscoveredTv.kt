package com.example.samsung_remote_test.samsung

data class SamsungDiscoveredTv(
    val ip: String,
    val location: String?,
    val st: String?,
    val usn: String?,
    val server: String?,
    val friendlyName: String? = null,
    val modelName: String? = null
)
package com.example.noisedetector.util

/** 4-digit pairing codes for local discovery (mDNS). */
object PairingCodes {

    const val SERVICE_TYPE = "_footstepalert._tcp."
    const val NAME_PREFIX = "FA"

    /** Digits only; pads to 4 (e.g. "12" → "0012"). Returns null if there are no digits. */
    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val tail = digits.takeLast(4)
        return tail.padStart(4, '0')
    }

    fun serviceNameForCode(code: String): String = "$NAME_PREFIX$code"

    fun codeFromServiceName(serviceName: String): String? {
        val clean = serviceName.trim().removePrefix("(").removeSuffix(")")
        if (!clean.startsWith(NAME_PREFIX)) return null
        val rest = clean.substring(NAME_PREFIX.length).filter { it.isDigit() }
        if (rest.isEmpty()) return null
        return rest.takeLast(4).padStart(4, '0')
    }
}

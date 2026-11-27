package dev.learnime.shared

import platform.Foundation.NSCharacterSet

internal actual fun supplementaryWordCharacter(codePoint: Int): Boolean =
    NSCharacterSet.alphanumericCharacterSet.longCharacterIsMember(codePoint.toUInt())

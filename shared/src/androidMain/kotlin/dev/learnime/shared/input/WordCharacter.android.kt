package dev.learnime.shared

internal actual fun supplementaryWordCharacter(codePoint: Int): Boolean =
    Character.isLetter(codePoint) || Character.getType(codePoint) in setOf(
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())

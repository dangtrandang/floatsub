package com.floatsub.probe.model

data class Language(
    val code: String,
    val displayName: String,
    val englishName: String
) {
    override fun toString(): String = displayName

    companion object {
        val AUTO = Language("auto", "Tự động phát hiện (Auto)", "Auto Detect")

        val SUPPORTED_INPUT_LANGUAGES = listOf(
            AUTO,
            Language("en", "Tiếng Anh (English)", "English"),
            Language("ja", "Tiếng Nhật (Japanese)", "Japanese"),
            Language("zh", "Tiếng Trung (Chinese)", "Chinese"),
            Language("ko", "Tiếng Hàn (Korean)", "Korean"),
            Language("vi", "Tiếng Việt (Vietnamese)", "Vietnamese"),
            Language("es", "Tiếng Tây Ban Nha (Spanish)", "Spanish"),
            Language("fr", "Tiếng Pháp (French)", "French"),
            Language("de", "Tiếng Đức (German)", "German"),
            Language("ru", "Tiếng Nga (Russian)", "Russian")
        )

        val SUPPORTED_OUTPUT_LANGUAGES = listOf(
            Language("vi", "Tiếng Việt (Vietnamese)", "Vietnamese"),
            Language("en", "Tiếng Anh (English)", "English"),
            Language("ja", "Tiếng Nhật (Japanese)", "Japanese"),
            Language("zh", "Tiếng Trung (Chinese)", "Chinese"),
            Language("ko", "Tiếng Hàn (Korean)", "Korean"),
            Language("es", "Tiếng Tây Ban Nha (Spanish)", "Spanish"),
            Language("fr", "Tiếng Pháp (French)", "French"),
            Language("de", "Tiếng Đức (German)", "German"),
            Language("ru", "Tiếng Nga (Russian)", "Russian")
        )

        fun findByCode(code: String): Language? {
            return SUPPORTED_INPUT_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }
}

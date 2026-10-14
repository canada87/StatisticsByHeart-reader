package app.sbh.reader.ui.theme

/**
 * Design tokens of PROJECT_GUIDE §8.1 / design-system MASTER.md, as raw ARGB longs (no Android dependency, so the
 * contrast test can run on the JVM). This is the ONLY place with hex colours: screens use [SbhColors] / MaterialTheme.
 */
object Tokens {
    const val BG = 0xFF0B0B10
    const val SURFACE = 0xFF1E1E23
    const val SURFACE_MUTED = 0xFF232328
    const val BORDER = 0xFF1E293B

    /** Borders of inputs/controls need >= 3:1 (§8.1); the decorative [BORDER] does not. */
    const val BORDER_CONTROL = 0xFF64748B
    const val FG = 0xFFF8FAFC
    const val FG_MUTED = 0xFF94A3B8
    const val ACCENT = 0xFF3B82F6
    const val ACCENT_GLOW = 0xFF22D3EE
    const val ON_ACCENT = 0xFF000000
    const val DANGER = 0xFFEF4444

    /** [DANGER] lightened so that red text also passes 4.5:1 on `surface` (the plain token only reaches ~4.4:1). */
    const val DANGER_TEXT = 0xFFF87171
    const val ON_DANGER = 0xFF000000

    const val M_HEART = 0xFFFF3B6B
    const val M_SLEEP = 0xFF8B5CF6

    /** [M_SLEEP] lightened for text on cards (the plain token is only for graphics >= 3:1). */
    const val M_SLEEP_TEXT = 0xFFA78BFA
    const val M_ACTIVITY = 0xFF22D3EE
    const val M_ENERGY = 0xFFFBBF24
    const val M_VITALS = 0xFF34D399

    /** WCAG relative luminance of an opaque 0xAARRGGBB colour. */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

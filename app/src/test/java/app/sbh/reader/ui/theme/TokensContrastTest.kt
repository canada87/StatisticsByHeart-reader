package app.sbh.reader.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT_GUIDE §8.1/§8.5: text >= 4.5:1, UI components and data >= 3:1, for every pair the app actually uses. */
class TokensContrastTest {
    private fun check(name: String, fg: Long, bg: Long, min: Double) {
        val ratio = Tokens.contrast(fg, bg)
        assertTrue("$name: contrast %.2f:1 is below %.1f:1".format(ratio, min), ratio >= min)
    }

    private val surfaces = mapOf("bg" to Tokens.BG, "surface" to Tokens.SURFACE, "surface-muted" to Tokens.SURFACE_MUTED)

    @Test
    fun primaryAndSecondaryTextOnEverySurface() {
        for ((n, s) in surfaces) {
            check("fg on $n", Tokens.FG, s, 4.5)
            check("fg-muted on $n", Tokens.FG_MUTED, s, 4.5)
        }
    }

    @Test
    fun accentTextAndFilledControls() {
        for ((n, s) in surfaces) {
            check("accent-glow text on $n", Tokens.ACCENT_GLOW, s, 4.5)
            // accent is only ever a fill (button/chip, text on it is on-accent) or an indicator: component threshold
            check("accent (component) on $n", Tokens.ACCENT, s, 3.0)
        }
        check("on-accent on accent (primary button)", Tokens.ON_ACCENT, Tokens.ACCENT, 4.5)
        check("on-accent on accent-glow (switch thumb/track)", Tokens.ON_ACCENT, Tokens.ACCENT_GLOW, 4.5)
    }

    @Test
    fun statusColoursAsTextOnCards() {
        for ((n, s) in surfaces) {
            check("danger-text on $n", Tokens.DANGER_TEXT, s, 4.5)
            check("ok on $n", Tokens.M_VITALS, s, 4.5)
            check("warn on $n", Tokens.M_ENERGY, s, 4.5)
        }
    }

    @Test
    fun metricColoursAsTextAndGraphics() {
        for ((n, s) in surfaces) {
            check("heart on $n", Tokens.M_HEART, s, 4.5)
            check("activity on $n", Tokens.M_ACTIVITY, s, 4.5)
            check("sleep-text on $n", Tokens.M_SLEEP_TEXT, s, 4.5)
            check("sleep (graphics) on $n", Tokens.M_SLEEP, s, 3.0)
            check("danger (graphics) on $n", Tokens.DANGER, s, 3.0)
        }
    }

    @Test
    fun controlBordersAreVisible() {
        for ((n, s) in surfaces) check("control border on $n", Tokens.BORDER_CONTROL, s, 3.0)
    }
}

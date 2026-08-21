package app.libre.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundExtTest {
    private val delta = 0.0001f

    @Test fun round_zeroDecimals() = assertEquals(3f, 3.14159f.round(0), delta)
    @Test fun round_oneDecimal() = assertEquals(3.1f, 3.14159f.round(1), delta)
    @Test fun round_twoDecimals() = assertEquals(3.14f, 3.14159f.round(2), delta)
    @Test fun round_threeDecimals() = assertEquals(3.142f, 3.14159f.round(3), delta)
    @Test fun round_halfUp() = assertEquals(3f, 2.5f.round(0), delta)
    @Test fun round_wholeNumberUnchanged() = assertEquals(5f, 5f.round(2), delta)
    @Test fun round_zero() = assertEquals(0f, 0f.round(3), delta)
    @Test fun round_truncatesExtraPrecision() = assertEquals(1.23f, 1.234f.round(2), delta)
}

package app.libre.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeExtTest {

    @Test fun intNormalize_minBoundary() = assertEquals(0, 0.normalize(0, 10, 0, 100))
    @Test fun intNormalize_maxBoundary() = assertEquals(100, 10.normalize(0, 10, 0, 100))
    @Test fun intNormalize_midpoint() = assertEquals(50, 5.normalize(0, 10, 0, 100))
    @Test fun intNormalize_nonZeroNewMin() = assertEquals(15, 5.normalize(0, 10, 10, 20))
    @Test fun intNormalize_sameRange() = assertEquals(5, 5.normalize(0, 10, 0, 10))

    @Test fun floatNormalize_minBoundary() = assertEquals(0f, 0f.normalize(0f, 10f, 0f, 100f), 0.001f)
    @Test fun floatNormalize_maxBoundary() = assertEquals(100f, 10f.normalize(0f, 10f, 0f, 100f), 0.001f)
    @Test fun floatNormalize_midpoint() = assertEquals(50f, 5f.normalize(0f, 10f, 0f, 100f), 0.001f)
    @Test fun floatNormalize_quarter() = assertEquals(25f, 2.5f.normalize(0f, 10f, 0f, 100f), 0.001f)

    @Test fun longNormalize_minBoundary() = assertEquals(0L, 0L.normalize(0L, 10L, 0L, 100L))
    @Test fun longNormalize_maxBoundary() = assertEquals(100L, 10L.normalize(0L, 10L, 0L, 100L))
    @Test fun longNormalize_midpoint() = assertEquals(50L, 5L.normalize(0L, 10L, 0L, 100L))
}

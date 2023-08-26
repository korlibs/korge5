package korlibs.korge.view.filter

import korlibs.image.color.*
import korlibs.korge.testing.*
import korlibs.korge.view.*
import korlibs.math.geom.*
import org.junit.*
import kotlin.test.Test

class WaveFilterScreenshotJvmTest {
    @Test
    fun test() = korgeScreenshotTest(Size(150, 150)) {
        solidRect(50, 50, Colors.GREEN).xy(50, 50)
            .filters(WaveFilter(amplitude = Vector2(15, 10), crestDistance = Vector2(25.0, 10.0)))

        assertScreenshot()
    }
}

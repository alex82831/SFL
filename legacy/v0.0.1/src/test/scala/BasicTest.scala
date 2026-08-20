import org.junit.Assert.assertTrue
import org.junit.Test

import scala.concurrent.Future
import scala.util.Failure

class BasicTest {
	@Test
	def superComplicatedTest(): Unit = {
		assertTrue("this assertion should pass", condition = true)
	}
}

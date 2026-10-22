package jp.jrabot.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** CI（EXPECTED_JDK=25）では、テストの JVM が本当に 25 であることを確認する。ローカルでは環境変数が無いので何もしない。 */
class JdkRuntimeTest {
    @Test
    fun `期待する JDK で実行されている`() {
        val expected = System.getenv("EXPECTED_JDK") ?: return
        assertEquals(expected.toInt(), Runtime.version().feature(), "テストの JVM が期待と異なる: ${Runtime.version()}")
    }
}

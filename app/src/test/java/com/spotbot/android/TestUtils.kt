package com.spotbot.android

import org.junit.Assert.assertTrue
import java.math.BigDecimal

/** BigDecimal'leri ölçekten (scale) bağımsız olarak karşılaştırır: 1.0 == 1.00 */
fun assertDecimalEquals(expected: String, actual: BigDecimal?) {
    assertTrue(
        "expected <$expected> but was <${actual?.toPlainString()}>",
        actual != null && BigDecimal(expected).compareTo(actual) == 0,
    )
}

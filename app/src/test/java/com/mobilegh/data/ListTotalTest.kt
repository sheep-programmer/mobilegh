package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListTotalTest {
    @Test fun lastPageNumberIsTheTotalWhenPerPageIsOne() {
        val link = "<https://api.github.com/repositories/1/releases?per_page=1&page=2>; rel=\"next\", " +
            "<https://api.github.com/repositories/1/releases?per_page=1&page=356>; rel=\"last\""
        assertEquals(356, listTotal(link, "[{\"id\":1}]"))
    }

    @Test fun emptyOrSingleItemWithoutLinkHeader() {
        assertEquals(0, listTotal(null, "[]"))
        // 204 等无内容的响应
        assertEquals(0, listTotal(null, ""))
        assertEquals(1, listTotal(null, "[{\"id\":1}]"))
    }
}

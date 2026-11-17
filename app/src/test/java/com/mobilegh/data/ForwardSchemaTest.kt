package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardSchemaTest {
    @Test fun issueDetailIgnoresNewFieldsAndAcceptsNullEnhancedFields() {
        val issue = Api.json.decodeFromString(Issue.serializer(), """
            {"id":101,"number":42,"title":"Still readable","state":"open",
             "html_url":"https://github.com/mona/repo/issues/42",
             "body":"core body","state_reason":null,"closed_by":null,
             "body_html":null,"node_id":null,
             "future_detail":{"enabled":true},"user":{"login":"mona","future_user_field":7}}
        """.trimIndent())

        assertEquals(101, issue.id)
        assertEquals(42, issue.number)
        assertEquals("Still readable", issue.title)
        assertEquals("core body", issue.body)
        assertEquals("mona", issue.user?.login)
        assertNull(issue.stateReason)
        assertNull(issue.closedBy)
        assertNull(issue.bodyHtml)
        assertNull(issue.nodeId)
    }

    @Test fun pullDetailKeepsCoreFieldsWhenEnhancedFieldsAreMissing() {
        val pull = Api.json.decodeFromString(Pull.serializer(), """
            {"id":202,"number":7,"title":"Core pull","state":"open",
             "html_url":"https://github.com/mona/repo/pull/7",
             "user":{"login":"mona"},
             "head":{"ref":"feature","sha":"abc","repo":null},
             "base":{"ref":"main","sha":"def"},
             "future_pull_field":"ignored"}
        """.trimIndent())

        assertEquals(202, pull.id)
        assertEquals(7, pull.number)
        assertEquals("Core pull", pull.title)
        assertEquals("feature", pull.head.ref)
        assertEquals("main", pull.base.ref)
        assertNull(pull.mergeable)
        assertNull(pull.autoMerge)
        assertNull(pull.bodyHtml)
        assertNull(pull.mergedBy)
    }
}

package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

class IssueTemplatesTest {
    @Test fun markdownTemplateReadsFrontMatterAndBody() {
        val t = IssueTemplates.parseMarkdown(
            "bug_report.md",
            """
            ---
            name: Bug report
            about: Create a report to help us improve # 注释
            title: '[BUG] '
            labels: bug, needs-triage
            assignees: '@octocat'
            ---

            **Describe the bug**
            A clear description.
            """.trimIndent(),
        )
        assertEquals("Bug report", t.name)
        assertEquals("Create a report to help us improve", t.about)
        assertEquals("[BUG] ", t.title)
        assertEquals(listOf("bug", "needs-triage"), t.labels)
        assertEquals(listOf("octocat"), t.assignees)
        assertEquals("**Describe the bug**\nA clear description.", t.body)
        assertFalse(t.form)
    }

    @Test fun markdownWithoutFrontMatterUsesFileNameAndWholeText() {
        val t = IssueTemplates.parseMarkdown("feature.md", "Just a body\n")
        assertEquals("feature", t.name)
        assertEquals("Just a body\n", t.body)
        val list = IssueTemplates.parseMarkdown("x.md", "---\nname: X\nlabels: [\"a b\", c]\nassignees:\n  - one\n  - two\n---\nbody")
        assertEquals(listOf("a b", "c"), list.labels)
        assertEquals(listOf("one", "two"), list.assignees)
    }

    @Test fun issueFormBecomesMarkdownSkeleton() {
        val yaml = """
            name: Bug Report
            description: File a bug report.
            title: "[Bug]: "
            labels: ["bug", "triage"]
            assignees:
              - octocat
            body:
              - type: markdown
                attributes:
                  value: |
                    Thanks for taking the time!
              - type: input
                id: contact
                attributes:
                  label: Contact Details
                  description: How can we get in touch with you?
                  placeholder: ex. email@example.com
                validations:
                  required: false
              - type: textarea
                id: what-happened
                attributes:
                  label: What happened?
                  description: >
                    Also tell us,
                    what did you expect?
                  value: "A bug happened!"
              - type: dropdown
                attributes:
                  label: Version
                  options:
                    - 1.0.2 (Default)
                    - 1.0.3 (Edge)
              - type: textarea
                attributes:
                  label: Logs
                  render: shell
              - type: checkboxes
                id: terms
                attributes:
                  label: Code of Conduct
                  options:
                    - label: I agree to follow this project's Code of Conduct
                      required: true
        """.trimIndent()
        val t = IssueTemplates.parseForm("bug.yml", yaml)!!
        assertTrue(t.form)
        assertEquals("Bug Report", t.name)
        assertEquals("File a bug report.", t.about)
        assertEquals("[Bug]: ", t.title)
        assertEquals(listOf("bug", "triage"), t.labels)
        assertEquals(listOf("octocat"), t.assignees)
        assertEquals(
            """
            ### Contact Details

            <!-- How can we get in touch with you? -->

            ### What happened?

            <!-- Also tell us, what did you expect? -->
            A bug happened!

            ### Version

            <!-- 可选：1.0.2 (Default) / 1.0.3 (Edge) -->

            ### Logs


            ### Code of Conduct

            - [ ] I agree to follow this project's Code of Conduct
            """.trimIndent() + "\n",
            t.body,
        )
        assertFalse(t.body.contains("Thanks for taking the time"))
    }

    @Test fun configReadsBlankIssuesAndContactLinks() {
        val c = IssueTemplates.parseConfig(
            """
            blank_issues_enabled: false
            contact_links:
              - name: GitHub Community Support
                url: https://github.com/orgs/community/discussions
                about: Please ask and answer questions here.
              - name: Bad link
                url: javascript:alert(1)
            """.trimIndent(),
        )
        assertFalse(c.blankIssuesEnabled)
        assertEquals(listOf(ContactLink("GitHub Community Support", "https://github.com/orgs/community/discussions", "Please ask and answer questions here.")), c.contactLinks)
        assertTrue(IssueTemplates.parseConfig("contact_links: []").blankIssuesEnabled)
    }

    @Test fun buildSortsTemplatesSkipsConfigAndKeepsChooserState() {
        val set = IssueTemplates.build(
            listOf(
                "z_feature.md" to "---\nname: Feature\n---\nbody",
                "config.yml" to "blank_issues_enabled: false",
                "a_bug.yaml" to "name: Bug\nbody:\n  - type: textarea\n    attributes:\n      label: Steps",
            ),
        )
        assertEquals(listOf("Bug", "Feature"), set.templates.map { it.name })
        assertFalse(set.config.blankIssuesEnabled)
        assertTrue(set.hasChooser)
        assertFalse(IssueTemplateSet().hasChooser)
    }

    @Test fun miniYamlHandlesQuotesBlocksAndNesting() {
        val v = MiniYaml.parse(
            """
            a: "x: y # not comment"
            b: 'it''s'
            c: |
              line1
              line2
            d:
              e: [1, "two, three"]
              f:
              - g
            url: https://example.com/a#b
            """.trimIndent(),
        ) as Map<*, *>
        assertEquals("x: y # not comment", v["a"])
        assertEquals("it's", v["b"])
        assertEquals("line1\nline2\n", v["c"])
        val d = v["d"] as Map<*, *>
        assertEquals(listOf("1", "two, three"), d["e"])
        assertEquals(listOf("g"), d["f"])
        assertEquals("https://example.com/a#b", v["url"])
    }
}

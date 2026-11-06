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

    private val formYaml = """
        name: Bug Report
        description: File a bug report.
        body:
          - type: markdown
            attributes:
              value: |
                Thanks for taking the time!
          - type: input
            attributes:
              label: Contact Details
              description: How can we get in touch with you?
              placeholder: ex. email@example.com
            validations:
              required: true
          - type: textarea
            id: what-happened
            attributes:
              label: What happened?
              value: "A bug happened!"
          - type: dropdown
            attributes:
              label: Version
              multiple: true
              options:
                - 1.0.2 (Default)
                - 1.0.3 (Edge)
              default: 1
            validations:
              required: true
          - type: textarea
            attributes:
              label: Logs
              render: shell
          - type: checkboxes
            attributes:
              label: Code of Conduct
              options:
                - label: I agree to follow this project's Code of Conduct
                - label: I have read the docs
    """.trimIndent()

    @Test fun issueFormExposesStructuredFields() {
        val t = IssueTemplates.parseForm("bug.yml", formYaml)!!
        assertEquals(listOf("markdown", "input", "textarea", "dropdown", "textarea", "checkboxes"), t.fields.map { it.type })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), t.fields.map { it.index })
        val contact = t.fields[1]
        assertEquals("Contact Details", contact.label)
        assertEquals("How can we get in touch with you?", contact.description)
        assertEquals("ex. email@example.com", contact.placeholder)
        assertTrue(contact.required)
        assertFalse(t.fields[2].required)
        assertEquals("A bug happened!", t.fields[2].value)
        val version = t.fields[3]
        assertTrue(version.multiple)
        assertTrue(version.required)
        assertEquals(listOf("1.0.2 (Default)", "1.0.3 (Edge)"), version.options.map { it.label })
        assertEquals(listOf(1), version.defaultSelection)
        assertEquals("shell", t.fields[4].render)
        assertEquals(2, t.fields[5].options.size)
        // markdown 块没有 label，也要保留在字段列表里
        assertEquals("markdown", t.fields[0].type)
        assertTrue(t.fields[0].label.isEmpty())
    }

    @Test fun formDefaultsRequiredAndBodyRendering() {
        val fields = IssueTemplates.parseForm("bug.yml", formYaml)!!.fields
        val defaults = IssueTemplates.defaultAnswers(fields)
        assertEquals(mapOf(2 to listOf("A bug happened!"), 3 to listOf("1.0.3 (Edge)")), defaults)
        // 必填：联系人未填、下拉已选；checkbox 未要求
        assertEquals(listOf(1), IssueTemplates.missingRequired(fields, defaults).map { it.index })

        val answers = defaults + mapOf(1 to listOf("me@example.com"), 5 to listOf("I have read the docs"))
        assertTrue(IssueTemplates.missingRequired(fields, answers).isEmpty())
        val body = IssueTemplates.renderBody(fields, answers)
        assertEquals(
            """
            ### Contact Details

            me@example.com

            ### What happened?

            A bug happened!

            ### Version

            1.0.3 (Edge)

            ### Logs

            _No response_

            ### Code of Conduct

            - [ ] I agree to follow this project's Code of Conduct
            - [x] I have read the docs
            """.trimIndent() + "\n",
            body,
        )
    }

    @Test fun formTextareaRenderWrapsAnswerInCodeFence() {
        val fields = IssueTemplates.parseForm(
            "x.yml",
            """
            name: X
            body:
              - type: textarea
                attributes:
                  label: Logs
                  render: shell
            """.trimIndent(),
        )!!.fields
        assertEquals("### Logs\n\n```shell\necho hi\n```\n", IssueTemplates.renderBody(fields, mapOf(0 to listOf("echo hi"))))
    }

    @Test fun multipleDropdownJoinsSelectionsOnOneLine() {
        val fields = IssueTemplates.parseForm(
            "x.yml",
            """
            name: X
            body:
              - type: dropdown
                attributes:
                  label: Platforms
                  multiple: true
                  options: [Android, iOS, Web]
            """.trimIndent(),
        )!!.fields
        assertEquals("### Platforms\n\nAndroid, Web\n", IssueTemplates.renderBody(fields, mapOf(0 to listOf("Android", "Web"))))
    }

    @Test fun requiredCheckboxOptionsBlockSubmissionUntilChecked() {
        val fields = IssueTemplates.parseForm(
            "x.yml",
            """
            name: X
            body:
              - type: checkboxes
                attributes:
                  label: Before submitting
                  options:
                    - label: I searched existing issues
                      required: true
                    - label: Send me updates
            """.trimIndent(),
        )!!.fields
        assertEquals(listOf(0), IssueTemplates.missingRequired(fields, emptyMap()).map { it.index })
        assertEquals(listOf(0), IssueTemplates.missingRequired(fields, mapOf(0 to listOf("Send me updates"))).map { it.index })
        assertTrue(IssueTemplates.missingRequired(fields, mapOf(0 to listOf("I searched existing issues"))).isEmpty())
        assertEquals(
            "### Before submitting\n\n- [x] I searched existing issues\n- [ ] Send me updates\n",
            IssueTemplates.renderBody(fields, mapOf(0 to listOf("I searched existing issues"))),
        )
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

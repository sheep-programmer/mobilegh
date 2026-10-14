package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDispatchTest {
    private fun inputs(yaml: String) = WorkflowDispatch.parse(yaml).also { assertTrue(it.enabled) }.inputs.associateBy { it.name }

    @Test fun typedInputsWithQuotedStringsAndOtherTriggers() {
        val yaml = """
            name: Deploy
            # 手动部署
            on:
              push:
                branches: [ main, 'release/**' ]
              pull_request:
                types: [opened, synchronize]
              workflow_dispatch:
                inputs:
                  environment:
                    description: "Target environment"
                    type: environment
                    required: true
                  logLevel:
                    description: 'Log level: pick one'   # 行尾注释
                    required: true
                    default: 'warning'
                    type: choice
                    options:
                    - info
                    - "warning"
                    - 'debug # not a comment'
                  dry_run:
                    description: Dry run only
                    type: boolean
                    default: true
                  replicas:
                    type: number
                    default: 3
                  tag:
                    description: "Image tag, e.g. \"v1.2\""
                    required: false

            jobs:
              deploy:
                runs-on: ubuntu-latest
                steps:
                  - run: echo "on: ${'$'}{{ inputs.tag }}"
        """.trimIndent()
        val m = inputs(yaml)
        assertEquals(listOf("environment", "logLevel", "dry_run", "replicas", "tag"), m.keys.toList())
        assertEquals("environment", m["environment"]!!.type)
        assertTrue(m["environment"]!!.required)
        assertEquals("Target environment", m["environment"]!!.description)
        val level = m["logLevel"]!!
        assertEquals("choice", level.type)
        assertEquals("Log level: pick one", level.description)
        assertEquals("warning", level.default)
        assertEquals(listOf("info", "warning", "debug # not a comment"), level.options)
        assertEquals("boolean", m["dry_run"]!!.type)
        assertEquals("true", m["dry_run"]!!.default)
        assertFalse(m["dry_run"]!!.required)
        assertEquals("number", m["replicas"]!!.type)
        assertEquals("3", m["replicas"]!!.default)
        assertEquals("string", m["tag"]!!.type)
        assertEquals("Image tag, e.g. \"v1.2\"", m["tag"]!!.description)
        assertNull(m["tag"]!!.default)
    }

    @Test fun quotedOnKeyAndFlowOptions() {
        val yaml = """
            "on":
              workflow_dispatch:
                inputs:
                  target: { description: "Where", type: choice, options: [staging, "prod"], default: staging }
                  force: {type: boolean}
        """.trimIndent()
        val m = inputs(yaml)
        assertEquals(listOf("staging", "prod"), m["target"]!!.options)
        assertEquals("Where", m["target"]!!.description)
        assertEquals("staging", m["target"]!!.default)
        assertEquals("boolean", m["force"]!!.type)
    }

    @Test fun yaml11TrueKeyIsTreatedAsOn() {
        // PyYAML 等按 YAML 1.1 把 on 读成 true，再写回时就会变成 true:
        val yaml = """
            name: Release
            true:
              workflow_dispatch:
                inputs:
                  version:
                    description: Version to release
                    required: true
            jobs: {}
        """.trimIndent()
        val m = inputs(yaml)
        assertEquals("Version to release", m["version"]!!.description)
        assertTrue(m["version"]!!.required)
    }

    @Test fun multiLineDescriptionsUsingBlockScalars() {
        val yaml = """
            on:
              schedule:
                - cron: '0 3 * * *'
              workflow_dispatch:
                inputs:
                  notes:
                    description: |
                      First line.
                      Second line: with colon.

                      Third paragraph.
                    type: string
                  folded:
                    description: >-
                      This text
                      is folded.
                    default: ""
                  plain:
                    description: A long plain
                      description continued
                    default: x
        """.trimIndent()
        val m = inputs(yaml)
        assertEquals("First line.\nSecond line: with colon.\n\nThird paragraph.", m["notes"]!!.description)
        assertEquals("string", m["notes"]!!.type)
        assertEquals("This text is folded.", m["folded"]!!.description)
        assertEquals("", m["folded"]!!.default)
        assertEquals("A long plain description continued", m["plain"]!!.description)
        assertEquals("x", m["plain"]!!.default)
    }

    @Test fun dispatchWithoutInputsInEveryShape() {
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on: workflow_dispatch\njobs:\n  a: {}\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on: [push, workflow_dispatch]\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on:\n  - push\n  - workflow_dispatch\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on:\n- workflow_dispatch\njobs: {}\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on:\n  push:\n  workflow_dispatch:\n\njobs:\n  b:\n    runs-on: x\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on:\n  workflow_dispatch: {}\n"))
        assertEquals(DispatchSpec(true), WorkflowDispatch.parse("on:\r\n  workflow_dispatch:\r\n    inputs: {}\r\n"))
    }

    @Test fun workflowsWithoutDispatchOrWithoutOnAreNotRunnable() {
        assertFalse(WorkflowDispatch.parse("on: push\n").enabled)
        assertFalse(WorkflowDispatch.parse("on:\n  push:\n    branches: [main]\n  pull_request:\njobs:\n  workflow_dispatch:\n    runs-on: x\n").enabled)
        assertFalse(WorkflowDispatch.parse("name: x\njobs: {}\n").enabled)
        assertFalse(WorkflowDispatch.parse("").enabled)
        // jobs 里出现 on: 字样不应被当成触发器
        assertFalse(WorkflowDispatch.parse("name: x\njobs:\n  a:\n    on: workflow_dispatch\n").enabled)
    }

    @Test fun unknownTypesFallBackToStringAndMalformedLinesAreTolerated() {
        val yaml = """
            on:
              workflow_dispatch:
                inputs:
                  weird:
                    type: password
                    this line is not yaml
                    description: still read
                  bare:
                  last:
                    required: yes
        """.trimIndent()
        val m = inputs(yaml)
        assertEquals("string", m["weird"]!!.type)
        assertEquals("still read", m["weird"]!!.description)
        assertEquals(WorkflowInput("bare"), m["bare"])
        assertTrue(m["last"]!!.required)
    }

    @Test fun repoWriteHelpers() {
        assertEquals("mona-patch-1", RepoWrite.patchBranch("mona", listOf("main")))
        assertEquals("mona-patch-3", RepoWrite.patchBranch("mona", listOf("mona-patch-1", "Mona-Patch-2")))
        assertEquals("patch-1", RepoWrite.patchBranch(null, emptyList()))
        assertTrue(RepoWrite.validBranch("feature/new-ui"))
        listOf("", "a b", "a..b", "a.lock", "-x", "x/", "a~1", "a:b", "@").forEach { assertFalse(it, RepoWrite.validBranch(it)) }
        val text = "第一行\r\nsecond\r\n"
        assertTrue(RepoWrite.usesCrlf(text))
        assertEquals("第一行\nsecond\n", RepoWrite.toEditor(text))
        assertEquals(text, RepoWrite.fromEditor(RepoWrite.toEditor(text), true))
        assertEquals(text, RepoWrite.decode(RepoWrite.encode(text)))
        // contents 接口返回的 Base64 每 60 个字符换行
        val wrapped = RepoWrite.encode("x".repeat(200)).chunked(60).joinToString("\n")
        assertEquals("x".repeat(200), RepoWrite.decode(wrapped))
        assertNull(RepoWrite.decode(java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0))))
        assertEquals(3, RepoWrite.lineCount("a\nb\nc"))
        assertEquals("Update a\n\nwhy", RepoWrite.commitMessage(" Update a ", "why\n"))
        assertTrue(RepoWrite.isShaConflict(ApiException(409, "a.txt does not match abc")))
        assertTrue(RepoWrite.isShaConflict(ApiException(422, "请求无效：Invalid request.\n\n\"sha\" wasn't supplied.")))
        assertFalse(RepoWrite.isShaConflict(ApiException(422, "请求无效：Reference already exists")))
        assertTrue(RepoWrite.isAlreadyExists(ApiException(422, "请求无效：Reference already exists")))
    }
}

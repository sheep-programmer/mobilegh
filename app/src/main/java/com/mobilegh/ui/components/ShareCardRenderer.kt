package com.mobilegh.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.mobilegh.data.Repo
import com.mobilegh.i18n.tr
import java.io.File

/**
 * Renders crisp, shareable PNG cards for code snippets and repository summaries
 * and exports them via FileProvider ACTION_SEND.
 */
object ShareCardRenderer {
    private data class Palette(
        val outerBg: Int,
        val cardBg: Int,
        val headerBg: Int,
        val border: Int,
        val fg: Int,
        val fgMuted: Int,
        val accent: Int,
        val lineNo: Int,
        val badgeBg: Int,
    )

    private fun palette(dark: Boolean) = if (dark) {
        Palette(
            outerBg = 0xFF0B1020.toInt(),
            cardBg = 0xFF111827.toInt(),
            headerBg = 0xFF1F2937.toInt(),
            border = 0xFF374151.toInt(),
            fg = 0xFFF3F4F6.toInt(),
            fgMuted = 0xFF9CA3AF.toInt(),
            accent = 0xFF60A5FA.toInt(),
            lineNo = 0xFF6B7280.toInt(),
            badgeBg = 0xFF1E293B.toInt(),
        )
    } else {
        Palette(
            outerBg = 0xFFEFF4FA.toInt(),
            cardBg = 0xFFFFFFFF.toInt(),
            headerBg = 0xFFF6F8FA.toInt(),
            border = 0xFFD0D7DE.toInt(),
            fg = 0xFF1F2328.toInt(),
            fgMuted = 0xFF656D76.toInt(),
            accent = 0xFF0969DA.toInt(),
            lineNo = 0xFF8C959F.toInt(),
            badgeBg = 0xFFF3F4F6.toInt(),
        )
    }

    fun renderCodeCard(
        owner: String,
        name: String,
        path: String,
        lines: List<String>,
        startLine: Int,
        dark: Boolean,
    ): Bitmap {
        val p = palette(dark)
        val width = 1080
        val outerPad = 48f
        val innerPad = 36f
        val headerH = 92f
        val footerH = 68f
        val lineH = 38f
        val shown = lines.take(28)
        val bodyH = (shown.size.coerceAtLeast(1) * lineH) + innerPad * 2
        val cardW = width - outerPad * 2
        val cardH = headerH + bodyH + footerH
        val height = (cardH + outerPad * 2).toInt().coerceAtLeast(360)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(p.outerBg)

        val cardRect = RectF(outerPad, outerPad, width - outerPad, outerPad + cardH)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.cardBg }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.border
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(cardRect, 24f, 24f, fill)
        canvas.drawRoundRect(cardRect, 24f, 24f, stroke)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.headerBg }
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(cardRect, 24f, 24f, android.graphics.Path.Direction.CW) })
        canvas.drawRect(outerPad, outerPad, width - outerPad, outerPad + headerH, headerPaint)
        canvas.restore()

        val dotY = outerPad + headerH / 2f
        listOf(0xFFFF5F56.toInt(), 0xFFFFBD2E.toInt(), 0xFF27C93F.toInt()).forEachIndexed { idx, c ->
            canvas.drawCircle(outerPad + 36f + idx * 28f, dotY, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
        }

        val endLine = startLine + shown.size - 1
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fg
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val titleText = "$owner/$name · $path (L$startLine-$endLine)"
        val maxTitleW = cardW - 160f
        val clippedTitle = android.text.TextUtils.ellipsize(titleText, titlePaint, maxTitleW, android.text.TextUtils.TruncateAt.MIDDLE).toString()
        canvas.drawText(clippedTitle, outerPad + 132f, dotY + 9f, titlePaint)

        val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.lineNo
            textSize = 24f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
        val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fg
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        val numColX = outerPad + innerPad + 54f
        val codeX = numColX + 24f
        val maxCodeW = width - outerPad - innerPad - codeX
        var y = outerPad + headerH + innerPad + 24f
        shown.forEachIndexed { idx, raw ->
            canvas.drawText("${startLine + idx}", numColX, y, numPaint)
            val expanded = raw.replace("\t", "    ")
            val lineText = android.text.TextUtils.ellipsize(expanded, codePaint, maxCodeW, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(lineText, codeX, y, codePaint)
            y += lineH
        }

        val dividerPaint = Paint().apply { color = p.border; strokeWidth = 1.5f }
        val footerTop = outerPad + headerH + bodyH
        canvas.drawLine(outerPad, footerTop, width - outerPad, footerTop, dividerPaint)
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fgMuted
            textSize = 22f
        }
        canvas.drawText("github.com/$owner/$name · MobileGH", outerPad + innerPad, footerTop + 42f, footerPaint)
        return bmp
    }

    fun renderRepoCard(repo: Repo, dark: Boolean): Bitmap {
        val p = palette(dark)
        val width = 1080
        val outerPad = 48f
        val innerPad = 44f
        val cardW = width - outerPad * 2
        val textW = (cardW - innerPad * 2).toInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.accent
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fg
            textSize = 28f
        }
        val desc = repo.description?.takeIf { it.isNotBlank() } ?: tr("暂无仓库简介")
        val descLayout = StaticLayout.Builder.obtain(desc, 0, desc.length, descPaint, textW)
            .setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

        val cardH = innerPad * 2 + 64f + descLayout.height + 36f + 64f + 48f
        val height = (cardH + outerPad * 2).toInt().coerceAtLeast(360)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(p.outerBg)

        val cardRect = RectF(outerPad, outerPad, width - outerPad, outerPad + cardH)
        canvas.drawRoundRect(cardRect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.cardBg })
        canvas.drawRoundRect(cardRect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.border
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })

        var y = outerPad + innerPad + 36f
        val fullName = repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }
        val clippedName = android.text.TextUtils.ellipsize(fullName, titlePaint, textW.toFloat(), android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedName, outerPad + innerPad, y, titlePaint)

        y += 28f
        canvas.save()
        canvas.translate(outerPad + innerPad, y)
        descLayout.draw(canvas)
        canvas.restore()
        y += descLayout.height + 42f

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.badgeBg }
        val statPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fg
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pills = buildList {
            repo.language?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("★ ${fmtCount(repo.stargazersCount)}")
            add("⑂ ${fmtCount(repo.forksCount)}")
            repo.license?.spdxId?.takeIf { it.isNotBlank() && it != "NOASSERTION" }?.let { add(it) }
        }
        var pillX = outerPad + innerPad
        for (pill in pills) {
            val w = statPaint.measureText(pill) + 32f
            if (pillX + w > width - outerPad - innerPad) break
            canvas.drawRoundRect(RectF(pillX, y, pillX + w, y + 46f), 14f, 14f, badgePaint)
            canvas.drawText(pill, pillX + 16f, y + 31f, statPaint)
            pillX += w + 14f
        }

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fgMuted
            textSize = 22f
        }
        canvas.drawText("github.com/$fullName · MobileGH", outerPad + innerPad, outerPad + cardH - 28f, footerPaint)
        return bmp
    }

    fun shareBitmap(ctx: Context, bitmap: Bitmap, fileName: String, chooserTitle: String) {
        val dir = File(ctx.cacheDir, "share_cards").apply { mkdirs() }
        val file = File(dir, "$fileName.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

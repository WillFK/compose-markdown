package dev.jeziellago.compose.markdowntext

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.accessibility.AccessibilityEvent
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withTranslation
import androidx.core.text.getSpans
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableSpan
import kotlin.math.ceil

/**
 * This View contains a hack of the original TextView to fix the sizing issue of multiline text.
 * When a text has multiple lines, the TextView forcefully sets the width to match_parent, even if
 * the text layout does not span the whole width.
 *
 * The code comes from this article:
 * https://medium.com/@mxdiland/android-textview-multiline-problem-61f8c3499bbb
 */
class CustomTextView : AppCompatTextView {
    private enum class ExplicitLayoutAlignment {
        LEFT, CENTER, RIGHT
    }

    private var extraPaddingRight: Int? = null
    private var isTextSelectable: Boolean = false
    var wrapMultilineTextWidth: Boolean = false
    private var lastMeasureWidth = -1
    private var onBlockClick: (() -> Unit)? = null
    private var areLinkClicksEnabled: Boolean = true
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var hasMoved = false
    private var didLongPress = false
    private var didPerformClickForCurrentGesture = false
    private var isBlockLevelAccessibilityEnabled: Boolean = false
    private var blockAccessibilityHelper: BlockAccessibilityHelper? = null

    private data class AccessibilityBlock(
        val id: Int,
        val firstLine: Int,
        val lastLine: Int,
        val bounds: Rect,
        val text: String,
    )

    init {
        blockAccessibilityHelper = BlockAccessibilityHelper(this)
        ViewCompat.setAccessibilityDelegate(this, blockAccessibilityHelper)
    }

    constructor(context: Context) :
            super(context, null, android.R.attr.textViewStyle)

    constructor(context: Context, attrs: AttributeSet?) :
            super(context, attrs, android.R.attr.textViewStyle)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)

        // If width changed significantly, recalculate layout
        // This fixes rendering issues in LazyColumn where views are recycled
        if (lastMeasureWidth != measuredWidth && measuredWidth > 0) {
            lastMeasureWidth = measuredWidth
            invalidate()
            requestLayout()
            return
        }

        if (!layout.shouldWrap()) return

        val maxLineWidth = ceil(getMaxLineWidth(layout)).toInt()
        val uselessPaddingWidth = layout.width - maxLineWidth
        val wrappedWidth = measuredWidth - uselessPaddingWidth
        val height = measuredHeight
        setMeasuredDimension(wrappedWidth, height)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return (blockAccessibilityHelper?.dispatchHoverEvent(event) == true) || super.dispatchHoverEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!layout.shouldWrap()) {
            super.onDraw(canvas)
            return
        }

        val layoutWidth = layout.width
        val maxLineWidth = ceil(getMaxLineWidth(layout)).toInt()
        if (layoutWidth == maxLineWidth) {
            super.onDraw(canvas)
            return
        }

        val explicitLayoutAlignment = when (layout.alignment) {
            Layout.Alignment.ALIGN_CENTER -> ExplicitLayoutAlignment.CENTER

            Layout.Alignment.ALIGN_NORMAL ->
                if (layoutDirection == LAYOUT_DIRECTION_LTR) ExplicitLayoutAlignment.LEFT
                else ExplicitLayoutAlignment.RIGHT

            Layout.Alignment.ALIGN_OPPOSITE ->
                if (layoutDirection == LAYOUT_DIRECTION_LTR) ExplicitLayoutAlignment.RIGHT
                else ExplicitLayoutAlignment.LEFT

            // Default for Java null
            else -> ExplicitLayoutAlignment.LEFT
        }

        val dx = when (explicitLayoutAlignment) {
            ExplicitLayoutAlignment.RIGHT -> -1 * (layoutWidth - maxLineWidth)
            ExplicitLayoutAlignment.CENTER -> -1 * (layoutWidth - maxLineWidth) / 2
            else -> 0
        }
        drawTranslatedHorizontally(canvas, dx) { super.onDraw(it) }
    }

    override fun getCompoundPaddingRight(): Int =
        extraPaddingRight ?: super.getCompoundPaddingRight()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hasMoved = false
                didLongPress = false
                didPerformClickForCurrentGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasMoved) {
                    val deltaX = kotlin.math.abs(event.x - downX)
                    val deltaY = kotlin.math.abs(event.y - downY)
                    hasMoved = deltaX > touchSlop || deltaY > touchSlop
                }
            }
        }

        if (areLinkClicksEnabled &&
            (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN)
        ) {
            val link = getClickableSpans(event)

            if (link.isNotEmpty()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    link[0].onClick(this)
                }
                return true
            }
        }

        if (isTextSelectable) {
            val handledBySuper = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP &&
                !didLongPress &&
                !hasMoved &&
                selectionStart == selectionEnd &&
                !didPerformClickForCurrentGesture
            ) {
                performClick()
                return true
            }
            return handledBySuper
        }

        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (selectionStart < 0 || selectionEnd < 0) {
            (text as? Spannable)?.let {
                Selection.setSelection(it, it.length)
            }
        } else if (selectionStart != selectionEnd) {
            if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                val text = getText()
                setText(null)
                setText(text)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        blockAccessibilityHelper?.invalidateRoot()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            blockAccessibilityHelper?.invalidateRoot()
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up resources when view is recycled in LazyColumn
        // This prevents text corruption when views are reused
        resetTextState()
    }

    fun removeAllSpans() {
        val text = text
        if (text is Spannable) {
            val spans = text.getSpans(0, text.length, Any::class.java)
            for (span in spans) {
                (text as? Spannable)?.removeSpan(span)
            }
        }
    }

    fun resetTextState() {
        text = ""
        removeAllSpans()
        lastMeasureWidth = -1
        blockAccessibilityHelper?.invalidateRoot()
    }

    private fun getClickableSpans(event: MotionEvent): Array<ClickableSpan> {
        var x = event.x.toInt()
        var y = event.y.toInt()

        x -= totalPaddingLeft
        y -= totalPaddingTop

        x += scrollX
        y += scrollY

        val layout = layout ?: return emptyArray()

        if (y < 0 || y >= layout.height) {
            return emptyArray()
        }

        val line = layout.getLineForVertical(y)
        if (line < 0 || line >= layout.lineCount) {
            return emptyArray()
        }

        val off = layout.getOffsetForHorizontal(line, x.toFloat())

        val spannable = text as? Spannable ?: return emptyArray()
        val directClickableSpans = spannable.getSpans(off, off, ClickableSpan::class.java)
        if (directClickableSpans.isNotEmpty()) {
            return directClickableSpans
        }

        // Table cells render clickable spans inside row-internal layouts.
        val tableRowSpans = spannable.getSpans(off, off, TableRowSpan::class.java)
        if (tableRowSpans.isEmpty()) {
            return emptyArray()
        }

        val tableRowSpan = tableRowSpans[0]
        val rowLayout = tableRowSpan.findLayoutForHorizontalOffset(x) ?: return emptyArray()
        val rowY = layout.getLineTop(line)
        val rowRelativeY = y - rowY
        if (rowRelativeY < 0 || rowRelativeY >= rowLayout.height) {
            return emptyArray()
        }

        val rowLine = rowLayout.getLineForVertical(rowRelativeY)
        if (rowLine < 0 || rowLine >= rowLayout.lineCount) {
            return emptyArray()
        }

        val cellWidth = tableRowSpan.cellWidth()
        if (cellWidth <= 0) {
            return emptyArray()
        }

        // Map touch coordinates into the tapped table cell layout.
        val rowOff = rowLayout.getOffsetForHorizontal(rowLine, (x % cellWidth).toFloat())
        val rowText = rowLayout.text as? Spanned ?: return emptyArray()
        return rowText.getSpans(rowOff, rowOff, ClickableSpan::class.java)
    }

    override fun performClick(): Boolean {
        didPerformClickForCurrentGesture = true
        val handledBySuper = super.performClick()
        onBlockClick?.invoke()
        return handledBySuper || onBlockClick != null
    }

    override fun performLongClick(): Boolean {
        didLongPress = true
        return super.performLongClick()
    }

    override fun setTextIsSelectable(selectable: Boolean) {
        super.setTextIsSelectable(selectable)
        isTextSelectable = selectable
    }

    fun setOnBlockClickListener(listener: (() -> Unit)?) {
        onBlockClick = listener
        blockAccessibilityHelper?.invalidateRoot()
    }

    fun setLinkClicksEnabled(enabled: Boolean) {
        areLinkClicksEnabled = enabled
    }

    fun setBlockLevelAccessibilityEnabled(enabled: Boolean) {
        if (isBlockLevelAccessibilityEnabled == enabled) return
        isBlockLevelAccessibilityEnabled = enabled
        blockAccessibilityHelper?.invalidateRoot()
    }

    private fun getMaxLineWidth(layout: Layout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0.0f

    private fun drawTranslatedHorizontally(canvas: Canvas, dx: Int, onDraw: (Canvas) -> Unit) {
        extraPaddingRight = dx
        canvas.withTranslation(dx.toFloat(), 0f) {
            onDraw.invoke(this)
            extraPaddingRight = null
        }
    }

    private fun Layout?.shouldWrap(): Boolean {
        return this != null && lineCount > 1 && wrapMultilineTextWidth && !containsLongMarkdown()
    }

    private fun containsLongMarkdown(): Boolean {
        // Do not wrap width when displaying markers needing full width (tables etc...)
        val spannable = if (text is Spannable) text as Spannable else SpannableString(text)
        return spannable.getSpans<Any>(0, text.length).any {
            it is TableRowSpan || it is TableSpan || it is CodeBlockSpan || it is BlockQuoteSpan
        }
    }

    private fun buildAccessibilityBlocks(): List<AccessibilityBlock> {
        val layout = layout ?: return emptyList()
        val content = text?.toString().orEmpty()
        if (content.isBlank() || layout.lineCount == 0) return emptyList()

        val blocks = mutableListOf<AccessibilityBlock>()
        var runStartLine = -1
        var runStartOffset = -1

        fun isLineBlank(line: Int): Boolean {
            val start = layout.getLineStart(line).coerceIn(0, content.length)
            val end = layout.getLineEnd(line).coerceIn(0, content.length)
            if (start >= end) return true
            return content.substring(start, end).trim().isEmpty()
        }

        fun lineEndsWithExplicitNewline(line: Int): Boolean {
            val end = layout.getLineEnd(line).coerceIn(0, content.length)
            if (end <= 0) return false
            return content[end - 1] == '\n'
        }

        fun closeRun(lastLine: Int, runEndOffset: Int) {
            if (runStartLine == -1 || runStartOffset == -1) return
            val safeEndOffset = runEndOffset.coerceIn(0, content.length)
            val blockText = content.substring(runStartOffset.coerceIn(0, safeEndOffset), safeEndOffset).trim()
            if (blockText.isBlank()) {
                runStartLine = -1
                runStartOffset = -1
                return
            }

            val left = (totalPaddingLeft - scrollX).coerceAtLeast(0)
            val right = (width - totalPaddingRight - scrollX).coerceAtLeast(left + 1)
            val top = (totalPaddingTop + layout.getLineTop(runStartLine) - scrollY).coerceAtLeast(0)
            val bottom = (totalPaddingTop + layout.getLineBottom(lastLine) - scrollY).coerceAtLeast(top + 1)

            blocks += AccessibilityBlock(
                id = blocks.size,
                firstLine = runStartLine,
                lastLine = lastLine,
                bounds = Rect(left, top, right, bottom),
                text = blockText,
            )
            runStartLine = -1
            runStartOffset = -1
        }

        for (line in 0 until layout.lineCount) {
            if (isLineBlank(line)) {
                closeRun(
                    lastLine = line - 1,
                    runEndOffset = layout.getLineStart(line).coerceIn(0, content.length),
                )
                continue
            }

            if (runStartLine == -1) {
                runStartLine = line
                runStartOffset = layout.getLineStart(line).coerceIn(0, content.length)
            }

            if (lineEndsWithExplicitNewline(line) && line < layout.lineCount - 1) {
                closeRun(
                    lastLine = line,
                    runEndOffset = layout.getLineEnd(line).coerceIn(0, content.length),
                )
            }
        }

        if (runStartLine != -1) {
            val lastLine = layout.lineCount - 1
            closeRun(
                lastLine = lastLine,
                runEndOffset = layout.getLineEnd(lastLine).coerceIn(0, content.length),
            )
        }

        return blocks
    }

    private fun shouldUseBlockLevelAccessibility(): Boolean {
        // Disable block-level accessibility when content contains complex markdown structures
        // that have their own internal accessibility handling (tables, code blocks, blockquotes)
        if (!isBlockLevelAccessibilityEnabled) return false
        return !containsLongMarkdown()
    }

    private inner class BlockAccessibilityHelper(host: CustomTextView) : ExploreByTouchHelper(host) {

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            super.onPopulateNodeForHost(node)
            if (!shouldUseBlockLevelAccessibility()) {
                return
            }
            // Keep host as a container so TalkBack traverses virtual block children instead of
            // announcing one giant TextView node first.
            node.text = null
            node.contentDescription = null
            node.className = android.view.View::class.java.name
            node.isFocusable = false
            node.isClickable = false
            node.isScreenReaderFocusable = false
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (!shouldUseBlockLevelAccessibility()) return INVALID_ID
            if (x < 0f || y < 0f || x >= width.toFloat() || y >= height.toFloat()) {
                return INVALID_ID
            }

            val layout = layout ?: return INVALID_ID
            if (layout.height <= 0) return INVALID_ID

            val blocks = buildAccessibilityBlocks()
            if (blocks.isEmpty()) return INVALID_ID

            val tappedBlock = blocks.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
            if (tappedBlock != null) return tappedBlock.id

            val localY = (y.toInt() + scrollY - totalPaddingTop).coerceIn(0, layout.height - 1)
            val line = layout.getLineForVertical(localY)
            return blocks.firstOrNull { line in it.firstLine..it.lastLine }?.id ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (!shouldUseBlockLevelAccessibility()) return
            val blocks = buildAccessibilityBlocks()
            for (block in blocks) {
                virtualViewIds += block.id
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            if (!shouldUseBlockLevelAccessibility()) {
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                node.isVisibleToUser = false
                return
            }
            val block = buildAccessibilityBlocks().getOrNull(virtualViewId) ?: run {
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                node.isVisibleToUser = false
                return
            }

            node.className = AppCompatTextView::class.java.name
            node.packageName = context.packageName
            node.setBoundsInParent(block.bounds)
            node.text = block.text
            node.contentDescription = block.text
            node.isFocusable = true
            node.isVisibleToUser = block.bounds.bottom > 0 && block.bounds.top < height
            if (onBlockClick != null) {
                node.isClickable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            }
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?,
        ): Boolean {
            if (!shouldUseBlockLevelAccessibility()) return false
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK && onBlockClick != null) {
                onBlockClick?.invoke()
                sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                return true
            }
            return false
        }
    }
}

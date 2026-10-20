package com.itexpert120.attendanceManager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp

/** Collects the rows of a [ListGroup]; mirrors how LazyColumn's DSL builds its items. */
class ListGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        rows += content
    }

    fun <T> items(items: List<T>, content: @Composable (T) -> Unit) {
        items.forEach { entry -> rows += { content(entry) } }
    }
}

/**
 * Material 3 grouped list: rows are separate rounded segments with a small gap between them; the group's
 * outer corners are large, the inner ones small (the PWA's `ListGroup`). Rows draw no background of their
 * own, so use [ListRow] or any custom row content.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    muted: Boolean = false,
    content: ListGroupScope.() -> Unit,
) {
    val rows = ListGroupScope().apply(content).rows
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { index, row ->
                Surface(
                    shape = segmentShape(index, rows.size),
                    color = if (muted) colors.surfaceContainerHighest else colors.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) { row() }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

private fun segmentShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OuterCorner else InnerCorner,
    topEnd = if (index == 0) OuterCorner else InnerCorner,
    bottomStart = if (index == count - 1) OuterCorner else InnerCorner,
    bottomEnd = if (index == count - 1) OuterCorner else InnerCorner,
)

/** A Material 3 list item: 56dp for one line, 72dp with [detail]. Clickable when [onClick] is given. */
@Composable
fun ListRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailMaxLines: Int = 1,
    value: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = if (detail != null) 72.dp else 56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) colors.onErrorContainer else colors.onSecondaryContainer,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (destructive) colors.errorContainer else colors.secondaryContainer)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) colors.error else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

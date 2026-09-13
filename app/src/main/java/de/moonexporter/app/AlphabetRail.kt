package de.moonexporter.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AlphabetRail(
    books: List<BookItem>,
    modifier: Modifier = Modifier,
    onBookIndex: (Int) -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
) {
    val letters = remember { listOf("#") + ('A'..'Z').map(Char::toString) }
    val firstIndex = remember(books) {
        buildMap<String, Int> {
            books.forEachIndexed { index, book ->
                val first = book.title.trim().firstOrNull()?.uppercaseChar()
                val section = if (first != null && first in 'A'..'Z') first.toString() else "#"
                putIfAbsent(section, index)
            }
        }
    }
    var heightPx by remember { mutableIntStateOf(1) }

    fun jump(y: Float) {
        if (heightPx <= 0) return
        val slot = ((y.coerceIn(0f, heightPx.toFloat() - 1f) / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val requested = letters[slot]
        val target = firstIndex[requested]
            ?: (slot downTo 0).firstNotNullOfOrNull { firstIndex[letters[it]] }
            ?: (slot..letters.lastIndex).firstNotNullOfOrNull { firstIndex[letters[it]] }
        if (target != null) onBookIndex(target)
    }

    Column(
        modifier = modifier
            .width(32.dp)
            .fillMaxHeight(0.9f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f))
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "↑",
            fontSize = 18.sp,
            lineHeight = 20.sp,
            modifier = Modifier.clickable(onClick = onTop).padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(books) {
                    detectVerticalDragGestures(
                        onDragStart = { jump(it.y) },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            jump(change.position.y)
                        },
                    )
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                val enabled = firstIndex.containsKey(letter)
                Text(
                    text = letter,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    modifier = Modifier
                        .alpha(if (enabled) 1f else 0.28f)
                        .clickable(enabled = enabled) { firstIndex[letter]?.let(onBookIndex) }
                        .padding(horizontal = 5.dp),
                )
            }
        }

        Text(
            text = "↓",
            fontSize = 18.sp,
            lineHeight = 20.sp,
            modifier = Modifier.clickable(onClick = onBottom).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

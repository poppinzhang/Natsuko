package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder

/** Drag coordinates stay in the grid viewport, including while the content scrolls underneath. */
internal class HomeFeatureReorderState(
    private val gridState: LazyGridState,
    initialOrder: List<Feature>,
    private val save: (List<Feature>) -> Unit,
) {
    var order by mutableStateOf(initialOrder)
        private set
    var draggedFeature by mutableStateOf<Feature?>(null)
        private set
    private var originalOrder = initialOrder
    private var draggedLeft by mutableFloatStateOf(0f)
    private var draggedTop by mutableFloatStateOf(0f)
    private var draggedWidth = 0
    private var draggedHeight = 0
    private var pointerX = 0f
    private var pointerY = 0f
    private var awaitingItemIndex: Int? = null

    fun updateSavedOrder(saved: List<Feature>) {
        if (saved != originalOrder) {
            draggedFeature = null
            awaitingItemIndex = null
            order = saved
            originalOrder = saved
        }
    }

    fun start(position: Offset, leftContentPaddingPx: Float): Boolean {
        val localX = position.x - leftContentPaddingPx
        val localY = position.y - gridState.layoutInfo.beforeContentPadding
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
            localX >= candidate.offset.x && localX < candidate.offset.x + candidate.size.width &&
                localY >= candidate.offset.y && localY < candidate.offset.y + candidate.size.height
        } ?: return false
        val feature = order.firstOrNull { it.name == item.key } ?: return false
        originalOrder = order
        awaitingItemIndex = null
        draggedLeft = item.offset.x.toFloat()
        draggedTop = item.offset.y.toFloat()
        draggedWidth = item.size.width
        draggedHeight = item.size.height
        pointerX = localX
        pointerY = localY
        draggedFeature = feature
        return true
    }

    fun dragBy(delta: Offset) {
        if (draggedFeature == null) return
        pointerX += delta.x
        pointerY += delta.y
        draggedLeft += delta.x
        draggedTop += delta.y
        moveIfNeeded()
    }

    fun translationFor(feature: Feature): Offset {
        if (draggedFeature != feature) return Offset.Zero
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == feature.name } ?: return Offset.Zero
        return Offset(draggedLeft - item.offset.x, draggedTop - item.offset.y)
    }

    suspend fun scrollAndMove(edgeSize: Float, maxDistance: Float) {
        if (draggedFeature == null) return
        val layout = gridState.layoutInfo
        // Use the same content-relative viewport coordinates as the dragged card.
        val top = layout.viewportStartOffset
        val bottom = layout.viewportEndOffset
        val fraction = when {
            pointerY < top + edgeSize -> -((top + edgeSize - pointerY) / edgeSize).coerceIn(0f, 1f)
            pointerY > bottom - edgeSize -> ((pointerY - bottom + edgeSize) / edgeSize).coerceIn(0f, 1f)
            else -> 0f
        }
        if (fraction != 0f) gridState.scrollBy(fraction * maxDistance)
        moveIfNeeded()
    }

    private fun moveIfNeeded() {
        val feature = draggedFeature ?: return
        val featureKeys = order.mapTo(HashSet(order.size), Feature::name)
        val featureItems = gridState.layoutInfo.visibleItemsInfo.filter { it.key in featureKeys }
        if (featureItems.isEmpty()) return

        val item = featureItems.firstOrNull { it.key == feature.name }
        if (awaitingItemIndex != null) {
            // Wait for the previous move to be laid out before considering another move.
            if (item != null && item.index != awaitingItemIndex) return
            awaitingItemIndex = null
        }

        val centerX = draggedLeft + draggedWidth / 2f
        val centerY = draggedTop + draggedHeight / 2f
        val minX = featureItems.minOf { it.offset.x }.toFloat()
        val maxX = featureItems.maxOf { it.offset.x + it.size.width - 1 }.toFloat()
        val hitX = centerX.coerceIn(minX, maxX)

        val targetItem = featureItems.firstOrNull { candidate ->
            hitX >= candidate.offset.x && hitX < candidate.offset.x + candidate.size.width &&
                centerY >= candidate.offset.y && centerY < candidate.offset.y + candidate.size.height
        }
        if (targetItem != null) {
            if (targetItem.key == feature.name) return
            val target = order.firstOrNull { it.name == targetItem.key } ?: return
            move(feature, target, expectedGridIndex = targetItem.index)
            return
        }

        val firstVisible = featureItems.minBy { it.index }
        if (firstVisible.key == order.first().name &&
            (pointerY < firstVisible.offset.y || centerY < firstVisible.offset.y)
        ) {
            if (feature != order.first()) {
                move(feature, order.first(), expectedGridIndex = firstVisible.index)
            }
            return
        }

        val lastVisible = featureItems.maxBy { it.index }
        val lastBottom = lastVisible.offset.y + lastVisible.size.height
        val pastLastRow = pointerY > lastBottom || centerY > lastBottom
        val rightOfIncompleteLastRow = centerY >= lastVisible.offset.y &&
            hitX > lastVisible.offset.x + lastVisible.size.width
        if (lastVisible.key == order.last().name && (pastLastRow || rightOfIncompleteLastRow)) {
            if (feature != order.last()) {
                move(feature, order.last(), expectedGridIndex = lastVisible.index)
            }
        }
    }

    private fun move(feature: Feature, target: Feature, expectedGridIndex: Int? = null) {
        // Keep viewport position by index; automatic key anchoring would otherwise follow the dragged item.
        gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        awaitingItemIndex = expectedGridIndex
        order = FeatureOrder.move(order, feature, target)
    }

    fun finish() {
        if (draggedFeature == null) return
        draggedFeature = null
        awaitingItemIndex = null
        if (order != originalOrder) save(order)
        originalOrder = order
    }

    fun cancel() {
        if (draggedFeature == null) return
        draggedFeature = null
        awaitingItemIndex = null
        order = originalOrder
    }

    fun moveBy(feature: Feature, delta: Int): Boolean {
        if (draggedFeature != null) return false
        val index = order.indexOf(feature)
        if (index < 0) return false
        val target = order.getOrNull(index + delta) ?: return false
        move(feature, target)
        save(order)
        originalOrder = order
        return true
    }
}

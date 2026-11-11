package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder

/** Drag coordinates stay in the list viewport, including while the content scrolls underneath. */
internal class HomeFeatureReorderState(
    private val listState: LazyListState,
    initialOrder: List<Feature>,
    private val save: (List<Feature>) -> Unit,
) {
    var order by mutableStateOf(initialOrder)
        private set
    var draggedFeature by mutableStateOf<Feature?>(null)
        private set
    private var originalOrder = initialOrder
    private var draggedTop by mutableFloatStateOf(0f)
    private var draggedHeight = 0
    private var pointerY = 0f

    fun updateSavedOrder(saved: List<Feature>) {
        if (saved != originalOrder) {
            draggedFeature = null
            order = saved
            originalOrder = saved
        }
    }

    fun start(y: Float): Boolean {
        val localY = y - listState.layoutInfo.beforeContentPadding
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { localY >= it.offset && localY < it.offset + it.size }
            ?: return false
        val feature = order.firstOrNull { it.name == item.key } ?: return false
        originalOrder = order
        draggedTop = item.offset.toFloat()
        draggedHeight = item.size
        pointerY = localY
        draggedFeature = feature
        return true
    }

    fun dragBy(delta: Float) {
        if (draggedFeature == null) return
        pointerY += delta
        draggedTop += delta
        moveIfNeeded()
    }

    fun translationFor(feature: Feature): Float {
        if (draggedFeature != feature) return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == feature.name } ?: return 0f
        return draggedTop - item.offset
    }

    suspend fun scrollAndMove(edgeSize: Float, maxDistance: Float) {
        if (draggedFeature == null) return
        val layout = listState.layoutInfo
        // Use the same content-relative viewport coordinates as the dragged card.
        val top = layout.viewportStartOffset
        val bottom = layout.viewportEndOffset
        val fraction = when {
            pointerY < top + edgeSize -> -((top + edgeSize - pointerY) / edgeSize).coerceIn(0f, 1f)
            pointerY > bottom - edgeSize -> ((pointerY - bottom + edgeSize) / edgeSize).coerceIn(0f, 1f)
            else -> 0f
        }
        if (fraction != 0f) listState.scrollBy(fraction * maxDistance)
        moveIfNeeded()
    }

    private fun moveIfNeeded() {
        val feature = draggedFeature ?: return
        val index = order.indexOf(feature)
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.key == feature.name } ?: return
        val direction = if (draggedTop > item.offset) 1 else -1
        val target = order.getOrNull(index + direction) ?: return
        val neighbor = visible.firstOrNull { it.key == target.name } ?: return
        // Wait for the previous move to be laid out before considering another move.
        if (neighbor.index != item.index + direction) return
        val midpoint = neighbor.offset + neighbor.size / 2f
        if ((direction > 0 && draggedTop + draggedHeight > midpoint) || (direction < 0 && draggedTop < midpoint)) {
            move(feature, target)
        }
    }

    private fun move(feature: Feature, target: Feature) {
        // Keep viewport position by index; automatic key anchoring would otherwise follow the dragged first row.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        order = FeatureOrder.move(order, feature, target)
    }

    fun finish() {
        if (draggedFeature == null) return
        draggedFeature = null
        if (order != originalOrder) save(order)
        originalOrder = order
    }

    fun cancel() {
        if (draggedFeature == null) return
        draggedFeature = null
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

package com.poppinzhang.natsuko.data.twitter

enum class TwitterDownloadScope { CURRENT, ALL }

/** The link's one-based attachment position and the number of videos available for this batch. */
data class TwitterVideoSelection(val mediaIndex: Int, val videoCount: Int)

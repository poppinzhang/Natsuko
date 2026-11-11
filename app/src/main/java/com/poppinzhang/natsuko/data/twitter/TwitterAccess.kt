package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import java.io.IOException

/** Only a positive, post-specific login signal may start the login flow. */
enum class TwitterAccessFailure {
    LOGIN_REQUIRED, SESSION_EXPIRED, DELETED, PROTECTED, AGE_RESTRICTED, WITHHELD,
    UNAVAILABLE, RATE_LIMITED, UNKNOWN,
}

class TwitterAccessException(val reason: TwitterAccessFailure) : IOException(reason.name), LocalizedFailure {
    override val userMessage: UiText = text(when (reason) {
        TwitterAccessFailure.LOGIN_REQUIRED -> R.string.twitter_access_login_required
        TwitterAccessFailure.SESSION_EXPIRED -> R.string.twitter_access_session_expired
        TwitterAccessFailure.DELETED -> R.string.twitter_access_deleted
        TwitterAccessFailure.PROTECTED -> R.string.twitter_access_protected
        TwitterAccessFailure.AGE_RESTRICTED -> R.string.twitter_access_age_restricted
        TwitterAccessFailure.WITHHELD -> R.string.twitter_access_withheld
        TwitterAccessFailure.UNAVAILABLE -> R.string.twitter_access_unavailable
        TwitterAccessFailure.RATE_LIMITED -> R.string.backend_x_rate_limited
        TwitterAccessFailure.UNKNOWN -> R.string.twitter_access_unknown
    })
}

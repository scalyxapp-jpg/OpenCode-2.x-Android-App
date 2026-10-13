package com.opencode.android.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri

/**
 * Link handling for plain (non-Markdown) chat text.
 *
 * Markdown answers already auto-link bare URLs, but a user PROMPT (and tool output)
 * was rendered as plain text: a URL in it could not be tapped at all. Everything a user
 * can see in the chat should be tappable and open in a browser.
 */

/**
 * Opens http(s) links externally.
 *
 * `CATEGORY_BROWSABLE` keeps it a web action (browser or an app that claims the domain),
 * and a failure (no handler, malformed URI) is reported instead of crashing the tap.
 */
@Composable
internal fun rememberBrowserLinkListener(): LinkInteractionListener {
    val context = LocalContext.current
    return remember(context) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url
            val uri = url?.let { runCatching { it.toUri() }.getOrNull() }
            if (uri == null) return@LinkInteractionListener
            val intent =
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            runCatching { context.startActivity(intent) }.onFailure {
                toast(context, context.getString(com.opencode.android.R.string.could_not_open_link))
            }
        }
    }
}

/** Underline + link colour, the treatment Markdown anchors use. */
internal fun browserLinkStyles(linkColor: Color): TextLinkStyles =
    TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
    )

/**
 * [text] with every bare URL turned into a tappable link.
 *
 * Same URL detection as the Markdown renderer ([autolinkAt]), so `www.example.com` in a
 * prompt behaves like `www.example.com` in an answer. Pure regex work (one pass), which is
 * why it is also safe for very long texts — the Markdown parser bails out above
 * `MARKDOWN_MAX_CHARS`, but links must stay tappable there too.
 */
internal fun autoLinkedText(
    text: String,
    linkColor: Color,
    linkListener: LinkInteractionListener? = null,
): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val match = URL_PATTERN.find(text, index)
            if (match == null) {
                append(text.substring(index))
                break
            }
            val range = autolinkAt(text, match.range.first)
            if (range == null) {
                append(text.substring(index, match.range.last + 1))
                index = match.range.last + 1
                continue
            }
            if (range.first > index) append(text.substring(index, range.first))
            val url = text.substring(range.first, range.last + 1)
            val href = if (url.startsWith("http")) url else "https://$url"
            withLink(
                LinkAnnotation.Url(
                    url = href,
                    styles = browserLinkStyles(linkColor),
                    linkInteractionListener = linkListener,
                ),
            ) { append(url) }
            index = range.last + 1
        }
    }

/** [autoLinkedText] memoised for a composable. */
@Composable
internal fun rememberAutoLinked(
    text: String,
    linkColor: Color,
): AnnotatedString {
    val listener = rememberBrowserLinkListener()
    return remember(text, linkColor, listener) { autoLinkedText(text, linkColor, listener) }
}

/**
 * Bare-URL pattern shared with the Markdown renderer. Kept here (not duplicated) so a
 * prompt and an answer recognise exactly the same URLs.
 */
internal val URL_PATTERN = Regex("""(https?://[^\s<>\[\]"']+|www\.[^\s<>\[\]"']+)""")

/** Style-only variant for callers that render their own Text. */
internal fun linkColorFor(textStyle: TextStyle): Color = textStyle.color.takeIf { it != Color.Unspecified } ?: Color(0xFF00BFFF)

/** Unused style marker so the helper stays independent of MaterialTheme. */
internal val DEFAULT_LINK_COLOR: Color = Color(0xFF00BFFF)

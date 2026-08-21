package app.libre.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.paging.PagingDataAdapter
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.obj.Comment
import app.libre.databinding.CommentsReplyRowBinding
import app.libre.databinding.CommentsRowBinding
import app.libre.extensions.formatShort
import app.libre.helpers.ImageHelper
import app.libre.helpers.ThemeHelper
import app.libre.ui.adapters.callbacks.DiffUtilItemCallback
import app.libre.ui.viewholders.CommentViewHolder
import app.libre.util.HtmlParser
import app.libre.util.LinkHandler
import app.libre.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommentsPagingAdapter(
    private val isReplies: Boolean,
    private val channelAvatar: String?,
    private val videoId: String? = null,
    private val handleLink: (url: String) -> Unit,
    private val saveToClipboard: (Comment) -> Unit,
    private val navigateToChannel: (Comment) -> Unit,
    private val navigateToReplies: ((Comment, String?) -> Unit)? = null,
) : PagingDataAdapter<Comment, CommentViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.commentId == newItem.commentId },
        areContentsTheSame = { _, _ -> true },
    )
) {

    private var clickEventConsumedByLinkHandler = false
    private val cachedReplies = mutableMapOf<String, List<Comment>>()
    private val expandedComments = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = CommentsRowBinding.inflate(layoutInflater, parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.binding.apply {
            val comment = getItem(position)!!
            val commentId = comment.commentId.orEmpty()
            commentAuthor.text = comment.author
            commentAuthor.setBackgroundResource(
                if (comment.channelOwner) R.drawable.comment_channel_owner_bg else 0
            )
            commentInfos.text = comment.commentedTimeMillis?.let {
                TextUtils.formatRelativeDate(it)
            } ?: comment.commentedTime

            commentText.movementMethod = LinkMovementMethodCompat.getInstance()
            val linkHandler = LinkHandler {
                clickEventConsumedByLinkHandler = true
                handleLink.invoke(it)
            }
            commentText.text = comment.commentText?.replace("</a>", "</a> ")
                ?.parseAsHtml(tagHandler = HtmlParser(linkHandler))

            ImageHelper.loadImage(comment.thumbnail, commentorImage, true)
            likesTextView.text = comment.likeCount.formatShort()

            if (comment.creatorReplied && !channelAvatar.isNullOrBlank()) {
                ImageHelper.loadImage(channelAvatar, creatorReplyImageView, true)
                creatorReplyImageView.isVisible = true
            } else {
                creatorReplyImageView.setImageDrawable(null)
                creatorReplyImageView.isVisible = false
            }

            verifiedImageView.isVisible = comment.verified
            pinnedImageView.isVisible = comment.pinned
            heartedImageView.isVisible = comment.hearted
            repliesCount.isVisible = false

            commentorImage.setOnClickListener {
                navigateToChannel(comment)
            }

            // Inline YouTube-style replies handling
            val hasReplies = !isReplies && comment.repliesPage != null
            repliesToggleButton.isVisible = hasReplies

            if (hasReplies) {
                val repliesWord = root.context.getString(R.string.replies).lowercase()
                val countText = if (comment.replyCount > 0) "${comment.replyCount.formatShort()} $repliesWord" else root.context.getString(R.string.replies)
                val isExpanded = expandedComments.contains(commentId)

                repliesContainer.isVisible = isExpanded
                repliesArrowIcon.setImageResource(if (isExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
                repliesText.text = if (isExpanded) "Hide $countText" else countText

                if (isExpanded) {
                    val cached = cachedReplies[commentId]
                    if (cached != null) {
                        repliesProgress.isVisible = false
                        populateReplies(repliesList, cached)
                    } else {
                        loadReplies(comment, commentId)
                    }
                }

                repliesToggleButton.setOnClickListener {
                    val currentlyExpanded = expandedComments.contains(commentId)
                    if (currentlyExpanded) {
                        expandedComments.remove(commentId)
                        repliesContainer.isVisible = false
                        repliesArrowIcon.setImageResource(R.drawable.ic_arrow_down)
                        repliesText.text = countText
                    } else {
                        expandedComments.add(commentId)
                        repliesContainer.isVisible = true
                        repliesArrowIcon.setImageResource(R.drawable.ic_arrow_up)
                        repliesText.text = "Hide $countText"

                        val cached = cachedReplies[commentId]
                        if (cached != null) {
                            repliesProgress.isVisible = false
                            populateReplies(repliesList, cached)
                        } else {
                            loadReplies(comment, commentId)
                        }
                    }
                }
            }

            if (isReplies) {
                // highlight the comment that is being replied to
                if (position == 0) {
                    root.setBackgroundColor(
                        ThemeHelper.getThemeColor(
                            root.context,
                            com.google.android.material.R.attr.colorSurface
                        )
                    )
                } else {
                    root.background = AppCompatResources.getDrawable(
                        root.context,
                        R.drawable.rounded_ripple
                    )
                    commentorImage.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = 58 }
                }
            }

            root.setOnLongClickListener {
                saveToClipboard(comment)
                true
            }
        }
    }

    private fun CommentsRowBinding.loadReplies(comment: Comment, commentId: String) {
        repliesProgress.isVisible = true
        repliesList.removeAllViews()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val key = comment.repliesPage.orEmpty()
                val result = withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getCommentsNextPage(videoId.orEmpty(), key)
                }
                val replies = result.comments.filter { it.commentId != comment.commentId }
                cachedReplies[commentId] = replies
                if (expandedComments.contains(commentId)) {
                    repliesProgress.isVisible = false
                    populateReplies(repliesList, replies)
                }
            } catch (_: Exception) {
                repliesProgress.isVisible = false
            }
        }
    }

    private fun populateReplies(container: LinearLayout, replies: List<Comment>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        for (reply in replies) {
            val replyBinding = CommentsReplyRowBinding.inflate(inflater, container, false)
            replyBinding.commentAuthor.text = reply.author
            replyBinding.commentAuthor.setBackgroundResource(
                if (reply.channelOwner) R.drawable.comment_channel_owner_bg else 0
            )
            replyBinding.commentInfos.text = reply.commentedTimeMillis?.let {
                TextUtils.formatRelativeDate(it)
            } ?: reply.commentedTime

            replyBinding.commentText.movementMethod = LinkMovementMethodCompat.getInstance()
            val linkHandler = LinkHandler { handleLink.invoke(it) }
            replyBinding.commentText.text = reply.commentText?.replace("</a>", "</a> ")
                ?.parseAsHtml(tagHandler = HtmlParser(linkHandler))

            ImageHelper.loadImage(reply.thumbnail, replyBinding.commentorImage, true)
            replyBinding.likesTextView.text = reply.likeCount.formatShort()
            replyBinding.verifiedImageView.isVisible = reply.verified
            replyBinding.heartedImageView.isVisible = reply.hearted

            replyBinding.commentorImage.setOnClickListener { navigateToChannel(reply) }
            replyBinding.root.setOnLongClickListener {
                saveToClipboard(reply)
                true
            }
            container.addView(replyBinding.root)
        }
    }
}
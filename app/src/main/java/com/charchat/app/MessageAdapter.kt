package com.charchat.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean,
    // Transient UI state only (a thinking placeholder always has blank content, which persist()
    // already filters out) - never meaningfully round-trips through JSON, but default it safely.
    val isThinking: Boolean = false,
    // Which character said this, for a group chat's multiple assistants sharing one conversation.
    // Always null for a user message and for any message in a solo (single-character) chat, where
    // the single character is already implied by which conversation this is.
    val speakerId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("content", content)
        put("isUser", isUser)
        put("speakerId", speakerId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Message = Message(
            id = json.optString("id"),
            content = json.optString("content"),
            isUser = json.optBoolean("isUser"),
            speakerId = json.optString("speakerId", "").takeIf { it.isNotBlank() }
        )

        fun listToJson(messages: List<Message>): JSONArray =
            JSONArray().apply { messages.forEach { put(it.toJson()) } }

        fun listFromJson(json: JSONArray): List<Message> =
            (0 until json.length()).map { fromJson(json.getJSONObject(it)) }
    }
}

private val STYLE_REGEX = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*")

/**
 * Renders lightweight roleplay markdown (**emphasis**, *actions*) as actual text styling instead
 * of showing the raw asterisks.
 */
fun styleRoleplayText(raw: String): CharSequence {
    if (raw.isEmpty()) return raw

    val builder = SpannableStringBuilder()
    var lastEnd = 0
    for (match in STYLE_REGEX.findAll(raw)) {
        if (match.range.first > lastEnd) {
            builder.append(raw.substring(lastEnd, match.range.first))
        }
        val bold = match.groupValues[1]
        val start = builder.length
        if (bold.isNotEmpty()) {
            builder.append(bold)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            builder.append(match.groupValues[2])
            builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < raw.length) {
        builder.append(raw.substring(lastEnd))
    }
    return builder
}

class MessageAdapter(
    private val messages: List<Message>,
    // Only offered on a solo chat's most recent exchange - null (the default) hides both actions,
    // which is what a group chat (many possible "last" speakers, no single redo target) wants.
    private val onRegenerateLast: (() -> Unit)? = null,
    private val onEditLastUser: (() -> Unit)? = null,
    // Long-press on any message (not just the last exchange) to delete it and everything after -
    // the direct way to strike a bad reply (a hallucinated fact, the model deciding what the
    // user's character did) from the conversation's actual memory the moment it appears, instead
    // of it standing as canon and compounding for the rest of the chat. Offered in both solo and
    // group chats, unlike regenerate/edit-last, since "delete from here" is unambiguous regardless
    // of how many possible speakers there are.
    private val onDeleteFrom: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var characterAvatar: Bitmap? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var characterAvatarStyle: AvatarStyle? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * Group chat mode: maps each participating character's id to itself, so every assistant
     * message can look up and show its own speaker's avatar/name instead of the single shared
     * [characterAvatar]/[characterAvatarStyle] a solo chat uses. Empty (the default) means solo
     * mode.
     */
    var speakers: Map<String, Character> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = layoutInflater.inflate(R.layout.item_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val contentView = holder.itemView.findViewById<TextView>(R.id.msg_content)

        holder.itemView.setOnLongClickListener {
            if (message.isThinking) return@setOnLongClickListener false
            onDeleteFrom?.invoke(position)
            onDeleteFrom != null
        }

        if (holder is AssistantMessageViewHolder) {
            if (message.isThinking) {
                contentView.visibility = View.GONE
                holder.thinkingDots.visibility = View.VISIBLE
                holder.startThinkingAnimation()
            } else {
                holder.stopThinkingAnimation()
                holder.thinkingDots.visibility = View.GONE
                contentView.visibility = View.VISIBLE
                contentView.text = styleRoleplayText(message.content)
            }

            val avatarView = holder.itemView.findViewById<ImageView>(R.id.msg_avatar)
            val avatarLetter = holder.itemView.findViewById<TextView>(R.id.msg_avatar_letter)
            val avatarTile = holder.itemView.findViewById<View>(R.id.msg_avatar_tile)
            val nameLabel = holder.itemView.findViewById<TextView>(R.id.msg_speaker_name)
            val context = holder.itemView.context

            val speaker = message.speakerId?.let { speakers[it] }
            val bitmap: Bitmap?
            val style: AvatarStyle?
            if (speakers.isNotEmpty()) {
                // Group mode: each message shows its own speaker, falling back to a "?" tile for
                // an unknown/removed character rather than silently borrowing another one's avatar.
                // Shown while thinking too, so it's clear who's about to reply.
                nameLabel.visibility = View.VISIBLE
                nameLabel.text = speaker?.name?.ifBlank { "Unnamed" } ?: "Unknown"
                bitmap = speaker?.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                style = speaker?.avatarStyle()
            } else {
                nameLabel.visibility = View.GONE
                bitmap = characterAvatar
                style = characterAvatarStyle
            }

            if (bitmap != null) {
                avatarView.setImageBitmap(bitmap)
                avatarView.visibility = View.VISIBLE
                avatarLetter.visibility = View.GONE
            } else {
                avatarView.visibility = View.GONE
                avatarLetter.visibility = View.VISIBLE
                avatarLetter.text = style?.letter ?: "?"
                avatarLetter.setTextColor(context.getColor(style?.foregroundColorRes ?: R.color.avatar_fg_green))
                avatarTile.setCircularAvatarBackground(style?.backgroundColorRes ?: R.color.avatar_bg_green)
            }

            val regenerateView = holder.itemView.findViewById<View>(R.id.msg_regenerate)
            val canRegenerate = onRegenerateLast != null && !message.isThinking && position == messages.lastIndex
            regenerateView.visibility = if (canRegenerate) View.VISIBLE else View.GONE
            regenerateView.setOnClickListener { onRegenerateLast?.invoke() }
        } else {
            contentView.text = styleRoleplayText(message.content)

            val editView = holder.itemView.findViewById<View>(R.id.msg_edit)
            val lastMessage = messages.lastOrNull()
            val isLastExchange = position == messages.lastIndex ||
                (position == messages.lastIndex - 1 && lastMessage != null && !lastMessage.isUser && !lastMessage.isThinking)
            val canEdit = onEditLastUser != null && isLastExchange
            editView.visibility = if (canEdit) View.VISIBLE else View.GONE
            editView.setOnClickListener { onEditLastUser?.invoke() }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AssistantMessageViewHolder) {
            holder.stopThinkingAnimation()
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thinkingDots: View = view.findViewById(R.id.thinking_dots)
        private val dots = listOf<View>(
            view.findViewById(R.id.dot_1),
            view.findViewById(R.id.dot_2),
            view.findViewById(R.id.dot_3)
        )
        private var animatorSet: AnimatorSet? = null

        fun startThinkingAnimation() {
            if (animatorSet?.isRunning == true) return
            val bounceDp = dots.first().resources.displayMetrics.density * -3f
            val animators = dots.mapIndexed { index, dot ->
                val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.25f, 1f, 0.25f)
                val translateY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, bounceDp, 0f)
                ObjectAnimator.ofPropertyValuesHolder(dot, alpha, translateY).apply {
                    duration = 900
                    startDelay = index * 150L
                    repeatCount = ValueAnimator.INFINITE
                }
            }
            animatorSet = AnimatorSet().apply {
                playTogether(animators)
                start()
            }
        }

        fun stopThinkingAnimation() {
            animatorSet?.cancel()
            animatorSet = null
            dots.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
        }
    }
}

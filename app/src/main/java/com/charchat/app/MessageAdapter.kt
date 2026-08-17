package com.charchat.app

import android.graphics.Bitmap
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
    val isUser: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("content", content)
        put("isUser", isUser)
    }

    companion object {
        fun fromJson(json: JSONObject): Message = Message(
            id = json.optString("id"),
            content = json.optString("content"),
            isUser = json.optBoolean("isUser")
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
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var characterAvatar: Bitmap? = null
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
        holder.itemView.findViewById<TextView>(R.id.msg_content).text = styleRoleplayText(message.content)

        if (holder is AssistantMessageViewHolder) {
            val avatarView = holder.itemView.findViewById<ImageView>(R.id.msg_avatar)
            val avatar = characterAvatar
            if (avatar != null) {
                avatarView.setImageBitmap(avatar)
                avatarView.scaleType = ImageView.ScaleType.CENTER_CROP
                avatarView.setPadding(0, 0, 0, 0)
            } else {
                avatarView.setImageResource(R.drawable.ic_character_placeholder)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

package com.charchat.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

class CharacterCardAdapter(
    private val characters: List<Character>,
    private val onClick: (Character) -> Unit,
    private val onLongClick: (Character) -> Unit
) : RecyclerView.Adapter<CharacterCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ShapeableImageView = view.findViewById(R.id.card_avatar)
        val name: TextView = view.findViewById(R.id.card_name)
        val subtitle: TextView = view.findViewById(R.id.card_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_character_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val character = characters[position]
        holder.name.text = character.name.ifBlank { "Unnamed" }
        holder.subtitle.text = "Tap to chat"

        val bitmap = character.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        if (bitmap != null) {
            holder.avatar.setImageBitmap(bitmap)
            holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.avatar.setPadding(0, 0, 0, 0)
        } else {
            holder.avatar.setImageResource(R.drawable.ic_character_placeholder)
        }

        holder.itemView.setOnClickListener { onClick(character) }
        holder.itemView.setOnLongClickListener {
            onLongClick(character)
            true
        }
    }

    override fun getItemCount(): Int = characters.size
}

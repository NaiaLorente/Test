package com.charchat.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

class CharacterCardAdapter(
    private val characters: List<Character>,
    private val onClick: (Character) -> Unit,
    private val onDeleteClick: (Character) -> Unit
) : RecyclerView.Adapter<CharacterCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarTile: View = view.findViewById(R.id.card_avatar_tile)
        val avatarPhoto: ShapeableImageView = view.findViewById(R.id.card_avatar_photo)
        val avatarLetter: TextView = view.findViewById(R.id.card_avatar_letter)
        val name: TextView = view.findViewById(R.id.card_name)
        val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        val delete: View = view.findViewById(R.id.card_delete)
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
            holder.avatarPhoto.setImageBitmap(bitmap)
            holder.avatarPhoto.visibility = View.VISIBLE
            holder.avatarLetter.visibility = View.GONE
        } else {
            val style = character.avatarStyle()
            holder.avatarPhoto.visibility = View.GONE
            holder.avatarLetter.visibility = View.VISIBLE
            holder.avatarLetter.text = style.letter
            holder.avatarLetter.setTextColor(holder.itemView.context.getColor(style.foregroundColorRes))
            holder.avatarTile.setBackgroundColor(holder.itemView.context.getColor(style.backgroundColorRes))
        }

        holder.itemView.setOnClickListener { onClick(character) }
        holder.delete.setOnClickListener { onDeleteClick(character) }
    }

    override fun getItemCount(): Int = characters.size
}

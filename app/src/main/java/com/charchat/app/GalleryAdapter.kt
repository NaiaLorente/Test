package com.charchat.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

/** One entry in the gallery grid: either a solo character or a group of up to [MAX_GROUP_SIZE]. */
sealed class GalleryItem {
    abstract val id: String
    abstract val sortKey: Long

    data class CharacterItem(val character: Character, override val sortKey: Long) : GalleryItem() {
        override val id get() = character.id
    }

    data class GroupItem(val group: Group, val members: List<Character>, override val sortKey: Long) : GalleryItem() {
        override val id get() = group.id
    }
}

class GalleryAdapter(
    private val items: List<GalleryItem>,
    private val onOpenCharacter: (Character) -> Unit,
    private val onOpenGroup: (Group) -> Unit,
    private val onDeleteCharacter: (Character) -> Unit,
    private val onDeleteGroup: (Group) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CHARACTER = 1
        private const val VIEW_TYPE_GROUP = 2
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is GalleryItem.CharacterItem -> VIEW_TYPE_CHARACTER
            is GalleryItem.GroupItem -> VIEW_TYPE_GROUP
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_CHARACTER) {
            CharacterViewHolder(inflater.inflate(R.layout.item_character_card, parent, false))
        } else {
            GroupViewHolder(inflater.inflate(R.layout.item_group_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GalleryItem.CharacterItem -> bindCharacter(holder as CharacterViewHolder, item.character)
            is GalleryItem.GroupItem -> bindGroup(holder as GroupViewHolder, item.group, item.members)
        }
    }

    private fun bindCharacter(holder: CharacterViewHolder, character: Character) {
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

        holder.delete.contentDescription = "Delete ${character.name.ifBlank { "this character" }}"
        holder.itemView.setOnClickListener { onOpenCharacter(character) }
        holder.delete.setOnClickListener { onDeleteCharacter(character) }
    }

    private fun bindGroup(holder: GroupViewHolder, group: Group, members: List<Character>) {
        holder.name.text = group.displayName(members)
        holder.subtitle.text = "Group chat · ${members.size} characters"

        holder.avatarSlots.forEachIndexed { index, slot ->
            val member = members.getOrNull(index)
            if (member == null) {
                slot.tile.visibility = View.GONE
                return@forEachIndexed
            }
            slot.tile.visibility = View.VISIBLE
            val bitmap = member.avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
            if (bitmap != null) {
                slot.photo.setImageBitmap(bitmap)
                slot.photo.visibility = View.VISIBLE
                slot.letter.visibility = View.GONE
            } else {
                val style = member.avatarStyle()
                slot.photo.visibility = View.GONE
                slot.letter.visibility = View.VISIBLE
                slot.letter.text = style.letter
                slot.letter.setTextColor(holder.itemView.context.getColor(style.foregroundColorRes))
                slot.tile.setBackgroundColor(holder.itemView.context.getColor(style.backgroundColorRes))
            }
        }

        holder.delete.contentDescription = "Delete ${group.displayName(members)}"
        holder.itemView.setOnClickListener { onOpenGroup(group) }
        holder.delete.setOnClickListener { onDeleteGroup(group) }
    }

    override fun getItemCount(): Int = items.size

    class CharacterViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarTile: View = view.findViewById(R.id.card_avatar_tile)
        val avatarPhoto: ShapeableImageView = view.findViewById(R.id.card_avatar_photo)
        val avatarLetter: TextView = view.findViewById(R.id.card_avatar_letter)
        val name: TextView = view.findViewById(R.id.card_name)
        val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        val delete: View = view.findViewById(R.id.card_delete)
    }

    class AvatarSlot(val tile: View, val photo: ShapeableImageView, val letter: TextView)

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.group_name)
        val subtitle: TextView = view.findViewById(R.id.group_subtitle)
        val delete: View = view.findViewById(R.id.group_delete)
        val avatarSlots: List<AvatarSlot> = listOf(
            AvatarSlot(
                view.findViewById(R.id.group_avatar_1),
                view.findViewById(R.id.group_avatar_1_photo),
                view.findViewById(R.id.group_avatar_1_letter)
            ),
            AvatarSlot(
                view.findViewById(R.id.group_avatar_2),
                view.findViewById(R.id.group_avatar_2_photo),
                view.findViewById(R.id.group_avatar_2_letter)
            ),
            AvatarSlot(
                view.findViewById(R.id.group_avatar_3),
                view.findViewById(R.id.group_avatar_3_photo),
                view.findViewById(R.id.group_avatar_3_letter)
            )
        )
    }
}

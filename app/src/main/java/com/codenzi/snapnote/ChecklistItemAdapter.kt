package com.codenzi.snapnote

import android.graphics.Color
import android.graphics.PorterDuff
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

class ChecklistItemAdapter(
    private val items: MutableList<ChecklistItem>
) : RecyclerView.Adapter<ChecklistItemAdapter.ChecklistItemViewHolder>() {

    private var currentTextColor: Int = Color.BLACK
    private var currentIconTint: Int = Color.BLACK

    fun updateColors(textColor: Int, iconTint: Int) {
        this.currentTextColor = textColor
        this.currentIconTint = iconTint
        // notifyDataSetChanged() burada çağrılmaz, NoteActivity'den çağrılır.
    }

    inner class ChecklistItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.cb_item)
        val editText: EditText = itemView.findViewById(R.id.et_item_text)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btn_delete_item)

        init {
            deleteButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items.removeAt(position)
                    notifyItemRemoved(position)
                }
            }

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        items[position].text = s.toString()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items[position].isChecked = isChecked
                }
            }
        }

        fun bindColors() {
            editText.setTextColor(currentTextColor)
            // İpucu rengini mevcut metin renginin yarı saydam (%40) bir versiyonu yap
            val hintColor = Color.argb(0x66, Color.red(currentTextColor), Color.green(currentTextColor), Color.blue(currentTextColor))
            editText.setHintTextColor(hintColor)
            checkBox.setTextColor(currentTextColor)
            deleteButton.setColorFilter(currentIconTint, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChecklistItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.checklist_item, parent, false)
        return ChecklistItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChecklistItemViewHolder, position: Int) {
        val item = items[position]
        holder.editText.setText(item.text)
        holder.checkBox.isChecked = item.isChecked
        holder.bindColors()
    }

    override fun getItemCount(): Int = items.size

    fun addItem() {
        items.add(ChecklistItem("", false))
        notifyItemInserted(items.size - 1)
    }
}
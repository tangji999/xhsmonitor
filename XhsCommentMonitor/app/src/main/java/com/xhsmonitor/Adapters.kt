package com.xhsmonitor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class KeywordAdapter(
    private val items: MutableList<String>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvKeyword: TextView = view.findViewById(R.id.tv_keyword)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_keyword)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyword, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvKeyword.text = items[position]
        holder.btnDelete.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}

class CommentAdapter(
    private val items: MutableList<CommentRecord>
) : RecyclerView.Adapter<CommentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMeta: TextView = view.findViewById(R.id.tv_meta)
        val tvContent: TextView = view.findViewById(R.id.tv_content)
        val tvTime: TextView = view.findViewById(R.id.tv_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvMeta.text = "#${item.keyword}  ·  ${item.noteTitle}"
        holder.tvContent.text = "${item.author}：${item.content}"
        holder.tvTime.text = item.timeText.ifBlank { "时间未知" }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<CommentRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}

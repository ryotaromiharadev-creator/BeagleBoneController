package com.example.linuxconnect.model

data class ChatMessage(
    val content: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

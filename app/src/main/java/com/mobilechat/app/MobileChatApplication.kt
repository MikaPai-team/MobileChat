package com.mobilechat.app

import android.app.Application
import com.mobilechat.app.data.ChatRepository

class MobileChatApplication : Application() {
    val repository by lazy { ChatRepository(this) }
}

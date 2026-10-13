package com.clearkit.app

import android.app.Application
import com.clearkit.app.ai.LocalAiEngine
import com.clearkit.app.data.ChatStore
import com.clearkit.app.data.MedStore
import com.clearkit.app.data.ProofStore
import com.clearkit.app.models.ModelManager

class ClearKitApp : Application() {
    lateinit var modelManager: ModelManager
    lateinit var chatStore: ChatStore
    lateinit var medStore: MedStore
    lateinit var proofStore: ProofStore
    lateinit var aiEngine: LocalAiEngine

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this)
        chatStore = ChatStore(this)
        medStore = MedStore(this)
        proofStore = ProofStore(this)
        aiEngine = LocalAiEngine(this, modelManager)
    }
}

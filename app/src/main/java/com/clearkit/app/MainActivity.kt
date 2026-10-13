package com.clearkit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clearkit.app.models.BUILTIN_VISION_MODELS
import com.clearkit.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as ClearKitApp

    private val chatVm: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V =
                ChatViewModel(application as ClearKitApp) as V
        }
    }
    private val medsVm: MedsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V =
                MedsViewModel(application as ClearKitApp) as V
        }
    }
    private val proofsVm: ProofsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V =
                ProofsViewModel(application as ClearKitApp) as V
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    val drawer = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    val chats by chatVm.chats.collectAsState()
                    val currentId by chatVm.currentId.collectAsState()
                    val activeId by app.modelManager.activeModelId.collectAsState()
                    val loaded by app.modelManager.modelLoaded.collectAsState()
                    val bigText by chatVm.bigText.collectAsState()
                    val proofs by proofsVm.items.collectAsState()

                    ModalNavigationDrawer(
                        drawerState = drawer,
                        drawerContent = {
                            DrawerContent(
                                chats = chats,
                                currentId = currentId,
                                modelName = BUILTIN_VISION_MODELS.firstOrNull { it.id == activeId }?.displayName,
                                modelLoaded = loaded,
                                bigText = bigText,
                                onSelectChat = { chatVm.selectChat(it); scope.launch { drawer.close() }; nav.navigate("chat") },
                                onNewChat = { mode -> chatVm.newChat(mode); scope.launch { drawer.close() }; nav.navigate("chat") },
                                onDeleteChat = { chatVm.deleteChat(it) },
                                onOpenModels = { scope.launch { drawer.close() }; nav.navigate("models") },
                                onOpenMeds = { scope.launch { drawer.close() }; nav.navigate("meds") },
                                onOpenProofs = { scope.launch { drawer.close() }; nav.navigate("proofs") },
                                onToggleBigText = { chatVm.toggleBigText() }
                            )
                        }
                    ) {
                        NavHost(nav, startDestination = "chat") {
                            composable("chat") {
                                ChatScreen(
                                    vm = chatVm,
                                    openDrawer = { scope.launch { drawer.open() } },
                                    openModels = { nav.navigate("models") },
                                    onSaveProof = { uri, note -> proofsVm.add(uri, note) }
                                )
                            }
                            composable("models") {
                                ModelsScreen(
                                    mm = app.modelManager,
                                    onBack = { nav.popBackStack() },
                                    onBrowse = { nav.navigate("browse") }
                                )
                            }
                            composable("browse") {
                                BrowseScreen(
                                    mm = app.modelManager,
                                    onBack = { nav.popBackStack() },
                                    onDownloaded = { nav.popBackStack() }
                                )
                            }
                            composable("meds") {
                                MedsScreen(vm = medsVm, bigText = bigText, onBack = { nav.popBackStack() })
                            }
                            composable("proofs") {
                                ProofsScreen(items = proofs, onBack = { nav.popBackStack() }, onDelete = { proofsVm.remove(it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

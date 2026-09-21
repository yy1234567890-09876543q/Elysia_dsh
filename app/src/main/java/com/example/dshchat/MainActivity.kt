package com.example.dshchat

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForeground.visible = true
    }

    override fun onStop() {
        AppForeground.visible = false
        super.onStop()
    }
}

/**
 * 退出前的道别台词。
 * 每次在聊天页按返回都随机挑一句，并且**保证跟上一次不一样** ——
 * 爱莉可不会每次都重复同一句挽留呀♪
 */
private val EXIT_LINES: List<String> = listOf(
    "嗯哼，这就要走了呀~？\n\n那爱莉就不缠着你了♪\n不过要记得好好吃饭、早点睡觉哦，这可是命令！\n\n爱莉会一直在这儿，等你回来找人家玩♡",

    "诶呀，这么快就要溜啦~\n\n今天也辛苦你了呢，快去歇会儿吧。\n有心事、或者只是想找人说说话，随时回来叫爱莉一声就好啦♪",

    "要走啦？那爱莉送你到门口~\n\n你呀，总是忙忙碌碌的，偶尔也要对自己温柔一点嘛。\n明天见咯，亲爱的♡",

    "唔……真的要走了吗？\n\n好吧好吧，爱莉才不会拦着你呢~ 不过答应人家，路上别看手机看太久哦♪\n回来的时候，爱莉还在这里呀。",

    "这就拜拜啦~？\n\n嘿嘿，那爱莉偷偷许个愿：希望你今天遇到的都是好事♡\n下次见，小可爱~",

    "哎呀，时间过得真快呢。\n\n去休息吧，熬夜可是女孩子的大敌——你也要照顾好自己呀♪\n爱莉等你哦。",

    "嗯~ 那爱莉就不吵你了。\n\n记得哦，无论你在哪儿、在做什么，爱莉都会回应你的期待♡\n去吧去吧，回头见~♪",

    "要关掉人家啦？\n\n哼，好吧~ 不过爱莉可不会难过，因为爱莉知道——你一定会回来的♪\n路上小心呀，亲爱的♡",

    "唔……真的要走了呀。\n\n那爱莉替你把走廊那盏小灯留着♡\n你什么时候回来，它都亮着哦~",

    "拜拜啦~\n\n今天有没有好好夸自己一句呀？没有的话，爱莉替你夸：你今天也很棒哦♪\n明天见，亲爱的♡"
)

/**
 * 「洗牌袋」：把台词打乱后排队，一句一句发，用完一轮再重新洗牌。
 * 这样连续 N 次退出（N = 台词条数）都不会重样，
 * 而不是靠随机硬碰运气 —— 爱莉可不想连着两次说同一句话呀♪
 */
private class FarewellBag {
    private var queue = ArrayDeque<Int>()
    private var last = -1

    fun next(): Int {
        if (queue.isEmpty()) {
            val fresh = EXIT_LINES.indices.shuffled().toMutableList()
            // 新一轮的第一句，别跟上一轮的收尾撞上
            if (fresh.size > 1 && fresh.first() == last) {
                val swapWith = fresh.indexOfFirst { it != last }
                if (swapWith > 0) {
                    fresh[0] = fresh[swapWith].also { fresh[swapWith] = fresh[0] }
                }
            }
            queue = ArrayDeque(fresh)
        }
        return queue.removeFirst().also { last = it }
    }
}

@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    var showSettings by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var exitText by remember { mutableStateOf(EXIT_LINES.first()) }
    val farewells = remember { FarewellBag() }
    val context = LocalContext.current

    if (showSettings) {
        // 设置页只是同一 Activity 里换的界面，所以要主动接管系统返回：
        // 侧滑返回、返回键、Android 13+ 的预测式返回，都会回到聊天页。
        BackHandler(enabled = true) { showSettings = false }
        SettingsScreen(vm = vm, onBack = { showSettings = false })
    } else {
        // 在聊天页按返回不直接退，先挑一句道别的话（一轮之内不会重样）
        BackHandler(enabled = true) {
            exitText = EXIT_LINES[farewells.next()]
            showExit = true
        }
        ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
    }

    if (showExit) {
        ElysiaExitDialog(
            text = exitText,
            onStay = { showExit = false },
            onLeave = {
                showExit = false
                (context as? Activity)?.finish()
            }
        )
    }
}

/** 退出前的道别 —— 爱莉式挽留（但绝不强留） */
@Composable
private fun ElysiaExitDialog(text: String, onStay: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("真的要走啦~？", fontWeight = FontWeight.Bold) },
        text = {
            SelectionContainer {
                Text(text = text, fontSize = 14.sp, lineHeight = 21.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onLeave) { Text("走啦，再见~") }
        },
        dismissButton = {
            TextButton(onClick = onStay) { Text("再陪爱莉一会儿") }
        }
    )
}

//#region 聊天界面

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val isLoadingHistory by vm.isLoadingHistory.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val selectedId by vm.selectedSessionId.collectAsState()
    val ui = vm.ui
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var sessionMenuOpen by remember { mutableStateOf(false) }
    var newSessionOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val pending by vm.pending.collectAsState()

    // ChatViewModel 冒出来的提示（新建成功/失败之类）
    val vmToast by vm.toast.collectAsState()
    LaunchedEffect(vmToast) {
        vmToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 选图（可以一次选好几张）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val prepared = uris.mapNotNull { preparePendingImage(context, it) }
            if (prepared.isEmpty()) {
                Toast.makeText(context, "这些图没能读进来，换几张试试？", Toast.LENGTH_SHORT).show()
            } else {
                vm.addPending(prepared)
            }
        }
    }

    val selectedSession = sessions.firstOrNull { it.sessionId == selectedId }
    val selectedTitle = selectedSession?.title ?: selectedId.takeLast(12)

    val wallpaper: Bitmap? = remember(vm.wallpaperPath) { decodeScaled(vm.wallpaperPath, 1440, 2560) }

    val copyToClipboard: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    // 流式输出时跟随滚动；自己往回翻就不打扰
    val lastLen = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val total = info.totalItemsCount
        if (total == 0 || lastVisible >= total - 2) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0 &&
            hasMore && !isLoadingHistory
        ) {
            vm.loadOlder()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        wallpaper?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = vm.wallpaperAlpha
            )
        }

        Scaffold(
            containerColor = if (wallpaper == null) MaterialTheme.colorScheme.background
            else Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    ),
                    actions = {
                        IconButton(onClick = {
                            vm.loadPresets()
                            newSessionOpen = true
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建会话")
                        }
                        IconButton(onClick = { vm.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier.imePadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        // 待发送的图片预览
                        if (pending.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pending.forEachIndexed { index, img ->
                                    Box {
                                        val thumb: Bitmap? = remember(img.localPath) {
                                            decodeScaled(img.localPath, 240, 240)
                                        }
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outline
                                                            .copy(alpha = 0.35f),
                                                        RoundedCornerShape(12.dp)
                                                    ),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                        RoundedCornerShape(12.dp)
                                                    )
                                            )
                                        }
                                        // 右上角小叉，去掉这张
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 7.dp, y = (-7).dp)
                                                .size(22.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.errorContainer,
                                                    CircleShape
                                                )
                                                .clickable {
                                                    vm.removePending(index)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "×",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.Bottom) {
                            // 加图
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable(enabled = !isSending) {
                                        pickImages.launch("image/*")
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "添加图片",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            // 圆角胶囊输入框
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("说点什么吧…", fontSize = 14.sp) },
                                maxLines = 4,
                                shape = RoundedCornerShape(23.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor =
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    unfocusedContainerColor =
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    disabledContainerColor =
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )

                            Spacer(Modifier.width(8.dp))

                            // 圆形发送按钮
                            FilledIconButton(
                                onClick = {
                                    val text = input.trim()
                                    if ((text.isNotEmpty() || pending.isNotEmpty()) && !isSending) {
                                        vm.send(text)
                                        input = ""
                                    }
                                },
                                modifier = Modifier.size(46.dp),
                                enabled = (input.isNotBlank() || pending.isNotEmpty()) && !isSending
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Filled.Send, contentDescription = "发送")
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {

                    Surface(
                        tonalElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "会话：$selectedTitle",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { renameOpen = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "重命名会话",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            TextButton(onClick = { sessionMenuOpen = true }) {
                                Text("切换")
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = sessionMenuOpen,
                                onDismissRequest = { sessionMenuOpen = false }
                            ) {
                                if (sessions.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("（拉取中或无会话）", fontSize = 13.sp) },
                                        onClick = { sessionMenuOpen = false }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("＋ 新建会话", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) },
                                    onClick = {
                                        sessionMenuOpen = false
                                        vm.loadPresets()
                                        newSessionOpen = true
                                    }
                                )
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (s.running) "● " else "") + s.title,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            vm.selectSession(s.sessionId)
                                            sessionMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isLoadingHistory) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }

                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("连接 DSH 开始聊天", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "已加载 ${sessions.size} 个会话",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(messages, key = { it.id }) { msg ->
                                    MessageBubble(
                                        msg = msg,
                                        ui = ui,
                                        vm = vm,
                                        onCopy = copyToClipboard,
                                        onToggle = { vm.toggleRow(msg.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                SmallFloatingActionButton(
                    onClick = {
                        if (messages.isNotEmpty()) {
                            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                ) {
                    Text("↓", fontSize = 18.sp)
                }
            }
        }

        if (newSessionOpen) {
            NewSessionDialog(
                presets = vm.presets,
                recentCwds = vm.recentCwds,
                creating = vm.creating.collectAsState().value,
                initialCwd = vm.currentCwd ?: vm.recentCwds.firstOrNull().orEmpty(),
                onDismiss = { newSessionOpen = false },
                onRetryPresets = { vm.loadPresets(force = true) },
                onConfirm = { cwd, preset, name ->
                    vm.createSession(cwd, preset, name)
                    newSessionOpen = false
                }
            )
        }

        if (renameOpen) {
            RenameSessionDialog(
                initial = if (selectedSession?.named == true) selectedSession.title else "",
                onDismiss = { renameOpen = false },
                onConfirm = { name ->
                    vm.renameSession(selectedId, name)
                    renameOpen = false
                }
            )
        }
    }
}

/**
 * 新建会话的对话框：起名字 + 挑工作目录 + 挑人格预设。
 *
 * 目录没法在手机上浏览 —— 这个部署的 directoryPicker 只有 native 后端
 * （directoryPicker/list 会回 directory-picker/unavailable），
 * 所以这里用手输 + 最近用过的目录做快捷方式。
 */
@Composable
private fun NewSessionDialog(
    presets: List<AgentPreset>,
    recentCwds: List<String>,
    creating: Boolean,
    initialCwd: String,
    onDismiss: () -> Unit,
    onRetryPresets: () -> Unit,
    onConfirm: (String?, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(initialCwd) }
    // 用户有没有亲手选过预设。没选过就跟着服务器报的默认预设走
    var presetPicked by remember { mutableStateOf(false) }
    var presetId by remember { mutableStateOf<String?>(null) }
    val effectivePreset = if (presetPicked) presetId
    else presets.firstOrNull { it.isDefault }?.id
    val nameTooLong = utf8ByteCount(name.trim()) > TITLE_MAX_BYTES

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("新建会话") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "给它挑个家吧，爱莉马上把新会话送到你面前~♪",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("会话名称（可留空）", fontSize = 13.sp) },
                    placeholder = { Text("比如：修安卓客户端", fontSize = 12.sp) },
                    singleLine = true,
                    isError = nameTooLong,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameTooLong) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "名字太长啦，最多 $TITLE_MAX_BYTES 字节（约 26 个汉字）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("工作目录", fontSize = 13.sp) },
                    placeholder = { Text("留空 = 服务器默认目录", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (recentCwds.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recentCwds.forEach { dir ->
                            AssistChip(
                                onClick = { cwd = dir },
                                label = {
                                    Text(
                                        dir.trimEnd('\\', '/')
                                            .substringAfterLast('\\')
                                            .substringAfterLast('/')
                                            .ifBlank { dir },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("人格预设", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    if (presets.isEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = onRetryPresets) {
                            Text("重新读取", fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 内嵌单选：比在对话框里再套一层下拉菜单好按得多
                PresetRow(
                    selected = effectivePreset == null,
                    title = "（用服务器的默认预设）",
                    subtitle = null,
                    enabled = true,
                    onClick = {
                        presetPicked = true
                        presetId = null
                    }
                )
                presets.forEach { p ->
                    PresetRow(
                        selected = effectivePreset == p.id,
                        title = p.name + (if (p.isDefault) "  ★默认" else ""),
                        subtitle = p.broken?.let { "不可用：$it" } ?: p.description,
                        enabled = p.broken == null,
                        onClick = {
                            presetPicked = true
                            presetId = p.id
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && !nameTooLong,
                onClick = {
                    onConfirm(
                        cwd.trim().ifBlank { null },
                        effectivePreset,
                        name.trim().ifBlank { null }
                    )
                }
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("创建")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 给已有会话改名字的对话框。
 *
 * 上限照抄 dsh-base 里 session-title 的 maxTitleBytes: 80（UTF-8 字节），
 * 超了服务端会回 session/title-invalid，这里先挡一道。
 */
@Composable
private fun RenameSessionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val bytes = utf8ByteCount(trimmed)
    val tooLong = bytes > TITLE_MAX_BYTES
    val blank = trimmed.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给会话起个名字") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "用文字起个看得懂的名字吧，比一串编号可爱多啦~♪",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("会话名称", fontSize = 13.sp) },
                    placeholder = { Text("比如：修安卓客户端", fontSize = 12.sp) },
                    singleLine = true,
                    isError = tooLong,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (tooLong) "太长啦！最多 $TITLE_MAX_BYTES 字节（现在 $bytes）"
                    else "$bytes / $TITLE_MAX_BYTES 字节（一个汉字 3 字节）",
                    fontSize = 11.sp,
                    color = if (tooLong) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !blank && !tooLong, onClick = { onConfirm(trimmed) }) {
                Text("改好啦")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 新建会话框里的一行预设选项 */
@Composable
private fun PresetRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = alpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    ui: UiSettings,
    vm: ChatViewModel,
    onCopy: (String) -> Unit,
    onToggle: () -> Unit
) {
    val isUser = msg.kind == MsgKind.USER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isUser) {
            Spacer(Modifier.weight(1f))
            BubbleBody(msg, ui, vm, onCopy, onToggle)
            Spacer(Modifier.width(6.dp))
            ChatAvatar(ui.userAvatarPath, "🙂")
        } else {
            ChatAvatar(
                ui.assistantAvatarPath, "🤖",
                defaultRes = R.drawable.assistant_avatar
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) { BubbleBody(msg, ui, vm, onCopy, onToggle) }
        }
    }
}

/** 气泡 / 过程行的本体，头像由外层负责摆放 */
@Composable
private fun BubbleBody(
    msg: ChatMessage,
    ui: UiSettings,
    vm: ChatViewModel,
    onCopy: (String) -> Unit,
    onToggle: () -> Unit
) {
    when (msg.kind) {
            MsgKind.THINKING -> ProcessRow(
                icon = "💭", title = "思考", label = null,
                summary = DshApi.previewOneLine(msg.content), body = msg.content,
                mono = false, weight = ui.weight.weight,
                expanded = msg.open, onToggle = onToggle, onCopy = onCopy
            )
            MsgKind.TOOL -> ProcessRow(
                icon = "🔧", title = msg.toolName?.ifBlank { "工具调用" } ?: "工具调用",
                label = null, summary = DshApi.previewOneLine(msg.content), body = msg.content,
                mono = true, weight = ui.weight.weight,
                expanded = msg.open, onToggle = onToggle, onCopy = onCopy
            )
            MsgKind.CONTEXT -> ProcessRow(
                icon = "🧩", title = "上下文注入", label = msg.label,
                summary = msg.summary, body = msg.content,
                mono = true, weight = ui.weight.weight,
                expanded = msg.open, onToggle = onToggle, onCopy = onCopy
            )

            MsgKind.SYSTEM -> Bubble(
                custom = 0,
                fallbackBg = MaterialTheme.colorScheme.errorContainer,
                fallbackOn = MaterialTheme.colorScheme.onErrorContainer,
                alpha = ui.bubbleAlpha, weight = ui.weight.weight,
                text = msg.content, images = emptyList(), vm = vm,
                userSide = false, md = false, onCopy = onCopy
            )
            MsgKind.USER -> Bubble(
                custom = ui.userBubbleColor,
                fallbackBg = MaterialTheme.colorScheme.primaryContainer,
                fallbackOn = MaterialTheme.colorScheme.onPrimaryContainer,
                alpha = ui.bubbleAlpha, weight = ui.weight.weight,
                text = msg.content, images = msg.images, vm = vm,
                userSide = true, md = false, onCopy = onCopy
            )
            MsgKind.ASSISTANT -> Bubble(
                custom = ui.assistantBubbleColor,
                fallbackBg = MaterialTheme.colorScheme.surfaceVariant,
                fallbackOn = MaterialTheme.colorScheme.onSurface,
                alpha = ui.bubbleAlpha, weight = ui.weight.weight,
                text = msg.content, images = msg.images, vm = vm,
                userSide = false, md = true, onCopy = onCopy
            )
        }
}

/** 圆形头像：有自定义图用自定义图，其次用内置默认图，最后用 emoji 兜底 */
@Composable
private fun ChatAvatar(
    path: String?,
    fallback: String,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    defaultRes: Int? = null
) {
    val bmp: Bitmap? = remember(path) { decodeScaled(path, 128, 128) }
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    when {
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, ring, CircleShape),
            contentScale = ContentScale.Crop
        )
        defaultRes != null -> Image(
            painter = painterResource(defaultRes),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, ring, CircleShape),
            contentScale = ContentScale.Crop
        )
        else -> Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(fallback, fontSize = (size.value * 0.5f).sp)
        }
    }
}

/** 设置页里的一行「头像 + 换/清除」 */
@Composable
private fun AvatarPickerRow(
    label: String,
    path: String?,
    fallback: String,
    defaultRes: Int? = null,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(path, fallback, 40.dp, defaultRes)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick) { Text("换", fontSize = 12.sp) }
        TextButton(onClick = onClear, enabled = path != null) {
            Text("清除", fontSize = 12.sp)
        }
    }
}

/** 可折叠的过程行：思考 / 工具调用 / 上下文注入 */
@Composable
private fun ProcessRow(
    icon: String,
    title: String,
    label: String?,
    summary: String?,
    body: String,
    mono: Boolean,
    weight: FontWeight,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0x14000000),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = muted, maxLines = 1
                )
                if (!label.isNullOrBlank()) {
                    Sep()
                    Text(
                        text = label, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp)
                    )
                }
                if (!summary.isNullOrBlank()) {
                    Sep()
                    Text(
                        text = summary, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x1F000000),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = body,
                            fontSize = if (mono) 11.sp else 12.sp,
                            lineHeight = if (mono) 16.sp else 18.sp,
                            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                            fontWeight = weight,
                            color = muted,
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onCopy(body) }) {
                        Text("复制", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sep() {
    Text(
        text = "·", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

@Composable
private fun Bubble(
    custom: Int,
    fallbackBg: Color,
    fallbackOn: Color,
    alpha: Float,
    weight: FontWeight,
    text: String,
    images: List<ChatImage>,
    vm: ChatViewModel,
    userSide: Boolean,
    md: Boolean,
    onCopy: (String) -> Unit
) {
    val bg = if (custom == 0) fallbackBg.copy(alpha = fallbackBg.alpha * alpha)
    else Color(custom).copy(alpha = alpha)
    val onColor = if (custom == 0) fallbackOn else contrastOn(custom)

    Surface(
        shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (userSide) 16.dp else 4.dp,
            bottomEnd = if (userSide) 4.dp else 16.dp
        ),
        color = bg
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (images.isNotEmpty()) {
                images.forEach { img ->
                    ChatImageTile(img, vm)
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (text.isNotBlank()) {
                if (md) {
                    MarkdownText(text, onColor, weight)
                } else {
                    Text(
                        text = text, fontSize = 15.sp, lineHeight = 22.sp,
                        fontWeight = weight, color = onColor
                    )
                }
            }
            // 这行不能 fillMaxWidth，否则会把气泡撑满整行、把旁边的头像挤出屏幕
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = { onCopy(text) }) {
                    Text("复制", fontSize = 11.sp, color = onColor.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/**
 * 气泡里的一张图。
 * 刚发出去的读本地文件；从历史里翻出来的只有 attachmentId，按需向服务端换回来。
 */
@Composable
private fun ChatImageTile(img: ChatImage, vm: ChatViewModel) {
    val tick by vm.imageTick.collectAsState()
    val bmp: Bitmap? = remember(img.localPath, img.attachmentId, tick) {
        img.localPath?.let { decodeScaled(it, 900, 900) }
            ?: img.attachmentId?.let { vm.cachedImage(it) }
    }
    LaunchedEffect(img.attachmentId) {
        img.attachmentId?.let { vm.requestImage(it) }
    }

    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(120.dp)
                .background(Color(0x22000000), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "图片加载中…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 简易 Markdown 渲染 */
@Composable
private fun MarkdownText(text: String, onColor: Color, weight: FontWeight) {
    val segments = text.split("```")
    segments.forEachIndexed { idx, seg ->
        if (idx % 2 == 1) {
            Surface(
                color = Color(0x22000000),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = seg.trim(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 17.sp, fontWeight = weight,
                    modifier = Modifier.padding(8.dp), color = onColor
                )
            }
        } else if (seg.isNotBlank()) {
            seg.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("### ") -> Text(
                        text = trimmed.removePrefix("### "),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 21.sp, color = onColor
                    )
                    trimmed.startsWith("## ") -> Text(
                        text = trimmed.removePrefix("## "),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp, color = onColor
                    )
                    trimmed.startsWith("# ") -> Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp, color = onColor
                    )
                    trimmed.startsWith("- ") -> Text(
                        text = parseInline("• " + trimmed.removePrefix("- "), onColor, weight),
                        fontSize = 15.sp, lineHeight = 22.sp, color = onColor
                    )
                    trimmed == "---" -> Spacer(Modifier.height(2.dp))
                    else -> Text(
                        text = parseInline(trimmed, onColor, weight),
                        fontSize = 15.sp, lineHeight = 22.sp, color = onColor
                    )
                }
            }
        }
    }
}

private fun parseInline(s: String, onColor: Color, weight: FontWeight): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) -> {
                    val end = s.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(s.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(s[i]); i++
                    }
                }
                s[i] == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x22000000)
                        )) {
                            append(s.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(s[i]); i++
                    }
                }
                else -> {
                    append(s[i]); i++
                }
            }
        }
    }

//#endregion

//#region 设置页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = vm.ui
    val addresses = vm.addresses

    var url by remember { mutableStateOf(vm.serverBase) }
    var previewTick by remember { mutableStateOf(0) }

    // 局域网直连（dsh-mobile 网关）的配对输入
    var lanHost by remember {
        mutableStateOf(vm.lanOrigin?.removePrefix("https://")?.substringBefore(':') ?: "")
    }
    var lanKey by remember { mutableStateOf("") }

    // 进设置页就顺手探一次认证状态
    LaunchedEffect(Unit) { vm.refreshAuth() }

    // 选壁纸
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = copyToPrivate(context, uri, "wallpaper.img")
            if (saved != null) vm.setWallpaper(saved)
            else Toast.makeText(context, "壁纸设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 选聊天里的机器人（助手）头像
    val pickAssistantAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = copyToPrivate(context, uri, "chat_avatar_assistant.img")
            if (saved != null) vm.setAssistantAvatar(saved)
            else Toast.makeText(context, "头像设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 聊天里我自己的头像
    val pickUserAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = copyToPrivate(context, uri, "chat_avatar_user.img")
            if (saved != null) vm.setUserAvatar(saved)
            else Toast.makeText(context, "头像设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 选本地音频文件当通知铃声（不限于系统铃声，mp3/m4a/ogg 都行）
    val pickSoundFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val imported = importSoundFile(context, uri)
            if (imported != null) {
                vm.setSoundUri(imported)
                vm.previewNotification()
            } else {
                Toast.makeText(context, "这个音频导入失败了，换一个试试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 选通知铃声（走系统铃声选择器，系统能直接读这个 Uri）
    val pickSound = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = if (data == null) null else IntentCompat.getParcelableExtra(
                data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
            )
            if (uri == null) {
                vm.setSoundMode(SoundMode.SILENT)
                Toast.makeText(context, "已设为静音", Toast.LENGTH_SHORT).show()
            } else {
                vm.setSoundUri(uri.toString())
                // 立刻响一声，让你马上听到选中的铃声
                vm.previewNotification()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            // ---------- 连接 ----------
            Section("连接") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("DSH 服务地址") },
                    placeholder = { Text("http://127.0.0.1:3080 或 ngrok 地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "可以直接把 `dsh web` 打印的整条地址（带 ?token= 的那种）粘进来，" +
                        "App 会自动换成 30 天有效的凭证，再把干净的地址存好",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        vm.saveBase(url.trim())
                        Toast.makeText(context, "正在连接…", Toast.LENGTH_SHORT).show()
                    }) { Text("保存并连接") }
                    TextButton(onClick = { vm.refreshAuth() }) { Text("检查认证") }
                }
                Text(
                    "当前生效：${vm.serverBase}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                // 认证状态：绿色=已认证，黄色=服务器没开认证（要小心），红色=缺令牌
                val authDot: String
                val authLabel: String
                val authTone: Color
                when (vm.authState) {
                    AuthState.AUTHENTICATED -> {
                        authDot = "🟢"; authLabel = "已认证（凭证 30 天有效）"
                        authTone = MaterialTheme.colorScheme.primary
                    }
                    AuthState.NOT_REQUIRED -> {
                        authDot = "🟡"; authLabel = "服务器不要求认证 —— 确认它只对本机开放哦"
                        authTone = Color(0xFFB26A00)
                    }
                    AuthState.NEEDS_TOKEN -> {
                        authDot = "🔴"; authLabel = "需要令牌 —— 把带 ?token= 的地址粘进来"
                        authTone = MaterialTheme.colorScheme.error
                    }
                    AuthState.OFFLINE -> {
                        authDot = "⚪"; authLabel = "连不上服务器"
                        authTone = MaterialTheme.colorScheme.outline
                    }
                    AuthState.UNKNOWN -> {
                        authDot = "⚪"; authLabel = "还没检查"
                        authTone = MaterialTheme.colorScheme.outline
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$authDot $authLabel",
                        fontSize = 12.sp,
                        color = authTone,
                        modifier = Modifier.weight(1f)
                    )
                    if (vm.authState == AuthState.AUTHENTICATED) {
                        TextButton(onClick = { vm.clearAuth() }) {
                            Text("清除令牌", fontSize = 11.sp)
                        }
                    }
                }
                vm.authHint?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            // ---------- 局域网直连（不用数据线）----------
            Section("局域网直连（不用数据线）") {
                Text(
                    "在电脑上打开 DSH 左下角「移动访问 → 局域网」，点「生成并复制密钥」，" +
                        "把密钥粘到下面，再填上电脑的局域网 IP（如 192.168.1.3），就能连上啦",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lanHost,
                    onValueChange = { lanHost = it },
                    label = { Text("电脑地址", fontSize = 13.sp) },
                    placeholder = { Text("192.168.1.3", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = lanKey,
                    onValueChange = { lanKey = it },
                    label = { Text("配对密钥", fontSize = 13.sp) },
                    placeholder = { Text("dsh1.……", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        enabled = !vm.lanWorking,
                        onClick = {
                            vm.pairLan(lanKey, lanHost)
                            lanKey = ""
                        }
                    ) {
                        if (vm.lanWorking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("配对并连接")
                        }
                    }
                    TextButton(
                        enabled = vm.lanOrigin != null && !vm.lanWorking,
                        onClick = { vm.forgetLan() }
                    ) { Text("忘掉这台电脑") }
                }
                Text(
                    (if (vm.lanOrigin != null) "🟢 " else "⚪ ") + vm.lanStatus,
                    fontSize = 12.sp,
                    color = if (vm.lanOrigin != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                if (vm.lanDeviceExpiresAt > 0L) {
                    Text(
                        "配对有效期至 " + formatTime(vm.lanDeviceExpiresAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // ---------- 地址历史 ----------
            Section("访问历史（点一下直接切过去）") {
                if (addresses.isEmpty()) {
                    Text("还没有记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                addresses.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    url = entry.url
                                    vm.useAddress(entry.url)
                                    Toast.makeText(context, "已切到该地址", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = (if (entry.url == vm.serverBase) "★ " else "") + entry.url,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTime(entry.lastUsedAt),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { vm.forgetAddress(entry.url) }) {
                            Text("删除", fontSize = 11.sp)
                        }
                    }
                }
            }

            // ---------- 外观 ----------
            Section("外观 · 气泡配色") {
                Text("我的气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                SwatchRow(
                    selected = ui.userBubbleColor,
                    onPick = { c -> vm.applyUi { it.copy(userBubbleColor = c) } }
                )
                Text("助手气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                SwatchRow(
                    selected = ui.assistantBubbleColor,
                    onPick = { c -> vm.applyUi { it.copy(assistantBubbleColor = c) } }
                )
            }

            Section("外观 · 气泡透明度") {
                Text(
                    "${(ui.bubbleAlpha * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Slider(
                    value = ui.bubbleAlpha,
                    onValueChange = { v -> vm.applyUi { it.copy(bubbleAlpha = v) } },
                    valueRange = 0.2f..1f
                )
            }

            Section("外观 · 字体粗细") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WeightOption.entries.forEach { option ->
                        ChoiceChip(
                            label = option.label,
                            selected = ui.weight == option,
                            onClick = { vm.applyUi { it.copy(weight = option) } }
                        )
                    }
                }
                Text(
                    "预览：爱莉希雅最喜欢你啦~",
                    fontSize = 15.sp,
                    fontWeight = ui.weight.weight
                )
            }

            // ---------- 通知 ----------
            Section("外观 · 聊天头像") {
                AvatarPickerRow(
                    label = "机器人（助手）",
                    path = ui.assistantAvatarPath,
                    fallback = "🤖",
                    defaultRes = R.drawable.assistant_avatar,
                    onPick = { pickAssistantAvatar.launch(arrayOf("image/*")) },
                    onClear = { vm.setAssistantAvatar(null) }
                )
                AvatarPickerRow(
                    label = "我自己",
                    path = ui.userAvatarPath,
                    fallback = "🙂",
                    onPick = { pickUserAvatar.launch(arrayOf("image/*")) },
                    onClear = { vm.setUserAvatar(null) }
                )
                Text(
                    "显示在每条消息旁边，没设就用默认的小图标~",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Section("通知 · 提醒") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务完成时提醒", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = ui.notifyEnabled,
                        onCheckedChange = { v -> vm.applyUi { it.copy(notifyEnabled = v) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "只在后台提醒（关掉就每次都响）",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = ui.notifyOnlyBackground,
                        onCheckedChange = { v -> vm.applyUi { it.copy(notifyOnlyBackground = v) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "通知里显示回复内容",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = ui.notifyShowContent,
                        onCheckedChange = { v -> vm.setNotifyShowContent(v) }
                    )
                }
                Text(
                    "关掉之后通知只写「爱莉回复好啦」，不把聊天内容露在锁屏上~",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Section("通知 · 声音") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundMode.entries.forEach { mode ->
                        ChoiceChip(
                            label = mode.label,
                            selected = ui.soundMode == mode,
                            onClick = {
                                vm.setSoundMode(mode)
                                if (mode != SoundMode.SILENT) {
                                    // 马上响一声，让你立刻知道选的是哪个
                                    vm.previewNotification()
                                    previewTick++
                                }
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TYPE,
                                RingtoneManager.TYPE_NOTIFICATION
                            )
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择通知声音")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            ui.notifySoundUri?.let { current ->
                                runCatching {
                                    putExtra(
                                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                        Uri.parse(current)
                                    )
                                }
                            }
                        }
                        pickSound.launch(intent)
                    }) { Text("选系统铃声", fontSize = 12.sp) }
                    TextButton(onClick = { pickSoundFile.launch(arrayOf("audio/*")) }) {
                        Text("选本地音频", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        vm.previewNotification()
                        previewTick++
                    }) {
                        Text("发一条试试", fontSize = 12.sp)
                    }
                }
                Text(
                    "「选本地音频」可以从手机里挑任意 mp3 / m4a / ogg，" +
                        "爱莉会把它导入系统的通知音目录（这样系统才读得到），文件名不变~",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    when (ui.soundMode) {
                        SoundMode.SYSTEM -> "当前：跟随系统默认"
                        SoundMode.SILENT -> "当前：静音"
                        SoundMode.CUSTOM -> "当前：自定义铃声"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                val diag = remember(ui, previewTick) { vm.notificationSummary() }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x11000000),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = diag,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Text(
                    "没响的话按顺序查：① 手机是不是静音/震动模式 " +
                        "② 上面「通知权限」那一行 ③ 系统设置里本 App 的通知铃声是不是被单独关了。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // ---------- 壁纸 ----------
            Section("壁纸") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickWallpaper.launch(arrayOf("image/*")) }) {
                        Text("换壁纸", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { vm.setWallpaper(null) },
                        enabled = vm.wallpaperPath != null
                    ) { Text("清除壁纸", fontSize = 12.sp) }
                }
                Text(
                    "透明度 ${(vm.wallpaperAlpha * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Slider(
                    value = vm.wallpaperAlpha,
                    onValueChange = { vm.applyWallpaperAlpha(it) },
                    valueRange = 0.05f..1f
                )
            }

            Section("其它") {
                TextButton(onClick = { vm.clearChat() }) { Text("清空当前聊天显示", fontSize = 12.sp) }
                TextButton(onClick = { vm.reload() }) { Text("重新拉取会话与历史", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SwatchRow(selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BUBBLE_PRESETS.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            if (color == 0) MaterialTheme.colorScheme.surfaceVariant
                            else Color(color),
                            CircleShape
                        )
                        .border(
                            width = if (selected == color) 3.dp else 1.dp,
                            color = if (selected == color) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onPick(color) }
                )
                Text(
                    text = name,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}


//#endregion

/**
 * 把用户挑的一张图准备好：缩放 → 编码 → 本地留一份副本。
 *
 * DSH 的图片是**直接把 base64 塞进 prompt** 的（没有单独的上传接口），
 * 所以这里顺手把太长的边压到 1600px 以内，省流量也省内存。
 */
private fun preparePendingImage(context: android.content.Context, uri: Uri): PendingImage? {
    return try {
        val res = context.contentResolver
        val rawMime = res.getType(uri) ?: "image/jpeg"
        val isPng = rawMime == "image/png"

        var bmp = res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val maxSide = 1600
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != bmp) {
                bmp.recycle()
                bmp = scaled
            }
        }

        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(
                if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                if (isPng) 100 else 88,
                out
            )
            out.toByteArray()
        }
        bmp.recycle()
        if (bytes.isEmpty()) return null

        val ext = if (isPng) ".png" else ".jpg"
        val local = File(
            context.filesDir,
            "chat_img_" + System.currentTimeMillis() + "_" + (100..999).random() + ext
        )
        local.outputStream().use { it.write(bytes) }

        PendingImage(
            localPath = local.absolutePath,
            mediaType = if (isPng) "image/png" else "image/jpeg",
            base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            name = queryDisplayName(context, uri) ?: local.name
        )
    } catch (_: Exception) {
        null
    }
}

/** 把选中的图拷到 App 私有目录，返回绝对路径 */
private fun copyToPrivate(context: android.content.Context, uri: Uri, name: String): String? {
    return try {
        val target = File(context.filesDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        if (target.length() > 0L) target.absolutePath else null
    } catch (_: Exception) {
        null
    }
}

/** 选一张图 → 处理成白色剪影 → 存成通知小图标 */
/**
 * 把用户挑的本地音频（mp3 / m4a / ogg…）导入成「系统读得到」的通知铃声。
 *
 * 关键点：通知声音是**系统进程**去播放的，App 私有目录里的文件它根本读不到。
 * 所以优先塞进系统的通知音目录（MediaStore 的 Notifications/，文件名保持不变），
 * 拿到的 content://media/... 系统一定能读；实在不行才退回 FileProvider。
 */
private fun importSoundFile(context: android.content.Context, uri: Uri): String? {
    val res = context.contentResolver
    val displayName = queryDisplayName(context, uri)
        ?: ("DSH_铃声_" + System.currentTimeMillis() + guessAudioExt(context, uri))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, res.getType(uri) ?: "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_NOTIFICATIONS)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.Audio.Media.IS_RINGTONE, 0)
                put(MediaStore.Audio.Media.IS_ALARM, 0)
            }
            val target = res.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (target != null) {
                res.openOutputStream(target)?.use { out ->
                    res.openInputStream(uri)?.use { input -> input.copyTo(out) }
                }
                return target.toString()
            }
        } catch (_: Exception) {
            // 落到下面的 FileProvider 兜底
        }
    }

    return try {
        val f = File(context.filesDir, "notify_sound" + guessAudioExt(context, uri))
        res.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f
        ).toString()
    } catch (_: Exception) {
        null
    }
}

/** 会话标题上限，照抄 dsh-base 里 session-title 的 maxTitleBytes（按 UTF-8 字节算） */
private const val TITLE_MAX_BYTES = 80

/** 一个字符串占几个 UTF-8 字节 —— 汉字是 3 个，不是 1 个 */
private fun utf8ByteCount(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/** 问一下文件选择器，这个音频本来叫什么名字 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
} catch (_: Exception) {
    null
}

private fun guessAudioExt(context: android.content.Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri) ?: return ".mp3"
    return when {
        mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
        mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
        mime.contains("ogg") -> ".ogg"
        mime.contains("wav") -> ".wav"
        mime.contains("aac") -> ".aac"
        mime.contains("flac") -> ".flac"
        else -> ".mp3"
    }
}

/** 读图并按需降采样，避免大图 OOM */
private fun decodeScaled(path: String?, maxW: Int, maxH: Int): Bitmap? {
    if (path.isNullOrEmpty()) return null
    return try {
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxW || bounds.outHeight / sample > maxH) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

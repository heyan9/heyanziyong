package com.example.aiwatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", r.resultCode).putExtra("data", d))
            Toast.makeText(this, "已开启,切到其他 App 就能看到 AI 悬浮球", Toast.LENGTH_LONG).show()
        } else toast("没有授权录屏")
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAssistant()
        else toast("需要麦克风权限才能听你说话")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("请允许“显示在其他应用上层”,然后回来再点一次"); return
        }
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { permLauncher.launch(need.toTypedArray()); return }
        if (Store.apiKey.isBlank() || Store.model.isBlank()) { toast("请先到“设置”填令牌和模型名"); return }
        projLauncher.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var tab by remember { mutableStateOf(0) }
                    Column(Modifier.statusBarsPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            listOf("启动", "角色", "设置").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                            when (tab) {
                                0 -> StartTab()
                                1 -> PersonaTab()
                                else -> SettingsTab()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable fun StartTab() {
        Text("使用步骤", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("1. 到“设置”填好令牌和模型名\n2. 到“角色”设定 AI 的人设\n3. 点下面按钮,授权悬浮窗、麦克风、录屏\n4. 切到任意 App,点悬浮球展开面板,点“开始聆听”就能对它说话")
        Spacer(Modifier.height(16.dp))
        Button(onClick = { startAssistant() }, Modifier.fillMaxWidth()) { Text("开启悬浮助手") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { stopService(Intent(this@MainActivity, OverlayService::class.java)) }, Modifier.fillMaxWidth()) { Text("关闭悬浮助手") }
    }

    @Composable fun PersonaTab() {
        var list by remember { mutableStateOf(Store.personas()) }
        var sel by remember { mutableStateOf(Store.active().id) }
        val cur = list.firstOrNull { it.id == sel } ?: list.first()
        var name by remember(cur.id) { mutableStateOf(cur.name) }
        var prompt by remember(cur.id) { mutableStateOf(cur.prompt) }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p ->
                FilterChip(selected = p.id == cur.id, onClick = { sel = p.id; Store.activeId = p.id }, label = { Text(p.name) })
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it }, label = { Text("角色名字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(prompt, { prompt = it }, label = { Text("人设(性格、身份、说话风格、对我的称呼……)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                list = list.map { if (it.id == cur.id) it.copy(name = name, prompt = prompt) else it }
                Store.savePersonas(list); toast("已保存")
            }) { Text("保存") }
            OutlinedButton(onClick = {
                val p = Persona(UUID.randomUUID().toString(), "新角色", "你是……")
                list = list + p; Store.savePersonas(list); sel = p.id; Store.activeId = p.id
            }) { Text("新建") }
            OutlinedButton(enabled = list.size > 1, onClick = {
                list = list.filter { it.id != cur.id }; Store.savePersonas(list)
                sel = list.first().id; Store.activeId = sel
            }) { Text("删除") }
        }
    }

    @Composable fun SettingsTab() {
        var base by remember { mutableStateOf(Store.baseUrl) }
        var key by remember { mutableStateOf(Store.apiKey) }
        var model by remember { mutableStateOf(Store.model) }
        var vm by remember { mutableStateOf(Store.visionMode) }
        var screen by remember { mutableStateOf(Store.useScreen) }
        var tts by remember { mutableStateOf(Store.tts) }
        var auto by remember { mutableStateOf(Store.autoComment) }
        var sec by remember { mutableStateOf(Store.autoInterval.toString()) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { base = "https://api-inference.modelscope.cn/v1"; Store.baseUrl = base }) { Text("魔搭") }
            OutlinedButton(onClick = { base = "https://api.deepseek.com"; Store.baseUrl = base }) { Text("DeepSeek 官方") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(base, { base = it; Store.baseUrl = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(key, { key = it; Store.apiKey = it }, label = { Text("令牌 / API Key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(model, { model = it; Store.model = it }, label = { Text("模型名(以平台模型页显示的为准)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        Spacer(Modifier.height(16.dp))
        Text("屏幕理解方式", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(vm == 0, { vm = 0; Store.visionMode = 0 }); Text("直接发图片(模型要支持图片)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(vm == 1, { vm = 1; Store.visionMode = 1 }); Text("本地识字后发文字(任何模型都行)")
        }
        SwitchRow("监看屏幕", screen) { screen = it; Store.useScreen = it }
        SwitchRow("语音播报回复", tts) { tts = it; Store.tts = it }
        SwitchRow("屏幕变化时主动评论(耗额度)", auto) { auto = it; Store.autoComment = it }
        OutlinedTextField(sec, { sec = it; it.toIntOrNull()?.let { n -> Store.autoInterval = n.coerceAtLeast(5) } },
            label = { Text("主动评论最短间隔(秒)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }

    @Composable fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(v, on)
        }
    }
}

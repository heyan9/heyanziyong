package com.example.aiwatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.UUID

private val Blue = Color(0xFF0084FF)
private val BlueLight = Color(0xFF5CB8FF)
private val Ink = Color(0xFF1A2233)
private val Grad = Brush.horizontalGradient(listOf(Blue, BlueLight))

class MainActivity : ComponentActivity() {

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", r.resultCode).putExtra("data", d))
            toast("已开启,切到其他 App 就能看到悬浮球")
        } else toast("没有授权录屏")
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAssistant()
        else toast("需要麦克风权限才能听你说话")
    }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opt)!! }
            val s = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
            val out = Bitmap.createScaledBitmap(sq, 256, 256, true)
            File(filesDir, "avatar.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("头像已设置,重新开启助手后生效")
        } catch (e: Exception) { toast("图片读取失败") }
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White)) {
                var tab by remember { mutableStateOf(0) }
                Box(
                    Modifier.fillMaxSize().background(Color.White).drawBehind {
                        drawCircle(
                            Brush.radialGradient(listOf(Blue.copy(0.20f), Color.Transparent),
                                center = Offset(size.width * 0.95f, size.height * 0.30f), radius = size.width * 0.8f),
                            radius = size.width * 0.8f, center = Offset(size.width * 0.95f, size.height * 0.30f))
                        drawCircle(
                            Brush.radialGradient(listOf(BlueLight.copy(0.18f), Color.Transparent),
                                center = Offset(size.width * 0.05f, size.height * 0.75f), radius = size.width * 0.8f),
                            radius = size.width * 0.8f, center = Offset(size.width * 0.05f, size.height * 0.75f))
                    }
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Header()
                        TabBar(tab) { tab = it }
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp).navigationBarsPadding().imePadding()
                        ) {
                            when (tab) {
                                0 -> StartTab()
                                1 -> PersonaTab()
                                else -> SettingsTab()
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    // ---------------- 通用组件 ----------------
    @Composable fun Header() {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Brush.linearGradient(listOf(Blue, BlueLight, Color(0xFF9AD3FF))))
                .statusBarsPadding().padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text("AI 屏幕助手", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("看得见屏幕,听得懂你说话", color = Color.White.copy(0.88f), fontSize = 13.sp)
        }
    }

    @Composable fun TabBar(sel: Int, onSel: (Int) -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clip(shape)
                .background(Color.White.copy(0.78f)).border(1.dp, Blue.copy(0.15f), shape).padding(4.dp)
        ) {
            listOf("启动", "角色", "设置").forEachIndexed { i, t ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .then(if (sel == i) Modifier.background(Grad) else Modifier)
                        .clickable { onSel(i) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text(t, color = if (sel == i) Color.White else Ink, fontWeight = FontWeight.Medium) }
            }
        }
    }

    /** 毛玻璃卡片:半透明白 + 柔和蓝色阴影 + 渐变描边 */
    @Composable fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 14.dp)
                .shadow(10.dp, shape, ambientColor = Blue.copy(0.25f), spotColor = Blue.copy(0.25f))
                .clip(shape).background(Color.White.copy(0.78f))
                .border(1.dp, Brush.linearGradient(listOf(Color.White, Blue.copy(0.25f))), shape)
                .padding(16.dp),
            content = content
        )
    }

    @Composable fun GButton(text: String, modifier: Modifier = Modifier, outlined: Boolean = false,
                            enabled: Boolean = true, onClick: () -> Unit) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier.fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
                .then(if (outlined) Modifier.background(Color.White.copy(0.6f)).border(1.dp, Blue.copy(0.5f), shape)
                      else Modifier.background(Grad))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(text, color = if (outlined) Blue else Color.White, fontWeight = FontWeight.Medium) }
    }

    @Composable fun GField(value: String, onChange: (String) -> Unit, label: String,
                           modifier: Modifier = Modifier, password: Boolean = false, single: Boolean = true) {
        OutlinedTextField(
            value, onChange, label = { Text(label) }, singleLine = single,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Blue, unfocusedBorderColor = Blue.copy(0.25f),
                focusedLabelColor = Blue, cursorColor = Blue,
                focusedContainerColor = Color.White.copy(0.6f), unfocusedContainerColor = Color.White.copy(0.5f)
            ),
            modifier = modifier.fillMaxWidth()
        )
    }

    @Composable fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Ink)
            Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = Blue, checkedThumbColor = Color.White))
        }
    }

    @Composable fun SectionTitle(t: String) {
        Text(t, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
    }

    // ---------------- 页面 ----------------
    @Composable fun StartTab() {
        GlassCard {
            SectionTitle("使用步骤")
            Text("1. 到“设置”填好令牌和模型名\n2. 到“角色”设定 AI 的人设\n3. 点下面按钮,授权悬浮窗、麦克风、录屏\n4. 切到任意 App,点悬浮球展开面板,点“开始聆听”就能对它说话",
                color = Ink.copy(0.8f), lineHeight = 22.sp)
        }
        GButton("开启悬浮助手") { startAssistant() }
        Spacer(Modifier.height(10.dp))
        GButton("关闭悬浮助手", outlined = true) { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
    }

    @Composable fun PersonaTab() {
        var list by remember { mutableStateOf(Store.personas()) }
        var sel by remember { mutableStateOf(Store.active().id) }
        val cur = list.firstOrNull { it.id == sel } ?: list.first()
        var name by remember(cur.id) { mutableStateOf(cur.name) }
        var prompt by remember(cur.id) { mutableStateOf(cur.prompt) }

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p ->
                val on = p.id == cur.id
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier.clip(shape)
                        .then(if (on) Modifier.background(Grad) else Modifier.background(Color.White.copy(0.7f)).border(1.dp, Blue.copy(0.25f), shape))
                        .clickable { sel = p.id; Store.activeId = p.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(p.name, color = if (on) Color.White else Ink) }
            }
        }
        GlassCard {
            SectionTitle("编辑角色")
            GField(name, { name = it }, "角色名字")
            Spacer(Modifier.height(10.dp))
            GField(prompt, { prompt = it }, "人设(性格、身份、说话风格、对我的称呼……)",
                modifier = Modifier.heightIn(min = 180.dp), single = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GButton("保存", Modifier.weight(1f)) {
                list = list.map { if (it.id == cur.id) it.copy(name = name, prompt = prompt) else it }
                Store.savePersonas(list); toast("已保存")
            }
            GButton("新建", Modifier.weight(1f), outlined = true) {
                val p = Persona(UUID.randomUUID().toString(), "新角色", "你是……")
                list = list + p; Store.savePersonas(list); sel = p.id; Store.activeId = p.id
            }
            GButton("删除", Modifier.weight(1f), outlined = true, enabled = list.size > 1) {
                list = list.filter { it.id != cur.id }; Store.savePersonas(list)
                sel = list.first().id; Store.activeId = sel
            }
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
        var op by remember { mutableStateOf(Store.opacity.toFloat()) }

        GlassCard {
            SectionTitle("模型接口")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GButton("魔搭", Modifier.weight(1f), outlined = true) { base = "https://api-inference.modelscope.cn/v1"; Store.baseUrl = base }
                GButton("DeepSeek 官方", Modifier.weight(1f), outlined = true) { base = "https://api.deepseek.com"; Store.baseUrl = base }
            }
            Spacer(Modifier.height(10.dp))
            GField(base, { base = it; Store.baseUrl = it }, "接口地址")
            Spacer(Modifier.height(10.dp))
            GField(key, { key = it; Store.apiKey = it }, "令牌 / API Key", password = true)
            Spacer(Modifier.height(10.dp))
            GField(model, { model = it; Store.model = it }, "模型名(以平台模型页显示的为准)")
        }
        GlassCard {
            SectionTitle("屏幕理解方式")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm == 0, { vm = 0; Store.visionMode = 0 }, colors = RadioButtonDefaults.colors(selectedColor = Blue))
                Text("直接发图片(模型要支持图片)", color = Ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm == 1, { vm = 1; Store.visionMode = 1 }, colors = RadioButtonDefaults.colors(selectedColor = Blue))
                Text("本地识字后发文字(任何模型都行)", color = Ink)
            }
        }
        GlassCard {
            SectionTitle("行为与外观")
            SwitchRow("监看屏幕", screen) { screen = it; Store.useScreen = it }
            SwitchRow("语音播报回复", tts) { tts = it; Store.tts = it }
            SwitchRow("屏幕变化时主动评论(耗额度)", auto) { auto = it; Store.autoComment = it }
            Spacer(Modifier.height(6.dp))
            GField(sec, { sec = it; it.toIntOrNull()?.let { n -> Store.autoInterval = n.coerceAtLeast(5) } }, "主动评论最短间隔(秒)")
            Spacer(Modifier.height(14.dp))
            Text("悬浮窗透明度  ${op.toInt()}%", color = Ink)
            Slider(
                value = op, onValueChange = { op = it; Store.opacity = it.toInt() }, valueRange = 20f..100f,
                colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Blue.copy(0.2f))
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GButton("选择悬浮球头像", Modifier.weight(1f)) { picker.launch("image/*") }
                GButton("恢复默认", Modifier.weight(1f), outlined = true) {
                    File(filesDir, "avatar.png").delete(); toast("已恢复,重新开启助手后生效")
                }
            }
        }
    }
}

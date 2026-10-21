package com.diego.pasoapaso.field

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import android.util.Base64
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.diego.pasoapaso.R
import com.diego.pasoapaso.data.WalkDatabase
import com.diego.pasoapaso.tracking.WalkRecorder
import com.diego.pasoapaso.tracking.SessionState
import com.diego.pasoapaso.ui.WalkActivity
import com.diego.pasoapaso.ui.applySystemInsets
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Pantallas de negocio; FieldStore concentra HTTP y TrackingSession concentra GPS. */
class FieldActivity : AppCompatActivity() {
    private lateinit var store: FieldStore
    private lateinit var content: LinearLayout
    private lateinit var banner: TextView
    private var snapshot = JSONObject()
    private var page = "home"
    private var selectedVisit = ""
    private var filter = "all"
    private var busy = false
    private var online = false
    private var agendaDay = LocalDate.now()
    private var agendaWeek = false
    private var agendaTech = ""
    private var agendaClient = ""
    private var agendaPriority = ""
    private var photoKind = "before"
    private var csvReport = ""
    private fun outbox() = VisitOutbox(this, store)
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) work {
            if(csvReport.isEmpty()) csvReport=makeCsv()
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(csvReport.toByteArray(Charsets.UTF_8)) } ?: error("No se pudo abrir el archivo.") }
            message("Reporte guardado.")
        }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && selectedVisit.isNotBlank()) {
            val id = selectedVisit; val kind = photoKind
            work {
                val encoded = withContext(Dispatchers.IO) { compressPhoto(uri) }
                outbox().edit(id) { draft ->
                    val photos = draft.optJSONArray("photos") ?: JSONArray()
                    check(photos.length() < 4) { "Puedes adjuntar hasta cuatro fotografías." }
                    photos.put(JSONObject().put("kind",kind).put("label",if(kind=="before") "Antes del mantenimiento" else "Después del mantenimiento").put("data",encoded))
                    draft.put("photos",photos)
                }
                render()
            }
        }
    }
    private val fields = linkedMapOf<String, EditText>()
    private val buttons = mutableListOf<MaterialButton>()
    private var pickerTarget: String? = null
    private var locationToken: CancellationTokenSource? = null
    private val accessPages = setOf("login", "activate", "recover")
    private val secretFields = setOf("password", "confirmation", "code")
    private val blue = Color.rgb(37, 99, 235)
    private val ink = Color.rgb(15, 23, 42)

    private val pickMap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            fields["latitude"]?.setText(result.data?.getDoubleExtra("lat",0.0).toString())
            fields["longitude"]?.setText(result.data?.getDoubleExtra("lon",0.0).toString())
        }
    }
    private val recorded = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (store.loggedIn) render()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            pickerTarget?.let(::arrival)
        } else message("Se necesita ubicación precisa para registrar la llegada. Puedes habilitarla en los ajustes del teléfono.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        photoKind = savedInstanceState?.getString("photoKind") ?: "before"
        agendaDay = LocalDate.parse(savedInstanceState?.getString("agendaDay") ?: LocalDate.now().toString())
        agendaWeek = savedInstanceState?.getBoolean("agendaWeek") ?: false
        store = FieldStore(this)
        snapshot = store.cached ?: JSONObject()
        page = savedInstanceState?.getString("page") ?: "home"
        selectedVisit = savedInstanceState?.getString("visit") ?: ""
        filter = savedInstanceState?.getString("filter") ?: "all"
        pickerTarget = savedInstanceState?.getString("picker")
        if (!store.loggedIn && page !in accessPages) page = "login"
        render()
        savedInstanceState?.getBundle("form")?.let { values -> fields.forEach { (key, view) ->
            if (key !in secretFields && values.containsKey(key)) view.setText(values.getString(key))
        } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page !in listOf("home", "login")) { page = if(store.loggedIn) "home" else "login"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        if (store.loggedIn && page == "home") refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("photoKind",photoKind); outState.putString("agendaDay",agendaDay.toString()); outState.putBoolean("agendaWeek",agendaWeek)
        outState.putString("page",page); outState.putString("visit",selectedVisit); outState.putString("filter",filter)
        outState.putString("picker",pickerTarget)
        outState.putBundle("form", Bundle().apply { fields.forEach { (key, value) -> if(key !in secretFields) putString(key, value.text.toString()) } })
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { locationToken?.cancel(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    private fun version(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    private fun rows(name: String): List<JSONObject> = snapshot.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun visit(): JSONObject? = rows("visits").firstOrNull { it.getString("id") == selectedVisit }
    private fun status(s: String) = when(s) { "pending" -> "Pendiente"; "in_progress" -> "En atención"; "completed" -> "Completada"; "not_done" -> "No realizada"; else -> s }
    private fun go(target: String) { page = target; render() }

    private fun render() {
        fields.clear(); buttons.clear()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff3f6fc.toInt()) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard))
            view.post { currentFocus?.let { focused -> focused.requestRectangleOnScreen(Rect(0,0,focused.width,focused.height+dp(24)),false) } }
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(24)) }
        val center = FrameLayout(this)
        val column=content
        center.addView(column, FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        center.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
            val width=minOf(right-left,dp(720))
            if(width>0 && column.layoutParams.width!=width) column.layoutParams=column.layoutParams.apply { this.width=width }
        }
        scroll.addView(center); root.addView(scroll, LinearLayout.LayoutParams(-1,-1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { setImageResource(R.drawable.nexo_mark); contentDescription = "NexoVisita" }
        header.addView(logo, LinearLayout.LayoutParams(dp(44),dp(44)))
        header.addView(TextView(this).apply { text = "NexoVisita"; textSize = 25f; setTextColor(ink); setTypeface(null,Typeface.BOLD); setPadding(dp(10),0,0,0) })
        content.addView(header)
        label("Cada visita, un trabajo bien registrado.",14)
        banner = label(if (page in accessPages) "Gestión de visitas técnicas · Versión ${version()}" else
            "${store.user?.optString("name")} · ${if(store.coordinator) "Coordinador" else "Técnico"}\n${if(online) "Datos actualizados" else "Copia local · pulsa Actualizar para consultar cambios"}",13)
        if (page !in listOf("home","login")) button("‹ Volver") { go(if(store.loggedIn) "home" else "login") }
        when(page) {
            "login" -> loginForm()
            "activate" -> accessForm(false)
            "recover" -> accessForm(true)
            "client" -> clientForm()
            "technician" -> technicianForm()
            "new_visit" -> visitForm()
            "agenda" -> agenda()
            "indicators" -> indicators()
            "reschedule" -> visit()?.let(::rescheduleForm)
            "sync" -> syncScreen()
            "detail" -> visit()?.let(::detail) ?: run { label("Actualiza la lista para consultar esta visita."); button("Actualizar") { refresh() } }
            "clients" -> { heading("Clientes"); button("+ Nuevo cliente") { go("client") }; rows("clients").forEach { c -> card { label(c.getString("name"),18,true); label(c.getString("address")); button("Ver ubicación") { openMap(c) } } } }
            "team" -> teamScreen()
            else -> home()
        }
    }

    private fun label(value: String, size: Int = 15, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink); setPadding(0,dp(6),0,dp(6))
        if(bold) setTypeface(null, Typeface.BOLD)
        content.addView(this, LinearLayout.LayoutParams(-1,-2))
    }
    private fun heading(value: String) { label(value,26,true) }
    private fun card(block: () -> Unit) {
        val parent = content
        val card = MaterialCardView(this).apply { radius = dp(18).toFloat(); cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE); strokeWidth = dp(1); strokeColor = 0xffe2e8f0.toInt() }
        parent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12)) }
        card.addView(content); block(); content = parent
    }
    private fun button(title: String, secondary: Boolean = false, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = title; isAllCaps = false; letterSpacing=0f; cornerRadius = dp(12); minHeight=dp(48); isEnabled = !busy
        content.addView(this, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        if (secondary) {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(blue)
            elevation = 0f
            stateListAnimator = null
            textSize = 14f
            maxLines = 2
        }
        buttons.add(this); setOnClickListener { if (!busy) action() }
    }
    private fun input(key: String, title: String, initial: String = "", multiline: Boolean = false, secret: Boolean = false): EditText {
        val box = TextInputLayout(this).apply {
            hint = title; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = Color.WHITE
            setBoxCornerRadii(dp(12).toFloat(),dp(12).toFloat(),dp(12).toFloat(),dp(12).toFloat())
        }
        val edit = TextInputEditText(box.context).apply {
            id = 100000 + (key.hashCode() and 0xfffff)
            background = null; setPadding(dp(16),dp(16),dp(16),dp(16)); minHeight=dp(56)
            setText(initial); textSize=16f; setTextColor(ink)
            inputType = when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                key == "code" || key == "duration_minutes" -> InputType.TYPE_CLASS_NUMBER
                key == "username" || key == "identifier" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                key == "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                key in listOf("latitude","longitude") -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT }
            if(multiline) { minLines=3; gravity=Gravity.TOP } else setSingleLine(true)
            imeOptions=if(multiline) android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters=arrayOf(InputFilter.LengthFilter(if(key=="code") 6 else if(key=="customer_name") 100 else if(multiline) 2000 else 240))
            if(secret || key == "code") isSaveEnabled=false
        }
        box.addView(edit); content.addView(box,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        fields[key]=edit; return edit
    }
    private fun value(key: String) = fields[key]?.text.toString().trim()
    private fun message(text: String) { if(!isFinishing) MaterialAlertDialogBuilder(this).setTitle("NexoVisita").setMessage(text).setPositiveButton("Entendido",null).show() }
    private fun enableForm(enabled: Boolean) {
        fun visit(group: android.view.ViewGroup) {
            for(i in 0 until group.childCount) {
                val view=group.getChildAt(i)
                if(view is EditText || view is CompoundButton) view.isEnabled=enabled
                if(view is android.view.ViewGroup) visit(view)
            }
        }
        visit(content)
    }
    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy=true; enableForm(false); buttons.forEach { it.isEnabled=false }; banner.text="Conectando con el servidor… Si estaba suspendido, puede tardar alrededor de un minuto."
        lifecycleScope.launch {
            try { action() } catch (e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                if(e is FieldApiException && e.status==401 && page !in accessPages) { store.clearSession(); snapshot=JSONObject(); page="login"; render() }
                val description = when(e) {
                    is FieldApiException -> e.message
                    is java.io.IOException -> "No hay conexión con el servidor. Revisa la dirección, el servidor y la conexión USB o Wi-Fi. Los recorridos guardados siguen en el teléfono."
                    is IllegalArgumentException, is IllegalStateException -> e.message
                    else -> "No se pudo realizar la operación. Tus datos guardados se conservan."
                }
                message(description ?: "Error de conexión.")
                banner.text="Operación no completada · puedes volver a intentarlo"
            } finally { busy=false; enableForm(true); buttons.forEach { it.isEnabled=true } }
        }
    }
    private fun refresh() = work {
        online=false
        outbox().sync(); VisitSyncService.schedule(this@FieldActivity)
        snapshot=store.refresh(); online=true; render()
    }
    private suspend fun changed() {
        try { snapshot=store.refresh(); online=true; render() }
        catch (_: java.io.IOException) {
            online=false; render()
            message("El cambio se guardó en el servidor, pero no se pudo actualizar la pantalla. Pulsa Actualizar cuando se restablezca la conexión.")
        }
    }

    private fun loginForm() {
        heading("Tu equipo, conectado")
        label("Ingresa con la cuenta creada por el responsable de tu empresa.")
        card {
            input("identifier","Usuario")
            input("password","Contraseña",secret=true)
            button("Iniciar sesión") {
                val email=value("identifier"); val password=value("password")
                if(email.isBlank() || password.isBlank()) { message("Completa el usuario y la contraseña."); return@button }
                work { store.login(email,password); snapshot=store.refresh(); online=true; go("home") }
            }
        }
        button("¿Olvidaste tu contraseña?", secondary=true) {
            go("recover")
        }
        button("¿Recibiste una invitación? Activa tu cuenta", secondary=true) {
            go("activate")
        }

    }

    private fun accessForm(recovery: Boolean) {
        heading(if(recovery) "Recuperar acceso" else "Activar / verificar cuenta")
        label(if(recovery) "Usa el correo verificado de tu cuenta. Al finalizar también te recordaremos tu usuario." else "Necesitas una cuenta creada por el coordinador. El código verifica que tienes acceso al correo; nunca escribas aquí la contraseña de Gmail.")
        input("email", "Correo de la cuenta")
        val path=if(recovery) "/password" else "/activate"
        button("Enviar / reenviar código") {
            val email=value("email")
            if(email.isBlank()) { message("Escribe tu correo."); return@button }
            work {
                val result=store.request("POST", "$path/request", JSONObject().put("email",email))
                banner.text="Revisa tu correo y la carpeta spam"
                message(result.getString("message"))
            }
        }
        label("Si ya recibiste la invitación, usa su código. Vence en 10 minutos. Para reenviar, espera 60 segundos.",13)
        input("code", "Código de 6 dígitos")
        if(!recovery) input("username", "Usuario: letras, números, punto o guion bajo")
        input("password", "Nueva contraseña de NexoVisita (10 a 128 caracteres)", secret=true)
        input("confirmation", "Repite la contraseña", secret=true)
        button(if(recovery) "Guardar nueva contraseña" else "Verificar y activar") {
            val password=value("password")
            if(password.length !in 10..128 || password!=value("confirmation")) {
                message("Las contraseñas deben coincidir y tener entre 10 y 128 caracteres."); return@button
            }
            if(!value("code").matches(Regex("[0-9]{6}"))) { message("Escribe el código de 6 dígitos."); return@button }
            val data=JSONObject().put("email",value("email")).put("code",value("code")).put("password",password)
            if(!recovery) data.put("username",value("username"))
            work {
                val result=store.request("POST", "$path/confirm", data)
                store.clearSession(); snapshot=JSONObject(); online=false; go("login")
                fields["identifier"]?.setText(result.getString("username"))
                message("${result.getString("message")}\nUsuario: ${result.getString("username")}")
            }
        }
    }

    private fun home() {
        val visits=rows("visits")
        heading(if(store.coordinator) "Panel de coordinación" else "Mis visitas")
        val today=LocalDate.now().toString()
        val todayVisits=visits.filter { it.optString("scheduled_date")==today }
        card {
            label("HOY · $today",12,true)
            label("${todayVisits.count { it.optString("status")=="completed" }} de ${todayVisits.size} visitas completadas",23,true)
            label("${visits.count { it.optString("status")=="pending" }} pendientes · ${visits.count { it.optString("status")=="in_progress" }} en atención",14)
        }
        button("Actualizar") { refresh() }
        button("Agenda diaria / semanal") { go("agenda") }
        if(!store.coordinator) button("Borradores y sincronización") { go("sync") }
        if(store.coordinator) {
            button("Indicadores y reporte CSV") { go("indicators") }
            button("+ Asignar visita") { go("new_visit") }
            button("Clientes (${rows("clients").size})") { go("clients") }
            button("Equipo (${rows("technicians").size})") { go("team") }
        } else {
            button("Registrar recorrido de trabajo") { record() }
            button("Mis recorridos guardados") { localRoutes() }
        }
        label("AGENDA",13,true)
        val names=arrayOf("Todas","Hoy","Pendientes","En atención","Completadas","No realizadas")
        val keys=arrayOf("all","today","pending","in_progress","completed","not_done")
        button("Filtrar: ${names[keys.indexOf(filter).coerceAtLeast(0)]}") {
            MaterialAlertDialogBuilder(this).setTitle("Mostrar visitas").setItems(names) { _, index -> filter=keys[index]; render() }.show()
        }
        val shown=visits.filter { filter=="all" || (filter=="today" && it.getString("scheduled_date")==today) || it.getString("status")==filter }
        if(shown.isEmpty()) card { label("No hay visitas en esta vista",18,true); label(if(store.coordinator) "Crea un cliente y un técnico para asignar la primera visita." else "Las visitas asignadas aparecerán aquí al actualizar.") }
        shown.forEach { v -> card {
            label("${status(v.getString("status"))} · ${v.getString("scheduled_date")}",13,true).setTextColor(blue)
            label(v.getString("client_name"),20,true); label(v.getString("task")); label(v.getString("address"),13)
            if(store.coordinator) label("Técnico: ${v.getString("technician_name")}",13)
            button("Ver visita") { selectedVisit=v.getString("id"); go("detail") }
        } }
        if(visits.isNotEmpty()) button("Compartir resumen de visitas") { shareSummary(visits) }
        button("Cerrar sesión") {
            val tracking=WalkRecorder.get(application).ui.value
            if(tracking.detail==null && tracking.state!=SessionState.IDLE) { message("Guarda o descarta el recorrido activo antes de cambiar de cuenta."); return@button }
            work {
                try { store.logout() }
                catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e }
                finally { snapshot=JSONObject(); online=false; go("login") }
            }
        }
        label("NexoVisita · Versión ${version()}",12)
    }

    private fun clientForm() {
        heading("Nuevo cliente")
        input("name","Nombre del cliente"); input("address","Dirección")
        button("Elegir ubicación en Google Maps") { pickMap.launch(Intent(this,FieldMapActivity::class.java).putExtra("pick",true)) }
        input("latitude","Latitud (ejemplo: -12.0464)")
        input("longitude","Longitud (ejemplo: -77.0428)")
        button("Guardar cliente") {
            val lat=value("latitude").toDoubleOrNull(); val lon=value("longitude").toDoubleOrNull()
            if(lat==null || lon==null || lat !in -90.0..90.0 || lon !in -180.0..180.0 || value("name").isBlank() || value("address").isBlank()) {
                message("Completa nombre, dirección y una ubicación válida."); return@button
            }
            val data=JSONObject().put("name",value("name")).put("address",value("address")).put("latitude",lat).put("longitude",lon)
            work { store.request("POST","/clients",data); page="clients"; changed() }
        }
    }
    private fun technicianForm() {
        heading("Invitar técnico")
        label("Escribe el correo real del técnico. Recibirá un código y elegirá su propio usuario y contraseña. Necesitas el correo del servidor configurado e Internet.")
        input("name","Nombre completo"); input("email","Correo real del técnico")
        button("Enviar invitación") {
            val data=JSONObject().put("name",value("name")).put("email",value("email"))
            work { val result=store.request("POST","/technicians",data); page="team"; changed(); message(result.getString("message")) }
        }
    }
    private fun select(title: String, choices: List<JSONObject>, name: String): Spinner {
        label(title,14,true)
        return Spinner(this).apply {
            adapter=ArrayAdapter(this@FieldActivity,android.R.layout.simple_spinner_dropdown_item,choices.map { it.getString(name) })
            content.addView(this,LinearLayout.LayoutParams(-1,dp(52)))
        }
    }
    private fun visitForm() {
        heading("Asignar visita")
        val clients=rows("clients"); val users=rows("technicians").filter { it.optInt("active",1)==1 }
        if(clients.isEmpty() || users.isEmpty()) {
            label("Primero registra un cliente y un técnico que haya activado su cuenta.")
            button("Crear cliente") { go("client") }; button("Invitar técnico") { go("technician") }; return
        }
        val client=select("Cliente",clients,"name"); client.id=R.id.nexo_client_spinner
        val tech=select("Técnico responsable",users,"name"); tech.id=R.id.nexo_technician_spinner
        val date=input("scheduled_date","Fecha (AAAA-MM-DD)",LocalDate.now().toString())
        button("Elegir fecha") {
            val d=runCatching { LocalDate.parse(date.text.toString()) }.getOrDefault(LocalDate.now())
            DatePickerDialog(this,{ _,y,m,day -> date.setText(LocalDate.of(y,m+1,day).toString()) },d.year,d.monthValue-1,d.dayOfMonth).show()
        }
        scheduleInputs()
        input("task","Trabajo solicitado",multiline=true)
        button("Asignar al técnico") {
            val data=JSONObject().put("client_id",clients[client.selectedItemPosition].getString("id"))
                .put("technician_id",users[tech.selectedItemPosition].getString("id")).put("scheduled_date",value("scheduled_date")).put("task",value("task"))
            addSchedule(data)
            work { store.request("POST","/visits",data); page="home"; changed() }
        }
    }
    private fun detail(v: JSONObject) {
        val id=v.getString("id"); val current=v.getString("status")
        heading(v.getString("client_name"))
        card {
            label(status(current),18,true).setTextColor(blue)
            label(v.getString("task"),18,true); label(v.getString("address"))
            label("Fecha: ${v.getString("scheduled_date")}\nTécnico: ${v.getString("technician_name")}",14)
            if(store.coordinator) {
                val state=v.optString("mail_status")
                label("Aviso por correo: " + when(state) {
                    "pending" -> "pendiente de envío"
                    "sent" -> "aceptado por el servidor de correo"
                    "failed" -> "no enviado; revisa el servidor"
                    else -> "no solicitado (correo sin verificar)"
                },13)
                if(state=="failed") button("Reintentar aviso por correo") {
                    work { store.request("POST","/mail/retry",JSONObject().put("visit_id",id)); changed() }
                }
            }
            if(!v.isNull("arrival_at")) label("Llegada: ${v.getString("arrival_at")}\nPrecisión GPS estimada: ${v.optDouble("arrival_accuracy").toInt()} m",13)
            if(!v.isNull("finished_at")) label("Cierre: ${v.getString("finished_at")}",13)
            if(v.optString("notes").isNotBlank()) label("Resultado: ${v.getString("notes")}")
        }
        label("Horario: ${v.optString("scheduled_time").ifBlank { "sin hora definida" }} · ${v.optInt("duration_minutes",60)} min · ${priorityName(v.optString("priority","normal"))}",13)
        if(store.coordinator && current=="pending") button("Reprogramar visita") { go("reschedule") }
        button("Ubicación del cliente") { openMap(v) }
        if(!v.isNull("arrival_lat")) button("Ver ubicación registrada de llegada") {
            openMap(JSONObject().put("name","Llegada registrada").put("latitude",v.getDouble("arrival_lat")).put("longitude",v.getDouble("arrival_lon")))
        }
        if(v.optInt("has_route")==1) button("Ver recorrido adjunto") {
            startActivity(Intent(this,FieldMapActivity::class.java).putExtra("visit",id).putExtra("title",v.getString("client_name"))
                .putExtra("lat",v.getDouble("latitude")).putExtra("lon",v.getDouble("longitude")))
        }
        if(!store.coordinator) {
            if(current=="pending") button("Registrar llegada con GPS") { pickerTarget=id; arrival(id) }
            if(current in listOf("pending","in_progress") && outbox().read(id).optString("state")=="draft") {
                evidenceForm(id)
                input("notes","Trabajo realizado u observaciones",store.draft(id),multiline=true).addTextChangedListener(object: android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { store.saveDraft(id,s?.toString().orEmpty()) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                if(current=="in_progress") button("Completar visita") { finishVisit(v,"completed") }
                button("Marcar como no realizada") { finishVisit(v,"not_done") }
            }
            button("Registrar recorrido") { record() }
            button(if(v.optInt("has_route")==1) "Reemplazar recorrido adjunto" else "Adjuntar un recorrido guardado") { attach(v) }
            label("Adjunta solo el recorrido de esta visita. Se compartirá con el coordinador. La ubicación es un dato de apoyo, no una prueba automática del trabajo.",13)
        }
        reportAndHistory(v)
        button("Actualizar visita") { refresh() }
    }
    private fun finishVisit(v: JSONObject, result: String) {
        val notes=value("notes")
        if(notes.length<5) { message("Describe el resultado o el motivo (mínimo 5 caracteres)."); return }
        MaterialAlertDialogBuilder(this).setTitle("${status(result)}: ¿confirmar?").setMessage("Se guardará el resultado de la atención.\n\n$notes")
            .setNegativeButton("Cancelar",null).setPositiveButton("Confirmar") { _, _ ->
                work {
                    val id=v.getString("id")
                    val draft=outbox().read(id)
                    val data=JSONObject().put("version",v.getInt("version")).put("status",result).put("notes",notes)
                        .put("checklist",draft.optJSONArray("checklist") ?: JSONArray())
                        .put("photos",draft.optJSONArray("photos") ?: JSONArray())
                        .put("confirmation",draft.optString("confirmation","not_requested"))
                        .put("customer_name",value("customer_name"))
                    if(data.optString("confirmation") in listOf("accepted","declined") && value("customer_name").isBlank()) {
                        message("Indica el nombre de quien recibió el servicio."); return@work
                    }
                    outbox().enqueue(id,data)
                    val retry=outbox().sync()
                    if(retry) VisitSyncService.schedule(this@FieldActivity)
                    render()
                    message(outbox().read(id).optString("message"))
                    try { snapshot=store.refresh(); online=true; render() } catch (_: java.io.IOException) { online=false }
                }
            }.show()
    }
    @SuppressLint("MissingPermission")
    private fun arrival(id: String) {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            pickerTarget=id; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        val v=rows("visits").firstOrNull { it.getString("id")==id } ?: return
        locationToken?.cancel(); val token=CancellationTokenSource().also { locationToken=it }
        busy=true; buttons.forEach { it.isEnabled=false }; banner.text="Obteniendo ubicación precisa…"
        LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(
            CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(0).setDurationMillis(15000).build(),token.token)
            .addOnCompleteListener { task ->
                if(isDestroyed || token.token.isCancellationRequested) return@addOnCompleteListener
                busy=false; buttons.forEach { it.isEnabled=true }
                val loc=if(task.isSuccessful) task.result else null
                if(loc==null || !loc.hasAccuracy() || loc.accuracy>100 || SystemClock.elapsedRealtime()-loc.elapsedRealtimeNanos/1000000 > 15000) {
                    banner.text="No se registró la llegada"; message("No se obtuvo una ubicación suficientemente precisa. Activa el GPS y vuelve a intentarlo en un lugar abierto."); return@addOnCompleteListener
                }
                val data=JSONObject().put("version",v.getInt("version")).put("latitude",loc.latitude).put("longitude",loc.longitude).put("accuracy",loc.accuracy.toDouble())
                work { store.request("POST","/visits/$id/arrive",data)
                    if(JourneyService.activeVisit==id) startService(Intent(this@FieldActivity,JourneyService::class.java).setAction(JourneyService.STOP))
                    changed() }
            }
    }
    private fun openMap(c: JSONObject) { startActivity(Intent(this,FieldMapActivity::class.java)
        .putExtra("lat",c.getDouble("latitude")).putExtra("lon",c.getDouble("longitude"))
        .putExtra("title",c.optString("client_name",c.optString("name","Cliente")))) }
    private fun record() { recorded.launch(Intent(this,WalkActivity::class.java).putExtra("field_owner",store.user?.getString("id"))) }
    private fun localRoutes() = work {
        val owned = store.routeIds().toSet()
        val walks = withContext(Dispatchers.IO) { WalkDatabase.get(this@FieldActivity).listWalks().filter { it.id in owned } }
        if (walks.isEmpty()) { message("Todavía no tienes recorridos guardados en esta cuenta."); return@work }
        MaterialAlertDialogBuilder(this@FieldActivity).setTitle("Mis recorridos guardados")
            .setItems(walks.map { "${java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()} · %.2f km".format(it.distanceMeters / 1000) }.toTypedArray()) { _, index ->
                startActivity(Intent(this@FieldActivity, WalkActivity::class.java).putExtra(WalkActivity.EXTRA_ID, walks[index].id))
            }.setNegativeButton("Cerrar", null).show()
    }
    private fun attach(v: JSONObject) = work {
        val owned=store.routeIds().toSet()
        val walks=withContext(Dispatchers.IO) { WalkDatabase.get(this@FieldActivity).listWalks().filter { it.id in owned } }
        if(walks.isEmpty()) { message("Primero registra y guarda un recorrido desde tu cuenta de técnico."); return@work }
        MaterialAlertDialogBuilder(this@FieldActivity).setTitle("Elige el recorrido de esta visita")
            .setItems(walks.map { "${java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()} · %.2f km".format(it.distanceMeters/1000) }.toTypedArray()) { _,index ->
                MaterialAlertDialogBuilder(this@FieldActivity).setTitle("Compartir con el coordinador")
                    .setMessage("El recorrido seleccionado quedará asociado a ${v.getString("client_name")}. Si había otro, será reemplazado.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Adjuntar") { _,_ -> work {
                        val detail=withContext(Dispatchers.IO) { WalkDatabase.get(this@FieldActivity).detail(walks[index].id) } ?: error("El recorrido ya no existe.")
                        val points=JSONArray(); detail.points.forEach { p -> points.put(JSONObject().put("lat",p.fix.latitude).put("lon",p.fix.longitude).put("segment",p.segment)) }
                        val w=detail.walk
                        store.request("POST","/visits/${v.getString("id")}/route",JSONObject().put("version",v.getInt("version"))
                            .put("distance_m",w.distanceMeters).put("duration_ms",w.durationMs).put("started_at",w.startedAt).put("ended_at",w.endedAt).put("points",points))
                        changed()
                    } }.show()
            }.setNegativeButton("Cancelar",null).show()
    }
    private fun shareSummary(visits: List<JSONObject>) {
        val report=buildString {
            append("NexoVisita — Resumen de visitas\n")
            append("Consulta: ${snapshot.optString("fetched_at")}\n\n")
            visits.forEach { append("${it.getString("scheduled_date")} | ${it.getString("client_name")} | ${it.getString("technician_name")} | ${status(it.getString("status"))}\n${it.getString("task")}\n${it.optString("notes")}\n\n") }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Compartir resumen"))
    }
    private fun teamScreen() {
        heading("Equipo técnico")
        button("+ Invitar técnico") { go("technician") }
        rows("technicians").forEach { u -> card {
            label(u.getString("name"),18,true); label(u.getString("email"))
            val verified=u.optInt("email_verified")==1
            val active=u.optInt("active")==1
            label(if(!verified) "Correo pendiente de verificación" else if(active) "Habilitado · Usuario: ${u.optString("username")}" else "Acceso deshabilitado")
            if(verified) button(if(active) "Revocar acceso" else "Habilitar acceso",true) {
                MaterialAlertDialogBuilder(this).setTitle(if(active) "Revocar acceso del técnico" else "Habilitar técnico")
                    .setMessage(if(active) "Se invalidarán sus sesiones y códigos. Se conservarán sus visitas. Una copia sin conexión ya descargada no puede borrarse a distancia." else "Podrá iniciar sesión nuevamente con su contraseña.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Confirmar") { _,_ -> work {
                        store.request("POST","/technicians/access",JSONObject().put("user_id",u.getString("id")).put("active",!active)); changed()
                    } }.show()
            }
        } }
    }
    private fun priorityName(value: String) = when(value) { "high" -> "Alta"; "low" -> "Baja"; else -> "Normal" }
    private fun scheduleInputs(v: JSONObject = JSONObject()) {
        input("scheduled_time","Hora de inicio (HH:MM)",v.optString("scheduled_time","09:00"))
        input("duration_minutes","Duración estimada en minutos",v.optInt("duration_minutes",60).toString())
        input("priority","Prioridad: alta, normal o baja",priorityName(v.optString("priority","normal")).lowercase())
        label("Horarios en la hora local del equipo. No se permiten visitas del mismo técnico que se superpongan.",13)
    }
    private fun addSchedule(data: JSONObject) {
        data.put("scheduled_time",value("scheduled_time")).put("duration_minutes",value("duration_minutes").toIntOrNull() ?: 0)
            .put("priority",when(value("priority").lowercase()) { "alta" -> "high"; "baja" -> "low"; "normal" -> "normal"; else -> "invalid" })
    }
    private fun rescheduleForm(v: JSONObject) {
        heading("Reprogramar visita")
        label("${v.getString("client_name")} · ${v.getString("technician_name")}")
        input("scheduled_date","Nueva fecha (AAAA-MM-DD)",v.getString("scheduled_date"))
        scheduleInputs(v)
        input("reason","Motivo del cambio",multiline=true)
        button("Guardar nueva programación") {
            val data=JSONObject().put("version",v.getInt("version")).put("scheduled_date",value("scheduled_date")).put("reason",value("reason"))
            addSchedule(data)
            work { store.request("POST","/visits/${v.getString("id")}/reschedule",data); page="detail"; changed() }
        }
    }
    private fun agenda() {
        heading(if(agendaWeek) "Agenda semanal" else "Agenda diaria")
        val first=if(agendaWeek) agendaDay.with(java.time.DayOfWeek.MONDAY) else agendaDay
        val last=if(agendaWeek) first.plusDays(6) else first
        label(if(agendaWeek) "$first — $last" else first.toString(),20,true)
        button("Elegir fecha") { DatePickerDialog(this,{ _,y,m,d -> agendaDay=LocalDate.of(y,m+1,d); render() },agendaDay.year,agendaDay.monthValue-1,agendaDay.dayOfMonth).show() }
        button(if(agendaWeek) "Cambiar a día" else "Cambiar a semana",true) { agendaWeek=!agendaWeek; render() }
        button("Anterior",true) { agendaDay=agendaDay.minusDays(if(agendaWeek) 7 else 1); render() }
        button("Siguiente",true) { agendaDay=agendaDay.plusDays(if(agendaWeek) 7 else 1); render() }
        fun choose(title: String, options: List<Pair<String,String>>, selected: String, changed: (String)->Unit) {
            button("$title: ${options.firstOrNull { it.first==selected }?.second ?: "Todos"}",true) {
                MaterialAlertDialogBuilder(this).setTitle(title).setItems(options.map { it.second }.toTypedArray()) { _,n -> changed(options[n].first); render() }.show()
            }
        }
        if(store.coordinator) choose("Técnico", listOf("" to "Todos")+rows("technicians").map { it.getString("id") to it.getString("name") },agendaTech) { agendaTech=it }
        choose("Cliente", listOf("" to "Todos")+rows("visits").map { it.getString("client_id") to it.getString("client_name") }.distinct(),agendaClient) { agendaClient=it }
        choose("Prioridad",listOf("" to "Todas","high" to "Alta","normal" to "Normal","low" to "Baja"),agendaPriority) { agendaPriority=it }
        choose("Estado",listOf("all" to "Todos","pending" to "Pendientes","in_progress" to "En atención","completed" to "Completadas","not_done" to "No realizadas"),filter) { filter=it }
        val shown=rows("visits").filter {
            val day=LocalDate.parse(it.getString("scheduled_date"))
            !day.isBefore(first) && !day.isAfter(last) && (agendaTech.isEmpty() || it.getString("technician_id")==agendaTech) &&
                (agendaClient.isEmpty() || it.getString("client_id")==agendaClient) && (agendaPriority.isEmpty() || it.optString("priority")==agendaPriority) &&
                (filter=="all" || filter=="today" || it.getString("status")==filter)
        }.sortedWith(compareBy({it.getString("scheduled_date")},{it.optString("scheduled_time")}))
        if(shown.isEmpty()) label("No hay visitas para este periodo y estos filtros.")
        shown.forEach { v -> card {
            label("${v.getString("scheduled_date")} · ${v.optString("scheduled_time").ifBlank { "Sin hora" }}",16,true)
            label(v.getString("client_name"),20,true); label("${v.getString("technician_name")} · ${priorityName(v.optString("priority"))}")
            label(status(v.getString("status")))
            button("Abrir visita") { selectedVisit=v.getString("id"); go("detail") }
        } }
        button("Actualizar agenda") { refresh() }
    }
    private fun indicators() {
        heading("Indicadores")
        label("Calculados con la última copia disponible: ${snapshot.optString("fetched_at")}. Actualiza antes de presentar un reporte.",13)
        val visits=rows("visits")
        val monday=LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        val completed=visits.filter { v -> v.optString("status")=="completed" && runCatching {
            val day=java.time.Instant.parse(v.getString("finished_at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            !day.isBefore(monday) && day.isBefore(monday.plusDays(7))
        }.getOrDefault(false) }
        val minutes=visits.filter { it.optString("status")=="completed" && !it.isNull("arrival_at") && !it.isNull("finished_at") }.mapNotNull {
            runCatching { java.time.Duration.between(java.time.Instant.parse(it.getString("arrival_at")),java.time.Instant.parse(it.getString("finished_at"))).seconds/60.0 }.getOrNull()?.takeIf { it>=0 }
        }
        card {
            label("${visits.count { it.optString("status")=="pending" }} visitas pendientes",22,true)
            label("${completed.size} completadas esta semana",18,true)
            label(if(minutes.isEmpty()) "Sin tiempos de atención disponibles" else "Promedio llegada → recepción del cierre: %.1f min (%d visitas)".format(minutes.average(),minutes.size))
            label("El tiempo incluye esperas y retrasos al sincronizar. No mide exclusivamente trabajo activo.",12)
        }
        val notDone=visits.filter { it.optString("status")=="not_done" }
        heading("No realizadas (${notDone.size})")
        if(notDone.isEmpty()) label("No hay visitas no realizadas.")
        notDone.forEach { card { label(it.getString("client_name"),17,true); label(it.optString("notes")) } }
        heading("Clientes con más visitas")
        visits.groupBy { it.getString("client_id") }.values.sortedByDescending { it.size }.take(10).forEach {
            label("${it.first().getString("client_name")}: ${it.size} servicios")
        }
        button("Guardar reporte CSV") {
            csvReport=makeCsv()
            exportCsv.launch("NexoVisita_${LocalDate.now()}.csv")
        }
        button("Actualizar indicadores") { refresh() }
    }
    private fun makeCsv(): String {
        val visits=rows("visits")
            fun cell(value: String): String {
                val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r')) "'$value" else value
                return "\""+safe.replace("\"","\"\"")+"\""
            }
            return "\uFEFFFecha,Hora,Cliente,Técnico,Prioridad,Estado,Llegada,Cierre,Resultado\r\n"+visits.joinToString("\r\n") { v ->
                listOf("scheduled_date","scheduled_time","client_name","technician_name","priority","status","arrival_at","finished_at","notes")
                    .joinToString(",") { key -> cell(if(v.isNull(key)) "" else v.optString(key)) }
            }
    }
    private fun evidenceForm(id: String) {
        val draft=outbox().read(id)
        heading("Evidencias del servicio")
        label("El borrador y las fotos se guardan cifrados en este teléfono. Adjunta hasta cuatro imágenes del equipo, con autorización del cliente.",13)
        val tasks=draft.optJSONArray("checklist") ?: JSONArray().apply {
            listOf("Diagnóstico","Limpieza","Prueba de funcionamiento","Entrega").forEach { put(JSONObject().put("title",it).put("done",false)) }
        }
        if(!draft.has("checklist")) outbox().edit(id) { it.put("checklist",tasks) }
        for(index in 0 until tasks.length()) {
            val task=tasks.getJSONObject(index)
            val check=CheckBox(this).apply { text=task.getString("title"); isChecked=task.optBoolean("done"); minHeight=dp(48) }
            content.addView(check)
            check.setOnCheckedChangeListener { _,done -> outbox().edit(id) { it.getJSONArray("checklist").getJSONObject(index).put("done",done) } }
        }
        val photos=draft.optJSONArray("photos") ?: JSONArray()
        label("${photos.length()} de 4 fotos guardadas",14,true)
        for(i in 0 until photos.length()) {
            val photo=photos.getJSONObject(i)
            button("Ver / quitar: ${photo.getString("label")}",true) { showPhoto(photo) { outbox().edit(id) { it.getJSONArray("photos").remove(i) }; render() } }
        }
        if(photos.length()<4) {
            button("Adjuntar foto de ANTES",true) { photoKind="before"; pickPhoto.launch("image/*") }
            button("Adjuntar foto de DESPUÉS",true) { photoKind="after"; pickPhoto.launch("image/*") }
        }
        label("Confirmación del cliente",17,true)
        label("Es una constancia registrada por el técnico; no es una firma digital ni una verificación independiente.",12)
        val options=listOf("not_requested" to "No solicitada","accepted" to "Cliente conforme","declined" to "Cliente no conforme","not_present" to "Cliente ausente")
        val group=RadioGroup(this)
        options.forEach { (key,title) ->
            val radio=RadioButton(this).apply { text=title; this.id=View.generateViewId(); minHeight=dp(48); isChecked=draft.optString("confirmation","not_requested")==key }
            group.addView(radio)
            radio.setOnCheckedChangeListener { _,checked -> if(checked) outbox().edit(id) { it.put("confirmation",key) } }
        }
        content.addView(group)
        input("customer_name","Nombre de quien recibe el servicio",draft.optString("customer_name")).addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { outbox().edit(id) { it.put("customer_name",s.toString()) } }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    private fun reportAndHistory(v: JSONObject) {
        val id=v.getString("id")
        if(!store.coordinator) {
            val entry=outbox().read(id)
            if(entry.optString("state")!="draft") card { label(entry.optString("message"),16,true); button("Ver estado de sincronización") { go("sync") } }
        }
        v.optJSONObject("report")?.let { report -> card {
            label("Trabajo documentado",20,true)
            val tasks=report.optJSONArray("checklist") ?: JSONArray()
            for(i in 0 until tasks.length()) tasks.getJSONObject(i).let { label("${if(it.optBoolean("done")) "✓" else "○"} ${it.getString("title")}") }
            label("Confirmación: ${confirmationName(report.optString("confirmation"))}\nRecibe: ${report.optString("customer_name").ifBlank { "No indicado" }}")
            label("Recepción en servidor: ${report.optString("received_at")}",13)
            button("Consultar fotografías") { work {
                val evidence=store.request("GET","/visits/$id/evidence").optJSONArray("photos") ?: JSONArray()
                if(evidence.length()==0) message("No se adjuntaron fotografías.")
                else MaterialAlertDialogBuilder(this@FieldActivity).setTitle("Evidencias")
                    .setItems((0 until evidence.length()).map { evidence.getJSONObject(it).getString("label") }.toTypedArray()) { _,index -> showPhoto(evidence.getJSONObject(index)) }.show()
            } }
        } }
        heading("Historial de atención")
        val history=v.optJSONArray("history") ?: JSONArray()
        if(history.length()==0) label("Actualiza para consultar el historial.")
        for(i in 0 until history.length()) {
            val event=history.getJSONObject(i)
            val action=when(event.optString("action")) { "journey_started" -> "Traslado compartido iniciado"; "journey_stopped" -> "Traslado compartido detenido"; "created" -> "Visita asignada"; "arrive" -> "Llegada registrada"; "finish" -> "Servicio finalizado"; "route" -> "Recorrido adjunto o actualizado"; "rescheduled" -> "Visita reprogramada"; "work_documented" -> "Trabajo documentado"; "service_closed" -> "Servicio finalizado y confirmación registrada"; "sensitive_data_removed" -> "Ubicaciones y fotos retiradas por retención"; else -> event.optString("action") }
            card {
                label(action,17,true); label("${event.optString("at")} · ${event.optString("actor")}",13)
                val details=runCatching { JSONObject(event.optString("details","{}")) }.getOrDefault(JSONObject())
                if(details.has("reason")) {
                    label("Motivo: ${details.getString("reason")}")
                    for(key in listOf("before","after")) details.optJSONObject(key)?.let { label("${if(key=="before") "Antes" else "Después"}: ${it.optString("scheduled_date")} ${it.optString("scheduled_time")} · ${it.optInt("duration_minutes")} min · ${priorityName(it.optString("priority"))}",13) }
                }
            }
        }
    }
    private fun confirmationName(key: String) = when(key) { "accepted" -> "Cliente conforme"; "declined" -> "Cliente no conforme"; "not_present" -> "Cliente ausente"; else -> "No solicitada" }
    private fun syncScreen() {
        heading("Borradores y envíos")
        label("Los envíos pendientes se reintentan cuando Android permite la tarea y hay conexión. Si la sesión venció, inicia sesión con la misma cuenta. Los conflictos requieren revisión.")
        val entries=outbox().entries()
        if(entries.isEmpty()) label("Todavía no tienes borradores ni envíos.")
        entries.forEach { (id,entry) -> card {
            val v=rows("visits").firstOrNull { it.optString("id")==id }
            label(v?.optString("client_name") ?: "Visita $id",18,true)
            label(entry.optString("message","Borrador guardado en el teléfono"))
            entry.optJSONObject("payload")?.let { payload ->
                label("Resultado: ${status(payload.optString("status"))}\n${payload.optString("notes")}")
                label("${payload.optJSONArray("photos")?.length() ?: 0} fotos · ${confirmationName(payload.optString("confirmation"))}",13)
            }
            if(entry.optString("state")=="rejected" && v!=null) button("Corregir borrador",true) {
                outbox().editRejected(id); selectedVisit=id; go("detail")
            }
            if(v!=null) button("Abrir visita",true) { selectedVisit=id; go("detail") }
            if(entry.optString("state")=="conflict") button("Revisar conflicto",true) { work {
                snapshot=store.refresh(); online=true
                val latest=rows("visits").firstOrNull { it.optString("id")==id } ?: error("Esta visita ya no está asignada a tu cuenta.")
                if(latest.optString("status") !in listOf("pending","in_progress")) { message("La visita ya está cerrada. Se conserva tu resultado local para consultarlo; no se sobrescribirá el cierre."); return@work }
                MaterialAlertDialogBuilder(this@FieldActivity).setTitle("Revisar antes de reenviar")
                    .setMessage("Servidor: ${status(latest.getString("status"))}\nProgramación: ${latest.getString("scheduled_date")} ${latest.optString("scheduled_time")}\nTrabajo: ${latest.getString("task")}\n\nTu resultado: ${entry.getJSONObject("payload").optString("notes")}\n\n¿Enviar tu resultado sobre esta versión actual?")
                    .setNegativeButton("Conservar sin enviar",null).setPositiveButton("Enviar resultado") { _,_ -> work { outbox().retryAfterReview(id,latest.getInt("version")); outbox().sync(); render() } }.show()
            } }
        } }
        button("Sincronizar ahora") { work { val retry=outbox().sync(); if(retry) VisitSyncService.schedule(this@FieldActivity); render(); if(retry) message("Los resultados siguen guardados. Se reintentará cuando el servidor esté disponible.") } }
    }
    private fun compressPhoto(uri: android.net.Uri): String {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "No se pudo leer esta imagen." }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1280) sample*=2
        val bitmap=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: error("No se pudo abrir la foto.")
        val orientation=runCatching { contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
        val matrix=Matrix().apply { when(orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f,1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f,1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f,1f) }
        } }
        val image=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        try {
            var quality=85
            var bytes: ByteArray
            do { bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG,quality,it); it.toByteArray() }; quality-=15 } while(bytes.size>400000 && quality>=25)
            require(bytes.size<=400000) { "La imagen sigue siendo muy grande. Selecciona una de menor tamaño." }
            return Base64.encodeToString(bytes,Base64.NO_WRAP)
        } finally { if(image!==bitmap) image.recycle(); bitmap.recycle() }
    }
    private fun showPhoto(photo: JSONObject, remove: (() -> Unit)? = null) {
        val bytes=Base64.decode(photo.getString("data"),Base64.NO_WRAP)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: run { message("No se pudo mostrar la fotografía."); return }
        val image=ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription=photo.getString("label") }
        val dialog=MaterialAlertDialogBuilder(this).setTitle(photo.getString("label")).setView(image).setPositiveButton("Cerrar",null)
        if(remove!=null) dialog.setNegativeButton("Quitar del borrador") { _,_ -> remove() }
        dialog.show()
    }

}

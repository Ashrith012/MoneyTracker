@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package `in`.moneytrack.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import `in`.moneytrack.app.data.*
import `in`.moneytrack.app.sync.GMAIL_SCOPE
import `in`.moneytrack.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity:FragmentActivity() {
 private lateinit var vm:MainViewModel
 private var locked by mutableStateOf(true)
 private val deviceCredential=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
  if(result.resultCode==android.app.Activity.RESULT_OK)locked=false
 }
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
 private val authorizeResult=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result->
  runCatching {Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)}
   .onSuccess {if(it.accessToken!=null)vm.connected() else vm.message.value="Gmail access not granted."}
   .onFailure {vm.message.value="Gmail authorization cancelled or failed."}
 }
 private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri->
  if(uri!=null) lifecycleScope.launch {
   runCatching {withContext(Dispatchers.IO) {
    val array=org.json.JSONArray(vm.rows.value.map {org.json.JSONObject(Codec.encode(it).json)})
    contentResolver.openOutputStream(uri)?.use {it.write(array.toString(2).toByteArray())}?:error("Could not open export")
   }}.onSuccess {vm.message.value="Export saved. Keep the file private."}.onFailure {vm.message.value="Export failed."}
  }
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
  vm=ViewModelProvider(this)[MainViewModel::class.java]
  setContent {
   val dark=isSystemInDarkTheme()
   MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF82D9BC))else lightColorScheme(primary=Color(0xFF146B53),secondary=Color(0xFF52665D),background=Color(0xFFF5F7F2))) {
    val user by vm.user.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
     if(user!=null && user!="demo" && locked && info["appLock"]!="false") {
      Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
       Text("MoneyTrack is locked",style=MaterialTheme.typography.headlineMedium)
       Text("Use your device PIN or biometric to view financial records.")
       val lockMessage by vm.message.collectAsStateWithLifecycle()
       if(lockMessage.isNotBlank())Text(lockMessage)
       Button(onClick={unlock()}) {Text("Unlock")}
       TextButton(onClick={vm.signOut()}) {Text("Sign out")}
      }
     } else key(user) { MoneyTrack(vm,{connectGmail()},{export.launch("moneytrack-records.json")},{if(android.os.Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)},{openGoogleSettings()}) }
    }
   }
  }
 }
 override fun onStop() {super.onStop();locked=true}
 private fun unlock() {
  if(android.os.Build.VERSION.SDK_INT<30) {
   val manager=getSystemService(android.app.KeyguardManager::class.java)
   @Suppress("DEPRECATION") val intent=manager.createConfirmDeviceCredentialIntent("Unlock MoneyTrack","Confirm your device PIN, pattern or password")
   if(intent!=null)deviceCredential.launch(intent) else vm.message.value="Set up a device screen lock first."
   return
  }
  BiometricPrompt(this,mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
   override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){locked=false}
   override fun onAuthenticationError(errorCode:Int,errString:CharSequence){vm.message.value="Device authentication unavailable. Set up a device screen lock."}
  }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock MoneyTrack").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
 }
 private fun connectGmail() {
  if(vm.user.value=="demo"){vm.message.value="Exit demo and sign in to connect Gmail.";return}
  Identity.getAuthorizationClient(this).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_SCOPE))).build())
   .addOnSuccessListener {r->if(r.hasResolution())authorizeResult.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())else vm.connected()}
   .addOnFailureListener {vm.message.value="Could not authorize Gmail. Check Google OAuth configuration."}
 }
 private fun openGoogleSettings() {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://myaccount.google.com/connections")))}
}

@Composable fun MoneyTrack(vm:MainViewModel,connect:()->Unit,export:()->Unit,notifyPermission:()->Unit,revoke:()->Unit) {
 val user by vm.user.collectAsStateWithLifecycle();val rows by vm.rows.collectAsStateWithLifecycle()
 val budgets by vm.budgets.collectAsStateWithLifecycle();val goals by vm.goals.collectAsStateWithLifecycle()
 val info by vm.info.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
 var screen by remember {mutableStateOf("Home")}
 var selected by remember {mutableStateOf<String?>(null)}
 var confirmProfile by remember {mutableStateOf(false)}
 var confirmDelete by remember {mutableStateOf(false)};var confirmExport by remember {mutableStateOf(false)}
 if(user==null) { Onboarding(vm,busy,message);return }
 val totals=Analytics.totals(rows,vm.month)
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
  Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
   Text("MoneyTrack",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
   Text(if(user=="demo") "SYNTHETIC DEMO" else "ON DEVICE",style=MaterialTheme.typography.labelMedium)
  }
  ScrollableTabRow(selectedTabIndex=listOf("Home","Transactions","Accounts","Insights","Plans","Assistant","Settings").indexOf(screen),edgePadding=12.dp) {
   listOf("Home","Transactions","Accounts","Insights","Plans","Assistant","Settings").forEach {label->Tab(selected=screen==label,onClick={screen=label},text={Text(label)})}
  }
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(message.isNotBlank()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) {
   Text(message,Modifier.weight(1f));TextButton(onClick={vm.message.value=""}){Text("Dismiss")}
  }
  LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
   item {Text(screen,style=MaterialTheme.typography.headlineLarge)}
   when(screen) {
    "Home"->{
     item {Panel("Your recorded month · ${vm.month}") {
      Text(money(totals.net),style=MaterialTheme.typography.displaySmall,color=MaterialTheme.colorScheme.primary)
      Text("Net cash flow · not a bank balance")
      Text("When payment time is unknown, grouping uses the email receipt month.",style=MaterialTheme.typography.bodySmall)
      Spacer(Modifier.height(12.dp));Text("Income ${money(totals.income)}    Expenses ${money(totals.spending)}")
      Text("Pending debits ${money(totals.pending)}",style=MaterialTheme.typography.bodySmall)
     }}
     item {Freshness(info,vm::refresh)}
     if(rows.isEmpty())item {Text("No recorded transactions yet. Connect Gmail in Settings. Supported bank formats are not yet configured.")}
     item {Text("Budget pulse",style=MaterialTheme.typography.titleLarge)}
     items(budgets) {b->BudgetCard(b,Analytics.categories(rows,vm.month)[b.category]?:0)}
     item {Text("Recent activity",style=MaterialTheme.typography.titleLarge)}
     items(rows.take(5),key={it.id}) {t->TransactionRow(t){selected=t.id}}
    }
    "Transactions"->{
     item {TransactionList(rows){selected=it.id}}
    }
    "Accounts"->{
     item {Text("Bank linking is coming soon. A verified phone number signs you into MoneyTrack; it does not connect a bank.")}
     items(rows.mapNotNull {it.account}.distinct()) {account->
      AccountCard(account,rows.filter {it.account==account},vm)
     }
     if(rows.none {it.account!=null})item {Text("No accounts detected from supported emails.")}
    }
    "Insights"->{
     item {Panel("Spending by category") {
      val categories=Analytics.categories(rows,vm.month);val max=(categories.values.maxOrNull()?:1L).coerceAtLeast(1L)
      if(categories.isEmpty())Text("Not enough data")
      categories.forEach {(name,value)->Text("$name · ${money(value)}");LinearProgressIndicator(progress={(value.toFloat()/max).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(10.dp))}
     }}
     item {Panel("Recorded monthly trend") {
      (3L downTo 0L).forEach {offset->val month=vm.month.minusMonths(offset);Text("$month    ${money(Analytics.totals(rows,month).spending)}")}
     }}
     item {Panel("Next month outlook") {
      val forecast=Analytics.forecast(rows,vm.month,info["coverageStart"]?.toLongOrNull())
      Text(forecast?.let {money(it)}?:"Not enough data",style=MaterialTheme.typography.headlineMedium)
      Text("Mean spending of the previous 3 complete calendar months. Requires recorded coverage of all 3. Email coverage may be incomplete; this is an estimate.")
      Text("Low-balance estimates appear in Accounts after you supply a dated opening balance and sufficient history.")
     }}
    }
    "Plans"->{
     items(budgets) {b->BudgetCard(b,Analytics.categories(rows,vm.month)[b.category]?:0)}
     item {BudgetEditor(vm)}
     items(goals) {g->Panel(g.name) {Text("${money(g.saved)} / ${money(g.target)}");LinearProgressIndicator(progress={(g.saved.toFloat()/g.target).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("Manually tracked savings; no money moved.",style=MaterialTheme.typography.bodySmall)}}
     item {GoalEditor(vm)}
    }
    "Assistant"->item {AssistantPanel(rows,vm.month)}
    "Settings"->{
     item {Panel("Gmail · read only") {
      Text("Reads likely transaction emails on this device. No send/delete permission. Current release: synthetic fixtures only; real-bank formats await validation.")
      var days by remember {mutableStateOf(info["historyDays"].orEmpty().ifBlank {"90"})}
      var filter by remember {mutableStateOf(info["filter"].orEmpty().ifBlank {"{debited credited transaction payment}"})}
      OutlinedTextField(days,{days=it},label={Text("Initial history days (1–365)")})
      OutlinedTextField(filter,{filter=it},label={Text("Gmail search filter")})
      Button(onClick={val n=days.toIntOrNull();if(n!=null&&n in 1..365){vm.set("historyDays",n.toString());vm.set("filter",filter);connect()}else vm.message.value="Choose 1–365 days."}) {Text("Connect Gmail")}
      TextButton(onClick={vm.disconnect()}) {Text("Disconnect collection")}
      TextButton(onClick=revoke) {Text("Revoke access at Google")}
      Text("Disconnecting stops checks; deleting imported records is a separate action. Android may delay the 15-minute background schedule.",style=MaterialTheme.typography.bodySmall)
     }}
     item {Panel("Privacy & alerts") {
      Toggle("Local notifications",info["notifications"]=="true") {vm.set("notifications",it.toString());if(it)notifyPermission()}
      Toggle("App lock",info["appLock"]!="false") {vm.set("appLock",it.toString())}
      Toggle("Optional Firebase usage analytics",info["analytics"]=="true") {vm.analytics(it)}
      Text("Analytics never includes transaction amounts, merchants, email contents, or account references.",style=MaterialTheme.typography.bodySmall)
      AlertEditor(vm,info)
     }}
     item {Panel("Your data") {
      Text("Financial records stay in an encrypted local database. Phone authentication and consent metadata use the configured server. Exported files are unencrypted.")
      OutlinedButton(onClick={confirmExport=true}){Text("Export recorded transactions")}
      TextButton(onClick={confirmDelete=true}){Text("Delete local financial data")}
      if(user!="demo")TextButton(onClick={confirmProfile=true}){Text("Delete cloud profile and local data")}
      TextButton(onClick={vm.signOut()}){Text(if(user=="demo")"Exit demo" else "Sign out")}
     }}
    }
   }
   item {Text("Read-only tracking · Never enter a UPI PIN or banking password",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  }
 }
 selected?.let {id->rows.find {it.id==id}?.let {t->TransactionDetails(t,vm){selected=null}}}
 if(confirmProfile)AlertDialog(onDismissRequest={confirmProfile=false},title={Text("Delete your MoneyTrack account?")},text={Text("This permanently deletes your cloud profile, sessions, consent records and this app user’s local financial data. It does not delete Gmail messages or previously exported files. Revoke Gmail access separately at Google.")},confirmButton={TextButton(onClick={confirmProfile=false;vm.deleteProfile()}){Text("Delete account")}},dismissButton={TextButton(onClick={confirmProfile=false}){Text("Cancel")}})
 if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete local records?")},text={Text("Transactions, budgets, goals and sync checkpoints will be removed for this app user. This does not delete emails or your cloud profile.")},confirmButton={TextButton(onClick={confirmDelete=false;vm.deleteLocal()}){Text("Delete")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancel")}})
 if(confirmExport)AlertDialog(onDismissRequest={confirmExport=false},title={Text("Export financial information?")},text={Text("The file contains private financial records and is not encrypted. Only save it somewhere you trust.")},confirmButton={TextButton(onClick={confirmExport=false;export()}){Text("Choose file")}},dismissButton={TextButton(onClick={confirmExport=false}){Text("Cancel")}})
}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
 Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {Text(title,style=MaterialTheme.typography.titleMedium);content()}
 }
}
@Composable fun Onboarding(vm:MainViewModel,busy:Boolean,message:String) {
 var phone by remember {mutableStateOf("")};var code by remember {mutableStateOf("")};var accepted by remember {mutableStateOf(false)}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
  Spacer(Modifier.height(28.dp));Text("MONEYTRACK",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
  Text("A clearer view of\nyour money.",style=MaterialTheme.typography.displaySmall)
  Text("Understand the transactions you have recorded. Keep the details on your device.",style=MaterialTheme.typography.titleMedium)
  Panel("Know what you connect") {
   Text("• Gmail access is read-only and optional. Parsing happens on-device.\n• Email alerts can be delayed or missing. Coverage is not universal.\n• Financial records are encrypted locally; phone sign-in and consent metadata use the server.\n• Disconnect Gmail and delete local data separately in Settings.\n• No payments, UPI PINs or bank passwords.")
  }
  val liveSignIn=AppConfig.apiUrl.startsWith("https://")
  if(liveSignIn) {
   Text("Phone verification signs you into this app. It does not discover or connect bank accounts.")
   Toggle("I understand and accept this data use",accepted){accepted=it}
   OutlinedTextField(phone,{phone=it},label={Text("Indian mobile number")},supportingText={Text("Enter 10 digits; +91 is optional.")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
   Button(onClick={
    if(!accepted)vm.message.value="Accept the data-use explanation before requesting an OTP."
    else vm.sendOtp(phone)
   },enabled=!busy) {Text(if(busy)"Please wait…" else "Send OTP")}
   OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("6-digit SMS verification code")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
   Button(onClick={
    if(!accepted)vm.message.value="Accept the data-use explanation before signing in."
    else vm.verify(phone,code)
   },enabled=!busy) {Text("Verify & continue")}
   OutlinedButton(onClick={vm.demo()},enabled=!busy) {Text("Explore synthetic demo")}
  } else {
   Panel("Demo build · SMS sign-in unavailable") {
    Text("This version is not connected to a phone sign-in service, so it cannot send or verify SMS codes. You do not need to enter a phone number to try the app.")
    Text("You can explore the app with synthetic sample transactions. No bank account is connected.")
    Button(onClick={vm.demo()},enabled=!busy) {Text("Explore synthetic demo")}
   }
  }
  Text("Demo uses a separate local database and no OTP. It is not a bank connection.",style=MaterialTheme.typography.bodySmall)
  if(message.isNotBlank())Text(message)
 }
}
@Composable fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,Modifier.weight(1f));Switch(checked=value,onCheckedChange=onChange)}}
fun timeLabel(value:String?)=value?.toLongOrNull()?.let {Instant.ofEpochMilli(it).atZone(Analytics.zone).format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))}?:"Never"
@Composable fun Freshness(info:Map<String,String>,refresh:()->Unit) {Panel("Email coverage") {
 Text("Last successful email check: ${timeLabel(info["lastCheck"])}")
 Text("Last transaction received by app: ${timeLabel(info["lastTransaction"])}")
 if(!info["syncError"].isNullOrBlank())Text(info["syncError"]!!,color=MaterialTheme.colorScheme.error)
 Text("An email check is not a complete bank sync.",style=MaterialTheme.typography.bodySmall)
 TextButton(onClick=refresh){Text("Check emails now")}
}}
@Composable fun TransactionRow(t:Transaction,click:()->Unit) {Card(onClick=click,modifier=Modifier.fillMaxWidth()) {Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
 Column(Modifier.weight(1f)){Text(t.merchant?:"Unknown counterparty",style=MaterialTheme.typography.titleMedium);Text("${t.category} · ${t.status.name.lowercase()}",style=MaterialTheme.typography.bodySmall);if(t.review)Text("Needs review",color=MaterialTheme.colorScheme.error)}
 Text((if(t.direction==Direction.CREDIT)"+" else "−")+money(t.amount),color=if(t.direction==Direction.CREDIT)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
}}}
@Composable fun TransactionList(rows:List<Transaction>,select:(Transaction)->Unit) {
 var search by remember {mutableStateOf("")};var status by remember {mutableStateOf("All")}
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
  OutlinedTextField(search,{search=it},label={Text("Search merchant, account or reference")},modifier=Modifier.fillMaxWidth())
  Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("All","SUCCESSFUL","PENDING","FAILED","REVERSED","Review").forEach {s->FilterChip(selected=status==s,onClick={status=s},label={Text(s.lowercase())})}}
  val filtered=rows.filter {t->listOfNotNull(t.merchant,t.reference,t.account,t.category).any {it.contains(search,true)} && (status=="All" || (status=="Review"&&t.review) || t.status.name==status)}
  if(filtered.isEmpty())Text("No matching transactions.")
  filtered.forEach {t->TransactionRow(t){select(t)}}
 }
}
@Composable fun TransactionDetails(t:Transaction,vm:MainViewModel,close:()->Unit) {
 val allRows by vm.rows.collectAsStateWithLifecycle()
 var category by remember(t.id) {mutableStateOf(t.category)}
 AlertDialog(onDismissRequest=close,title={Text(t.merchant?:"Transaction")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("${money(t.amount)} · ${t.direction} · ${t.status}")
  Text("Account: ${t.account?:"Unknown"}\nReference: ${t.reference?:"Missing"}\nPayment time: ${t.occurredAt?.let {timeLabel(it.toString())}?:"Unknown"}\nEmail received: ${timeLabel(t.receivedAt.toString())}\nImported: ${timeLabel(t.ingestedAt.toString())}")
  Text("Sources: ${t.sources.joinToString()}\nStatus evidence: ${t.history.joinToString()}\nRelated record: ${t.relatedId?:"None confirmed"}")
  OutlinedTextField(category,{category=it},label={Text("Category correction")})
  TextButton(onClick={vm.update(t.copy(category=category.ifBlank {"Other"}));close()}){Text("Save category")}
  TextButton(onClick={vm.update(t.copy(review=false));close()}){Text("Mark reviewed")}
  TextButton(onClick={vm.update(t.copy(excludedDuplicate=!t.excludedDuplicate,review=false));close()}){Text(if(t.excludedDuplicate)"Include in totals" else "Exclude duplicate from totals")}
  Text("Link with another record after checking source evidence:")
  allRows.filter {it.id!=t.id&&it.direction!=t.direction&&it.currency==t.currency&&it.status==Status.SUCCESSFUL}.take(20).forEach {other->
   Text("${other.merchant?:other.account} · ${money(other.amount)} · ${other.reference?:other.id}")
   Row {
    TextButton(onClick={vm.link(t,other,true);close()}){Text("Confirm refund")}
    TextButton(onClick={vm.link(t,other,false);close()}){Text("Own transfer")}
   }
  }
  Text("Excluding a duplicate preserves source evidence. Confirm transfers/refunds only with supporting records.",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={TextButton(onClick=close){Text("Close")}})
}
@Composable fun BudgetCard(b:Budget,spent:Long) {Panel(b.category+" budget") {Text("${money(spent)} / ${money(b.limit)}");LinearProgressIndicator(progress={(spent.toFloat()/b.limit).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("Alert threshold: ${b.threshold}%")}}
fun minor(text:String):Long?=runCatching {BigDecimal(text).movePointRight(2).longValueExact()}.getOrNull()
@Composable fun BudgetEditor(vm:MainViewModel) {
 var category by remember {mutableStateOf("Food")};var amount by remember {mutableStateOf("")};var threshold by remember {mutableStateOf("80")}
 Panel("Add or update monthly budget") {
  OutlinedTextField(category,{category=it},label={Text("Category")});OutlinedTextField(amount,{amount=it},label={Text("Limit in rupees")});OutlinedTextField(threshold,{threshold=it},label={Text("Alert threshold %")})
  Button(onClick={val a=minor(amount);val t=threshold.toIntOrNull();if(a!=null&&a>0&&t!=null&&t in 1..100)vm.budget(category,a,t)else vm.message.value="Enter a positive amount and threshold from 1–100."}){Text("Save budget")}
 }
}
@Composable fun GoalEditor(vm:MainViewModel) {
 var name by remember {mutableStateOf("")};var target by remember {mutableStateOf("")};var saved by remember {mutableStateOf("")}
 Panel("Add or update savings goal") {
  OutlinedTextField(name,{name=it},label={Text("Goal name")});OutlinedTextField(target,{target=it},label={Text("Target in rupees")});OutlinedTextField(saved,{saved=it},label={Text("Saved in rupees")})
  Button(onClick={val t=minor(target);val s=minor(saved);if(name.isNotBlank()&&t!=null&&t>0&&s!=null&&s>=0)vm.goal(name,t,s)else vm.message.value="Enter a name, positive target and non-negative savings."}){Text("Save goal")}
 }
}
@Composable fun AssistantPanel(rows:List<Transaction>,month:YearMonth) {
 var question by remember {mutableStateOf("")};var answer by remember {mutableStateOf("Ask about your recorded data. Answers are calculated locally.")}
 Panel("Your read-only assistant") {
  Text(answer);OutlinedTextField(question,{question=it},label={Text("How much did I spend this month?")},modifier=Modifier.fillMaxWidth())
  Button(onClick={answer=Assistant.answer(question,rows,month)}){Text("Ask")}
  listOf("Show transactions above ₹2,000","Which category had the highest spending?","Show pending transactions","Why is this balance marked estimated?").forEach {q->TextButton(onClick={question=q;answer=Assistant.answer(q,rows,month)}){Text(q)}}
 }
}
@Composable fun AccountCard(account:String,rows:List<Transaction>,vm:MainViewModel) {
 val currentUser by vm.user.collectAsStateWithLifecycle()
 val currentInfo by vm.info.collectAsStateWithLifecycle()
 var label by remember {mutableStateOf(account)};var starting by remember {mutableStateOf("")};var date by remember {mutableStateOf(LocalDate.now(Analytics.zone).toString())}
 var saved by remember {mutableStateOf<Long?>(null)};var since by remember {mutableStateOf<Long?>(null)}
 LaunchedEffect(account) {label=vm.repo.setting("label:$account")?:account;saved=vm.repo.setting("balance:$account")?.toLongOrNull();since=vm.repo.setting("balanceDate:$account")?.toLongOrNull()}
 Panel("$label · $account") {
  Text("Detected from email${if(currentUser=="demo")" · SYNTHETIC" else ""}")
  val moves=Analytics.movement(rows,since?:Long.MAX_VALUE)
  Text(saved?.let {"Estimated balance: ${money(it+moves)}"}?:"Balance unavailable — not reported by a bank")
  val projection=Analytics.forecast(rows,vm.month,currentInfo["coverageStart"]?.toLongOrNull())
  if(saved!=null&&projection!=null&&saved!!+moves<projection)Text("Potential low balance: estimated funds are below the next-month spending average. Assumes spending continues and no new income; not a bank prediction.")
  Text("Includes recorded movements since your dated opening balance; missing emails affect accuracy.",style=MaterialTheme.typography.bodySmall)
  OutlinedTextField(label,{label=it},label={Text("Confirm account label")})
  TextButton(onClick={vm.set("label:$account",label)}){Text("Save label")}
  OutlinedTextField(starting,{starting=it},label={Text("Opening balance in rupees")});OutlinedTextField(date,{date=it},label={Text("As of start of day: YYYY-MM-DD")})
  TextButton(onClick={val value=minor(starting);val stamp=runCatching {LocalDate.parse(date).atStartOfDay(Analytics.zone).toInstant().toEpochMilli()}.getOrNull();if(value!=null&&stamp!=null){saved=value;since=stamp;vm.set("balance:$account",value.toString());vm.set("balanceDate:$account",stamp.toString())}else vm.message.value="Enter a valid amount and date."}){Text("Save opening balance")}
 }
}
@Composable fun AlertEditor(vm:MainViewModel,info:Map<String,String>) {
 var start by remember {mutableStateOf(info["quietStart"].orEmpty().ifBlank {"22"})};var end by remember {mutableStateOf(info["quietEnd"].orEmpty().ifBlank {"7"})};var minimum by remember {mutableStateOf("0")}
 OutlinedTextField(start,{start=it},label={Text("Quiet hours start (0–23, India time)")});OutlinedTextField(end,{end=it},label={Text("Quiet hours end (0–23)")});OutlinedTextField(minimum,{minimum=it},label={Text("Minimum transaction alert in rupees")})
 TextButton(onClick={val s=start.toIntOrNull();val e=end.toIntOrNull();val m=minor(minimum);if(s!=null&&e!=null&&s in 0..23&&e in 0..23&&m!=null&&m>=0){vm.set("quietStart",s.toString());vm.set("quietEnd",e.toString());vm.set("alertMinimum",m.toString())}else vm.message.value="Check the quiet hours and minimum amount."}){Text("Save alert preferences")}
}

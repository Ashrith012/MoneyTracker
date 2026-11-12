package `in`.moneytrack.app
import android.app.Application
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.moneytrack.app.auth.AuthClient
import `in`.moneytrack.app.data.*
import `in`.moneytrack.app.sync.*
import `in`.moneytrack.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.YearMonth

class MainViewModel(application:Application):AndroidViewModel(application) {
 val app=application as MoneyApp
 val user=MutableStateFlow(app.vault.read("user"))
 val rows=MutableStateFlow<List<Transaction>>(emptyList())
 val budgets=MutableStateFlow<List<Budget>>(emptyList())
 val goals=MutableStateFlow<List<Goal>>(emptyList())
 val message=MutableStateFlow("")
 val busy=MutableStateFlow(false)
 val info=MutableStateFlow<Map<String,String>>(emptyMap())
 private val collectors=mutableListOf<Job>()
 val month get()=YearMonth.now(Analytics.zone)
 val repo get()=app.repository(user.value?:error("Sign in first"))
 init { user.value?.let { observe() } }
 private val actions=kotlinx.coroutines.sync.Mutex()
 fun action(block:suspend ()->Unit) {
  val requestedUser=user.value
  viewModelScope.launch {actions.withLockCompat {
   if(requestedUser!=user.value)return@withLockCompat
   busy.value=true
   try {block()}catch(e:CancellationException){throw e}catch(e:Exception){message.value=e.message?:"Action could not be completed"}finally{busy.value=false}
  }}
 }
 private fun resetView() {
  collectors.forEach {it.cancel()};collectors.clear()
  if(com.google.firebase.FirebaseApp.getApps(app).isNotEmpty())com.google.firebase.analytics.FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(false)
  rows.value=emptyList();budgets.value=emptyList();goals.value=emptyList();info.value=emptyMap()
 }
 fun link(a:Transaction,b:Transaction,refund:Boolean)=action {
  repo.mutex.withLockCompat {
   repo.db.withTransaction {
    if(refund) {
     val debit=if(a.direction==Direction.DEBIT)a else b
     val credit=if(a.direction==Direction.CREDIT)a else b
     val refunded=repo.rowsNow().filter {it.relatedId==debit.id&&it.id!=credit.id&&it.direction==Direction.CREDIT}.sumOf {it.amount}
     repo.dao.save(listOf(Codec.encode(Reconcile.refund(debit,credit,refunded))))
    } else {
     val pair=Reconcile.transfer(a,b);repo.dao.save(listOf(Codec.encode(pair.first),Codec.encode(pair.second)))
    }
   }
  }
 }
 private fun observe() {
  collectors.forEach {it.cancel()};collectors.clear()
  val r=repo
  collectors+=viewModelScope.launch {r.rows.collect {rows.value=it.sortedByDescending { t->t.occurredAt?:t.receivedAt }}}
  collectors+=viewModelScope.launch {r.dao.budgets().collect {budgets.value=it}}
  collectors+=viewModelScope.launch {r.dao.goals().collect {goals.value=it}}
  collectors+=viewModelScope.launch {while(isActive){reloadInfo();delay(3000)}}
 }
 suspend fun reloadInfo() {
  info.value=listOf("lastCheck","lastTransaction","syncError","gmailEnabled","coverageStart","notifications","quietStart","quietEnd","alertMinimum","analytics","historyDays","filter","appLock").associateWith {repo.setting(it).orEmpty()}
 }
 fun demo()=action { app.sessionMutex.withLockCompat {resetView();app.stopCollection();app.vault.write("user","demo");user.value="demo";repo.demo();observe()} }
 fun sendOtp(phone:String)=action {AuthClient().send(phone);message.value="OTP requested. Check your SMS inbox."}
 fun verify(phone:String,code:String)=action {
  val response=AuthClient().verify(phone,code)
  val id=response.getJSONObject("user").getString("id");val token=response.getString("token")
  AuthClient().consent(token,"privacy",true)
  app.sessionMutex.withLockCompat {
   resetView();app.stopCollection();app.vault.write("token",token);app.vault.write("user",id);user.value=id;observe()
  }
 }
 fun signOut()=action {
  app.sessionMutex.withLockCompat {
   app.stopCollection()
   resetView()
   app.vault.read("token")?.let { runCatching {AuthClient().call("/api/auth/sign-out",org.json.JSONObject(),it)} }
   collectors.forEach {it.cancel()};collectors.clear();user.value?.let {app.close(it)}
   app.vault.remove("token");app.vault.remove("user");user.value=null;rows.value=emptyList();budgets.value=emptyList();goals.value=emptyList();info.value=emptyMap()
  }
 }
 fun set(key:String,value:String)=action {repo.set(key,value);reloadInfo()}
 fun connected()=action {
  if(user.value=="demo") {message.value="Gmail is unavailable in synthetic demo mode.";return@action}
  val token=app.vault.read("token")?:error("Sign in again before connecting Gmail.")
  AuthClient().consent(token,"gmail",true)
  repo.set("gmailEnabled","true");SyncWorker.schedule(app,user.value!!);SyncWorker.refresh(app,user.value!!);reloadInfo()
 }
 fun disconnect()=action {
  app.stopCollection()
  app.sessionMutex.withLockCompat {repo.set("gmailEnabled","false");reloadInfo()}
  app.vault.read("token")?.let {AuthClient().consent(it,"gmail",false)}
  message.value="Collection stopped; imported records remain. Use Google's access settings to revoke the grant."
 }
 fun refresh() {if(user.value=="demo")message.value="Demo is local synthetic data; no email check performed." else if(info.value["gmailEnabled"]=="true")SyncWorker.refresh(app,user.value!!) else message.value="Connect Gmail in Settings first."}
 fun update(t:Transaction)=action {repo.update(t)}
 fun budget(category:String,amount:Long,threshold:Int)=action {require(category.isNotBlank()&&amount>0&&threshold in 1..100);repo.dao.budget(Budget(category,amount,threshold))}
 fun goal(name:String,target:Long,saved:Long)=action {require(name.isNotBlank()&&target>0&&saved>=0);repo.dao.goal(Goal(name,target,saved))}
 fun deleteLocal()=action {
  app.stopCollection()
  app.sessionMutex.withLockCompat {
   val id=user.value?:return@withLockCompat
   collectors.forEach {it.cancel()};collectors.clear()
   val hash=userHash(id);app.close(id)
   check(app.deleteDatabase("ledger-$hash.db"));app.vault.remove("db.$hash")
   rows.value=emptyList();budgets.value=emptyList();goals.value=emptyList();info.value=emptyMap();observe()
   message.value="Local financial records deleted. Gmail collection stopped."
  }
 }
 fun deleteProfile()=action {
  val token=app.vault.read("token")?:error("Cloud profile is unavailable in demo mode.")
  AuthClient().call("/api/profile",org.json.JSONObject(),token,"DELETE")
  app.stopCollection()
  app.sessionMutex.withLockCompat {
   val id=user.value?:return@withLockCompat
   resetView();val hash=userHash(id);app.close(id);app.deleteDatabase("ledger-$hash.db")
   app.vault.remove("db.$hash");app.vault.remove("token");app.vault.remove("user");user.value=null
   message.value="Cloud profile and this user's local financial data deleted. Revoke Gmail access in Google connections."
  }
 }
 fun analytics(enabled:Boolean)=action {
  val firebase=com.google.firebase.FirebaseApp.getApps(app).firstOrNull()
  if(firebase==null&&enabled){message.value="Firebase is not configured; analytics remain off.";return@action}
  if(enabled)app.vault.read("token")?.let {AuthClient().consent(it,"analytics",true)}
  if(firebase!=null)com.google.firebase.analytics.FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(enabled)
  repo.set("analytics",enabled.toString())
  if(!enabled)app.vault.read("token")?.let {AuthClient().consent(it,"analytics",false)}
  reloadInfo()
 }
}

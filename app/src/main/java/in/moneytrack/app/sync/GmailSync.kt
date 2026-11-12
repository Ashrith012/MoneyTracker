package `in`.moneytrack.app.sync

import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.api.Scope
import `in`.moneytrack.app.MoneyApp
import `in`.moneytrack.app.data.*
import `in`.moneytrack.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val GMAIL_SCOPE="https://www.googleapis.com/auth/gmail.readonly"
class GmailFailure(val code:Int):IOException()
interface GmailGateway {
 suspend fun get(path:String,params:Map<String,String> = emptyMap()):JSONObject
 suspend fun email(id:String):Email
}
class GmailApi(private val token:String):GmailGateway {
 private val client=OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()
 override suspend fun get(path:String,params:Map<String,String>):JSONObject=withContext(Dispatchers.IO) {
  val url=HttpUrl.Builder().scheme("https").host("gmail.googleapis.com").addPathSegments("gmail/v1/users/me/$path").apply {
   params.forEach { (k,v)->addQueryParameter(k,v) }
  }.build()
  client.newCall(Request.Builder().url(url).header("Authorization","Bearer $token").build()).execute().use {
   if(!it.isSuccessful) throw GmailFailure(it.code)
   JSONObject(it.body!!.string())
  }
 }
 override suspend fun email(id:String):Email {
  val j=get("messages/$id",mapOf("format" to "full"))
  fun text(part:JSONObject):String {
   if(part.optString("mimeType")=="text/plain") {
    val encoded=part.optJSONObject("body")?.optString("data").orEmpty()
    return String(android.util.Base64.decode(encoded,android.util.Base64.URL_SAFE))
   }
   val children=part.optJSONArray("parts")?:return ""
   return (0 until children.length()).joinToString("\n") { text(children.getJSONObject(it)) }
  }
  return Email(id,j.getString("internalDate").toLong(),text(j.getJSONObject("payload")))
 }
}
/** Bodies remain in memory only. Parser list is deliberately empty for unvalidated live formats. */
class GmailSync(private val repo:Repository,private val api:GmailGateway,private val parser:EmailParser,
 private val notify:suspend (Transaction)->Unit) {
 suspend fun run() {
  val profile=api.get("profile")
  val mailbox=profile.getString("emailAddress")
  val bound=repo.setting("mailbox")
  check(bound==null || bound==mailbox) { "Different Gmail account. Delete imported data before changing mailbox." }
  repo.set("mailbox",mailbox)
  var history=repo.setting("history")
  if(history==null) {
   val anchor=repo.setting("initialAnchor")?:profile.getString("historyId").also { repo.set("initialAnchor",it) }
   val days=repo.setting("historyDays")?.toIntOrNull()?.coerceIn(1,365)?:90
   val query=repo.setting("initialQuery")?:"${repo.setting("filter")?:"{debited credited transaction payment}"} after:${(System.currentTimeMillis()/1000)-days*86400L}".also { repo.set("initialQuery",it) }
   var page=repo.setting("initialPage").orEmpty()
   do {
    val result=try { api.get("messages",buildMap { put("q",query);put("maxResults","100");if(page.isNotBlank()) put("pageToken",page) }) }
    catch(e:GmailFailure) {
     if(e.code==400 && page.isNotBlank())repo.set("initialPage","")
     throw e
    }
    val messages=result.optJSONArray("messages")
    if(messages!=null) for(i in 0 until messages.length()) ingest(messages.getJSONObject(i).getString("id"),false)
    page=result.optString("nextPageToken")
    // Commit page only after every message has been processed. Replays are idempotent.
    repo.set("initialPage",page)
   } while(page.isNotBlank())
   repo.set("history",anchor); history=anchor
   repo.set("coverageStart",(System.currentTimeMillis()-days*86400000L).toString())
  }
  var page=""
  var newest=history!!
  do {
   val result=try { api.get("history",buildMap {put("startHistoryId",history);put("historyTypes","messageAdded");put("maxResults","100");if(page.isNotBlank())put("pageToken",page)}) }
   catch(e:GmailFailure) {
    if(e.code==404) {
     // Expired history: restart a bounded full scan; no old-message notification flood.
     repo.db.openHelper.writableDatabase.execSQL("DELETE FROM Setting WHERE name IN ('history','initialAnchor','initialQuery','initialPage')")
    }
    throw e
   }
   val entries=result.optJSONArray("history")
   if(entries!=null) for(i in 0 until entries.length()) {
    val added=entries.getJSONObject(i).optJSONArray("messagesAdded")?:continue
    for(k in 0 until added.length()) ingest(added.getJSONObject(k).getJSONObject("message").getString("id"),true)
   }
   newest=result.optString("historyId",newest)
   page=result.optString("nextPageToken")
  } while(page.isNotBlank())
  repo.set("history",newest)
  repo.set("lastCheck",System.currentTimeMillis().toString());repo.set("syncError","")
 }
 private suspend fun ingest(id:String,alerts:Boolean) {
  val email=try { api.email(id) } catch(e:GmailFailure) { if(e.code==404)return else throw e }
  val changes=repo.ingest(email,parser)
  if(alerts) changes.forEach { notify(it) }
 }
}
class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  val app=applicationContext as MoneyApp
  return app.sessionMutex.withLockCompat {
   val user=inputData.getString("user")?:return@withLockCompat Result.failure()
   if(app.vault.read("user")!=user || user=="demo")return@withLockCompat Result.success()
   val repo=app.repository(user)
   if(repo.setting("gmailEnabled")!="true")return@withLockCompat Result.success()
   var usedToken:String?=null
   try {
    val auth=Identity.getAuthorizationClient(applicationContext).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_SCOPE))).build()).await()
    if(auth.hasResolution() || auth.accessToken==null) {
     repo.set("syncError","Gmail needs authorization. Reconnect in Settings.");return@withLockCompat Result.failure()
    }
    usedToken=auth.accessToken!!
    GmailSync(repo,GmailApi(usedToken),EmailParserRegistry.live) { app.alerts.transaction(repo,it) }.run()
    app.alerts.budgets(repo)
    app.alerts.forecasts(repo)
    Result.success()
   } catch(e:kotlinx.coroutines.CancellationException) { throw e }
   catch(e:Exception) {
    if(e is GmailFailure && e.code==401 && usedToken!=null) {
     try {Identity.getAuthorizationClient(applicationContext).clearToken(ClearTokenRequest.builder().setToken(usedToken).build()).await()}
     catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
     catch(ignored:Exception){ /* The user can still revoke access through Google settings. */ }
    }
    repo.set("syncError",if(e is GmailFailure && e.code in listOf(401,403)) "Gmail access expired, revoked, or restricted. Reconnect in Settings." else "Email check interrupted. It will retry; existing records are safe.")
    if(e is GmailFailure && e.code in listOf(401,403)) Result.failure() else Result.retry()
   }
  }
 }
 companion object {
  fun schedule(context:Context,user:String) {
   val request=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setInputData(workDataOf("user" to user))
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
   WorkManager.getInstance(context).enqueueUniquePeriodicWork("gmail",ExistingPeriodicWorkPolicy.UPDATE,request)
  }
  fun refresh(context:Context,user:String) {
   WorkManager.getInstance(context).enqueueUniqueWork("gmail-manual",ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("user" to user)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
  }
 }
}
object EmailParserRegistry {
 // Add a verified parser here only after redacted fixtures and false-positive tests exist.
 val live=object:EmailParser { override fun parse(email:Email):Transaction?=null }
}
suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(block:suspend ()->T):T { lock();try{return block()}finally{unlock()} }

package `in`.moneytrack.app.notifications
import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import `in`.moneytrack.app.data.*
import `in`.moneytrack.core.*
import java.time.*
class Alerts(private val context:Context) {
 private val manager=context.getSystemService(NotificationManager::class.java)
 init { manager.createNotificationChannel(NotificationChannel("updates","Recorded activity",NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE }) }
 private suspend fun post(repo:Repository,key:String,title:String) {
  if(repo.setting("notifications")!="true")return
  if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
  val hour=LocalTime.now(Analytics.zone).hour
  val start=repo.setting("quietStart")?.toIntOrNull()?:22;val end=repo.setting("quietEnd")?.toIntOrNull()?:7
  if(if(start>end) hour>=start||hour<end else hour>=start&&hour<end)return
  if(repo.dao.notice(Notice(key,System.currentTimeMillis()))==-1L)return
  manager.notify(key.hashCode(),NotificationCompat.Builder(context,"updates").setSmallIcon(android.R.drawable.ic_dialog_info)
   .setContentTitle("MoneyTrack").setContentText(title).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
   .setPublicVersion(NotificationCompat.Builder(context,"updates").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("MoneyTrack update").build())
   .setAutoCancel(true).build())
 }
 suspend fun transaction(repo:Repository,t:Transaction) {
  val minimum=repo.setting("alertMinimum")?.toLongOrNull()?:0
  if(t.amount>=minimum)post(repo,"${t.id}:${t.status}","Recorded activity changed. Open the app to review.")
 }
 suspend fun forecasts(repo:Repository) {
  val rows=repo.rowsNow();val month=YearMonth.now(Analytics.zone)
  rows.mapNotNull {it.account}.distinct().forEach {account->
   val starting=repo.setting("balance:$account")?.toLongOrNull()?:return@forEach
   val date=repo.setting("balanceDate:$account")?.toLongOrNull()?:return@forEach
   val accountRows=rows.filter {it.account==account}
   val estimate=Analytics.forecast(accountRows,month,repo.setting("coverageStart")?.toLongOrNull())?:return@forEach
   val movement=Analytics.movement(accountRows,date)
   if(starting+movement<estimate)post(repo,"forecast:$month:$account","A local balance estimate may be below projected spending. Review the assumptions in Accounts.")
  }
 }
 suspend fun budgets(repo:Repository) {
  val month=YearMonth.now(Analytics.zone);val spending=Analytics.categories(repo.rowsNow(),month)
  repo.dao.budgetList().forEach { b->if((spending[b.category]?:0)>=b.limit*b.threshold/100)post(repo,"budget:$month:${b.category}:${b.threshold}","A recorded spending budget reached its threshold.") }
 }
}

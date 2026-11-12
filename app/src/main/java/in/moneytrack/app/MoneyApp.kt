package `in`.moneytrack.app
import android.app.Application
import androidx.work.WorkManager
import `in`.moneytrack.app.data.*
import `in`.moneytrack.app.notifications.Alerts
import kotlinx.coroutines.sync.Mutex
object AppConfig {
 var apiUrl:String=""
  internal set
}
class MoneyApp:Application() {
 override fun onCreate() {super.onCreate();AppConfig.apiUrl=getString(R.string.api_url)}
 val vault by lazy { Vault(this) }
 val alerts by lazy { Alerts(this) }
 val sessionMutex=Mutex()
 private val repositories=mutableMapOf<String,Repository>()
 @Synchronized fun repository(user:String)=repositories.getOrPut(user) { Repository(this,user) }
 @Synchronized fun close(user:String) { repositories.remove(user)?.db?.close() }
 fun stopCollection() { WorkManager.getInstance(this).cancelUniqueWork("gmail");WorkManager.getInstance(this).cancelUniqueWork("gmail-manual") }
}

package `in`.moneytrack.app.auth
import `in`.moneytrack.app.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
class AuthClient {
 private val http=OkHttpClient()
 suspend fun call(path:String,body:JSONObject,token:String?=null,method:String="POST"):JSONObject=withContext(Dispatchers.IO) {
  require(AppConfig.apiUrl.startsWith("https://")) { "Live OTP backend is not configured. Use the separate demo mode." }
  val request=Request.Builder().url(AppConfig.apiUrl.trimEnd('/')+path)
   .method(method,body.toString().toRequestBody("application/json".toMediaType()))
   .apply { if(token!=null)header("Authorization","Bearer $token") }.build()
  http.newCall(request).execute().use { response->
   if(!response.isSuccessful)error(if(response.code==429) "Too many attempts. Wait before trying again." else "Authentication request failed. Check the code and connection.")
   val text=response.body?.string().orEmpty()
   val json=if(text.isBlank())JSONObject()else JSONObject(text)
   response.header("set-auth-token")?.let { json.put("token",it) }
   json
  }
 }
 suspend fun send(phone:String) {require(Regex("\\+91[6-9][0-9]{9}").matches(phone));call("/api/auth/phone-number/send-otp",JSONObject().put("phoneNumber",phone))}
 suspend fun verify(phone:String,code:String)=call("/api/auth/phone-number/verify",JSONObject().put("phoneNumber",phone).put("code",code))
 suspend fun consent(token:String,kind:String,granted:Boolean)=call("/api/consents",JSONObject().put("kind",kind).put("granted",granted).put("version","1"),token)
}

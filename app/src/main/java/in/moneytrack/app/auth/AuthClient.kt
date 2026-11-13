package `in`.moneytrack.app.auth
import `in`.moneytrack.app.AppConfig
import `in`.moneytrack.core.PhoneInput
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
 suspend fun send(phone:String) {
  val normalized=PhoneInput.normalize(phone)
  call("/api/auth/phone-number/send-otp",JSONObject().put("phoneNumber",normalized))
 }
 suspend fun verify(phone:String,code:String):JSONObject {
  val normalized=PhoneInput.normalize(phone)
  require(Regex("[0-9]{6}").matches(code)) { "Enter the 6-digit verification code from your SMS." }
  return call("/api/auth/phone-number/verify",JSONObject().put("phoneNumber",normalized).put("code",code))
 }
 suspend fun consent(token:String,kind:String,granted:Boolean)=call("/api/consents",JSONObject().put("kind",kind).put("granted",granted).put("version","1"),token)
}

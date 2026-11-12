package `in`.moneytrack.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import `in`.moneytrack.core.*
import `in`.moneytrack.core.Transaction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(private val context:Context) {
 private val prefs=context.getSharedPreferences("vault",Context.MODE_PRIVATE)
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  return (store.getKey("moneytrack.wrap",null) as? SecretKey)?:KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("moneytrack.wrap",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 @Synchronized fun write(name:String,value:String) {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
  val bytes=cipher.iv+cipher.doFinal(value.toByteArray())
  check(prefs.edit().putString(name,Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
 }
 @Synchronized fun read(name:String):String? {
  val raw=prefs.getString(name,null)?:return null
  val bytes=Base64.decode(raw,Base64.NO_WRAP)
  return Cipher.getInstance("AES/GCM/NoPadding").run {
   init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
   String(doFinal(bytes.copyOfRange(12,bytes.size)))
  }
 }
 fun remove(name:String) { prefs.edit().remove(name).commit() }
 fun databaseKey(user:String):ByteArray {
  val name="db.$user"
  val saved=read(name)
  if(saved!=null) return Base64.decode(saved,Base64.NO_WRAP)
  val bytes=ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
  write(name,Base64.encodeToString(bytes,Base64.NO_WRAP)); return bytes
 }
}
fun userHash(user:String)=MessageDigest.getInstance("SHA-256").digest(user.toByteArray()).joinToString("") { "%02x".format(it) }
@Entity data class Record(@PrimaryKey val id:String,val json:String)
@Entity data class Setting(@PrimaryKey val name:String,val value:String)
@Entity data class Budget(@PrimaryKey val category:String,val limit:Long,val threshold:Int=80)
@Entity data class Goal(@PrimaryKey val name:String,val target:Long,val saved:Long)
@Entity data class Notice(@PrimaryKey val key:String,val createdAt:Long)
@Dao interface LedgerDao {
 @Query("SELECT * FROM Record") fun observe():kotlinx.coroutines.flow.Flow<List<Record>>
 @Query("SELECT * FROM Record") suspend fun records():List<Record>
 @Upsert suspend fun save(rows:List<Record>)
 @Query("SELECT * FROM Setting WHERE name=:key") suspend fun setting(key:String):Setting?
 @Upsert suspend fun setting(value:Setting)
 @Query("SELECT * FROM Budget") fun budgets():kotlinx.coroutines.flow.Flow<List<Budget>>
 @Query("SELECT * FROM Budget") suspend fun budgetList():List<Budget>
 @Upsert suspend fun budget(value:Budget)
 @Query("SELECT * FROM Goal") fun goals():kotlinx.coroutines.flow.Flow<List<Goal>>
 @Upsert suspend fun goal(value:Goal)
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun notice(value:Notice):Long
}
@Database(entities=[Record::class,Setting::class,Budget::class,Goal::class,Notice::class],version=1,exportSchema=false)
abstract class Ledger:RoomDatabase() { abstract fun dao():LedgerDao }
object Codec {
 fun encode(t:Transaction):Record {
  val j=JSONObject().put("id",t.id).put("reference",t.reference).put("amount",t.amount).put("currency",t.currency)
   .put("direction",t.direction.name).put("occurredAt",t.occurredAt).put("receivedAt",t.receivedAt).put("ingestedAt",t.ingestedAt)
   .put("account",t.account).put("merchant",t.merchant).put("category",t.category).put("status",t.status.name)
   .put("sources",JSONArray(t.sources.toList())).put("review",t.review).put("relatedId",t.relatedId)
   .put("internalTransfer",t.internalTransfer).put("history",JSONArray(t.history)).put("excludedDuplicate",t.excludedDuplicate)
  return Record(t.id,j.toString())
 }
 fun decode(row:Record):Transaction {
  val j=JSONObject(row.json)
  fun optional(k:String)=if(j.has(k)&&!j.isNull(k)) j.getString(k) else null
  fun strings(k:String)=j.getJSONArray(k).let { a->(0 until a.length()).map { a.getString(it) } }
  return Transaction(id=row.id,reference=optional("reference"),amount=j.getLong("amount"),currency=j.getString("currency"),
   direction=Direction.valueOf(j.getString("direction")),occurredAt=optional("occurredAt")?.toLong(),
   receivedAt=j.getLong("receivedAt"),ingestedAt=j.getLong("ingestedAt"),account=optional("account"),merchant=optional("merchant"),
   category=j.getString("category"),status=Status.valueOf(j.getString("status")),sources=strings("sources").toSet(),
   review=j.getBoolean("review"),relatedId=optional("relatedId"),internalTransfer=j.getBoolean("internalTransfer"),
   history=strings("history"),excludedDuplicate=j.optBoolean("excludedDuplicate"))
 }
}
class Repository(context:Context,val user:String) {
 val hash=userHash(user)
 val db:Ledger
 val mutex=Mutex()
 init {
  System.loadLibrary("sqlcipher")
  db=Room.databaseBuilder(context.applicationContext,Ledger::class.java,"ledger-$hash.db")
   .openHelperFactory(SupportOpenHelperFactory(Vault(context).databaseKey(hash))).build()
 }
 val dao get()=db.dao()
 val rows=dao.observe().map { it.map(Codec::decode) }
 suspend fun rowsNow()=dao.records().map(Codec::decode)
 suspend fun setting(key:String)=dao.setting(key)?.value
 suspend fun set(key:String,value:String)=dao.setting(Setting(key,value))
 suspend fun ingest(email:Email,parser:EmailParser):List<Transaction> = mutex.withLock {
  val parsed=parser.parse(email)?:return@withLock emptyList()
  db.withTransaction {
   val before=rowsNow(); val after=Reconcile.ingest(before,parsed)
   val changed=after.filter { a->before.none { it==a } }
   dao.save(changed.map(Codec::encode))
   if(changed.isNotEmpty()) set("lastTransaction",System.currentTimeMillis().toString())
   changed
  }
 }
 suspend fun update(t:Transaction)=mutex.withLock { dao.save(listOf(Codec.encode(t))) }
 suspend fun demo()=mutex.withLock {
  if(rowsNow().isNotEmpty()) return@withLock
  val now=java.time.ZonedDateTime.now(Analytics.zone)
  val rows=(0..3).flatMap { m->
   val date=now.minusMonths(m.toLong()).withDayOfMonth(1)
   listOf(Triple("Salary",7200000L,"Income"),Triple("Rent",1800000L,"Home"),Triple("Groceries",425000L,"Food"),Triple("Metro",120000L,"Travel"),Triple("Coffee",18000L,"Food"),Triple("Bookshop",240000L,"Shopping")).mapIndexed { i,v->
    val time=date.plusDays(i.toLong()).toInstant().toEpochMilli().coerceAtMost(System.currentTimeMillis())
    Transaction(id="demo-$m-$i",reference="DEMO$m$i",amount=v.second,direction=if(i==0) Direction.CREDIT else Direction.DEBIT,
     occurredAt=time,receivedAt=time,ingestedAt=time,account="****4821",merchant=v.first,category=v.third,
     status=Status.SUCCESSFUL,sources=setOf("synthetic-$m-$i"),history=listOf("Synthetic fixture:SUCCESSFUL"))
   }
  }
  dao.save(rows.map(Codec::encode))
  dao.budget(Budget("Food",800000)); dao.budget(Budget("Shopping",500000))
  dao.goal(Goal("Emergency fund",30000000,8500000))
  set("coverageStart",now.minusMonths(3).withDayOfMonth(1).toLocalDate().atStartOfDay(Analytics.zone).toInstant().toEpochMilli().toString())
 }
}

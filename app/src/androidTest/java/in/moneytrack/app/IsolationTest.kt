package `in`.moneytrack.app
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.moneytrack.app.data.*
import `in`.moneytrack.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class IsolationTest {
 @Test fun encryptedUserStoresAndNotificationDedup()=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val one=Repository(ctx,"test-one");val two=Repository(ctx,"test-two")
  try {
   one.db.clearAllTables();two.db.clearAllTables()
   one.demo()
   Assert.assertTrue(one.rowsNow().isNotEmpty());Assert.assertTrue(two.rowsNow().isEmpty())
   Assert.assertNotEquals(-1L,one.dao.notice(Notice("same-event",1)))
   Assert.assertEquals(-1L,one.dao.notice(Notice("same-event",2)))
   val file=ctx.getDatabasePath("ledger-${one.hash}.db")
   val prefix=file.inputStream().use {stream->ByteArray(16).also {stream.read(it)}}
   Assert.assertFalse(String(prefix).startsWith("SQLite format 3"))
  } finally {
   one.db.close();two.db.close()
   listOf("test-one","test-two").forEach {val hash=userHash(it);ctx.deleteDatabase("ledger-$hash.db");Vault(ctx).remove("db.$hash")}
  }
 }
}

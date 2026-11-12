package `in`.moneytrack.app
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.moneytrack.app.data.*
import `in`.moneytrack.app.sync.*
import `in`.moneytrack.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class SyncTest {
 @Test fun interruptedInitialImportReplaysWithoutDuplicateOrNotification()=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val repo=Repository(ctx,"sync-test")
  try {
   repo.db.clearAllTables();var interrupted=true;var notices=0
   val fake=object:GmailGateway {
    override suspend fun get(path:String,params:Map<String,String>):JSONObject=when(path) {
     "profile"->JSONObject("""{"emailAddress":"test@example.invalid","historyId":"10"}""")
     "messages"->JSONObject("""{"messages":[{"id":"a"},{"id":"b"}]}""")
     "history"->JSONObject("""{"historyId":"11"}""")
     else->error("Unexpected call")
    }
    override suspend fun email(id:String):Email {
     if(id=="b"&&interrupted)throw java.io.IOException("Simulated network interruption")
     return Email(id,1,"MONEYTRACK-SYNTHETIC-V1\namount=10.00\ndirection=DEBIT\nstatus=SUCCESSFUL\naccount=****1234\nreference=$id")
    }
   }
   val sync=GmailSync(repo,fake,SyntheticParser()){notices++}
   Assert.assertTrue(runCatching {sync.run()}.isFailure)
   Assert.assertNull(repo.setting("history"));Assert.assertNull(repo.setting("lastCheck"))
   Assert.assertEquals(1,repo.rowsNow().size)
   interrupted=false;sync.run()
   Assert.assertEquals(2,repo.rowsNow().size);Assert.assertEquals(0,notices)
   Assert.assertEquals("11",repo.setting("history"));Assert.assertNotNull(repo.setting("lastCheck"))
  } finally {repo.db.close();ctx.deleteDatabase("ledger-${repo.hash}.db");Vault(ctx).remove("db.${repo.hash}")}
 }
 @Test fun revokedAccessDoesNotAdvanceCheckpoint()=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val repo=Repository(ctx,"revoked-test")
  try {
   repo.db.clearAllTables();repo.set("history","10")
   val api=object:GmailGateway {
    override suspend fun get(path:String,params:Map<String,String>):JSONObject=throw GmailFailure(401)
    override suspend fun email(id:String):Email=error("No body may be fetched")
   }
   Assert.assertTrue(runCatching {GmailSync(repo,api,SyntheticParser()){}.run()}.isFailure)
   Assert.assertEquals("10",repo.setting("history"));Assert.assertNull(repo.setting("lastCheck"))
  } finally {repo.db.close();ctx.deleteDatabase("ledger-${repo.hash}.db");Vault(ctx).remove("db.${repo.hash}")}
 }
}

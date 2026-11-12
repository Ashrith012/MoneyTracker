package `in`.moneytrack.core
import java.math.BigDecimal
import java.time.*
import java.util.UUID

enum class Direction { DEBIT, CREDIT }
enum class Status { UNKNOWN, PENDING, SUCCESSFUL, FAILED, REVERSED }
data class Transaction(
 val id: String = UUID.randomUUID().toString(), val reference: String? = null,
 val amount: Long, val currency: String = "INR", val direction: Direction,
 val occurredAt: Long? = null, val receivedAt: Long, val ingestedAt: Long,
 val account: String? = null, val merchant: String? = null, val category: String = "Other",
 val status: Status = Status.UNKNOWN, val sources: Set<String>, val review: Boolean = false,
 val relatedId: String? = null, val internalTransfer: Boolean = false,
 val history: List<String> = emptyList(), val excludedDuplicate: Boolean = false
)
data class Email(val id: String, val receivedAt: Long, val body: String)
interface EmailParser { fun parse(email: Email): Transaction? }
/** Synthetic fixtures only; unsupported real-bank formats produce no record. */
class SyntheticParser : EmailParser {
 override fun parse(email: Email): Transaction? {
  if (!email.body.startsWith("MONEYTRACK-SYNTHETIC-V1\n")) return null
  val fields = email.body.lineSequence().drop(1).mapNotNull {
   val split=it.split('=',limit=2); if(split.size==2) split[0] to split[1] else null
  }.toMap()
  return runCatching {
   val amount=BigDecimal(fields.getValue("amount")).movePointRight(2).longValueExact()
   require(amount>0)
   val status=Status.valueOf(fields.getValue("status"))
   Transaction(reference=fields["reference"]?.takeIf { it.isNotBlank() }, amount=amount,
    direction=Direction.valueOf(fields.getValue("direction")),
    occurredAt=fields["time"]?.let { Instant.parse(it).toEpochMilli() },
    receivedAt=email.receivedAt, ingestedAt=System.currentTimeMillis(),
    account=fields["account"]?.takeIf { Regex("[*xX]{2,}[0-9]{4}").matches(it) },
    merchant=fields["merchant"], status=status, sources=setOf(email.id),
    review=fields["reference"].isNullOrBlank() || fields["time"].isNullOrBlank() || fields["account"].isNullOrBlank(), history=listOf("${email.id}:$status"))
  }.getOrNull()
 }
}
object Reconcile {
 fun ingest(existing: List<Transaction>, incoming: Transaction): List<Transaction> {
  if(existing.any { (it.sources intersect incoming.sources).isNotEmpty() }) return existing
  val exact=existing.filter { incoming.reference!=null && it.reference==incoming.reference &&
   it.account!=null && it.account==incoming.account && it.amount==incoming.amount &&
   it.currency==incoming.currency && it.direction==incoming.direction }
  if(exact.size==1) {
   val old=exact.single()
   val allowed=old.status==Status.UNKNOWN ||
    (old.status==Status.PENDING && incoming.status in setOf(Status.SUCCESSFUL,Status.FAILED)) ||
    (old.status==Status.SUCCESSFUL && incoming.status==Status.REVERSED)
   val conflict=incoming.status!=old.status && !allowed
   return existing.map { if(it.id!=old.id) it else old.copy(
    status=if(allowed) incoming.status else old.status, sources=old.sources+incoming.sources,
    history=old.history+incoming.history, review=old.review||incoming.review||conflict) }
  }
  val possible=existing.any { it.account!=null && it.account==incoming.account &&
   it.amount==incoming.amount && it.direction==incoming.direction &&
   it.merchant!=null && it.merchant==incoming.merchant && it.occurredAt!=null && incoming.occurredAt!=null &&
   kotlin.math.abs(it.occurredAt-incoming.occurredAt)<=120_000 }
  return existing+incoming.copy(review=incoming.review||possible||exact.size>1)
 }
 fun refund(debit:Transaction,credit:Transaction,alreadyRefunded:Long=0):Transaction {
  require(debit.direction==Direction.DEBIT && credit.direction==Direction.CREDIT)
  require(debit.currency==credit.currency && credit.amount<=debit.amount-alreadyRefunded)
  require(credit.relatedId==null || credit.relatedId==debit.id)
  require(debit.status in setOf(Status.SUCCESSFUL,Status.REVERSED) && credit.status==Status.SUCCESSFUL)
  require(!debit.internalTransfer && !credit.internalTransfer && !debit.excludedDuplicate && !credit.excludedDuplicate)
  return credit.copy(relatedId=debit.id,history=credit.history+"User confirmed refund relation")
 }
 fun transfer(a: Transaction,b: Transaction): Pair<Transaction,Transaction> {
  require(a.direction!=b.direction && a.amount==b.amount && a.currency==b.currency)
  require((a.relatedId==null || a.relatedId==b.id) && (b.relatedId==null || b.relatedId==a.id))
  require(!a.excludedDuplicate && !b.excludedDuplicate)
  require(a.account!=null && b.account!=null && a.account!=b.account)
  require(a.status==Status.SUCCESSFUL && b.status==Status.SUCCESSFUL)
  return a.copy(internalTransfer=true,relatedId=b.id) to b.copy(internalTransfer=true,relatedId=a.id)
 }
}
data class Totals(val income:Long,val spending:Long,val pending:Long) { val net get()=income-spending }
object Analytics {
 val zone:ZoneId=ZoneId.of("Asia/Kolkata")
 fun month(t:Transaction)=YearMonth.from(Instant.ofEpochMilli(t.occurredAt?:t.receivedAt).atZone(zone))
 fun totals(rows:List<Transaction>,month:YearMonth):Totals {
  val selected=rows.filter { month(it)==month && !it.internalTransfer && !it.excludedDuplicate }
  val settled=selected.filter { it.status==Status.SUCCESSFUL }
  val refunds=settled.filter { credit->credit.direction==Direction.CREDIT && credit.relatedId!=null && rows.any { it.id==credit.relatedId && it.status==Status.SUCCESSFUL } }
  return Totals(settled.filter { it.direction==Direction.CREDIT && it.relatedId==null }.sumOf { it.amount },
   settled.filter { it.direction==Direction.DEBIT }.sumOf { it.amount }-refunds.sumOf { it.amount },
   selected.filter { it.status==Status.PENDING && it.direction==Direction.DEBIT }.sumOf { it.amount })
 }
 /** Account balances include internal transfers, unlike spending/income totals. */
 fun movement(rows:List<Transaction>,since:Long):Long = rows.filter {t->
  t.status==Status.SUCCESSFUL && !t.excludedDuplicate && (t.occurredAt?:t.receivedAt)>=since &&
   !(t.direction==Direction.CREDIT && !t.internalTransfer && t.relatedId!=null && rows.any {it.id==t.relatedId&&it.status==Status.REVERSED})
 }.sumOf {if(it.direction==Direction.CREDIT)it.amount else -it.amount}
 fun categories(rows:List<Transaction>,month:YearMonth):Map<String,Long> {
  val totals=mutableMapOf<String,Long>()
  rows.filter { month(it)==month && it.status==Status.SUCCESSFUL && !it.internalTransfer && !it.excludedDuplicate }.forEach { t->
   if(t.direction==Direction.DEBIT) totals[t.category]=(totals[t.category]?:0)+t.amount
   else if(t.relatedId!=null) {
    val original=rows.find {it.id==t.relatedId && it.status==Status.SUCCESSFUL}
    if(original!=null) totals[original.category]=(totals[original.category]?:0)-t.amount
   }
  }
  return totals
 }
 fun forecast(rows:List<Transaction>,current:YearMonth,coverageStart:Long?):Long? {
  val start=coverageStart?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?:return null
  if(start.isAfter(current.minusMonths(3).atDay(1))) return null
  val months=(1L..3L).map {current.minusMonths(it)}
  if(months.any { m->rows.none {month(it)==m && it.status==Status.SUCCESSFUL && !it.internalTransfer && !it.excludedDuplicate && it.direction==Direction.DEBIT} })return null
  return months.sumOf {totals(rows,it).spending.coerceAtLeast(0)}/3
 }
}
object Assistant {
 fun answer(question:String,rows:List<Transaction>,month:YearMonth):String {
  val q=question.lowercase()
  return when {
   "estimated" in q->"Estimated balances combine your starting balance with recorded movements only. Missing emails can make them incomplete."
   "why" in q && "fail" in q->"The available information does not confirm the failure cause. Contact your bank through its official app or website."
   "refund" in q->{
    val payment=rows.find {it.direction==Direction.DEBIT && it.reference!=null && q.contains(it.reference.lowercase())}
    val credits=rows.filter {it.direction==Direction.CREDIT && it.relatedId!=null && !it.internalTransfer && !it.excludedDuplicate && it.status==Status.SUCCESSFUL && (payment==null||it.relatedId==payment.id)}
    if(payment==null) "${credits.size} confirmed refund credits recorded. Include the payment reference to check a specific payment. Missing evidence does not prove no refund occurred."
    else "${credits.size} confirmed refund credits linked to ${payment.reference}, totaling ${money(credits.sumOf {it.amount})}. This only describes recorded evidence."
   }
   "pending" in q->"${rows.count { it.status==Status.PENDING }} pending records; excluded from settled totals."
   "category" in q->Analytics.categories(rows,month).maxByOrNull { it.value }?.let { "Highest recorded category: ${it.key}, ${money(it.value)}." }?:"No recorded spending this month."
   "above" in q->Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?").find(q)?.value?.replace(",","")?.let {
    val threshold=runCatching { BigDecimal(it).movePointRight(2).longValueExact() }.getOrNull()?:return "Amount is too large."
    rows.filter { t->t.amount>threshold }.joinToString("\n") { t->"${t.merchant?:"Unknown"}: ${money(t.amount)} (${t.status})" }.ifBlank { "No matching records." }
   }?:"Include an amount, for example: Show transactions above ₹2,000."
   "spend" in q || "spent" in q->"Recorded spending this month: ${money(Analytics.totals(rows,month).spending)}. Missing payment times use email receipt month."
   else->"Ask about spending this month, highest category, pending records, transactions above ₹2,000, refunds, or estimated balances. I cannot initiate payments or resolve bank-side failures."
  }
 }
}
fun money(value:Long):String {
 val digits=BigDecimal.valueOf(value,2).abs().setScale(2).toPlainString().split('.')
 val whole=digits[0]; val tail=whole.takeLast(3); val lead=whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
 return (if(value<0) "−" else "")+"₹"+(if(lead.isEmpty()) tail else "$lead,$tail")+"."+digits[1]
}
interface BankDataProvider {
 suspend fun discover(consent:String):List<BankAccount>
 suspend fun link(accountId:String,consent:String):BankAccount
 suspend fun transactions(accountId:String,after:Long):List<Transaction>
 suspend fun revoke(consent:String)
}
data class BankAccount(val id:String,val masked:String,val reportedBalance:Long?,val retrievedAt:Long?,val consentExpiresAt:Long)
